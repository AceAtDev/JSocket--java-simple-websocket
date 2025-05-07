package javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server;




import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final WebSocketListener listener;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private volatile boolean running = true;
    private volatile boolean closeFrameSent = false;
    private volatile boolean closeFrameReceived = false;

    // Buffer for reading incoming data
    // You might wanna make a logic to break down the long requests into chunks
    // because the socket won't accept long
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8192); 


    // Queue for outgoing frames to ensure writes don't block reads
    private final BlockingQueue<WebSocketFrame> outgoingFrames = new LinkedBlockingQueue<>();
    private Thread writerThread;


    public ClientHandler(Socket clientSocket, WebSocketListener listener) throws IOException {
        this.clientSocket = clientSocket;
        this.listener = listener;

        try {
            this.inputStream = clientSocket.getInputStream();
            this.outputStream = clientSocket.getOutputStream();
        } catch (IOException e) {
            System.err.println("ClientHandler: Error getting streams for " + clientSocket.getInetAddress() + ": " + e.getMessage());
            // Try to notify listener even if streams fail, connection might be null for listener
            // but 'this' ClientHandler object exists.


            this.listener.onError(this, e);
            this.running = false; 
            // Attempt to close what we can, though socket might be in a bad state (e.g. network discount on the serveer side)
            if (!clientSocket.isClosed()) {
                try {
                    clientSocket.close();
                } catch (IOException closeEx) { /* ignore */ }
            }
            throw e; // Re-throw to signal failure to WebSocketServer
        }
        readBuffer.limit(0);
    }

    @Override
    public void run() {
        if (!running) { // Streams might have failed in constructor
            // Listener should have been notified of error already.
            // A close notification might also be needed if onOpen was never called.
            // However, if constructor throws, this run() might not even be called by executor.
            return;
        }

        // Start the writer thread
        writerThread = new Thread(this::processOutgoingFrames);
        writerThread.setName("ClientHandler-Writer-" + clientSocket.getInetAddress().getHostAddress());
        writerThread.setDaemon(true); 
        writerThread.start();

        try {
            listener.onOpen(this); // Notify listener that connection is open and ready

            while (running && !clientSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                // Try to parse a frame from existing data in buffer
                try {
                    readBuffer.mark(); // Mark current position in case we need to reset
                    WebSocketFrame frame = WebSocketFrame.WebSocketFrameparseClientFrame(readBuffer);
                    // If parseClientFrame returns, it means a full frame was parsed.
                    // The readBuffer's position is now after the parsed frame.
                    handleFrame(frame);
                    readBuffer.compact().flip(); // Compact and prepare for reading remaining data or next read
                                                 // Or readBuffer.clear().limit(0) if fully consumed and no compact needed.
                                                 // If compact().flip() results in position == limit, buffer is empty.
                    if (readBuffer.position() == readBuffer.limit()) { // Buffer is empty
                        readBuffer.clear().limit(0);
                    }


                } catch (WebSocketFrame.BufferUnderflowException e) {
                    // Not enough data in the buffer to form a complete frame. Need to read more.
                    readBuffer.reset(); // Reset to marked position before attempting to parse
                    fillBufferFromSocket(); // This will block until data is read or EOF/error

                } catch (WebSocketFrame.ProtocolException e) {
                    System.err.println("ClientHandler: Protocol error from " + clientSocket.getInetAddress() + ": " + e.getMessage());
                    listener.onError(this, e);
                    close(1002, "Protocol error");
                    running = false;
                }

                if (closeFrameSent && closeFrameReceived) { // close handshake
                    running = false;
                }
            }
        } catch (SocketException e) {
            if (running) { // Only log/notify if we weren't already shutting down
                if ("Socket closed".equalsIgnoreCase(e.getMessage()) ||
                    "Connection reset".equalsIgnoreCase(e.getMessage()) ||
                    "Broken pipe".equalsIgnoreCase(e.getMessage())) {
                    System.out.println("ClientHandler: Socket closed or connection reset by client " + clientSocket.getInetAddress());
                } else {
                    System.err.println("ClientHandler: SocketException for " + clientSocket.getInetAddress() + ": " + e.getMessage());
                    listener.onError(this, e);
                }
                running = false; // Ensure loop terminates
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("ClientHandler: IOException in run loop for " + clientSocket.getInetAddress() + ": " + e.getMessage());
                listener.onError(this, e);
                running = false; // Ensure loop terminates
            }
        } catch (Exception e) { // Catch any other unexpected runtime exceptions
             if (running) {
                System.err.println("ClientHandler: Unexpected exception in run loop for " + clientSocket.getInetAddress() + ": " + e.getMessage());
                e.printStackTrace(); // Good for debugging
                listener.onError(this, e);
                running = false;
            }
        }
        finally {
            running = false; // Ensure running is false before final cleanup
            if (writerThread != null) {
                writerThread.interrupt(); // Signal writer thread to stop
            }
            // Determine if close was initiated by remote
            // This is tricky. If closeFrameReceived is true, it was. If EOF, it was.
            boolean remoteInitiated = closeFrameReceived || clientSocket.isInputShutdown();
            int closeCode = closeFrameReceived ? 1000 : 1006; // 1006 for abnormal
            String closeReason = closeFrameReceived ? "Normal closure" : "Connection closed abnormally";
            
            // If we sent a close frame but didn't receive one, it's still our initiated close
            // If we received one, it's remote.
            // This logic needs refinement for perfect onClose reporting.
            
            closeConnection(closeCode, closeReason, remoteInitiated);
        }
        System.out.println("ClientHandler for " + clientSocket.getInetAddress() + " finished.");
    }

    private void fillBufferFromSocket() throws IOException {
        readBuffer.compact(); // Make space for new data at the end of the buffer
        int bytesRead = inputStream.read(readBuffer.array(), readBuffer.position(), readBuffer.remaining());
        if (bytesRead == -1) { // End of stream
            System.out.println("ClientHandler: Client " + clientSocket.getInetAddress() + " closed connection (EOF).");
            running = false; // Signal to stop the main loop
            closeFrameReceived = true; // Treat EOF as a form of remote close indication
            throw new SocketException("Connection closed by client (EOF)"); // Or handle directly
        }
        readBuffer.position(readBuffer.position() + bytesRead); // Update position based on bytes read
        readBuffer.flip(); // Prepare buffer for reading (parsing)
    }

    private void handleFrame(WebSocketFrame frame) {
        System.out.println("ClientHandler: Received frame from " + clientSocket.getInetAddress() + ": " + frame);
        switch (frame.getOpcode()) {
            case TEXT:
                listener.onMessage(this, frame.getTextPayload());
                break;
            case BINARY:
                // listener.onBinaryMessage(this, frame.getPayloadData()); // If you add this to listener
                System.out.println("ClientHandler: Received BINARY frame from " + clientSocket.getInetAddress() + " (payload " + frame.getPayloadData().length + " bytes) - Not fully handled.");
                // For now, can treat as simple message for listener
                listener.onMessage(this, "[Binary data: " + frame.getPayloadData().length + " bytes]");
                break;
            case CLOSE:
                closeFrameReceived = true;
                System.out.println("ClientHandler: Received CLOSE frame from " + clientSocket.getInetAddress());
                if (!closeFrameSent) {
                    // Client initiated close, we must respond with a CLOSE frame
                    // Extract code and reason if possible (payload format: 2 bytes code, rest reason)
                    int clientCode = 1005; // No status Rcvd
                    String clientReason = "";
                    if (frame.getPayloadData().length >= 2) {
                        ByteBuffer bb = ByteBuffer.wrap(frame.getPayloadData());
                        clientCode = bb.getShort();
                        if (bb.hasRemaining()) {
                            clientReason = new String(Arrays.copyOfRange(frame.getPayloadData(), 2, frame.getPayloadData().length), StandardCharsets.UTF_8);
                        }
                         System.out.println("ClientHandler: Client close code=" + clientCode + ", reason='" + clientReason + "'");
                    }
                    sendFrame(WebSocketFrame.createCloseFrame(clientCode == 1005 ? 1000 : clientCode, "")); // Respond with their code or 1000
                }
                running = false; // Signal main loop to stop
                break;
            case PING:
                System.out.println("ClientHandler: Received PING from " + clientSocket.getInetAddress() + ", sending PONG.");
                sendFrame(WebSocketFrame.createPongFrame(frame.getPayloadData())); // Echo payload in PONG
                break;
            case PONG:
                System.out.println("ClientHandler: Received PONG from " + clientSocket.getInetAddress());
                // Can be used for keep-alive logic, e.g., reset a timeout timer.
                break;
            case CONTINUATION:
                System.err.println("ClientHandler: Received CONTINUATION frame - Fragmentation not supported in this version.");
                // For fragmentation, you'd buffer continuation frames until FIN is true.
                close(1003, "Continuation frames not supported"); // 1003: Data type not supported
                break;
            default:
                System.err.println("ClientHandler: Received unknown/unsupported opcode: " + frame.getOpcode());
                close(1002, "Unsupported opcode"); // 1002: Protocol Error
                break;
        }
    }

    private void processOutgoingFrames() {
        try {
            while (!Thread.currentThread().isInterrupted() && running) {
                // Poll with a timeout to allow checking 'running' and interrupt status periodically
                WebSocketFrame frame = outgoingFrames.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    try {
                        outputStream.write(frame.toBytes());
                        outputStream.flush();
                        System.out.println("ClientHandler: Sent frame to " + clientSocket.getInetAddress() + ": " + frame);
                        if (frame.getOpcode() == WebSocketFrame.Opcode.CLOSE) {
                            closeFrameSent = true;
                            // If we also received a close frame, we can break the writer loop.
                            // The main read loop will handle full shutdown.
                            if (closeFrameReceived) break; 
                        }
                    } catch (SocketException e) {
                        if (running) { // Avoid error if already shutting down
                             System.err.println("ClientHandler: SocketException during send to " + clientSocket.getInetAddress() + ": " + e.getMessage());
                             listener.onError(this, e);
                             running = false; 
                        }
                        break; // Stop writer thread
                    } catch (IOException e) {
                         if (running) {
                            System.err.println("ClientHandler: IOException during send to " + clientSocket.getInetAddress() + ": " + e.getMessage());
                            listener.onError(this, e);
                            running = false; 
                        }
                        break; 
                    }
                }
            }
        } catch (InterruptedException e) {
            System.out.println("ClientHandler-Writer for " + clientSocket.getInetAddress() + " interrupted.");
            Thread.currentThread().interrupt(); 
        }
        System.out.println("ClientHandler-Writer for " + clientSocket.getInetAddress() + " finished.");
    }


    public void sendMessage(String message) {
        if (running && !clientSocket.isClosed()) {
            sendFrame(WebSocketFrame.createTextFrame(message, true));
        } else {
            System.err.println("ClientHandler: Attempted to send message on closed or non-running connection to " + clientSocket.getInetAddress());
        }
    }

    public void sendFrame(WebSocketFrame frame) {
        if (running && !clientSocket.isClosed()) {
            try {
                outgoingFrames.put(frame); 
            } catch (InterruptedException e) {
                System.err.println("ClientHandler: Interrupted while queueing frame for " + clientSocket.getInetAddress());
                listener.onError(this, e);
                Thread.currentThread().interrupt();
                close(1011, "Internal server error during send queueing");
            }
        }
    }

    public void close(int code, String reason) {
        if (running) { 
            running = false; 
            System.out.println("ClientHandler: Initiating close for " + clientSocket.getInetAddress() + " with code=" + code + ", reason='" + reason + "'");
            if (!closeFrameSent) {
                sendFrame(WebSocketFrame.createCloseFrame(code, reason));
            }
            // The main run() loop's finally block will handle the actual socket closure
            // and listener.onClose() notification.
            // We interrupt the writer thread here if it's stuck on poll,
            // so it can see running=false and exit.
            if (writerThread != null) {
                writerThread.interrupt();
            }
        }
    }

    private synchronized void closeConnection(int code, String reason, boolean remote) {
        if (clientSocket.isClosed()) {
            // If already closed, ensure listener is called if it hasn't been.
            // This part is tricky to get right without a more robust state machine.
            // For now, we assume if socket is closed, onClose might have been missed.
            // However, the listener.onClose in the finally block should cover most cases.
            return;
        }
        
        running = false; 

        // Attempt to send a final close frame if not already done and possible
        if (!closeFrameSent && outputStream != null) {
            try {
                System.out.println("ClientHandler: Sending final CLOSE frame during closeConnection for " + clientSocket.getInetAddress());
                WebSocketFrame closeFrame = WebSocketFrame.createCloseFrame(code, reason);
                outputStream.write(closeFrame.toBytes());
                outputStream.flush();
                closeFrameSent = true;
            } catch (IOException e) {
                // Ignore, we are closing anyway
            }
        }

        try {
            // clientSocket.shutdownInput(); // Can cause issues if client is still sending
            // clientSocket.shutdownOutput(); // Can cause issues if we just sent a close frame
        } catch (Exception e) { /* ignore */ }
        finally {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("ClientHandler: Error closing socket for " + clientSocket.getInetAddress() + ": " + e.getMessage());
            }
        }
        
        listener.onClose(this, code, reason, remote);
        System.out.println("ClientHandler: Connection fully closed for " + clientSocket.getInetAddress() + ". Code: " + code + ", Reason: " + reason + ", Remote: " + remote);
    }


    public Socket getSocket() {
        return clientSocket;
    }

    public boolean isOpen() {
        // A more robust isOpen would check if the close handshake has started/completed.
        return running && clientSocket != null && !clientSocket.isClosed() && clientSocket.isConnected() && !closeFrameSent && !closeFrameReceived;
    }
}
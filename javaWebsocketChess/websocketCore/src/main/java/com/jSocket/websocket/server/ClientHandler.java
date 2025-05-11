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

public class ClientHandler implements Runnable, WebSocketDataReaderListener, WebSocketDataWriterListener {

    private final Socket clientSocket;
    private final WebSocketListener userListener; // Renamed to avoid confusion
    private InputStream inputStream;  // Made non-final to handle potential init failure better
    private OutputStream outputStream; // Made non-final
    private volatile boolean clientHandlerRunning = true; // Overall state for this handler
    private volatile boolean closeFrameSentByUs = false;
    private volatile boolean closeFrameReceivedFromPeer = false;

    private final BlockingQueue<WebSocketFrame> outgoingFrames = new LinkedBlockingQueue<>();
    private Thread readerThread;
    private Thread writerThread;
    private WebSocketDataReader dataReader;
    private WebSocketDataWriter dataWriter;

    private final Object closeLock = new Object(); // For synchronizing close operations

    public ClientHandler(Socket clientSocket, WebSocketListener listener) throws IOException {
        this.clientSocket = clientSocket;
        this.userListener = listener;

        try {
            this.inputStream = clientSocket.getInputStream();
            this.outputStream = clientSocket.getOutputStream();
        } catch (IOException e) {
            System.err.println("ClientHandler: Error getting streams for " + clientSocket.getInetAddress() + ": " + e.getMessage());
            // Notify listener even if streams fail.
            this.userListener.onError(this, e); // 'this' ClientHandler exists
            this.clientHandlerRunning = false;
            // Attempt to close what we can
            if (!clientSocket.isClosed()) {
                try {
                    clientSocket.close();
                } catch (IOException closeEx) { /* ignore */ }
            }
            throw e; // Re-throw to signal failure to WebSocketServer
        }
    }

    @Override
    public void run() {
        if (!clientHandlerRunning) { // Streams might have failed in constructor
            // Listener should have been notified of error already.
            return;
        }

        this.dataReader = new WebSocketDataReader(this.inputStream, this, this);
        this.dataWriter = new WebSocketDataWriter(this.outputStream, this.outgoingFrames, this, this);

        readerThread = new Thread(dataReader);
        readerThread.setName("ClientHandler-Reader-" + clientSocket.getInetAddress().getHostAddress());
        readerThread.setDaemon(true);

        writerThread = new Thread(dataWriter);
        writerThread.setName("ClientHandler-Writer-" + clientSocket.getInetAddress().getHostAddress());
        writerThread.setDaemon(true);

        readerThread.start();
        writerThread.start();

        try {
            userListener.onOpen(this); // Notify listener that connection is open

            // Main loop for ClientHandler is now primarily to wait for termination signals
            // or manage overall state that reader/writer threads can't.
            while (clientHandlerRunning && !clientSocket.isClosed()) {
                if (closeFrameSentByUs && closeFrameReceivedFromPeer) {
                    System.out.println("ClientHandler: Close handshake complete for " + clientSocket.getInetAddress());
                    clientHandlerRunning = false; // Both sides acknowledged close
                    break;
                }
                // Check if threads are alive, if not, something went wrong.
                if ((readerThread != null && !readerThread.isAlive() && clientHandlerRunning) ||
                    (writerThread != null && !writerThread.isAlive() && clientHandlerRunning && !outgoingFrames.isEmpty())) {
                     // One of the threads died unexpectedly while handler was supposed to be running
                     System.err.println("ClientHandler: Reader or Writer thread died unexpectedly for " + clientSocket.getInetAddress());
                     if (clientHandlerRunning) { // Avoid redundant error if already closing
                        userListener.onError(this, new IOException("Internal reader/writer thread failure."));
                     }
                     clientHandlerRunning = false; // Trigger shutdown
                     break;
                }

                try {
                    Thread.sleep(100); // Sleep a bit to avoid busy-waiting
                } catch (InterruptedException e) {
                    System.out.println("ClientHandler: Main loop interrupted for " + clientSocket.getInetAddress());
                    Thread.currentThread().interrupt();
                    clientHandlerRunning = false; // Ensure exit
                }
            }
        } catch (Exception e) { // Catch any other unexpected runtime exceptions in this main logic
             if (clientHandlerRunning) {
                System.err.println("ClientHandler: Unexpected exception in main run loop for " + clientSocket.getInetAddress() + ": " + e.getMessage());
                e.printStackTrace();
                userListener.onError(this, e);
                clientHandlerRunning = false;
            }
        } finally {
            System.out.println("ClientHandler: Entering finally block for " + clientSocket.getInetAddress());
            clientHandlerRunning = false; // Ensure state is set for dependent threads/logic

            // Signal reader and writer to stop if they haven't already
            if (dataReader != null) dataReader.stop();
            if (dataWriter != null) dataWriter.stop();

            // Interrupt threads to unblock any IO operations
            if (readerThread != null && readerThread.isAlive()) {
                readerThread.interrupt();
            }
            if (writerThread != null && writerThread.isAlive()) {
                // Offer a dummy frame to unblock writer from queue.poll if it's stuck there
                // and not checking running flag due to poll timeout.
                // This is a bit of a hack; ideally, closing the socket is the cleanest way.
                outgoingFrames.offer(WebSocketFrame.createPingFrame(null)); // Or a special "shutdown" frame
                writerThread.interrupt();
            }
            
            // Wait for threads to finish (with a timeout)
            joinThread(readerThread, "Reader", 500);
            joinThread(writerThread, "Writer", 500);

            // Determine close parameters
            // This logic needs to be robust based on closeFrameReceivedFromPeer and if we initiated.
            boolean remoteInitiated = closeFrameReceivedFromPeer && !closeFrameSentByUs; // Simplified: if they sent close and we didn't start it
            int closeCode = 1006; // Default abnormal
            String closeReason = "Connection closed abnormally";

            if (closeFrameReceivedFromPeer) { // If we got a close frame, use its info if available (though we don't store it directly here now)
                closeCode = 1000; // Assume normal if peer sent close
                closeReason = "Normal closure initiated by peer";
            } else if (closeFrameSentByUs) {
                // We initiated, but didn't get a response (or this finally block hit before handshake completed)
                // The code/reason for our sent frame should be used. For now, generic.
                closeCode = 1005; // No status rcvd (if we sent close but no reply)
                closeReason = "Close initiated by server";
            }
            
            // Actual socket closure and listener notification
            closeConnection(closeCode, closeReason, remoteInitiated);
        }
        System.out.println("ClientHandler for " + clientSocket.getInetAddress() + " finished run method.");
    }

    private void joinThread(Thread thread, String name, long timeoutMillis) {
        if (thread != null && thread.isAlive()) {
            try {
                System.out.println("ClientHandler: Waiting for " + name + " thread to join for " + clientSocket.getInetAddress());
                thread.join(timeoutMillis);
                if (thread.isAlive()) {
                    System.err.println("ClientHandler: " + name + " thread did not join in time for " + clientSocket.getInetAddress());
                } else {
                     System.out.println("ClientHandler: " + name + " thread joined for " + clientSocket.getInetAddress());
                }
            } catch (InterruptedException e) {
                System.err.println("ClientHandler: Interrupted while waiting for " + name + " thread to join for " + clientSocket.getInetAddress());
                Thread.currentThread().interrupt();
            }
        }
    }


    // --- WebSocketDataReaderListener Implementation ---
    @Override
    public void onFrameReceived(WebSocketFrame frame) {
        if (!clientHandlerRunning) return;
        System.out.println("ClientHandler: Received frame from " + clientSocket.getInetAddress() + ": " + frame);
        handleFrame(frame);
    }

    @Override
    public void onReaderError(ClientHandler context, Exception e) {
        if (!clientHandlerRunning) return;
        System.err.println("ClientHandler: ReaderError for " + clientSocket.getInetAddress() + ": " + e.getMessage());
        userListener.onError(this, e);
        // If reader fails critically, we should initiate close.
        // The reader itself sets its running to false. ClientHandler's main loop might detect thread death.
        // Or, more proactively:
        if (clientHandlerRunning) { // Avoid closing if already in process
            close(1011, "Reader error: " + e.getMessage());
        }
    }

    @Override
    public void onReaderClosed(ClientHandler context) {
        System.out.println("ClientHandler: Reader closed for " + clientSocket.getInetAddress());
        // If the reader closed due to EOF, it means the client closed the connection.
        // This is a trigger for us to also close if we haven't started.
        // This might be where closeFrameReceivedFromPeer is effectively true if it was an EOF.
        // For now, the main loop's conditions or an explicit closeFrame handling will manage shutdown.
        // If clientHandlerRunning is true and reader closed, it might imply an issue or EOF.
        // If it was EOF, the reader's SocketException("Connection closed by client (EOF)")
        // would have called onReaderError, leading to a close() call.
        // If reader just stops, and clientHandlerRunning is true, it's an issue.
        if (clientHandlerRunning && (readerThread != null && !readerThread.isAlive())) {
            // If reader died and we are still "running", it's an abnormal situation.
            // This is partly handled by the main run loop's check for thread aliveness.
            // Consider if a more direct close initiation is needed here.
            // For now, rely on main loop or explicit close frame handling.
            if (!closeFrameSentByUs && !closeFrameReceivedFromPeer) { // If no close handshake started
                 System.out.println("ClientHandler: Reader closed unexpectedly for " + clientSocket.getInetAddress() + ". Initiating close.");
                 close(1006, "Reader closed unexpectedly");
            }
        }
    }

    // --- WebSocketDataWriterListener Implementation ---
    @Override
    public void onFrameSent(ClientHandler context, WebSocketFrame frame) {
        // Optional: log or update state if needed
        // System.out.println("ClientHandler: Confirmed frame sent: " + frame.getOpcode());
    }

    @Override
    public void onWriterError(ClientHandler context, Exception e) {
        if (!clientHandlerRunning) return;
        System.err.println("ClientHandler: WriterError for " + clientSocket.getInetAddress() + ": " + e.getMessage());
        userListener.onError(this, e);
        if (clientHandlerRunning) {
            close(1011, "Writer error: " + e.getMessage());
        }
    }

    @Override
    public void onWriterClosed(ClientHandler context) {
        System.out.println("ClientHandler: Writer closed for " + clientSocket.getInetAddress());
        // Similar to reader, if writer dies unexpectedly while handler is running.
        if (clientHandlerRunning && (writerThread != null && !writerThread.isAlive())) {
            if (!closeFrameSentByUs && !closeFrameReceivedFromPeer) {
                 System.out.println("ClientHandler: Writer closed unexpectedly for " + clientSocket.getInetAddress() + ". Initiating close.");
                 close(1006, "Writer closed unexpectedly");
            }
        }
    }
    
    @Override
    public boolean isHandlerRunning() {
        return clientHandlerRunning && !clientSocket.isClosed();
    }

    @Override
    public void setCloseFrameSentFlag() {
        this.closeFrameSentByUs = true;
    }
    
    @Override
    public boolean hasReceivedCloseFrame() {
        return this.closeFrameReceivedFromPeer;
    }


    private void handleFrame(WebSocketFrame frame) {
        // This logic remains largely the same as before
        switch (frame.getOpcode()) {
            case TEXT:
                userListener.onMessage(this, frame.getTextPayload());
                break;
            case BINARY:
                userListener.onMessage(this, "[Binary data: " + frame.getPayloadData().length + " bytes]");
                break;
            case CLOSE:
                synchronized(closeLock) {
                    closeFrameReceivedFromPeer = true;
                    System.out.println("ClientHandler: Received CLOSE frame from " + clientSocket.getInetAddress());
                    if (!closeFrameSentByUs) {
                        // Client initiated close, we must respond
                        int clientCode = 1005; String clientReason = "";
                        if (frame.getPayloadData().length >= 2) {
                            ByteBuffer bb = ByteBuffer.wrap(frame.getPayloadData());
                            clientCode = bb.getShort();
                            if (bb.hasRemaining()) {
                                clientReason = new String(Arrays.copyOfRange(frame.getPayloadData(), 2, frame.getPayloadData().length), StandardCharsets.UTF_8);
                            }
                            System.out.println("ClientHandler: Client close code=" + clientCode + ", reason='" + clientReason + "'");
                        }
                        // Respond with their code or 1000 (Normal Closure) if they sent 1005 (No Status Rcvd)
                        sendFrame(WebSocketFrame.createCloseFrame(clientCode == 1005 ? 1000 : clientCode, ""));
                    }
                    // Signal main loop to stop or complete shutdown process
                    // The main loop checks closeFrameSentByUs && closeFrameReceivedFromPeer
                    clientHandlerRunning = false; // This will lead to finally block and proper closure
                }
                break;
            case PING:
                System.out.println("ClientHandler: Received PING from " + clientSocket.getInetAddress() + ", sending PONG.");
                sendFrame(WebSocketFrame.createPongFrame(frame.getPayloadData()));
                break;
            case PONG:
                System.out.println("ClientHandler: Received PONG from " + clientSocket.getInetAddress());
                break;
            case CONTINUATION:
                System.err.println("ClientHandler: Received CONTINUATION frame - Fragmentation not supported.");
                close(1003, "Continuation frames not supported");
                break;
            default:
                System.err.println("ClientHandler: Received unknown/unsupported opcode: " + frame.getOpcode());
                close(1002, "Unsupported opcode");
                break;
        }
    }

    public void sendMessage(String message) {
        if (isHandlerRunning() && !closeFrameSentByUs) {
            sendFrame(WebSocketFrame.createTextFrame(message, true));
        } else {
            System.err.println("ClientHandler: Attempted to send message on closing or non-running connection to " + clientSocket.getInetAddress());
        }
    }

    public void sendFrame(WebSocketFrame frame) {
        if (isHandlerRunning() && !(closeFrameSentByUs && frame.getOpcode() != WebSocketFrame.Opcode.CLOSE) ) { // Allow sending CLOSE even if we initiated
            try {
                outgoingFrames.put(frame);
            } catch (InterruptedException e) {
                System.err.println("ClientHandler: Interrupted while queueing frame for " + clientSocket.getInetAddress());
                Thread.currentThread().interrupt();
                userListener.onError(this, e); // Notify listener
                close(1011, "Internal server error during send queueing");
            }
        } else {
             System.err.println("ClientHandler: Attempted to send frame on closing or non-running connection to " + clientSocket.getInetAddress() + " Frame: " + frame.getOpcode());
        }
    }

    public void close(int code, String reason) {
        synchronized(closeLock) {
            if (!clientHandlerRunning && closeFrameSentByUs) { // Already closing or closed
                System.out.println("ClientHandler: Close called but already closing/closed for " + clientSocket.getInetAddress());
                return;
            }
            System.out.println("ClientHandler: Initiating close for " + clientSocket.getInetAddress() + " with code=" + code + ", reason='" + reason + "'");
            clientHandlerRunning = false; // Signal all loops to stop

            if (!closeFrameSentByUs) {
                sendFrame(WebSocketFrame.createCloseFrame(code, reason));
                // setCloseFrameSentFlag() will be called by the writer listener
            }
            // The main run() loop's finally block will handle the actual socket closure
            // and listener.onClose() notification after threads are joined.
            // Interrupt reader/writer if they are blocked.
            if (readerThread != null && readerThread.isAlive()) readerThread.interrupt();
            if (writerThread != null && writerThread.isAlive()) writerThread.interrupt();
        }
    }

    private void closeConnection(int code, String reason, boolean remote) {
        // This method is now primarily called from the main run() loop's finally block.
        synchronized(closeLock) {
            if (clientSocket.isClosed()) {
                return; // Already handled
            }
            clientHandlerRunning = false; // Ensure state

            // Attempt to send a final close frame if not already done and possible (e.g. if we initiated close but writer died)
            // This is a best-effort if the writer thread didn't manage it.
            if (!closeFrameSentByUs && outputStream != null) {
                try {
                    System.out.println("ClientHandler: Sending final CLOSE frame during closeConnection for " + clientSocket.getInetAddress());
                    WebSocketFrame closeFrame = WebSocketFrame.createCloseFrame(code, reason);
                    outputStream.write(closeFrame.toBytes());
                    outputStream.flush();
                    closeFrameSentByUs = true;
                } catch (IOException e) {
                    // Ignore, we are closing anyway
                    System.err.println("ClientHandler: Error sending final close frame during closeConnection: " + e.getMessage());
                }
            }

            try {
                if (inputStream != null) inputStream.close();
            } catch (IOException e) { /* ignore */ }
            try {
                if (outputStream != null) outputStream.close();
            } catch (IOException e) { /* ignore */ }
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("ClientHandler: Error closing socket for " + clientSocket.getInetAddress() + ": " + e.getMessage());
            }
        }
        // Notify user listener outside synchronized block to prevent deadlocks if listener calls back into ClientHandler
        userListener.onClose(this, code, reason, remote);
        System.out.println("ClientHandler: Connection fully closed for " + clientSocket.getInetAddress() + ". Code: " + code + ", Reason: " + reason + ", Remote: " + remote);
    }

    public Socket getSocket() {
        return clientSocket;
    }

    public boolean isOpen() {
        // isOpen should reflect if the WebSocket session is active, not just socket.
        return clientHandlerRunning && clientSocket != null && !clientSocket.isClosed() &&
               !closeFrameSentByUs && !closeFrameReceivedFromPeer;
    }
}
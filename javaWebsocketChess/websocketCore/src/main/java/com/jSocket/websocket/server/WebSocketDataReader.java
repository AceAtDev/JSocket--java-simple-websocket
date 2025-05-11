package javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.ByteBuffer;

public class WebSocketDataReader implements Runnable {
    private final InputStream inputStream;
    private final WebSocketDataReaderListener listener;
    private final ClientHandler contextHandler; // The ClientHandler instance for context
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8192); // Standard buffer size
    private volatile boolean running = true;

    public WebSocketDataReader(InputStream inputStream, ClientHandler contextHandler, WebSocketDataReaderListener listener) {
        this.inputStream = inputStream;
        this.contextHandler = contextHandler;
        this.listener = listener;
        this.readBuffer.limit(0); // Initially empty
    }

    @Override
    public void run() {
        try {
            while (running && listener.isHandlerRunning() && !Thread.currentThread().isInterrupted()) {
                try {
                    readBuffer.mark(); // Mark current position in case we need to reset
                    WebSocketFrame frame = WebSocketFrame.WebSocketFrameparseClientFrame(readBuffer);
                    // If parseClientFrame returns, it means a full frame was parsed.
                    listener.onFrameReceived(frame);
                    readBuffer.compact().flip(); // Compact and prepare for reading remaining data or next read
                    if (readBuffer.position() == readBuffer.limit()) { // Buffer is empty
                        readBuffer.clear().limit(0);
                    }
                } catch (WebSocketFrame.BufferUnderflowException e) {
                    // Not enough data in the buffer to form a complete frame. Need to read more.
                    readBuffer.reset(); // Reset to marked position before attempting to parse
                    fillBufferFromSocket(); // This will block until data is read or EOF/error
                } catch (WebSocketFrame.ProtocolException e) {
                    System.err.println("WebSocketDataReader: Protocol error from " + contextHandler.getSocket().getInetAddress() + ": " + e.getMessage());
                    listener.onReaderError(contextHandler, e);
                    running = false; // Stop this reader
                }
            }
        } catch (SocketException e) {
            if (running && listener.isHandlerRunning()) {
                if ("Socket closed".equalsIgnoreCase(e.getMessage()) ||
                    "Connection reset".equalsIgnoreCase(e.getMessage()) ||
                    "Broken pipe".equalsIgnoreCase(e.getMessage()) ||
                    "Connection closed by client (EOF)".equalsIgnoreCase(e.getMessage())) {
                    System.out.println("WebSocketDataReader: Socket closed or connection reset for " + contextHandler.getSocket().getInetAddress());
                    // EOF is a normal closure from client, ClientHandler will manage via onReaderClosed
                } else {
                    System.err.println("WebSocketDataReader: SocketException for " + contextHandler.getSocket().getInetAddress() + ": " + e.getMessage());
                    listener.onReaderError(contextHandler, e);
                }
                running = false;
            }
        } catch (IOException e) {
            if (running && listener.isHandlerRunning()) {
                System.err.println("WebSocketDataReader: IOException in run loop for " + contextHandler.getSocket().getInetAddress() + ": " + e.getMessage());
                listener.onReaderError(contextHandler, e);
                running = false;
            }
        } finally {
            running = false;
            listener.onReaderClosed(contextHandler);
            System.out.println("WebSocketDataReader for " + contextHandler.getSocket().getInetAddress() + " finished.");
        }
    }

    private void fillBufferFromSocket() throws IOException {
        readBuffer.compact(); // Make space for new data at the end of the buffer
        int bytesRead = inputStream.read(readBuffer.array(), readBuffer.position(), readBuffer.remaining());
        if (bytesRead == -1) { // End of stream
            System.out.println("WebSocketDataReader: Client " + contextHandler.getSocket().getInetAddress() + " closed connection (EOF).");
            running = false; // Signal to stop the main loop
            throw new SocketException("Connection closed by client (EOF)");
        }
        readBuffer.position(readBuffer.position() + bytesRead); // Update position based on bytes read
        readBuffer.flip(); // Prepare buffer for reading (parsing)
    }

    public void stop() {
        running = false;
        // The ClientHandler is responsible for interrupting the thread if blocked on IO,
        // typically by closing the socket.
    }
}
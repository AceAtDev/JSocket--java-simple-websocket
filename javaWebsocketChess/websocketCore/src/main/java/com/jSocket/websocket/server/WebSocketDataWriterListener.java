package javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server;

import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.ClientHandler;
import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.WebSocketFrame;

public interface WebSocketDataWriterListener {
        /**
     * Called after a WebSocket frame has been successfully sent. (Optional callback)
     * @param context The ClientHandler associated with this writer.
     * @param frame The frame that was sent.
     */
    void onFrameSent(ClientHandler context, WebSocketFrame frame);

    /**
     * Called when an error occurs during writing.
     * @param context The ClientHandler associated with this writer.
     * @param e The exception that occurred.
     */
    void onWriterError(ClientHandler context, Exception e);

    /**
     * Called when the writer has finished its execution.
     * @param context The ClientHandler associated with this writer.
     */
    void onWriterClosed(ClientHandler context);

    /**
     * Allows the writer to check if the parent ClientHandler is still considered active.
     * @return true if the handler is running, false otherwise.
     */
    boolean isHandlerRunning();

    /**
     * Notifies the ClientHandler that a CLOSE frame has been sent by this writer.
     */
    void setCloseFrameSentFlag();

    /**
     * Allows the writer to check if the ClientHandler has received a CLOSE frame from the remote peer.
     * @return true if a CLOSE frame has been received, false otherwise.
     */
    boolean hasReceivedCloseFrame();

}

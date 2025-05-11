package javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server;

import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.ClientHandler;
import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.WebSocketFrame;

public interface WebSocketDataReaderListener {
        /**
     * Called when a complete WebSocket frame has been received and parsed.
     * @param frame The parsed WebSocket frame.
     */
    void onFrameReceived(WebSocketFrame frame);

    /**
     * Called when an error occurs during reading or parsing.
     * @param context The ClientHandler associated with this reader.
     * @param e The exception that occurred.
     */
    void onReaderError(ClientHandler context, Exception e);

    /**
     * Called when the reader has finished its execution (e.g., EOF or stop).
     * @param context The ClientHandler associated with this reader.
     */
    void onReaderClosed(ClientHandler context);

    /**
     * Allows the reader to check if the parent ClientHandler is still considered active.
     * @return true if the handler is running, false otherwise.
     */
    boolean isHandlerRunning();

}

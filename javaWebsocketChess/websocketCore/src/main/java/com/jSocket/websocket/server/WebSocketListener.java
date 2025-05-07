package javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server;

import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.ClientHandler;


public interface WebSocketListener {
    /**
     * Called when a new WebSocket connection has been established and handshake is complete.
     * @param connection The ClientHandler representing the connection.
     */
    void onOpen(ClientHandler connection);

    /**
     * Called when a text message is received from the client.
     * @param connection The ClientHandler representing the connection.
     * @param message The text message received.
     */
    void onMessage(ClientHandler connection, String message);

    /**
     * Called when a WebSocket connection has been closed.
     * @param connection The ClientHandler representing the connection.
     * @param code The close code sent by the client or server.
     * @param reason The close reason sent by the client or server.
     * @param remote True if the close was initiated by the remote peer (the client).
     */
    void onClose(ClientHandler connection, int code, String reason, boolean remote);

    /**
     * Called when an error occurs on the WebSocket connection.
     * @param connection The ClientHandler representing the connection (can be null if error occurs before handler creation).
     * @param ex The exception that occurred.
     */
    void onError(ClientHandler connection, Exception ex);

    // Later, we might add onBinaryMessage, onPing, onPong, etc.
}
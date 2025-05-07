package javaWebsocketChess.chess;

import java.io.IOException;
import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.WebSocketServer; // Correct import


public class ChessServerMain {
        public static void main(String[] args) {
        int port = 8080; // Default port for the chess server

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number specified. Using default port " + port + ".");
            }
        }

        // 1. Create an instance of your ChessGameManager (which is a WebSocketListener)
        ChessGameManager chessListener = new ChessGameManager();

        // 2. Create an instance of the generic WebSocketServer, passing your chess listener
        WebSocketServer server = new WebSocketServer(port, chessListener);

        try {
            // 3. Start the server
            server.start();
            System.out.println("Chess WebSocket Server is running on port: " + port);
            System.out.println("Open your browser and connect to ws://localhost:" + port);
            System.out.println("Press Ctrl+C to stop the server.");

            // Add a shutdown hook for graceful termination
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down chess server...");
                server.stop();
                System.out.println("Chess server shut down.");
            }));

        } catch (IOException e) {
            System.err.println("Could not start the chess server: " + e.getMessage());
            e.printStackTrace();
        }
    }

}

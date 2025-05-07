package javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket;

import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.ClientHandler;
import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.WebSocketListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class WebSocketServer {

    private final int port;

    // unless you just testing locally, please move this to a .env file to make safe
    private static final String WEBSOCKET_SECRET_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"; 


    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final ExecutorService clientExecutorService;
    private final WebSocketListener webSocketListener;

    public WebSocketServer(int port, WebSocketListener listener) {
        this.port = port;
        this.webSocketListener = listener; 
        this.clientExecutorService = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        if (running) { throw new IllegalStateException("Server is already running."); }


        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("Generic WebSocket Server core started on port: " + port);
        System.out.println("Listening for WebSocket connections...");

        clientExecutorService.submit(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    
                    try {
                        performHandshake(clientSocket);
                        ClientHandler handler = new ClientHandler(clientSocket, this.webSocketListener);
                        clientExecutorService.submit(handler);

                    } catch (IOException | NoSuchAlgorithmException e) {
                        System.err.println("WebSocketServer Core: Handshake failed for " + clientSocket.getInetAddress() + ": " + e.getMessage());
                        if (!clientSocket.isClosed()) {
                            try {
                                clientSocket.close();
                            } catch (IOException closeEx) {
                                // Add logic here if you want to hanlde closing errors
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!running || serverSocket.isClosed()) {
                        break;
                    }
                    System.err.println("WebSocketServer Core: Error accepting client connection: " + e.getMessage());
                }
            }
        });
    }

    

    private void performHandshake(Socket clientSocket) throws IOException, NoSuchAlgorithmException {
        InputStream inputStream = clientSocket.getInputStream();
        OutputStream outputStream = clientSocket.getOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        
        String requestLine = reader.readLine();
        if (requestLine == null || !requestLine.startsWith("GET")) {
            sendHttpResponse(outputStream, "HTTP/1.1 400 Bad Request", "Invalid Method.");
            throw new IOException("Invalid request line: " + requestLine);
        }



        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            String[] headerParts = headerLine.split(": ", 2);
            if (headerParts.length == 2) {
                headers.put(headerParts[0].trim(), headerParts[1].trim());
            }
        }

        String webSocketKey = headers.get("Sec-WebSocket-Key");
        String upgradeHeader = headers.get("Upgrade");
        String connectionHeader = headers.get("Connection");
        String versionHeader = headers.get("Sec-WebSocket-Version");

        if (webSocketKey == null || !"websocket".equalsIgnoreCase(upgradeHeader) ||
            connectionHeader == null || !connectionHeader.toLowerCase().contains("upgrade") ||
            !"13".equals(versionHeader)) {
            sendHttpResponse(outputStream, "HTTP/1.1 400 Bad Request", "Invalid WebSocket Handshake.");
            throw new IOException("Invalid WebSocket handshake request.");
        }



        String acceptKey = generateWebSocketAcceptKey(webSocketKey);
        String handshakeResponse = "HTTP/1.1 101 Switching Protocols\r\n" +
                                   "Upgrade: websocket\r\n" +
                                   "Connection: Upgrade\r\n" +
                                   "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                                   "\r\n"; // clean up the request to make it readable



        outputStream.write(handshakeResponse.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }



    private String generateWebSocketAcceptKey(String clientKey) throws NoSuchAlgorithmException {
        String combined = clientKey + WEBSOCKET_SECRET_STRING;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(combined.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }



    private void sendHttpResponse(OutputStream out, String statusLine, String body) throws IOException {
        String response = statusLine + "\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                        + "Connection: close\r\n" // Important: tell client to close after error response
                        + "\r\n"
                        + body;
        try {
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            // The caller (performHandshake) is responsible for closing the main clientSocket
            System.out.println("HANDSHAKE FAILED");
        }
    }
    
    public void stop() {
        System.out.println("Stopping Jsocket Server core...");
        running = false; 
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); // Force stope the accept() thread
            }
        } catch (IOException e) {
            System.err.println("Error closing server socket: " + e.getMessage());
        }

        // Shutdown the executor service
        clientExecutorService.shutdown(); // Disable new tasks from being submitted
        try {

            // I used shutdown() cause it cause errors with my linux config
            // but unless you have complex linux config, you'd be good to go with shutdownNow()

            // Wait a while for existing tasks to terminate
            if (!clientExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                clientExecutorService.shutdown(); // you might wanna use shutdownNow() to force kill it 

                if (!clientExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Client executor service did not terminate.");
                }
            }
        } catch (InterruptedException ie) {

            // Make sure the treads are dead if interupted before by OS

            clientExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("WebSocket Server core stopped.");
    }

}
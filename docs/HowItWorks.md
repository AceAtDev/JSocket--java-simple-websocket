### ⚙️ Core Logic Explained: How JSocket Works

Understanding the flow of data and control is key to working with JSocket. Here's a simplified breakdown:

1.  **The Server Starts (`WebSocketServer.java`)**
    *   Think of the [`WebSocketServer`](javaWebsocketChess/websocketCore/src/main/java/com/jSocket/websocket/WebSocketServer.java) as the main receptionist for your WebSocket service.
    *   It listens for new client connections on a specific port.
    *   When a new client tries to connect, it performs the initial "WebSocket Handshake" (a special HTTP upgrade request).

    ```bash
    # Conceptual Server Startup
    $ ./run_jsocket_server --port 8080 --listener YourAppLogic

    # WebSocketServer: Listening on port 8080...
    # Client trying to connect from IP: 192.168.1.101
    # WebSocketServer: Performing handshake with 192.168.1.101...
    ```

2.  **Connection Established (`ClientHandler.java`)**
    *   If the handshake is successful, the `WebSocketServer` creates a dedicated [`ClientHandler`](javaWebsocketChess/websocketCore/src/main/java/com/jSocket/websocket/server/ClientHandler.java) for this specific client.
    *   The `ClientHandler` is like a personal assistant assigned to manage all communication for that one client.
    *   It then starts two specialized workers: a `WebSocketDataReader` and a `WebSocketDataWriter`.

    ```bash
    # WebSocketServer: Handshake with 192.168.1.101 successful!
    # WebSocketServer: Creating ClientHandler for 192.168.1.101.

    # ClientHandler (for 192.168.1.101): I'm now active.
    # ClientHandler: Starting my WebSocketDataReader (to listen for messages).
    # ClientHandler: Starting my WebSocketDataWriter (to send messages).
    # ClientHandler: Notifying YourAppLogic.onOpen(client_192_168_1_101).
    ```

3.  **Receiving Data (`WebSocketDataReader.java` & `WebSocketFrame.java`)**
    *   The [`WebSocketDataReader`](javaWebsocketChess/websocketCore/src/main/java/com/jSocket/websocket/server/WebSocketDataReader.java) runs in its own thread, constantly listening for incoming data from the client's connection.
    *   When data arrives, it uses [`WebSocketFrame.WebSocketFrameparseClientFrame()`](javaWebsocketChess/websocketCore/src/main/java/com/jSocket/websocket/server/WebSocketFrame.java) to decode the raw bytes into a structured `WebSocketFrame`. This frame tells us if it's text, binary, a ping, etc.
    *   The `WebSocketDataReader` then passes this structured frame to its `ClientHandler`.

    ```bash
    # WebSocketDataReader (for 192.168.1.101): Listening for data...
    # WebSocketDataReader: Received raw bytes: [0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58]
    # WebSocketDataReader: Using WebSocketFrame to parse...
    #   Parsed Frame: Type=TEXT, Content="Hello Server"
    # WebSocketDataReader: Telling ClientHandler about new Frame("Hello Server").
    ```

4.  **Processing Data (`ClientHandler.java` & `WebSocketListener.java`)**
    *   The `ClientHandler` receives the parsed `WebSocketFrame`.
    *   It looks at the frame's type (opcode):
        *   If it's a **TEXT** message, it calls `yourAppLogic.onMessage(client, "Hello Server")`. (Your `ChessGameManager` is an example of `yourAppLogic` via the [`WebSocketListener`](javaWebsocketChess/websocketCore/src/main/java/com/jSocket/websocket/server/WebSocketListener.java) interface).
        *   If it's a **PING**, the `ClientHandler` automatically prepares a PONG frame to send back.
        *   If it's a **CLOSE** frame, it starts the connection closing procedure.

    ```bash
    # ClientHandler (for 192.168.1.101): Got Frame("Hello Server") from DataReader.
    # ClientHandler: It's a TEXT frame.
    # ClientHandler: Notifying YourAppLogic.onMessage(client_192_168_1_101, "Hello Server").

    # YourAppLogic (e.g., ChessGameManager):
    #   Received "Hello Server" from client_192_168_1_101.
    #   Let's say my logic is to reply with "Hello Client".
    #   Calling: client_192_168_1_101.sendMessage("Hello Client").
    ```

5.  **Sending Data (`WebSocketDataWriter.java` & `WebSocketFrame.java`)**
    *   When your application logic (e.g., `ChessGameManager`) wants to send a message (e.g., `clientHandler.sendMessage("Hello Client")`), the `ClientHandler` creates a new `WebSocketFrame`.
    *   This frame is put into a queue for the [`WebSocketDataWriter`](javaWebsocketChess/websocketCore/src/main/java/com/jSocket/websocket/server/WebSocketDataWriter.java).
    *   The `WebSocketDataWriter` (also in its own thread) picks up frames from this queue, converts them back into bytes using [`WebSocketFrame.toBytes()`](javaWebsocketChess/websocketCore/src/main/java/com/jSocket/websocket/server/WebSocketFrame.java), and sends them to the client.

    ```bash
    # ClientHandler (for 192.168.1.101):
    #   Application wants to send "Hello Client".
    #   Creating new WebSocketFrame: Type=TEXT, Content="Hello Client".
    #   Adding Frame("Hello Client") to DataWriter's outgoing queue.

    # WebSocketDataWriter (for 192.168.1.101): Checking my queue...
    # WebSocketDataWriter: Found Frame("Hello Client")!
    # WebSocketDataWriter: Using WebSocketFrame to convert to bytes...
    #   Raw Bytes: [0x81, 0x0c, 0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20, 0x43, 0x6c, 0x69, 0x65, 0x6e, 0x74]
    # WebSocketDataWriter: Sending bytes to client 192.168.1.101.
    ```

6.  **Connection Close**
    *   Either the client or the server can initiate a close.
    *   A special CLOSE frame is exchanged.
    *   The `ClientHandler` manages this, stops its `DataReader` and `DataWriter`, closes the socket, and notifies your application logic via `yourAppLogic.onClose()`.

This cycle of reading, processing, and writing happens continuously for each connected client, all managed by their respective `ClientHandler` and its helpers. The `WebSocketServer` just focuses on accepting new clients.
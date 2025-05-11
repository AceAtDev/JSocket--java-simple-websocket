package javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class WebSocketDataWriter implements Runnable {
    private final OutputStream outputStream;
    private final BlockingQueue<WebSocketFrame> outgoingFrames;
    private final WebSocketDataWriterListener listener;
    private final ClientHandler contextHandler; // The ClientHandler instance for context
    private volatile boolean running = true;

    public WebSocketDataWriter(OutputStream outputStream, BlockingQueue<WebSocketFrame> outgoingFrames, ClientHandler contextHandler, WebSocketDataWriterListener listener) {
        this.outputStream = outputStream;
        this.outgoingFrames = outgoingFrames;
        this.contextHandler = contextHandler;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            while (running && listener.isHandlerRunning() && !Thread.currentThread().isInterrupted()) {
                WebSocketFrame frame = outgoingFrames.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    try {
                        outputStream.write(frame.toBytes());
                        outputStream.flush();
                        System.out.println("WebSocketDataWriter: Sent frame to " + contextHandler.getSocket().getInetAddress() + ": " + frame);
                        listener.onFrameSent(contextHandler, frame);

                        if (frame.getOpcode() == WebSocketFrame.Opcode.CLOSE) {
                            listener.setCloseFrameSentFlag();
                            // If we also received a close frame, the main read loop in ClientHandler
                            // or the reader itself will handle full shutdown.
                            if (listener.hasReceivedCloseFrame()) {
                                running = false; // Both sides sent CLOSE, writer can stop.
                                break;
                            }
                        }
                    } catch (SocketException e) {
                        if (listener.isHandlerRunning()) {
                             System.err.println("WebSocketDataWriter: SocketException during send to " + contextHandler.getSocket().getInetAddress() + ": " + e.getMessage());
                             listener.onWriterError(contextHandler, e);
                        }
                        running = false; // Stop writer thread
                        break;
                    } catch (IOException e) {
                         if (listener.isHandlerRunning()) {
                            System.err.println("WebSocketDataWriter: IOException during send to " + contextHandler.getSocket().getInetAddress() + ": " + e.getMessage());
                            listener.onWriterError(contextHandler, e);
                        }
                        running = false; // Stop writer thread
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            System.out.println("WebSocketDataWriter for " + contextHandler.getSocket().getInetAddress() + " interrupted.");
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            listener.onWriterClosed(contextHandler);
            System.out.println("WebSocketDataWriter for " + contextHandler.getSocket().getInetAddress() + " finished.");
        }
    }

    public void stop() {
        running = false;
        // Thread interruption will be handled by ClientHandler if needed.
    }
}
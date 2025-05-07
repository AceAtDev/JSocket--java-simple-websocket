package javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random; // For generating masking key if we were building a client



/**
 * Represents a WebSocket frame.
 * This class handles parsing of incoming (client-to-server, masked) frames
 * and construction of outgoing (server-to-client, unmasked) frames.
 */
public class WebSocketFrame {

    public enum Opcode {
        CONTINUATION(0x0),
        TEXT(0x1),
        BINARY(0x2),
        // 0x3-7 are reserved for further non-control frames
        CLOSE(0x8),
        PING(0x9),
        PONG(0xA);
        // 0xB-F are reserved for further control frames

        private final int code;

        Opcode(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static Opcode valueOf(int code) {
            for (Opcode op : values()) {
                if (op.getCode() == code) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Unknown opcode: " + code);
        }
    }

    private final boolean fin;        
    private final Opcode opcode;
    private final byte[] payloadData;  // Unmasked payload data

    // Constructor for creating a frame to SEND (server ---> client, unmasked)
    public WebSocketFrame(Opcode opcode, boolean fin, byte[] payloadData) {
        this.opcode = opcode;
        this.fin = fin;
        this.payloadData = payloadData != null ? payloadData : new byte[0];
    }

    // Constructor for a RECEIVED frame (parsed from bytes, client ---> server, payload already unmasked)
    private WebSocketFrame(boolean fin, Opcode opcode, byte[] unmaskedPayload) {
        this.fin = fin;
        this.opcode = opcode;
        this.payloadData = unmaskedPayload;
    }

    public boolean isFin() {
        return fin;
    }

    public Opcode getOpcode() {
        return opcode;
    }

    public byte[] getPayloadData() {
        return payloadData;
    }

    public String getTextPayload() {
        if (opcode == Opcode.TEXT) {
            return new String(payloadData, StandardCharsets.UTF_8);
        }
        // throw an exception if it's not a text frame, or you colud return null if you want
        throw new IllegalStateException("Cannot get text payload for non-TEXT frame. Opcode: " + opcode);
    }

    /**
     * Converts this WebSocketFrame object into a byte array for sending over the network.
     * This method creates an UNMASKED frame (for server-to-client communication).
     *
     * @return byte array representing the frame.
     */
    public byte[] toBytes() {
        int payloadLength = payloadData.length;
        ByteBuffer frameBuffer;
        int headerLength = 2; // Minimum header: FIN/Opcode byte + Mask(0)/Length_short byte

        if (payloadLength <= 125) {
            // No extra bytes for length
        } else if (payloadLength <= 65535) { // 0xFFFF
            headerLength += 2; // 2 extra bytes for 16-bit length
        } else {
            headerLength += 8; // 8 extra bytes for 64-bit length
        }
        frameBuffer = ByteBuffer.allocate(headerLength + payloadLength);

        // Byte 1: FIN bit, RSV bits (all 0), Opcode
        byte b1 = (byte) ((fin ? 0b10000000 : 0) | (opcode.getCode() & 0x0F));
        frameBuffer.put(b1);

        // Byte 2: Mask bit (0 for server-to-client), Payload length
        if (payloadLength <= 125) {
            frameBuffer.put((byte) payloadLength); 
        } else if (payloadLength <= 65535) {
            frameBuffer.put((byte) 126); 
            frameBuffer.putShort((short) payloadLength);
        } else {
            frameBuffer.put((byte) 127); 
            frameBuffer.putLong(payloadLength);
        }

        // Payload Data (no mask for server-to-client frames)
        frameBuffer.put(payloadData);

        frameBuffer.flip(); // Prepare buffer for reading (e.g., to get the byte array)
        return Arrays.copyOf(frameBuffer.array(), frameBuffer.limit());
    }

    /**
     * Parses a WebSocket frame from a ByteBuffer (typically containing data read from a client).
     * This method expects a MASKED frame (client-to-server).
     * The ByteBuffer's position will be advanced past the parsed frame.
     *
     * @param buffer The ByteBuffer containing the raw frame data.
     * @return 
     * @return A WebSocketFrame object.
     * @throws ProtocolException if the frame is malformed or doesn't follow WebSocket protocol.
     * @throws BufferUnderflowException if the buffer doesn't contain enough data for a full frame.
     */
    public static WebSocketFrame WebSocketFrameparseClientFrame(ByteBuffer buffer) throws ProtocolException, BufferUnderflowException {
        if (buffer.remaining() < 2) {
            throw new BufferUnderflowException("Insufficient data for frame header (need at least 2 bytes). Remaining: " + buffer.remaining());
        }

        // Byte 1: FIN, RSV1-3, Opcode
        byte b1 = buffer.get();
        boolean fin = (b1 & 0b10000000) != 0;
        // RSV bits (b1 & 0b01110000) should be 0. Add validation if needed.
        // if ((b1 & 0b01110000) != 0) throw new ProtocolException("RSV bits must be 0.");
        Opcode opcode = Opcode.valueOf(b1 & 0x0F);

        // Byte 2: Mask bit, Payload length
        byte b2 = buffer.get();
        boolean isMasked = (b2 & 0b10000000) != 0;
        if (!isMasked) {
            // As per RFC 6455, client frames MUST be masked. IT'S A MUST.
            throw new ProtocolException("Client frame must be masked.");
        }

        long payloadLength = b2 & 0x7F; // 7-bit payload length (0-125)

        if (payloadLength == 126) { // 16-bit extended payload length
            if (buffer.remaining() < 2) throw new BufferUnderflowException("Insufficient data for 16-bit payload length. Remaining: " + buffer.remaining());
            payloadLength = buffer.getShort() & 0xFFFF; // Read as unsigned short
        } else if (payloadLength == 127) { // 64-bit extended payload length
            if (buffer.remaining() < 8) throw new BufferUnderflowException("Insufficient data for 64-bit payload length. Remaining: " + buffer.remaining());
            payloadLength = buffer.getLong();
            if (payloadLength < 0) throw new ProtocolException("Invalid 64-bit payload length (MSB set, too large)."); // Or payloadLength > MAX_INT if byte[]
        }
        
        // WebSocket spec section 5.1: "payload length MUST NOT be larger than 2^63-1"
        // Java arrays are limited by Integer.MAX_VALUE.
        if (payloadLength > Integer.MAX_VALUE) {
            throw new ProtocolException("Payload length exceeds Integer.MAX_VALUE: " + payloadLength);
        }
        int intPayloadLength = (int) payloadLength;


        // Masking Key (4 bytes)
        if (buffer.remaining() < 4) throw new BufferUnderflowException("Insufficient data for masking key. Remaining: " + buffer.remaining());
        byte[] maskingKey = new byte[4];
        buffer.get(maskingKey);

        // Payload Data
        if (buffer.remaining() < intPayloadLength) {
            throw new BufferUnderflowException("Insufficient data for payload. Expected: " + intPayloadLength + ", Remaining: " + buffer.remaining());
        }
        byte[] maskedPayload = new byte[intPayloadLength];
        buffer.get(maskedPayload);

        // Unmask payload
        byte[] unmaskedPayload = new byte[intPayloadLength];
        for (int i = 0; i < intPayloadLength; i++) {
            unmaskedPayload[i] = (byte) (maskedPayload[i] ^ maskingKey[i % 4]);
        }

        return new WebSocketFrame(fin, opcode, unmaskedPayload);
    }





    // --- Static helper methods for creating common frames ---

    

    
    public static WebSocketFrame createTextFrame(String text, boolean fin) {
        if (text == null) text = "";
        return new WebSocketFrame(Opcode.TEXT, fin, text.getBytes(StandardCharsets.UTF_8));
    }

    public static WebSocketFrame createCloseFrame(int statusCode, String reasonText) {
        // Max reasonText length for a close frame is 123 bytes (125 - 2 for status code).
        byte[] reasonBytes = (reasonText != null && !reasonText.isEmpty()) ? reasonText.getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (reasonBytes.length > 123) {
            reasonBytes = Arrays.copyOf(reasonBytes, 123);
        }

        ByteBuffer payload = ByteBuffer.allocate(2 + reasonBytes.length);
        payload.putShort((short) statusCode);
        payload.put(reasonBytes);
        return new WebSocketFrame(Opcode.CLOSE, true, payload.array()); 
    }
    
    public static WebSocketFrame createPingFrame(byte[] payload) {
        // Ping payload can be empty or up to 125 bytes
        if (payload != null && payload.length > 125) {
            throw new IllegalArgumentException("Ping payload cannot exceed 125 bytes.");
        }
        return new WebSocketFrame(Opcode.PING, true, payload == null ? new byte[0] : payload);
    }

    public static WebSocketFrame createPongFrame(byte[] payload) {
        // Pong payload should typically mirror the Ping payload, up to 125 bytes
         if (payload != null && payload.length > 125) {
            throw new IllegalArgumentException("Pong payload cannot exceed 125 bytes.");
        }
        return new WebSocketFrame(Opcode.PONG, true, payload == null ? new byte[0] : payload);
    }

    public static class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }
    
    public static class BufferUnderflowException extends IOException {
        public BufferUnderflowException(String message) {
            super(message);
        }
    }

    @Override
    public String toString() {
        return "WebSocketFrame{" +
               "fin=" + fin +
               ", opcode=" + opcode +
               ", payloadLength=" + (payloadData != null ? payloadData.length : 0) +
               (opcode == Opcode.TEXT ? ", textPayload='" + getTextPayloadPreview() + "'" : "") +
               '}';
    }

    private String getTextPayloadPreview() {
        if (payloadData == null || opcode != Opcode.TEXT) return "N/A";
        String text = new String(payloadData, StandardCharsets.UTF_8);
        if (text.length() > 50) {
            return text.substring(0, 47) + "...";
        }
        return text;
    }
}
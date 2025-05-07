package javaWebsocketChess.chess;

import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.ClientHandler; // Correct import
import javaWebsocketChess.websocketCore.src.main.java.com.jSocket.websocket.server.WebSocketListener; // Correct import
// Other imports for managing players, games, etc.

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChessGameManager implements WebSocketListener {

    private ClientHandler waitingPlayer = null;
    private ClientHandler player1 = null; // White
    private ClientHandler player2 = null; // Black
    private boolean gameInProgress = false;
    private ClientHandler currentPlayerTurn = null;

    private static final String PLAYER_1_NAME = "Player 1 (White)";
    private static final String PLAYER_2_NAME = "Player 2 (Black)";

    private String[][] board;
    private static final Pattern MOVE_PATTERN = Pattern.compile("([a-h])([1-8])([a-h])([1-8])([qrbn])?"); // Added optional promotion piece

    // Piece identifiers
    private static final String WHITE_PAWN = "wP";
    private static final String WHITE_ROOK = "wR";
    private static final String WHITE_KNIGHT = "wN";
    private static final String WHITE_BISHOP = "wB";
    private static final String WHITE_QUEEN = "wQ";
    private static final String WHITE_KING = "wK";
    private static final String BLACK_PAWN = "bP";
    private static final String BLACK_ROOK = "bR";
    private static final String BLACK_KNIGHT = "bN";
    private static final String BLACK_BISHOP = "bB";
    private static final String BLACK_QUEEN = "bQ";
    private static final String BLACK_KING = "bK";

    // Game State for advanced rules
    private boolean whiteKingMoved;
    private boolean whiteRookAMoved; // Queenside (a1)
    private boolean whiteRookHMoved; // Kingside (h1)
    private boolean blackKingMoved;
    private boolean blackRookAMoved; // Queenside (a8)
    private boolean blackRookHMoved; // Kingside (h8)
    private int[] enPassantTargetSquare; // [row, col] or null if no en passant is possible

    public ChessGameManager() {
        System.out.println("ChessGameManager initialized. Waiting for players...");
    }

    private void initializeBoardAndState() {
        board = new String[8][8];
        // White pieces
        board[0] = new String[]{WHITE_ROOK, WHITE_KNIGHT, WHITE_BISHOP, WHITE_QUEEN, WHITE_KING, WHITE_BISHOP, WHITE_KNIGHT, WHITE_ROOK};
        Arrays.fill(board[1], WHITE_PAWN);
        // Black pieces
        board[7] = new String[]{BLACK_ROOK, BLACK_KNIGHT, BLACK_BISHOP, BLACK_QUEEN, BLACK_KING, BLACK_BISHOP, BLACK_KNIGHT, BLACK_ROOK};
        Arrays.fill(board[6], BLACK_PAWN);

        // Initialize game state variables
        whiteKingMoved = false;
        whiteRookAMoved = false;
        whiteRookHMoved = false;
        blackKingMoved = false;
        blackRookAMoved = false;
        blackRookHMoved = false;
        enPassantTargetSquare = null;

        System.out.println("ChessGameManager: Board and game state initialized.");
    }

    private String getBoardStateJson() {
        if (board == null) return "[]";
        StringBuilder json = new StringBuilder("[");
        for (int r = 0; r < 8; r++) {
            json.append("[");
            for (int c = 0; c < 8; c++) {
                json.append("\"").append(board[r][c] == null ? "" : board[r][c]).append("\"");
                if (c < 7) json.append(",");
            }
            json.append("]");
            if (r < 7) json.append(",");
        }
        json.append("]");
        return json.toString();
    }
    
    private String getBoardStateString() { 
        if (board == null) return "Board not initialized.";
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            sb.append(r + 1).append(" ");
            for (int c = 0; c < 8; c++) {
                sb.append(String.format("%3s", board[r][c] == null ? "." : board[r][c]));
            }
            sb.append("\n");
        }
        sb.append("   a  b  c  d  e  f  g  h\n");
        return sb.toString().trim();
    }

    private int[] algebraicToIndices(String algebraic) {
        if (algebraic == null || algebraic.length() != 2) return null;
        char fileChar = algebraic.charAt(0);
        char rankChar = algebraic.charAt(1);
        if (fileChar < 'a' || fileChar > 'h' || rankChar < '1' || rankChar > '8') return null;
        int col = fileChar - 'a';
        int row = rankChar - '1'; // 0-indexed row
        return new int[]{row, col};
    }

    @Override
    public synchronized void onOpen(ClientHandler connection) {
        System.out.println("ChessGameManager: New connection from " + connection.getSocket().getInetAddress());

        if (gameInProgress && (connection != player1 && connection != player2)) {
            connection.sendMessage("{\"type\":\"info\", \"message\":\"A game is already in progress. Please wait.\"}");
            // Optionally close connection or add to a spectator list
            return;
        }

        if (waitingPlayer == null) {
            waitingPlayer = connection;
            System.out.println("ChessGameManager: " + PLAYER_1_NAME + " connected. Waiting for opponent.");
            waitingPlayer.sendMessage("{\"type\":\"status\", \"role\":\"" + PLAYER_1_NAME + "\", \"message\":\"Welcome! You are " + PLAYER_1_NAME + ". Waiting for an opponent...\"}");
        } else if (waitingPlayer == connection) {
            // Player refreshed or reconnected while waiting
            System.out.println("ChessGameManager: Waiting player (" + connection.getSocket().getInetAddress() + ") re-triggered onOpen. No action.");
            return;
        } else {
            player1 = waitingPlayer;
            player2 = connection;
            waitingPlayer = null;
            gameInProgress = true;
            currentPlayerTurn = player1; 

            initializeBoardAndState(); 

            System.out.println("ChessGameManager: " + PLAYER_2_NAME + " connected. Game starting between " +
                               player1.getSocket().getInetAddress() + " and " + player2.getSocket().getInetAddress());
            System.out.println("Initial board state:\n" + getBoardStateString());

            String initialBoardJson = getBoardStateJson();
            player1.sendMessage("{\"type\":\"game_start\", \"role\":\"" + PLAYER_1_NAME + "\", \"opponent\":\"" + PLAYER_2_NAME + "\", \"board\":" + initialBoardJson + ", \"message\":\"Game started! It's your turn.\"}");
            player2.sendMessage("{\"type\":\"game_start\", \"role\":\"" + PLAYER_2_NAME + "\", \"opponent\":\"" + PLAYER_1_NAME + "\", \"board\":" + initialBoardJson + ", \"message\":\"Game started! It's " + PLAYER_1_NAME + "'s turn.\"}");
        }
    }

    @Override
    public synchronized void onMessage(ClientHandler connection, String message) {
        if (!gameInProgress) {
            connection.sendMessage("{\"type\":\"error\", \"message\":\"No game in progress or waiting for opponent.\"}");
            return;
        }
        if (connection != player1 && connection != player2) {
            connection.sendMessage("{\"type\":\"info\", \"message\":\"You are not part of the current game.\"}");
            return;
        }
        System.out.println("ChessGameManager: Message from " + (connection == player1 ? PLAYER_1_NAME : PLAYER_2_NAME) + ": " + message);

        if (connection != currentPlayerTurn) {
            connection.sendMessage("{\"type\":\"error\", \"message\":\"It's not your turn.\"}");
            return;
        }

        Matcher matcher = MOVE_PATTERN.matcher(message.toLowerCase().trim());
        if (!matcher.matches()) {
            connection.sendMessage("{\"type\":\"error\", \"message\":\"Invalid move format. Use algebraic like 'e2e4' or 'e7e8q' for promotion.\"}");
            return;
        }

        String fromAlg = matcher.group(1) + matcher.group(2);
        String toAlg = matcher.group(3) + matcher.group(4);
        String promotionPieceChar = matcher.group(5); // e.g., "q", "r", "b", "n" or null

        int[] fromIndices = algebraicToIndices(fromAlg);
        int[] toIndices = algebraicToIndices(toAlg);

        if (fromIndices == null || toIndices == null) {
            connection.sendMessage("{\"type\":\"error\", \"message\":\"Internal error parsing move coordinates.\"}");
            return;
        }
        int fromRow = fromIndices[0];
        int fromCol = fromIndices[1];
        int toRow = toIndices[0];
        int toCol = toIndices[1];

        String pieceToMove = board[fromRow][fromCol];
        if (pieceToMove == null) {
            connection.sendMessage("{\"type\":\"error\", \"message\":\"Source square " + fromAlg + " is empty.\"}");
            return;
        }

        boolean isCurrentPlayerWhite = (connection == player1);
        if ((isCurrentPlayerWhite && !pieceToMove.startsWith("w")) || (!isCurrentPlayerWhite && !pieceToMove.startsWith("b"))) {
            connection.sendMessage("{\"type\":\"error\", \"message\":\"You cannot move your opponent's piece from " + fromAlg + ".\"}");
            return;
        }

        // Call ChessLogic for move validation
        if (!ChessLogic.isValidMove(pieceToMove, fromRow, fromCol, toRow, toCol, isCurrentPlayerWhite, this.board,
                                    whiteKingMoved, whiteRookAMoved, whiteRookHMoved,
                                    blackKingMoved, blackRookAMoved, blackRookHMoved,
                                    enPassantTargetSquare)) {
            connection.sendMessage("{\"type\":\"error\", \"message\":\"Invalid move for " + pieceToMove + " from " + fromAlg + " to " + toAlg + ".\"}");
            System.out.println("ChessGameManager: Invalid move (ChessLogic): " + pieceToMove + " " + fromAlg + toAlg);
            // Optionally send current board state if client expects it on error
            // connection.sendMessage("{\"type\":\"board_update\", \"board\":" + getBoardStateJson() + "}");
            return; 
        }
        
        System.out.println("ChessGameManager: Valid move. Processing " + pieceToMove + " from " + fromAlg + " to " + toAlg);
        String capturedPiece = board[toRow][toCol]; // For client message

        // --- Apply the move and update game state ---
        board[toRow][toCol] = pieceToMove;
        board[fromRow][fromCol] = null;

        // 1. Handle En Passant capture (remove the captured pawn)
        if (pieceToMove.endsWith("P") && enPassantTargetSquare != null &&
            toRow == enPassantTargetSquare[0] && toCol == enPassantTargetSquare[1]) {
            int capturedPawnRow = isCurrentPlayerWhite ? toRow - 1 : toRow + 1;
            capturedPiece = board[capturedPawnRow][toCol]; // Store the actual captured pawn for the message
            board[capturedPawnRow][toCol] = null;
            System.out.println("ChessGameManager: En passant capture of " + capturedPiece + " at " + (char)('a'+toCol) + (capturedPawnRow+1));
        }

        // 2. Handle Castling (move the rook)
        if (pieceToMove.endsWith("K") && Math.abs(fromCol - toCol) == 2) {
            if (toCol == 6) { // Kingside
                board[fromRow][5] = board[fromRow][7]; // Move rook
                board[fromRow][7] = null;
                System.out.println("ChessGameManager: Kingside castle for " + (isCurrentPlayerWhite ? "White" : "Black"));
            } else if (toCol == 2) { // Queenside
                board[fromRow][3] = board[fromRow][0]; // Move rook
                board[fromRow][0] = null;
                System.out.println("ChessGameManager: Queenside castle for " + (isCurrentPlayerWhite ? "White" : "Black"));
            }
        }

        // 3. Update Castling Rights
        if (pieceToMove.equals(WHITE_KING)) whiteKingMoved = true;
        else if (pieceToMove.equals(BLACK_KING)) blackKingMoved = true;
        else if (pieceToMove.equals(WHITE_ROOK)) {
            if (fromRow == 0 && fromCol == 0) whiteRookAMoved = true;
            else if (fromRow == 0 && fromCol == 7) whiteRookHMoved = true;
        } else if (pieceToMove.equals(BLACK_ROOK)) {
            if (fromRow == 7 && fromCol == 0) blackRookAMoved = true;
            else if (fromRow == 7 && fromCol == 7) blackRookHMoved = true;
        }

        // 4. Handle Pawn Promotion
        String promotedToPiece = null;
        if (pieceToMove.endsWith("P")) {
            if ((isCurrentPlayerWhite && toRow == 7) || (!isCurrentPlayerWhite && toRow == 0)) {
                String colorPrefix = isCurrentPlayerWhite ? "w" : "b";
                String newPieceType = "Q"; // Default to Queen
                if (promotionPieceChar != null) {
                    switch (promotionPieceChar) {
                        case "r": newPieceType = "R"; break;
                        case "b": newPieceType = "B"; break;
                        case "n": newPieceType = "N"; break;
                        // "q" or invalid defaults to Queen
                    }
                }
                promotedToPiece = colorPrefix + newPieceType;
                board[toRow][toCol] = promotedToPiece;
                System.out.println("ChessGameManager: Pawn promoted to " + promotedToPiece);
            }
        }

        // 5. Update En Passant Target Square for the NEXT turn
        // Must be done AFTER current move's en passant capture is handled
        if (pieceToMove.endsWith("P") && Math.abs(fromRow - toRow) == 2) {
            enPassantTargetSquare = new int[]{isCurrentPlayerWhite ? fromRow + 1 : fromRow - 1, fromCol};
            System.out.println("ChessGameManager: En passant target set to: " + (char)('a'+enPassantTargetSquare[1]) + (enPassantTargetSquare[0]+1));
        } else {
            enPassantTargetSquare = null;
        }

        // --- Check for Game End (Checkmate or Stalemate) ---
        boolean opponentIsWhite = !isCurrentPlayerWhite;
        String gameEndMessage = null;
        boolean gameOver = false;

        if (ChessLogic.isCheckmate(opponentIsWhite, board, whiteKingMoved, whiteRookAMoved, whiteRookHMoved,
                                   blackKingMoved, blackRookAMoved, blackRookHMoved, enPassantTargetSquare)) {
            gameEndMessage = "Checkmate! " + (isCurrentPlayerWhite ? PLAYER_1_NAME : PLAYER_2_NAME) + " wins!";
            gameOver = true;
        } else if (ChessLogic.isStalemate(opponentIsWhite, board, whiteKingMoved, whiteRookAMoved, whiteRookHMoved,
                                          blackKingMoved, blackRookAMoved, blackRookHMoved, enPassantTargetSquare)) {
            gameEndMessage = "Stalemate! The game is a draw.";
            gameOver = true;
        }

        // --- Send messages to clients ---
        ClientHandler opponent = (connection == player1) ? player2 : player1;
        String senderName = (connection == player1) ? PLAYER_1_NAME : PLAYER_2_NAME;
        String opponentName = (opponent == player1) ? PLAYER_1_NAME : PLAYER_2_NAME;
        String currentBoardJson = getBoardStateJson();

        String opponentMoveType = gameOver ? "game_over" : "opponent_move";
        String ackMoveType = gameOver ? "game_over" : "move_ack";

        String opponentMessageContent = "\"move\":\"" + message + (promotedToPiece != null ? promotedToPiece.substring(1) : "") + "\", \"board\":" + currentBoardJson +
                                        (capturedPiece != null ? ", \"captured\":\"" + capturedPiece + "\"" : "") +
                                        (promotedToPiece != null ? ", \"promoted\":\"" + promotedToPiece + "\"" : "") +
                                        ", \"message\":\"" + (gameOver ? gameEndMessage : "It's your turn.") + "\"";
        
        String ackMessageContent = "\"move\":\"" + message + (promotedToPiece != null ? promotedToPiece.substring(1) : "") + "\", \"board\":" + currentBoardJson +
                                   (capturedPiece != null ? ", \"captured\":\"" + capturedPiece + "\"" : "") +
                                   (promotedToPiece != null ? ", \"promoted\":\"" + promotedToPiece + "\"" : "") +
                                   ", \"message\":\"" + (gameOver ? gameEndMessage : "Move sent. It's " + opponentName + "'s turn.") + "\"";


        opponent.sendMessage("{\"type\":\"" + opponentMoveType + "\", " + opponentMessageContent + "}");
        connection.sendMessage("{\"type\":\"" + ackMoveType + "\", " + ackMessageContent + "}");

        if (gameOver) {
            System.out.println("ChessGameManager: Game Over. " + gameEndMessage);
            System.out.println("Final board state:\n" + getBoardStateString());
            resetGame(); // Or set gameInProgress = false and wait for new game command
        } else {
            currentPlayerTurn = opponent;
            System.out.println("ChessGameManager: Turn switched to " + opponentName);
            System.out.println("Current board state:\n" + getBoardStateString());
            if (ChessLogic.isKingInCheck(opponentIsWhite, board)) {
                 System.out.println("ChessGameManager: " + opponentName + " is in check!");
                 // Optionally send a specific "check" message to the opponent
                 // opponent.sendMessage("{\"type\":\"info\", \"message\":\"You are in check!\"}");
            }
        }
    }

    @Override
    public synchronized void onClose(ClientHandler connection, int code, String reason, boolean remote) {
        System.out.println("ChessGameManager: Connection closed from " + 
                           (connection != null && connection.getSocket() != null ? connection.getSocket().getInetAddress() : "UNKNOWN_ADDRESS") +
                           " Code: " + code + ", Reason: " + reason + ", Remote: " + remote);

        if (connection == waitingPlayer) {
            waitingPlayer = null;
            System.out.println("ChessGameManager: Waiting player disconnected.");
        } else if (connection == player1 || connection == player2) {
            if (!gameInProgress) { // Game might have ended normally before disconnect
                System.out.println("ChessGameManager: A player disconnected but game was not marked as in progress or already ended.");
            } else {
                 ClientHandler opponent = (connection == player1 && player2 != null) ? player2 : (connection == player2 && player1 != null ? player1 : null);
                 String disconnectedPlayerName = (connection == player1) ? PLAYER_1_NAME : PLAYER_2_NAME;

                System.out.println("ChessGameManager: " + disconnectedPlayerName + " disconnected from the game.");
                if (opponent != null && opponent.isOpen()) {
                    opponent.sendMessage("{\"type\":\"opponent_disconnected\", \"message\":\"Your opponent (" + disconnectedPlayerName + ") has disconnected. Game over.\"}");
                }
            }
            resetGame(); // Always reset if a game player disconnects
        } else {
            System.out.println("ChessGameManager: A non-game participant or already handled player disconnected.");
        }
    }

    @Override
    public synchronized void onError(ClientHandler connection, Exception ex) {
        System.err.println("ChessGameManager: Error on connection " +
                           (connection != null && connection.getSocket() != null ? connection.getSocket().getInetAddress() : "UNKNOWN") +
                           ": " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
        // ex.printStackTrace(); // For more detailed debugging
        if (connection != null) {
            // Attempt to notify client before closing, then handle onClose logic
            // connection.sendMessage("{\"type\":\"error\", \"message\":\"A server error occurred. Disconnecting.\"}");
            onClose(connection, 1011, "Connection error: " + ex.getMessage(), true); 
        }
    }

    private void resetGame() {
        System.out.println("ChessGameManager: Resetting game state.");
        player1 = null;
        player2 = null;
        // waitingPlayer is not reset here, as a new game might start with them.
        // If a game was in progress, waitingPlayer should be null anyway.
        gameInProgress = false;
        currentPlayerTurn = null;
        board = null; 
        
        // Reset game state variables
        whiteKingMoved = false;
        whiteRookAMoved = false;
        whiteRookHMoved = false;
        blackKingMoved = false;
        blackRookAMoved = false;
        blackRookHMoved = false;
        enPassantTargetSquare = null;
        System.out.println("ChessGameManager: Waiting for new players...");
    }
}
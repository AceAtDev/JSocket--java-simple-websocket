package javaWebsocketChess.chess;

import java.util.Arrays;

public class ChessLogic {

    private static final int BOARD_SIZE = 8;

    // --- Helper Methods for identifying pieces and positions ---
    private static boolean isSquareOccupiedByOwnPiece(int r, int c, boolean isWhiteTurn, String[][] board) {
        if (!isSquareOnBoard(r, c) || board[r][c] == null) return false;
        boolean isTargetWhite = board[r][c].startsWith("w");
        return isWhiteTurn == isTargetWhite;
    }

    private static boolean isSquareOccupiedByOpponent(int r, int c, boolean isWhiteTurn, String[][] board) {
        if (!isSquareOnBoard(r, c) || board[r][c] == null) return false;
        boolean isTargetWhite = board[r][c].startsWith("w");
        return isWhiteTurn != isTargetWhite;
    }

    private static boolean isSquareOnBoard(int r, int c) {
        return r >= 0 && r < BOARD_SIZE && c >= 0 && c < BOARD_SIZE;
    }

    private static int[] findKingPosition(boolean findWhiteKing, String[][] board) {
        String kingToFind = findWhiteKing ? "wK" : "bK";
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                if (kingToFind.equals(board[r][c])) {
                    return new int[]{r, c};
                }
            }
        }
        return null; // Should not happen in a valid game
    }

    // --- Core Move Validation ---
    public static boolean isValidMove(String piece, int fromRow, int fromCol, int toRow, int toCol,
                                      boolean isWhitePlayerTurn, String[][] board,
                                      boolean whiteKingMoved, boolean whiteRookAMoved, boolean whiteRookHMoved,
                                      boolean blackKingMoved, boolean blackRookAMoved, boolean blackRookHMoved,
                                      int[] enPassantTargetSquare) { // enPassantTargetSquare: [row, col] or null

        if (piece == null) return false;
        if (!isSquareOnBoard(toRow, toCol)) return false;
        if (fromRow == toRow && fromCol == toCol) return false;
        if (isSquareOccupiedByOwnPiece(toRow, toCol, isWhitePlayerTurn, board)) return false;

        String pieceType = piece.substring(1);
        boolean isPieceWhite = piece.startsWith("w");
        if (isPieceWhite != isWhitePlayerTurn) return false; // Moving opponent's piece

        boolean isPseudoLegal; // Legal according to piece movement rules, before checking for self-check

        switch (pieceType) {
            case "P":
                isPseudoLegal = isValidPawnMove(fromRow, fromCol, toRow, toCol, isWhitePlayerTurn, board, enPassantTargetSquare);
                break;
            case "R":
                isPseudoLegal = isValidRookMove(fromRow, fromCol, toRow, toCol, board);
                break;
            case "N":
                isPseudoLegal = isValidKnightMove(fromRow, fromCol, toRow, toCol);
                break;
            case "B":
                isPseudoLegal = isValidBishopMove(fromRow, fromCol, toRow, toCol, board);
                break;
            case "Q":
                isPseudoLegal = isValidQueenMove(fromRow, fromCol, toRow, toCol, board);
                break;
            case "K":
                isPseudoLegal = isValidKingMove(fromRow, fromCol, toRow, toCol, isWhitePlayerTurn, board,
                                                whiteKingMoved, whiteRookAMoved, whiteRookHMoved,
                                                blackKingMoved, blackRookAMoved, blackRookHMoved);
                break;
            default:
                return false;
        }

        if (!isPseudoLegal) {
            return false;
        }

        // After pseudo-legal check, simulate the move and see if the king is in check
        String[][] tempBoard = new String[BOARD_SIZE][BOARD_SIZE];
        for (int i = 0; i < BOARD_SIZE; i++) {
            tempBoard[i] = Arrays.copyOf(board[i], BOARD_SIZE);
        }

        // Simulate the move on the temporary board
        tempBoard[toRow][toCol] = tempBoard[fromRow][fromCol];
        tempBoard[fromRow][fromCol] = null;

        // Handle en passant capture on temp board
        if ("P".equals(pieceType) && enPassantTargetSquare != null &&
            toRow == enPassantTargetSquare[0] && toCol == enPassantTargetSquare[1] &&
            Math.abs(fromCol - toCol) == 1) { // It's an en passant capture
            int capturedPawnRow = isWhitePlayerTurn ? toRow - 1 : toRow + 1;
            tempBoard[capturedPawnRow][toCol] = null;
        }
        
        // Handle castling piece movement on temp board (king already moved, now rook)
        if ("K".equals(pieceType) && Math.abs(fromCol - toCol) == 2) { // Castling move
            if (toCol == 6) { // Kingside
                tempBoard[fromRow][5] = tempBoard[fromRow][7]; // Move rook
                tempBoard[fromRow][7] = null;
            } else if (toCol == 2) { // Queenside
                tempBoard[fromRow][3] = tempBoard[fromRow][0]; // Move rook
                tempBoard[fromRow][0] = null;
            }
        }


        return !isKingInCheck(isWhitePlayerTurn, tempBoard);
    }

    // --- Check Detection ---
    public static boolean isKingInCheck(boolean checkWhiteKing, String[][] board) {
        int[] kingPos = findKingPosition(checkWhiteKing, board);
        if (kingPos == null) return false; // Should not happen

        int kingRow = kingPos[0];
        int kingCol = kingPos[1];

        // Check for attacks from opponent pieces
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                String piece = board[r][c];
                if (piece != null) {
                    boolean isPieceWhite = piece.startsWith("w");
                    // If we are checking white's king, look for black pieces, and vice-versa
                    if (isPieceWhite == checkWhiteKing) continue; // Skip own pieces

                    // Can this opponent piece attack the king's square?
                    // For this check, the "turn" is the opponent's.
                    // The "target" is the king's square.
                    // No need to check for self-check here, just raw attack capability.
                    String pieceType = piece.substring(1);
                    boolean canAttackKing = false;
                    switch (pieceType) {
                        case "P":
                            canAttackKing = isPawnAttacking(r, c, kingRow, kingCol, !checkWhiteKing, board);
                            break;
                        case "R":
                            canAttackKing = isValidRookMove(r, c, kingRow, kingCol, board);
                            break;
                        case "N":
                            canAttackKing = isValidKnightMove(r, c, kingRow, kingCol);
                            break;
                        case "B":
                            canAttackKing = isValidBishopMove(r, c, kingRow, kingCol, board);
                            break;
                        case "Q":
                            canAttackKing = isValidQueenMove(r, c, kingRow, kingCol, board);
                            break;
                        case "K": // King attacking king (should only be 1 square away)
                            canAttackKing = (Math.abs(kingRow - r) <= 1 && Math.abs(kingCol - c) <= 1);
                            break;
                    }
                    if (canAttackKing) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    // Simplified pawn attack check for isKingInCheck (doesn't consider forward moves)
    private static boolean isPawnAttacking(int fromRow, int fromCol, int toRow, int toCol, boolean isAttackerWhite, String[][] board) {
        int direction = isAttackerWhite ? 1 : -1;
        return toRow == fromRow + direction && Math.abs(toCol - fromCol) == 1;
    }


    // --- Individual Piece Move Validation (Pseudo-Legal) ---

    private static boolean isValidPawnMove(int fromRow, int fromCol, int toRow, int toCol, boolean isWhite, String[][] board, int[] enPassantTargetSquare) {
        int direction = isWhite ? 1 : -1;
        int startRow = isWhite ? 1 : 6;

        // Standard one-square move
        if (toCol == fromCol && toRow == fromRow + direction && board[toRow][toCol] == null) {
            return true; // Promotion will be handled by GameManager
        }
        // Two-square move from starting position
        if (toCol == fromCol && fromRow == startRow && toRow == fromRow + 2 * direction &&
            board[toRow][toCol] == null && board[fromRow + direction][toCol] == null) {
            return true;
        }
        // Pawn Capture (diagonal)
        if (Math.abs(toCol - fromCol) == 1 && toRow == fromRow + direction && isSquareOccupiedByOpponent(toRow, toCol, isWhite, board)) {
            return true; // Promotion will be handled by GameManager
        }
        // En Passant
        if (enPassantTargetSquare != null && toRow == enPassantTargetSquare[0] && toCol == enPassantTargetSquare[1]) {
            // Check if the capturing pawn is correctly positioned
            return Math.abs(toCol - fromCol) == 1 && toRow == fromRow + direction;
        }
        return false;
    }

    private static boolean isValidRookMove(int fromRow, int fromCol, int toRow, int toCol, String[][] board) {
        if (fromRow != toRow && fromCol != toCol) return false;
        if (fromRow == toRow) {
            int step = (toCol > fromCol) ? 1 : -1;
            for (int c = fromCol + step; c != toCol; c += step) if (board[fromRow][c] != null) return false;
        } else {
            int step = (toRow > fromRow) ? 1 : -1;
            for (int r = fromRow + step; r != toRow; r += step) if (board[r][fromCol] != null) return false;
        }
        return true;
    }

    private static boolean isValidKnightMove(int fromRow, int fromCol, int toRow, int toCol) {
        int rowDiff = Math.abs(toRow - fromRow);
        int colDiff = Math.abs(toCol - fromCol);
        return (rowDiff == 1 && colDiff == 2) || (rowDiff == 2 && colDiff == 1);
    }

    private static boolean isValidBishopMove(int fromRow, int fromCol, int toRow, int toCol, String[][] board) {
        if (Math.abs(toRow - fromRow) != Math.abs(toCol - fromCol)) return false;
        int rowStep = (toRow > fromRow) ? 1 : -1;
        int colStep = (toCol > fromCol) ? 1 : -1;
        int currentRow = fromRow + rowStep;
        int currentCol = fromCol + colStep;
        while (currentRow != toRow) {
            if (board[currentRow][currentCol] != null) return false;
            currentRow += rowStep;
            currentCol += colStep;
        }
        return true;
    }

    private static boolean isValidQueenMove(int fromRow, int fromCol, int toRow, int toCol, String[][] board) {
        return isValidRookMove(fromRow, fromCol, toRow, toCol, board) ||
               isValidBishopMove(fromRow, fromCol, toRow, toCol, board);
    }

    private static boolean isValidKingMove(int fromRow, int fromCol, int toRow, int toCol, boolean isWhiteKing, String[][] board,
                                           boolean whiteKingMoved, boolean whiteRookAMoved, boolean whiteRookHMoved,
                                           boolean blackKingMoved, boolean blackRookAMoved, boolean blackRookHMoved) {
        int rowDiff = Math.abs(toRow - fromRow);
        int colDiff = Math.abs(toCol - fromCol);

        // Standard one-square move
        if (rowDiff <= 1 && colDiff <= 1) {
            return true;
        }

        // Castling
        boolean kingHasMoved = isWhiteKing ? whiteKingMoved : blackKingMoved;
        if (!kingHasMoved && fromRow == toRow && (toCol == 6 || toCol == 2)) { // King hasn't moved, and target is castling square
            if (isKingInCheck(isWhiteKing, board)) return false; // Cannot castle out of check

            if (toCol == 6) { // Kingside castling (O-O)
                boolean rookHMoved = isWhiteKing ? whiteRookHMoved : blackRookHMoved;
                if (!rookHMoved && board[fromRow][5] == null && board[fromRow][6] == null) {
                    // Check if squares king passes through are attacked
                    if (isSquareAttacked(fromRow, 4, !isWhiteKing, board)) return false; // e1/e8
                    if (isSquareAttacked(fromRow, 5, !isWhiteKing, board)) return false; // f1/f8
                    // if (isSquareAttacked(fromRow, 6, !isWhiteKing, board)) return false; // g1/g8 (king lands here)
                    return true;
                }
            } else if (toCol == 2) { // Queenside castling (O-O-O)
                boolean rookAMoved = isWhiteKing ? whiteRookAMoved : blackRookAMoved;
                if (!rookAMoved && board[fromRow][1] == null && board[fromRow][2] == null && board[fromRow][3] == null) {
                    // Check if squares king passes through are attacked
                    if (isSquareAttacked(fromRow, 4, !isWhiteKing, board)) return false; // e1/e8
                    if (isSquareAttacked(fromRow, 3, !isWhiteKing, board)) return false; // d1/d8
                    // if (isSquareAttacked(fromRow, 2, !isWhiteKing, board)) return false; // c1/c8 (king lands here)
                    return true;
                }
            }
        }
        return false;
    }

    // Helper to check if a square is attacked by the opponent
    public static boolean isSquareAttacked(int r, int c, boolean byWhite, String[][] board) {
        // Iterate over all opponent pieces and see if they can attack (r,c)
        for (int i = 0; i < BOARD_SIZE; i++) {
            for (int j = 0; j < BOARD_SIZE; j++) {
                String piece = board[i][j];
                if (piece != null) {
                    boolean isPieceWhite = piece.startsWith("w");
                    if (isPieceWhite != byWhite) continue; // Not an attacker's piece

                    String pieceType = piece.substring(1);
                    boolean canAttack = false;
                    switch (pieceType) {
                        case "P":
                            canAttack = isPawnAttacking(i, j, r, c, byWhite, board);
                            break;
                        case "R":
                            canAttack = isValidRookMove(i, j, r, c, board);
                            break;
                        case "N":
                            canAttack = isValidKnightMove(i, j, r, c);
                            break;
                        case "B":
                            canAttack = isValidBishopMove(i, j, r, c, board);
                            break;
                        case "Q":
                            canAttack = isValidQueenMove(i, j, r, c, board);
                            break;
                        case "K":
                            canAttack = (Math.abs(r - i) <= 1 && Math.abs(c - j) <= 1);
                            break;
                    }
                    if (canAttack) return true;
                }
            }
        }
        return false;
    }
    
    // --- Checkmate and Stalemate (Basic stubs, full logic is complex) ---
    public static boolean isCheckmate(boolean isWhiteTurn, String[][] board,
                                      boolean whiteKingMoved, boolean whiteRookAMoved, boolean whiteRookHMoved,
                                      boolean blackKingMoved, boolean blackRookAMoved, boolean blackRookHMoved,
                                      int[] enPassantTargetSquare) {
        if (!isKingInCheck(isWhiteTurn, board)) {
            return false; // Not in check, so not checkmate
        }
        // Check if there are any legal moves for the current player
        return !hasAnyLegalMoves(isWhiteTurn, board, whiteKingMoved, whiteRookAMoved, whiteRookHMoved,
                                 blackKingMoved, blackRookAMoved, blackRookHMoved, enPassantTargetSquare);
    }

    public static boolean isStalemate(boolean isWhiteTurn, String[][] board,
                                      boolean whiteKingMoved, boolean whiteRookAMoved, boolean whiteRookHMoved,
                                      boolean blackKingMoved, boolean blackRookAMoved, boolean blackRookHMoved,
                                      int[] enPassantTargetSquare) {
        if (isKingInCheck(isWhiteTurn, board)) {
            return false; // In check, so not stalemate (could be checkmate)
        }
        // Check if there are any legal moves for the current player
        return !hasAnyLegalMoves(isWhiteTurn, board, whiteKingMoved, whiteRookAMoved, whiteRookHMoved,
                                 blackKingMoved, blackRookAMoved, blackRookHMoved, enPassantTargetSquare);
    }

    private static boolean hasAnyLegalMoves(boolean isWhiteTurn, String[][] board,
                                           boolean whiteKingMoved, boolean whiteRookAMoved, boolean whiteRookHMoved,
                                           boolean blackKingMoved, boolean blackRookAMoved, boolean blackRookHMoved,
                                           int[] enPassantTargetSquare) {
        for (int rFrom = 0; rFrom < BOARD_SIZE; rFrom++) {
            for (int cFrom = 0; cFrom < BOARD_SIZE; cFrom++) {
                String piece = board[rFrom][cFrom];
                if (piece != null && piece.startsWith(isWhiteTurn ? "w" : "b")) {
                    for (int rTo = 0; rTo < BOARD_SIZE; rTo++) {
                        for (int cTo = 0; cTo < BOARD_SIZE; cTo++) {
                            if (isValidMove(piece, rFrom, cFrom, rTo, cTo, isWhiteTurn, board,
                                            whiteKingMoved, whiteRookAMoved, whiteRookHMoved,
                                            blackKingMoved, blackRookAMoved, blackRookHMoved,
                                            enPassantTargetSquare)) {
                                return true; // Found a legal move
                            }
                        }
                    }
                }
            }
        }
        return false; // No legal moves found
    }
}
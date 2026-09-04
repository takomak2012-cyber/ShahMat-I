package com.shahmat.game.ui.learn

import com.shahmat.game.engine.Board
import com.shahmat.game.engine.Piece
import com.shahmat.game.engine.PieceColor
import com.shahmat.game.engine.PieceType
import com.shahmat.game.engine.Position

/**
 * Builds the demonstration position shown on the learning board. Kept as a plain factory
 * (no Android dependencies) so unit tests can verify the position is rule-legal and that
 * every figure type has at least one legal move to display/animate.
 */
object DemoBoardFactory {

    fun build(): Board {
        val b = Board()
        for (f in 0..7) for (r in 0..7) b.setPiece(Position(f, r), null)

        // Kings always present: white on its back rank (rank 7), black on rank 0.
        b.setPiece(Position(4, 7), Piece(PieceType.KING, PieceColor.WHITE))
        b.setPiece(Position(6, 0), Piece(PieceType.KING, PieceColor.BLACK))   // g8 (not attacked)

        // Pawns (and black pawns to demonstrate captures).
        b.setPiece(Position(4, 3), Piece(PieceType.PAWN, PieceColor.WHITE))   // e4
        b.setPiece(Position(1, 1), Piece(PieceType.PAWN, PieceColor.WHITE))   // b2
        b.setPiece(Position(6, 1), Piece(PieceType.PAWN, PieceColor.WHITE))   // g2
        b.setPiece(Position(3, 2), Piece(PieceType.PAWN, PieceColor.BLACK))   // d5
        b.setPiece(Position(5, 2), Piece(PieceType.PAWN, PieceColor.BLACK))   // f5

        // Knights, bishops, queen, rooks.
        b.setPiece(Position(2, 1), Piece(PieceType.KNIGHT, PieceColor.WHITE)) // c3
        b.setPiece(Position(5, 1), Piece(PieceType.KNIGHT, PieceColor.WHITE)) // f3
        b.setPiece(Position(2, 3), Piece(PieceType.BISHOP, PieceColor.WHITE)) // c4
        b.setPiece(Position(5, 3), Piece(PieceType.BISHOP, PieceColor.WHITE)) // f4

        b.setPiece(Position(0, 7), Piece(PieceType.ROOK, PieceColor.WHITE))   // a1
        b.setPiece(Position(7, 7), Piece(PieceType.ROOK, PieceColor.WHITE))   // h1
        b.setPiece(Position(3, 5), Piece(PieceType.QUEEN, PieceColor.WHITE))  // d3

        return b
    }
}

package com.shahmat.game.engine

enum class PieceType {
    PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING
}

enum class PieceColor {
    WHITE, BLACK
}

data class Piece(
    val type: PieceType,
    val color: PieceColor
) {
    val notation: Char
        get() = when (type) {
            PieceType.PAWN -> 'P'
            PieceType.KNIGHT -> 'N'
            PieceType.BISHOP -> 'B'
            PieceType.ROOK -> 'R'
            PieceType.QUEEN -> 'Q'
            PieceType.KING -> 'K'
        }

    val unicodeWhite: String
        get() = when (type) {
            PieceType.PAWN -> "♙"
            PieceType.KNIGHT -> "♘"
            PieceType.BISHOP -> "♗"
            PieceType.ROOK -> "♖"
            PieceType.QUEEN -> "♕"
            PieceType.KING -> "♔"
        }

    val unicodeBlack: String
        get() = when (type) {
            PieceType.PAWN -> "♟"
            PieceType.KNIGHT -> "♞"
            PieceType.BISHOP -> "♝"
            PieceType.ROOK -> "♜"
            PieceType.QUEEN -> "♛"
            PieceType.KING -> "♚"
        }

    fun unicode(): String = if (color == PieceColor.WHITE) unicodeWhite else unicodeBlack
}
package com.shahmat.game.engine

enum class MoveType {
    NORMAL, CAPTURE, CASTLE_KINGSIDE, CASTLE_QUEENSIDE,
    PROMOTION, EN_PASSANT, DOUBLE_PAWN_PUSH
}

data class Move(
    val from: Position,
    val to: Position,
    val piece: Piece,
    val capturedPiece: Piece? = null,
    val type: MoveType = MoveType.NORMAL,
    val promotionType: PieceType? = null,
    val isCheck: Boolean = false,
    val isCheckmate: Boolean = false
) {
    fun toUci(): String {
        val base = "${from.toAlgebraic()}${to.toAlgebraic()}"
        return if (promotionType != null) {
            val promoNotation = when (promotionType!!) {
                PieceType.QUEEN -> 'q'
                PieceType.ROOK -> 'r'
                PieceType.BISHOP -> 'b'
                PieceType.KNIGHT -> 'n'
                else -> 'q'
            }
            base + promoNotation
        } else base
    }

    fun isCapture(): Boolean = type == MoveType.CAPTURE || type == MoveType.EN_PASSANT

    fun isPromotion(): Boolean = promotionType != null
}
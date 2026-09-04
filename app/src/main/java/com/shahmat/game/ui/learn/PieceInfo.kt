package com.shahmat.game.ui.learn

import androidx.annotation.StringRes
import com.shahmat.game.R
import com.shahmat.game.engine.PieceType

/** Teaching content for the "Спросить" learning screen. All texts live in strings.xml. */
data class PieceInfo(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val movementRes: Int
)

object PieceInfoProvider {
    fun infoFor(type: PieceType): PieceInfo = when (type) {
        PieceType.PAWN -> PieceInfo(R.string.learn_pawn_name, R.string.learn_pawn_desc, R.string.learn_pawn_move)
        PieceType.KNIGHT -> PieceInfo(R.string.learn_knight_name, R.string.learn_knight_desc, R.string.learn_knight_move)
        PieceType.BISHOP -> PieceInfo(R.string.learn_bishop_name, R.string.learn_bishop_desc, R.string.learn_bishop_move)
        PieceType.ROOK -> PieceInfo(R.string.learn_rook_name, R.string.learn_rook_desc, R.string.learn_rook_move)
        PieceType.QUEEN -> PieceInfo(R.string.learn_queen_name, R.string.learn_queen_desc, R.string.learn_queen_move)
        PieceType.KING -> PieceInfo(R.string.learn_king_name, R.string.learn_king_desc, R.string.learn_king_move)
    }
}

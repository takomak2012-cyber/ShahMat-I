package com.shahmat.game.ui.learn

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.shahmat.game.R
import com.shahmat.game.engine.Board
import com.shahmat.game.engine.Piece
import com.shahmat.game.engine.PieceType
import com.shahmat.game.engine.Position

/**
 * Teaching (learning) screen opened by the "Спросить" button. It shows a static demo
 * board with figures, lets the user pick any figure to read its description and watch an
 * animation of how it moves. This board is NOT the real game and no actual chess move can
 * be made from it. Returning to the game finishes this activity and the ongoing game (its
 * ViewModel inside GameActivity) is preserved untouched.
 */
class LearnActivity : AppCompatActivity() {

    private lateinit var learningBoard: LearningBoardView
    private lateinit var tvPrompt: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvDescription: TextView
    private lateinit var tvMovement: TextView
    private lateinit var btnReturn: Button

    private val demoBoard: Board by lazy { DemoBoardFactory.build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_learn)

            learningBoard = findViewById(R.id.learningBoard)
            tvPrompt = findViewById(R.id.tvLearnPrompt)
            tvTitle = findViewById(R.id.tvLearnFigureTitle)
            tvDescription = findViewById(R.id.tvLearnDescription)
            tvMovement = findViewById(R.id.tvLearnMovement)
            btnReturn = findViewById(R.id.btnLearnReturn)

            tvPrompt.text = getString(R.string.learn_select_prompt)
            tvTitle.text = getString(R.string.learn_title)

            learningBoard.setDemoBoard(demoBoard)
            learningBoard.setOnSquareClickListener { pos -> onBoardSquareTapped(pos) }

            btnReturn.setOnClickListener {
                try {
                    finish()
                } catch (e: Exception) {
                    Log.e("ShahMatI", "Learn return error", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ShahMatI", "LearnActivity onCreate error", e)
        }
    }

    private fun onBoardSquareTapped(pos: Position) {
        try {
            val piece = demoBoard.getPiece(pos)
            if (piece == null) {
                // Tapping an empty square just clears the selection.
                learningBoard.clearSelection()
                showPromptOnly()
                return
            }
            learningBoard.selectFigure(pos, legalTargetsFor(pos))
            showPieceInfo(piece.type)
        } catch (e: Exception) {
            Log.e("ShahMatI", "Learn tap error", e)
        }
    }

    private fun legalTargetsFor(from: Position): List<Position> =
        demoBoard.generateLegalMoves()
            .filter { it.from == from }
            .map { it.to }
            .distinct()

    private fun showPieceInfo(type: PieceType) {
        try {
            val info = PieceInfoProvider.infoFor(type)
            tvPrompt.visibility = View.GONE
            tvTitle.text = getString(info.titleRes)
            tvDescription.text = getString(info.descriptionRes)
            tvMovement.text = getString(R.string.learn_how_moves) + "\n" + getString(info.movementRes)
            tvDescription.visibility = View.VISIBLE
            tvMovement.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("ShahMatI", "Learn info error", e)
        }
    }

    private fun showPromptOnly() {
        tvPrompt.visibility = View.VISIBLE
        tvTitle.text = getString(R.string.learn_title)
        tvDescription.visibility = View.GONE
        tvMovement.visibility = View.GONE
    }
}

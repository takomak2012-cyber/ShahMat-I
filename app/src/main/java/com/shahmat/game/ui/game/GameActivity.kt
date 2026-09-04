package com.shahmat.game.ui.game

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import com.shahmat.game.BuildConfig
import com.shahmat.game.R
import com.shahmat.game.engine.Difficulty
import com.shahmat.game.engine.PieceColor
import com.shahmat.game.engine.Position
import com.shahmat.game.ui.gameend.GameEndActivity
import com.shahmat.game.ui.learn.LearnActivity

class GameActivity : AppCompatActivity() {

    private lateinit var viewModel: GameViewModel
    private lateinit var boardView: ChessBoardView
    private lateinit var btnSurrender: Button
    private lateinit var btnHint: Button
    private lateinit var btnAsk: Button
    private lateinit var btnMinimize: Button
    private lateinit var btnDonate: Button
    private lateinit var tvTurnIndicator: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvDifficulty: TextView
    private lateinit var gameControlButtons: View

    // Frame-coalesced board refresh. A single move/selection can update several LiveData
    // sources (board, selectedSquare, legalMoves, hintMove, showHints, captured lists) and
    // each used to trigger its own refreshBoardView(), causing multiple redraws per frame.
    // Instead every source funnels into requestBoardRefresh(), which schedules ONE redraw
    // for the whole batch. This preserves exact behaviour while cutting redundant draws.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var boardRefreshPending = false
    private val boardRefreshRunnable = Runnable {
        boardRefreshPending = false
        refreshBoardView()
    }

    private fun requestBoardRefresh() {
        if (boardRefreshPending) return
        boardRefreshPending = true
        mainHandler.post(boardRefreshRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ShahMatI", "GameActivity onCreate")
        try {
            setContentView(R.layout.activity_game)
            Log.d("ShahMatI", "GameActivity setContentView done")

            viewModel = ViewModelProvider(this)[GameViewModel::class.java]
            Log.d("ShahMatI", "ViewModel created")

            boardView = findViewById(R.id.chessBoardView)
            btnSurrender = findViewById(R.id.btnSurrender)
            btnHint = findViewById(R.id.btnHint)
            btnAsk = findViewById(R.id.btnAsk)
            btnMinimize = findViewById(R.id.btnMinimize)
            btnDonate = findViewById(R.id.btnDonate)
            tvTurnIndicator = findViewById(R.id.tvTurnIndicator)
            tvVersion = findViewById(R.id.tvVersion)
            tvDifficulty = findViewById(R.id.tvDifficulty)
            gameControlButtons = findViewById(R.id.gameControlButtons)
            Log.d("ShahMatI", "Views found")

            // Version from BuildConfig so it updates automatically with versionName in Gradle.
            tvVersion.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)

            val playerColor = try {
                PieceColor.valueOf(intent.getStringExtra("playerColor") ?: "WHITE")
            } catch (e: Exception) { PieceColor.WHITE }
            val difficulty = try {
                Difficulty.valueOf(intent.getStringExtra("difficulty") ?: "LEARN")
            } catch (e: Exception) { Difficulty.LEARN }
            val twoPlayer = intent.getBooleanExtra("twoPlayer", false)
            Log.d("ShahMatI", "playerColor=$playerColor, difficulty=$difficulty, twoPlayer=$twoPlayer")

            tvDifficulty.text = if (twoPlayer) {
                getString(R.string.difficulty_two_player)
            } else {
                getString(R.string.difficulty_label, getString(difficultyNameRes(difficulty)))
            }

            viewModel.newGame(playerColor, difficulty, twoPlayer)
            Log.d("ShahMatI", "newGame called")

            setupObservers()
            setupClickListeners()
            updateButtonVisibility(difficulty, twoPlayer)
            applyNavigationBarInsets()
            // Runtime safety net: after the first layout pass, verify the board custom view
            // actually got a visible, non-zero size. With the view's own onMeasure() fallback
            // this should now always hold; the guard logs a diagnostic and forces a re-layout
            // if it ever does not, so an invisible board can't silently ruin the game screen.
            boardView.post { guardChessBoardVisibility() }
            Log.d("ShahMatI", "GameActivity ready")
        } catch (e: Exception) {
            Log.e("ShahMatI", "GameActivity error", e)
            Toast.makeText(this, "Ошибка инициализации: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun guardChessBoardVisibility() {
        try {
            if (boardView.width <= 0 || boardView.height <= 0 || boardView.visibility != View.VISIBLE) {
                Log.w("ShahMatI",
                    "ChessBoardView hidden/zero-size (${boardView.width}x${boardView.height}); re-layout")
                boardView.visibility = View.VISIBLE
                boardView.requestLayout()
            } else {
                Log.d("ShahMatI", "ChessBoardView visible at ${boardView.width}x${boardView.height}")
            }
        } catch (e: Exception) {
            Log.e("ShahMatI", "ChessBoardView guard error", e)
        }
    }

    private fun setupObservers() {
        // All board-drawing sources funnelled through a single request that coalesces
        // multiple LiveData deliveries in the same frame into one redraw.
        viewModel.board.observe(this) { requestBoardRefresh() }
        viewModel.selectedSquare.observe(this) { requestBoardRefresh() }
        viewModel.legalMoves.observe(this) { requestBoardRefresh() }
        viewModel.hintMove.observe(this) { requestBoardRefresh() }
        viewModel.showHints.observe(this) { requestBoardRefresh() }
        viewModel.capturedWhite.observe(this) { requestBoardRefresh() }
        viewModel.capturedBlack.observe(this) { requestBoardRefresh() }

        // Show "hint shown" only once the hint has actually been computed and published.
        viewModel.hintReady.observe(this) { gen ->
            try {
                if (gen != null && gen == viewModel.currentGeneration) {
                    Toast.makeText(this, getString(R.string.hint_shown), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ShahMatI", "Hint ready observer error", e)
            }
        }

        // Warn once the per-game hint allowance of 3 is used up.
        viewModel.hintsExhausted.observe(this) { exhausted ->
            try {
                if (exhausted == true) {
                    Toast.makeText(this, getString(R.string.hint_exhausted), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ShahMatI", "Hints exhausted observer error", e)
            }
        }

        viewModel.currentTurn.observe(this) { color ->
            try {
                val isPlayerTurn = color == viewModel.playerColor.value
                tvTurnIndicator.text = if (isPlayerTurn) getString(R.string.your_turn) else getString(R.string.ai_thinking)
                tvTurnIndicator.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("ShahMatI", "Turn observer error", e)
            }
        }

        viewModel.gameResult.observe(this) { result ->
            try {
                // Draws and resignation show an in-game dialog; victory/defeat are
                // handled by the gameOutcome observer (full-screen end screen).
                if (result != null && viewModel.gameOutcome.value != GameOutcome.VICTORY
                    && viewModel.gameOutcome.value != GameOutcome.DEFEAT) {
                    showGameEndDialog(result)
                }
            } catch (e: Exception) {
                Log.e("ShahMatI", "Result observer error", e)
            }
        }

        viewModel.gameOutcome.observe(this) { outcome ->
            try {
                if (outcome != null && outcome != GameOutcome.DRAW) {
                    navigateToGameEnd(outcome == GameOutcome.VICTORY)
                }
            } catch (e: Exception) {
                Log.e("ShahMatI", "Outcome observer error", e)
            }
        }

        // While the AI is thinking, disable the "Спросить" button so the user cannot
        // leave to the learning screen mid-computation (that would let the AI's result
        // rewrite the game state without the user watching).
        viewModel.aiThinking.observe(this) { thinking ->
            try {
                btnAsk.isEnabled = !thinking
            } catch (e: Exception) {
                Log.e("ShahMatI", "AI thinking observer error", e)
            }
        }
    }

    override fun onDestroy() {
        // Drop any still-scheduled coalesced board redraw so it cannot fire on a destroyed
        // activity. LiveData observers attached with observe(this) are removed automatically.
        if (boardRefreshPending) {
            mainHandler.removeCallbacks(boardRefreshRunnable)
            boardRefreshPending = false
        }
        super.onDestroy()
    }

private fun refreshBoardView() {
        try {
            val playerColor = viewModel.playerColor.value!!
            val board = viewModel.getBoard()
            val twoPlayer = viewModel.isTwoPlayer
            // The "Вам шах!" warning shows whenever the human (solo) or the side to
            // move (two-player) is in check and the game is still running. In a local
            // two-player match the warning therefore follows whichever player's turn it
            // is, because both sides are human.
            val checkSide = if (twoPlayer) {
                if (board.whiteToMove) PieceColor.WHITE else PieceColor.BLACK
            } else {
                playerColor
            }
            val playerInCheck = board.isInCheck(checkSide) && viewModel.gameOver.value != true
            boardView.updateBoard(
                board = board,
                playerColor = playerColor,
                selectedSquare = viewModel.selectedSquare.value,
                legalMoves = viewModel.legalMoves.value!!,
                hintMove = viewModel.hintMove.value,
                showHints = viewModel.showHints.value!!,
                capturedWhite = viewModel.capturedWhite.value!!,
                capturedBlack = viewModel.capturedBlack.value!!,
                playerInCheck = playerInCheck,
                twoPlayer = twoPlayer
            )
        } catch (e: Exception) {
            Log.e("ShahMatI", "Board refresh error", e)
        }
    }

    private fun setupClickListeners() {
        boardView.setOnSquareClickListener { pos ->
            try {
                viewModel.onSquareClicked(pos)
            } catch (e: Exception) {
                Log.e("ShahMatI", "Square click error", e)
            }
        }

        btnSurrender.setOnClickListener {
            try {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.surrender_title))
                    .setMessage(getString(R.string.surrender_message))
                    .setPositiveButton(getString(R.string.surrender_yes)) { _, _ -> viewModel.surrender() }
                    .setNegativeButton(getString(R.string.surrender_no), null)
                    .show()
            } catch (e: Exception) {
                Log.e("ShahMatI", "Surrender click error", e)
            }
        }

        btnHint.setOnClickListener {
            try {
                viewModel.requestHint()
            } catch (e: Exception) {
                Log.e("ShahMatI", "Hint click error", e)
            }
        }

        btnAsk.setOnClickListener {
            try {
                // A defensive double-check: only on teaching levels and when the AI is
                // not computing (the button is disabled in that state anyway).
                if (viewModel.askEnabled) {
                    startActivity(Intent(this, LearnActivity::class.java))
                }
            } catch (e: Exception) {
                Log.e("ShahMatI", "Ask click error", e)
            }
        }

        btnMinimize.setOnClickListener {
            try {
                moveTaskToBack(true)
            } catch (e: Exception) {
                Log.e("ShahMatI", "Minimize click error", e)
            }
        }

        btnDonate.setOnClickListener {
            try {
                openDonatePage()
            } catch (e: Exception) {
                Log.e("ShahMatI", "Donate click error", e)
            }
        }
    }

    private fun updateButtonVisibility(difficulty: Difficulty, twoPlayer: Boolean) {
        try {
            val buttons = if (twoPlayer) GameButtons(showHint = false, showAsk = false) else gameButtonsFor(difficulty)
            btnHint.visibility = if (buttons.showHint) View.VISIBLE else View.GONE
            // The "Спросить" teaching function (buttons.showAsk) is only for LEARN/BEGINNER.
            btnAsk.visibility = if (buttons.showAsk) View.VISIBLE else View.GONE

            val params = btnSurrender.layoutParams as LinearLayout.LayoutParams
            if (!buttons.showHint) {
                params.weight = 1f
                btnSurrender.layoutParams = params
                val params2 = btnMinimize.layoutParams as LinearLayout.LayoutParams
                params2.weight = 1f
                btnMinimize.layoutParams = params2
            }
        } catch (e: Exception) {
            Log.e("ShahMatI", "Button visibility error", e)
        }
    }

    private fun navigateToGameEnd(isVictory: Boolean) {
        val intent = Intent(this, GameEndActivity::class.java)
        intent.putExtra(GameEndActivity.EXTRA_RESULT,
            if (isVictory) GameEndActivity.RESULT_VICTORY else GameEndActivity.RESULT_DEFEAT)
        if (!isVictory) {
            intent.putExtra(GameEndActivity.EXTRA_PLAYER_COLOR, viewModel.playerColor.value?.name)
        }
        intent.putExtra(GameEndActivity.EXTRA_TWO_PLAYER, viewModel.isTwoPlayer)
        intent.putExtra(GameEndActivity.EXTRA_WINNER_COLOR, viewModel.winnerColor.value?.name)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun showGameEndDialog(message: String) {
        try {
            val builder = AlertDialog.Builder(this)
                .setTitle(getString(R.string.game_over))
                .setMessage(message)
                .setCancelable(false)

            builder.setNegativeButton(getString(R.string.btn_exit)) { _, _ ->
                // finishAffinity() closes the whole task gracefully; no need to kill
                // the process with System.exit(0) (it also prevents clean teardown).
                finishAffinity()
            }

            builder.show()
        } catch (e: Exception) {
            Log.e("ShahMatI", "Game end dialog error", e)
        }
    }

    private fun openDonatePage() {
        try {
            val url = "https://pay.cloudtips.ru/p/8873a575"
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("ShahMatI", "Donate page error", e)
        }
    }

    override fun onBackPressed() {
        try {
            moveTaskToBack(true)
        } catch (e: Exception) {
            Log.e("ShahMatI", "Back press error", e)
        }
    }

    /** Reserves the system navigation bar (bottom) height below the version label so it
     *  never sits under the gesture/navigation area, including on edge-to-edge devices. */
    private fun applyNavigationBarInsets() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
                val bottomInset = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                ).bottom
                val lp = tvVersion.layoutParams as ViewGroup.MarginLayoutParams
                lp.bottomMargin = bottomInset
                tvVersion.layoutParams = lp
                insets
            }
        } catch (e: Exception) {
            Log.e("ShahMatI", "Insets error", e)
        }
    }
}

/**
 * Maps an engine [Difficulty] to the display string resource for its human-readable
 * level name. Level names are kept in string resources (not in the engine enum) so
 * they can be localised; the engine enum stays free of user-facing text.
 */
private fun difficultyNameRes(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.LEARN -> R.string.difficulty_learn
    Difficulty.BEGINNER -> R.string.difficulty_beginner
    Difficulty.INTERMEDIATE -> R.string.difficulty_intermediate
    Difficulty.MASTER -> R.string.difficulty_master
}
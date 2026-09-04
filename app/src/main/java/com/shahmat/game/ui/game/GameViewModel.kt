package com.shahmat.game.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.shahmat.game.engine.Board
import com.shahmat.game.engine.ChessEngine
import com.shahmat.game.engine.Difficulty
import com.shahmat.game.engine.Move
import com.shahmat.game.engine.Piece
import com.shahmat.game.engine.PieceColor
import com.shahmat.game.engine.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class GameOutcome { VICTORY, DEFEAT, DRAW }

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val _board = MutableLiveData<Board>()
    val board: LiveData<Board> = _board

    private val _currentTurn = MutableLiveData<PieceColor>(PieceColor.WHITE)
    val currentTurn: LiveData<PieceColor> = _currentTurn

    private val _gameResult = MutableLiveData<String?>()
    val gameResult: LiveData<String?> = _gameResult

    private val _gameOutcome = MutableLiveData<GameOutcome?>()
    val gameOutcome: LiveData<GameOutcome?> = _gameOutcome

    private val _selectedSquare = MutableLiveData<Position?>()
    val selectedSquare: LiveData<Position?> = _selectedSquare

    private val _legalMoves = MutableLiveData<List<Move>>()
    val legalMoves: LiveData<List<Move>> = _legalMoves

    private val _hintMove = MutableLiveData<Move?>()
    val hintMove: LiveData<Move?> = _hintMove

    private val _showHints = MutableLiveData<Boolean>(false)
    val showHints: LiveData<Boolean> = _showHints

    /** One-shot signal emitted only AFTER the hint has actually been computed and
     *  published to hintMove. Used by the UI to show "hint shown" at the right time
     *  (never before the hint really is available). The Long payload is the game
     *  generation the hint belongs to, so a stale signal can be filtered out. */
    private val _hintReady = MutableLiveData<Long?>()
    val hintReady: LiveData<Long?> = _hintReady

    private val _playerColor = MutableLiveData<PieceColor>(PieceColor.WHITE)
    val playerColor: LiveData<PieceColor> = _playerColor

    private val _difficulty = MutableLiveData<Difficulty>(Difficulty.LEARN)

    private val _gameOver = MutableLiveData<Boolean>(false)
    val gameOver: LiveData<Boolean> = _gameOver

    private val _capturedWhite = MutableLiveData<List<Piece>>(emptyList())
    val capturedWhite: LiveData<List<Piece>> = _capturedWhite

    private val _capturedBlack = MutableLiveData<List<Piece>>(emptyList())
    val capturedBlack: LiveData<List<Piece>> = _capturedBlack

    /** True when the game is a two-player local match (no AI). */
    private var twoPlayer = false

    /** LiveData mirror of the two-player mode so the UI knows which warnings act on. */
    val isTwoPlayer: Boolean
        get() = twoPlayer

    /** Winner's colour once a game ends; null for a draw. Used by the end screen. */
    private val _winnerColor = MutableLiveData<PieceColor?>()
    val winnerColor: LiveData<PieceColor?> = _winnerColor

    /** Number of hints left for the current game (max 3 per game). */
    private var hintsRemaining = 3

    /** One-shot signal emitted when the player tries to request a hint after exhausting
     *  the per-game limit, so the UI can show a "hints exhausted" toast. */
    private val _hintsExhausted = MutableLiveData<Boolean>(false)
    val hintsExhausted: LiveData<Boolean> = _hintsExhausted

    private val engine = ChessEngine()

    // --- AI search lifecycle ---------------------------------------------------

    /** Monotonically incremented on each new game or surrender. Every background
     *  coroutine captures the current value before starting the slow work; after
     *  the computation it checks whether the generation still matches. A mismatch
     *  means the user already left that game and the result must be discarded. */
    private var gameGeneration = 0L

    /** Current game generation. The UI uses it to filter one-shot signals (e.g. the
     *  hint-ready Toast) so a stale event from a previous game is never shown. */
    val currentGeneration: Long
        get() = gameGeneration

    /** Tracked so newGame() / surrender() can cancel an in-flight AI search.
     *  Cancellation is cooperative — the coroutine will still finish the current
     *  engine call, but the generation guard discards the stale result. */
    private var aiJob: Job? = null

    /** Same pattern for the hint computation. */
    private var hintJob: Job? = null

    /** Flag indicating that the AI is computing a move. Written from both main
     *  and IO threads, therefore must be @Volatile. */
    @Volatile
    private var isAiThinking = false

    /** LiveData mirror of the AI busy flag, so the UI can enable/disable controls
     *  (e.g. the "Спросить" button) while the AI is computing. */
    private val _aiThinking = MutableLiveData<Boolean>(false)
    val aiThinking: LiveData<Boolean> = _aiThinking

    // --- Helpers ----------------------------------------------------------------

    /** Cancel all background work and bump the generation counter. */
    private fun cancelBackgroundWork() {
        aiJob?.cancel()
        aiJob = null
        hintJob?.cancel()
        hintJob = null
        setAiThinking(false)
        gameGeneration++
    }

    /** Sets the AI busy flag and mirrors it to the LiveData for the UI. All threads. */
    private fun setAiThinking(thinking: Boolean) {
        isAiThinking = thinking
        _aiThinking.postValue(thinking)
    }

    // --- Game actions -----------------------------------------------------------

    fun newGame(playerColor: PieceColor, difficulty: Difficulty, twoPlayer: Boolean = false) {
        try {
            // Cancel any in-flight AI or hint computation from the previous game.
            cancelBackgroundWork()

            this.twoPlayer = twoPlayer
            hintsRemaining = 3
            _hintsExhausted.value = false
            _playerColor.value = playerColor
            _difficulty.value = difficulty
            val newBoard = Board()
            _board.value = newBoard
            _currentTurn.value = PieceColor.WHITE
            _gameResult.value = null
            _gameOutcome.value = null
            _winnerColor.value = null
            _selectedSquare.value = null
            _legalMoves.value = emptyList()
            _hintMove.value = null
            _hideHintReady()
            _showHints.value = difficulty == Difficulty.LEARN && !twoPlayer
            _gameOver.value = false
            _capturedWhite.value = emptyList()
            _capturedBlack.value = emptyList()

            if (playerColor == PieceColor.BLACK && !twoPlayer) {
                makeAiMove()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun onSquareClicked(pos: Position) {
        try {
            val board = _board.value ?: return
            if (_gameOver.value == true) return
            if (isAiThinking) return
            // In a two-player match either side may move; otherwise only the human's pieces.
            if (!twoPlayer && board.whiteToMove != (_playerColor.value == PieceColor.WHITE)) return

            val piece = board.getPiece(pos)
            val selected = _selectedSquare.value

            if (selected != null && selected == pos) {
                _selectedSquare.value = null
                _legalMoves.value = emptyList()
                return
            }

            if (selected != null) {
                val move = board.generateLegalMoves().firstOrNull { it.from == selected && it.to == pos }
                if (move != null) {
                    makeMove(move)
                    return
                }
            }

            if (piece != null && (twoPlayer || piece.color == _playerColor.value)) {
                _selectedSquare.value = pos
                val moves = board.generateLegalMoves().filter { it.from == pos }
                _legalMoves.value = moves
                if (_difficulty.value == Difficulty.LEARN && !twoPlayer) {
                    _showHints.value = true
                }
            } else {
                _selectedSquare.value = null
                _legalMoves.value = emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun makeMove(move: Move) {
        try {
            val board = _board.value ?: return
            val newBoard = board.makeMove(move)

            if (move.capturedPiece != null) {
                if (move.capturedPiece!!.color == PieceColor.WHITE) {
                    _capturedWhite.value = _capturedWhite.value!! + move.capturedPiece!!
                } else {
                    _capturedBlack.value = _capturedBlack.value!! + move.capturedPiece!!
                }
            }

            _board.value = newBoard
            _selectedSquare.value = null
            _legalMoves.value = emptyList()
            _hintMove.value = null
            _currentTurn.value = if (newBoard.whiteToMove) PieceColor.WHITE else PieceColor.BLACK

            checkGameEnd(newBoard)

            if (_gameOver.value != true && !twoPlayer
                && newBoard.whiteToMove != (_playerColor.value == PieceColor.WHITE)) {
                makeAiMove()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun makeAiMove() {
        try {
            val board = _board.value ?: return
            setAiThinking(true)
            _currentTurn.value = if (board.whiteToMove) PieceColor.WHITE else PieceColor.BLACK

            aiJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val gen = gameGeneration
                    kotlinx.coroutines.delay(500)
                    if (gen != gameGeneration) return@launch
                    val move = engine.findBestMove(board, _difficulty.value!!)
                    setAiThinking(false)

                    // Discard the result if a new game started / game ended while we were computing.
                    if (gen != gameGeneration) return@launch
                    if (move != null) {
                        val newBoard = board.makeMove(move)

                        if (move.capturedPiece != null) {
                            if (move.capturedPiece!!.color == PieceColor.WHITE) {
                                _capturedWhite.postValue(_capturedWhite.value!! + move.capturedPiece!!)
                            } else {
                                _capturedBlack.postValue(_capturedBlack.value!! + move.capturedPiece!!)
                            }
                        }

                        _board.postValue(newBoard)
                        _currentTurn.postValue(if (newBoard.whiteToMove) PieceColor.WHITE else PieceColor.BLACK)
                        checkGameEnd(newBoard)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    setAiThinking(false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            setAiThinking(false)
        }
    }

    private fun checkGameEnd(board: Board) {
        try {
            val result = board.getGameResult()
            if (result != null) {
                _gameOver.postValue(true)
                _winnerColor.postValue(result.winner)
                _gameOutcome.postValue(
                    when {
                        result.winner == _playerColor.value -> GameOutcome.VICTORY
                        result.winner == null -> GameOutcome.DRAW
                        else -> GameOutcome.DEFEAT
                    }
                )
                when {
                    result.winner == _playerColor.value -> _gameResult.postValue("Победа! ${result.reason}")
                    result.winner == null -> _gameResult.postValue("Ничья: ${result.reason}")
                    else -> _gameResult.postValue("Поражение: ${result.reason}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun requestHint() {
        try {
            val board = _board.value ?: return
            if (_gameOver.value == true) return
            if (twoPlayer) return
            if (board.whiteToMove != (_playerColor.value == PieceColor.WHITE)) return

            // Only 3 hints per game; further attempts are rejected with a toast.
            if (hintsRemaining <= 0) {
                _hintsExhausted.value = true
                return
            }
            hintsRemaining--

            // Cancel any previous hint computation so we never show a stale one.
            hintJob?.cancel()

            hintJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val gen = gameGeneration
                    val move = engine.getHint(board, _difficulty.value!!)
                    // Only publish if the game hasn't changed underneath us.
                    if (gen != gameGeneration) return@launch
                    _hintMove.postValue(move)
                    _showHints.postValue(true)
                    // Emit the readiness signal ONLY after the hint is actually available.
                    _hintReady.postValue(gen)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Resets the one-shot hint-ready signal so a new game never leaks the previous one. */
    private fun _hideHintReady() {
        _hintReady.value = null
    }

    fun surrender() {
        try {
            cancelBackgroundWork()
            _gameOver.value = true
            _gameOutcome.value = GameOutcome.DEFEAT
            _winnerColor.value = if (_playerColor.value == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE
            _gameResult.value = "Вы сдались"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getBoard(): Board = _board.value ?: Board()

    /** The "Спросить" feature is only meaningful on the teaching-friendly levels. */
    val askEnabled: Boolean
        get() = !twoPlayer && gameButtonsFor(_difficulty.value ?: Difficulty.LEARN).showAsk
}

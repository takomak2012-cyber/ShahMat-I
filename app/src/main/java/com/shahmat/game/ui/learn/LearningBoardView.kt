package com.shahmat.game.ui.learn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.shahmat.game.engine.Board
import com.shahmat.game.engine.Piece
import com.shahmat.game.engine.PieceColor
import com.shahmat.game.engine.Position

/**
 * Teaching board used by the learning screen. It is NOT a playable game board: it only
 * draws a demonstration position, highlights the chosen figure and its legal destinations,
 * then plays a slow, sequential animation of the figure visiting each destination in turn
 * (out, pause, back, pause), ending with the board in its original state. The animation
 * cancels cleanly when another figure is chosen or when the view is detached (screen closed).
 */
class LearningBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var board: Board = Board()
    private var boardSize = 0f
    private var boardOffsetX = 0f
    private var boardOffsetY = 0f
    private var squareSize = 0f

    // Teaching state (driven by the Activity).
    private var selectedPos: Position? = null
    private var legalTargets: List<Position> = emptyList()

    // Animation state. A monotonically increasing token lets us discard any stale
    // animation frames/delays when a new figure is selected or the view is cleared.
    private var animToken = 0
    private var animFrom: Position? = null
    private var animPieceIsWhite = true
    private var animCurrentTarget: Position? = null
    private var animCurrentIndex = 0
    private var animPhase = Phase.IDLE
    private val animLeg = android.animation.ValueAnimator.ofFloat(0f, 1f)

    private val runnable = Runnable { advanceSequence() }

    private enum class Phase { IDLE, MOVE_OUT, HOLD_OUT, MOVE_BACK, HOLD_BACK }

    // Pacing tuned for a child/beginner: clearly visible but not sluggish.
    private val moveOutDuration = 480L
    private val holdOutDuration = 380L
    private val moveBackDuration = 480L
    private val holdBackDuration = 240L

    private var onSquareClickListener: ((Position) -> Unit)? = null

    init {
        squarePaint.style = Paint.Style.FILL
        piecePaint.style = Paint.Style.FILL
        piecePaint.textAlign = Paint.Align.CENTER
        piecePaint.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        highlightPaint.style = Paint.Style.FILL
        selectionPaint.style = Paint.Style.FILL
        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        framePaint.style = Paint.Style.FILL
        framePaint.color = Color.parseColor("#4E342E")

        animLeg.interpolator = DecelerateInterpolator()
        animLeg.duration = moveOutDuration
    }

    fun setOnSquareClickListener(listener: (Position) -> Unit) {
        onSquareClickListener = listener
    }

    /** Push a fresh position for the teaching board. Does not touch the real game. */
    fun setDemoBoard(demoBoard: Board) {
        this.board = demoBoard
        clearSelection()
    }

    /** Highlight a figure and start the sequential movement demonstration. */
    fun selectFigure(pos: Position, targets: List<Position>) {
        stopAnimation()
        selectedPos = pos
        legalTargets = targets
        invalidate()
        if (targets.isNotEmpty()) {
            animFrom = pos
            animPieceIsWhite = board.getPiece(pos)?.color == PieceColor.WHITE
            animCurrentIndex = 0
            startLegTo(targets[0])
        }
    }

    fun clearSelection() {
        stopAnimation()
        selectedPos = null
        legalTargets = emptyList()
        invalidate()
    }

    /** Aborts any running animation for the current selection, leaving the board as-is. */
    private fun stopAnimation() {
        animToken++
        animLeg.cancel()
        removeCallbacks(runnable)
        animPhase = Phase.IDLE
        animFrom = null
        animCurrentTarget = null
        animCurrentIndex = 0
    }

    private fun startLegTo(target: Position) {
        animCurrentTarget = target
        animPhase = Phase.MOVE_OUT
        animLeg.cancel()
        animLeg.removeAllUpdateListeners()
        animLeg.duration = moveOutDuration
        animLeg.interpolator = DecelerateInterpolator()
        animLeg.addUpdateListener {
            if (it.animatedFraction >= 1f) {
                onLegFinished()
            }
            invalidate()
        }
        animLeg.start()
    }

    private fun onLegFinished() {
        // Guard against a stale frame (a new selection may have started meanwhile).
        if (animPhase == Phase.IDLE) return
        when (animPhase) {
            Phase.MOVE_OUT -> {
                animPhase = Phase.HOLD_OUT
                postDelayedDelayed(holdOutDuration)
            }
            Phase.MOVE_BACK -> {
                animPhase = Phase.HOLD_BACK
                postDelayedDelayed(holdBackDuration)
            }
            else -> Unit
        }
    }

    private fun postDelayedDelayed(delay: Long) {
        postDelayed(runnable, delay)
    }

    private fun advanceSequence() {
        when (animPhase) {
            Phase.HOLD_OUT -> {
                // Move back to the origin.
                animPhase = Phase.MOVE_BACK
                animLeg.cancel()
                animLeg.removeAllUpdateListeners()
                animLeg.duration = moveBackDuration
                animLeg.interpolator = LinearInterpolator()
                animLeg.addUpdateListener {
                    if (it.animatedFraction >= 1f) onLegFinished()
                    invalidate()
                }
                animLeg.start()
            }
            Phase.HOLD_BACK -> {
                animCurrentIndex++
                animCurrentTarget = null
                if (animCurrentIndex < legalTargets.size) {
                    startLegTo(legalTargets[animCurrentIndex])
                } else {
                    // Demonstration complete: board is back to its original state.
                    animPhase = Phase.IDLE
                    invalidate()
                }
            }
            else -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        // Cancel the animation when the screen is closed so no callback fires later.
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        boardSize = minOf(w.toFloat(), h.toFloat()) * 0.92f
        squareSize = boardSize / 8f
        boardOffsetX = (w - boardSize) / 2f
        boardOffsetY = (h - boardSize) / 2f
        piecePaint.textSize = squareSize * 0.62f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            canvas.drawRect(
                boardOffsetX - 4f, boardOffsetY - 4f,
                boardOffsetX + boardSize + 4f, boardOffsetY + boardSize + 4f,
                framePaint
            )

            val lightColor = Color.parseColor("#F0D9B5")
            val darkColor = Color.parseColor("#B58863")
            val selectedColor = Color.parseColor("#FFD54F")
            val targetColor = Color.parseColor("#81C784")

            // Always render from White's perspective (a1 bottom-left), files/ranks clear.
            for (file in 0..7) {
                for (rank in 0..7) {
                    val x = boardOffsetX + file * squareSize
                    val y = boardOffsetY + (7 - rank) * squareSize
                    val isLight = (file + rank) % 2 == 0
                    squarePaint.color = if (isLight) lightColor else darkColor
                    canvas.drawRect(x, y, x + squareSize, y + squareSize, squarePaint)

                    val pos = Position(file, rank)

                    if (selectedPos == pos) {
                        selectionPaint.color = selectedColor
                        canvas.drawRect(x, y, x + squareSize, y + squareSize, selectionPaint)
                        selectionPaint.style = Paint.Style.STROKE
                        selectionPaint.strokeWidth = squareSize * 0.06f
                        selectionPaint.color = Color.parseColor("#E65100")
                        canvas.drawRect(x, y, x + squareSize, y + squareSize, selectionPaint)
                        selectionPaint.style = Paint.Style.FILL
                    }

                    if (legalTargets.contains(pos)) {
                        val cx = x + squareSize / 2
                        val cy = y + squareSize / 2
                        val hasPiece = board.getPiece(pos) != null
                        highlightPaint.color = targetColor
                        if (hasPiece) {
                            highlightPaint.alpha = 170
                            canvas.drawRect(x, y, x + squareSize, y + squareSize, highlightPaint)
                            highlightPaint.alpha = 255
                        } else {
                            val r = squareSize * 0.14f
                            canvas.drawCircle(cx, cy, r, highlightPaint)
                        }
                    }
                }
            }

            // Coordinates (white view: a..h on the bottom, 1..8 on the left).
            textPaint.color = Color.parseColor("#5D4037")
            textPaint.textSize = 12f
            for (file in 0..7) {
                val cx = boardOffsetX + file * squareSize + squareSize / 2
                val cy = boardOffsetY + boardSize + squareSize * 0.35f
                canvas.drawText(('a' + file).toChar().toString(), cx, cy, textPaint)
            }
            for (rank in 0..7) {
                val cx = boardOffsetX - squareSize * 0.35f
                val cy = boardOffsetY + (7 - rank) * squareSize + squareSize / 2 + 5
                canvas.drawText((rank + 1).toString(), cx, cy, textPaint)
            }

            // Pieces (skip the animated piece's origin while it is away).
            for (file in 0..7) {
                for (rank in 0..7) {
                    val piece = board.getPiece(Position(file, rank)) ?: continue
                    if (isAnimatingAway() && animFrom == Position(file, rank)) continue
                    drawPiece(canvas, piece, file, rank)
                }
            }

            drawAnimatingPiece(canvas)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isAnimatingAway(): Boolean = animPhase != Phase.IDLE

    private fun drawAnimatingPiece(canvas: Canvas) {
        if (animPhase == Phase.IDLE || animFrom == null || animCurrentTarget == null) return
        val from = centerOf(animFrom!!)
        val to = centerOf(animCurrentTarget!!)
        val p = if (animPhase == Phase.MOVE_OUT || animPhase == Phase.MOVE_BACK) {
            val t = (animLeg.animatedValue as? Float) ?: 0f
            ease(t.coerceIn(0f, 1f))
        } else {
            // HOLD phases: the piece stays put (at the target during HOLD_OUT,
            // back at the origin during HOLD_BACK).
            1f
        }
        val pieceAt = when (animPhase) {
            Phase.MOVE_OUT, Phase.HOLD_OUT -> interpolate(from, to, p)
            else -> interpolate(to, from, p)
        }
        val pieceColor = if (animPieceIsWhite) Color.WHITE else Color.BLACK
        drawPieceAt(canvas, animFrom!!.pieceUnicode(), pieceColor, pieceAt.first, pieceAt.second)
    }

    private fun interpolate(a: Pair<Float, Float>, b: Pair<Float, Float>, t: Float): Pair<Float, Float> =
        a.first + (b.first - a.first) * t to a.second + (b.second - a.second) * t

    private fun Position.pieceUnicode(): String = board.getPiece(this)?.unicode() ?: "?"

    private fun centerOf(pos: Position): Pair<Float, Float> {
        val x = boardOffsetX + pos.file * squareSize + squareSize / 2
        val y = boardOffsetY + (7 - pos.rank) * squareSize + squareSize / 2
        return x to y
    }

    private fun ease(t: Float): Float =
        if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { u -> u * u / 2f }

    private fun drawPiece(canvas: Canvas, piece: Piece, file: Int, rank: Int) {
        val c = centerOf(Position(file, rank))
        val isWhite = piece.color == PieceColor.WHITE
        drawPieceAt(canvas, piece.unicode(), if (isWhite) Color.WHITE else Color.BLACK, c.first, c.second)
    }

    private fun drawPieceAt(canvas: Canvas, text: String, color: Int, cx: Float, cy: Float) {
        val size = squareSize * 0.78f
        piecePaint.color = Color.parseColor("#80000000")
        canvas.drawText(text, cx + 2, cy + size * 0.08f + 2, piecePaint)
        piecePaint.color = color
        if (color == Color.WHITE) {
            piecePaint.style = Paint.Style.STROKE
            piecePaint.strokeWidth = 3f
            piecePaint.color = Color.parseColor("#2E2E2E")
            canvas.drawText(text, cx, cy + size * 0.08f, piecePaint)
            piecePaint.style = Paint.Style.FILL
            piecePaint.color = Color.WHITE
        }
        canvas.drawText(text, cx, cy + size * 0.08f, piecePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                for (file in 0..7) {
                    for (rank in 0..7) {
                        val left = boardOffsetX + file * squareSize
                        val top = boardOffsetY + (7 - rank) * squareSize
                        if (x >= left && x < left + squareSize && y >= top && y < top + squareSize) {
                            onSquareClickListener?.invoke(Position(file, rank))
                            return true
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return true
        }
    }
}

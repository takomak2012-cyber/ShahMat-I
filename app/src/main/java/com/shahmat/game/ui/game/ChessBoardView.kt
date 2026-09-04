package com.shahmat.game.ui.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.shahmat.game.R
import com.shahmat.game.engine.Board
import com.shahmat.game.engine.Move
import com.shahmat.game.engine.Piece
import com.shahmat.game.engine.PieceColor
import com.shahmat.game.engine.Position

class ChessBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Reused, immutable drawing objects -------------------------------------
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capturedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- Colors precomputed once (no parseColor() during draw) -----------------
    private companion object {
        const val COLOR_LIGHT = 0xFFF0D9B5.toInt()
        const val COLOR_DARK = 0xFFB58863.toInt()
        const val COLOR_FRAME = 0xFF4E342E.toInt()
        const val COLOR_FRAME_STROKE = 0xFF3E2723.toInt()
        const val COLOR_SELECTED = 0xFFFFD54F.toInt()
        const val COLOR_LAST_MOVE = 0xFFFFF9C4.toInt()
        const val COLOR_HINT = 0xFF81C784.toInt()
        const val COLOR_CAPTURE_HINT = 0xFFEF5350.toInt()
        const val COLOR_MOVE_HINT = 0xFF4CAF50.toInt()
        const val COLOR_PIECE_SHADOW = 0x80000000.toInt()
        const val COLOR_PIECE_STROKE = 0xFF2E2E2E.toInt()
        const val COLOR_PIECE_BLACK = 0xFF000000.toInt()
        const val COLOR_COORD = 0xFF5D4037.toInt()
        const val COLOR_CHECK_BACKDROP = 0xFFFFEBEE.toInt()
        const val COLOR_CHECK_FROM = 0xFFFF5252.toInt()
        const val COLOR_CHECK_TO = 0xFFB71C1C.toInt()

        // 3D edge shades precomputed once per board colour.
        val COLOR_LIGHT_EDGE = darken(COLOR_LIGHT, 0.15f)
        val COLOR_LIGHT_SIDE = darken(COLOR_LIGHT, 0.10f)
        val COLOR_DARK_EDGE = darken(COLOR_DARK, 0.15f)
        val COLOR_DARK_SIDE = darken(COLOR_DARK, 0.10f)

        private fun darken(color: Int, factor: Float): Int =
            Color.rgb(
                (Color.red(color) * (1 - factor)).toInt(),
                (Color.green(color) * (1 - factor)).toInt(),
                (Color.blue(color) * (1 - factor)).toInt()
            )
    }

    private var board: Board = Board()
    private var playerColor: PieceColor = PieceColor.WHITE
    private var twoPlayer = false
    private var selectedFile = -1
    private var selectedRank = -1
    private val hasSelection: Boolean
        get() = selectedFile >= 0

    // Legal move destinations as an O(1) lookup: index = file * 8 + rank.
    private val legalTargetMask = BooleanArray(64)
    private var hintFromFile = -1
    private var hintFromRank = -1
    private var hintToFile = -1
    private var hintToRank = -1
    private var showHints = false
    private var lastFromFile = -1
    private var lastFromRank = -1
    private var lastToFile = -1
    private var lastToRank = -1
    private var capturedWhite: List<Piece> = emptyList()
    private var capturedBlack: List<Piece> = emptyList()

    private var squareSize = 0f
    private var boardOffsetX = 0f
    private var boardOffsetY = 0f
    private val perspectiveFactor = 0.15f // 3D tilt factor

    // Move animation state
    private var animatingFromFile = -1
    private var animatingFromRank = -1
    private var animatingToFile = -1
    private var animatingToRank = -1
    private var animatingPiece: Piece? = null
    private var animProgress = 0f
    private var animLastUci: String? = null
    private val animator by lazy {
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            addUpdateListener {
                animProgress = it.animatedFraction
                invalidate()
                if (it.animatedFraction >= 1f) {
                    resetAnimation()
                }
            }
        }
    }
    private val isAnimating: Boolean
        get() = animatingFromFile >= 0 && animatingToFile >= 0 && animatingPiece != null

    // "Шах!" warning state.
    private var playerInCheck = false
    private var wasInCheck = false
    private var checkPulse = 0f
    private val checkText: String by lazy { context.getString(R.string.check_warn) }

    private val checkAnimator by lazy {
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener {
                checkPulse = it.animatedFraction
                invalidate()
            }
        }
    }

    private var onSquareClickListener: ((Position) -> Unit)? = null

    init {
        boardPaint.style = Paint.Style.FILL
        piecePaint.textAlign = Paint.Align.CENTER
        piecePaint.isLinearText = true
        piecePaint.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

        highlightPaint.style = Paint.Style.FILL
        highlightPaint.color = COLOR_MOVE_HINT
        highlightPaint.alpha = 180

        capturedPaint.style = Paint.Style.FILL
        capturedPaint.textAlign = Paint.Align.CENTER
        capturedPaint.textSize = 24f

        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = 14f

        backdropPaint.style = Paint.Style.FILL
    }

    // Guarantee the board is never given a zero (invisible) size regardless of how the
    // view is measured by its parent. Without onMeasure(), a custom View whose measured
    // size falls to 0 (bad constraint, collapsed parent, etc.) would not render at all.
    // We still honour a size supplied by the layout/ConstraintLayout, but if the
    // supplied width or height is empty/unspecified we fall back to a sensible minimum
    // so the board always remains visible and tappable.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wSize = View.MeasureSpec.getSize(widthMeasureSpec)
        val hSize = View.MeasureSpec.getSize(heightMeasureSpec)
        val wMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val hMode = View.MeasureSpec.getMode(heightMeasureSpec)

        val side = when {
            wMode != View.MeasureSpec.UNSPECIFIED && hMode != View.MeasureSpec.UNSPECIFIED ->
                minOf(wSize, hSize)
            wMode != View.MeasureSpec.UNSPECIFIED -> wSize
            hMode != View.MeasureSpec.UNSPECIFIED -> hSize
            else -> 0
        }
        // A sane fallback so the board can never collapse to 0 (invisible).
        val fallback = 240
        val resolvedWidth = if (side > 0) side else fallback
        val resolvedHeight = if (side > 0) side else fallback

        setMeasuredDimension(
            resolveSize(resolvedWidth, widthMeasureSpec),
            resolveSize(resolvedHeight, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        try {
            // Reserve vertical space for the captured-piece rows (top & bottom) and the
            // turn indicator, so the board never goes off-screen and stays tappable.
            val topReserve = h * 0.16f
            val bottomReserve = h * 0.13f
            val availW = w.toFloat()
            val availH = (h - topReserve - bottomReserve).coerceAtLeast(1f)

            val boardSize = minOf(availW, availH)
            squareSize = boardSize / 8f
            boardOffsetX = (w - boardSize) / 2f
            boardOffsetY = topReserve
            piecePaint.textSize = squareSize * 0.7f
            capturedPaint.textSize = squareSize * 0.5f
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateBoard(
        board: Board,
        playerColor: PieceColor,
        selectedSquare: Position?,
        legalMoves: List<Move>,
        hintMove: Move?,
        showHints: Boolean,
        capturedWhite: List<Piece>,
        capturedBlack: List<Piece>,
        playerInCheck: Boolean,
        twoPlayer: Boolean
    ) {
        try {
            this.board = board
            this.playerColor = playerColor
            this.twoPlayer = twoPlayer
            this.selectedFile = selectedSquare?.file ?: -1
            this.selectedRank = selectedSquare?.rank ?: -1
            this.showHints = showHints
            this.capturedWhite = capturedWhite
            this.capturedBlack = capturedBlack

            // Build the legal-destination mask once (no re-scan per square in onDraw).
            java.util.Arrays.fill(legalTargetMask, false)
            for (m in legalMoves) {
                legalTargetMask[m.to.file * 8 + m.to.rank] = true
            }

            // Flatten last-move positions to integers (no Position per square in onDraw).
            val lastFrom = board.lastMove?.from
            val lastTo = board.lastMove?.to
            lastFromFile = lastFrom?.file ?: -1
            lastFromRank = lastFrom?.rank ?: -1
            lastToFile = lastTo?.file ?: -1
            lastToRank = lastTo?.rank ?: -1

            hintFromFile = hintMove?.from?.file ?: -1
            hintFromRank = hintMove?.from?.rank ?: -1
            hintToFile = hintMove?.to?.file ?: -1
            hintToRank = hintMove?.to?.rank ?: -1

            updatePlayerInCheck(playerInCheck)

            // Trigger a sliding-piece animation whenever a new move is made.
            val move = board.lastMove
            val uci = move?.toUci()
            if (move != null && uci != animLastUci) {
                startMoveAnimation(move)
            }
            animLastUci = uci

            invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Starts/stops the check warning animation only on a real state transition. */
    private fun updatePlayerInCheck(inCheck: Boolean) {
        this.playerInCheck = inCheck
        if (inCheck && !wasInCheck) {
            startCheckAnimation()
        } else if (!inCheck && wasInCheck) {
            stopCheckAnimation()
        }
        wasInCheck = inCheck
    }

    private fun startCheckAnimation() {
        checkPulse = 0f
        if (!checkAnimator.isStarted) {
            checkAnimator.start()
        }
    }

    private fun stopCheckAnimation() {
        checkAnimator.cancel()
        checkPulse = 0f
        invalidate()
    }

    fun setOnSquareClickListener(listener: (Position) -> Unit) {
        onSquareClickListener = listener
    }

    private fun startMoveAnimation(move: Move) {
        animatingFromFile = move.from.file
        animatingFromRank = move.from.rank
        animatingToFile = move.to.file
        animatingToRank = move.to.rank
        animatingPiece = move.piece
        animProgress = 0f
        animator.start()
        invalidate()
    }

    private fun resetAnimation() {
        animatingFromFile = -1
        animatingFromRank = -1
        animatingToFile = -1
        animatingToRank = -1
        animatingPiece = null
        animProgress = 0f
        invalidate()
    }

    /** On-screen X coordinate of the left edge of a (logical) file square. */
    private fun onScreenFile(file: Int): Int =
        if (playerColor == PieceColor.BLACK) 7 - file else file

    /** On-screen Y coordinate of the top edge of a (logical) rank square. */
    private fun onScreenRank(rank: Int): Int =
        if (playerColor == PieceColor.BLACK) 7 - rank else rank

    private fun drawAnimatingPiece(canvas: Canvas) {
        if (!isAnimating) return
        val fromX = boardOffsetX + onScreenFile(animatingFromFile) * squareSize + squareSize / 2
        val fromY = boardOffsetY + onScreenRank(animatingFromRank) * squareSize + squareSize / 2
        val toX = boardOffsetX + onScreenFile(animatingToFile) * squareSize + squareSize / 2
        val toY = boardOffsetY + onScreenRank(animatingToRank) * squareSize + squareSize / 2
        val p = animProgress
        val x = fromX + (toX - fromX) * p
        val y = fromY + (toY - fromY) * p
        val w = squareSize * 0.9f
        drawPiece(canvas, animatingPiece!!, x - w / 2f, y - squareSize / 2f, w, squareSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            drawCapturedArea(canvas, true) // Black captured (top)
            drawCheckWarning(canvas)       // "Шах!" between top captured pieces and the board
            drawBoard(canvas)
            drawCapturedArea(canvas, false) // White captured (bottom)
            drawCoordinates(canvas)
        } catch (e: Exception) {
            e.printStackTrace()
            // Draw fallback simple board
            drawFallbackBoard(canvas)
        }
    }

    private fun drawFallbackBoard(canvas: Canvas) {
        val boardSize = kotlin.math.min(width.toFloat(), height.toFloat() * 0.85f)
        val sq = boardSize / 8f
        val offsetX = (width - boardSize) / 2f
        val offsetY = (height - boardSize) * 0.1f

        for (file in 0..7) {
            for (rank in 0..7) {
                val isLight = (file + rank) % 2 == 0
                boardPaint.color = if (isLight) COLOR_LIGHT else COLOR_DARK
                val x = offsetX + file * sq
                val y = offsetY + rank * sq
                canvas.drawRect(x, y, x + sq, y + sq, boardPaint)
            }
        }
    }

    private fun drawBoard(canvas: Canvas) {
        val blackPerspective = playerColor == PieceColor.BLACK

        // Dark frame behind the board so its border is clearly visible.
        boardPaint.color = COLOR_FRAME
        val bx0 = boardOffsetX - 6f
        val by0 = boardOffsetY - 6f
        val bx1 = boardOffsetX + squareSize * 8 + 6f
        val by1 = boardOffsetY + squareSize * 8 + 6f
        canvas.drawRect(bx0, by0, bx1, by1, boardPaint)

        val hasLast = lastFromFile >= 0
        val hasHint = hintFromFile >= 0

        for (file in 0..7) {
            for (rank in 0..7) {
                val isLight = (file + rank) % 2 == 0
                val baseColor = if (isLight) COLOR_LIGHT else COLOR_DARK

                val df = if (blackPerspective) 7 - file else file
                val dr = if (blackPerspective) 7 - rank else rank
                val x = boardOffsetX + df * squareSize
                val y = boardOffsetY + dr * squareSize

                // Draw square (no RectF allocation).
                boardPaint.color = baseColor
                canvas.drawRect(x, y, x + squareSize, y + squareSize, boardPaint)

                // 3D effect - darker bottom edge
                if (rank < 7) {
                    boardPaint.color = if (isLight) COLOR_LIGHT_EDGE else COLOR_DARK_EDGE
                    canvas.drawLine(x, y + squareSize, x + squareSize, y + squareSize, boardPaint)
                }
                // 3D depth side
                if (file > 0) {
                    boardPaint.color = if (isLight) COLOR_LIGHT_SIDE else COLOR_DARK_SIDE
                    canvas.drawLine(x, y, x, y + squareSize, boardPaint)
                }

                // Last move highlight (integer comparison, no Position allocation).
                if (hasLast &&
                    ((lastFromFile == file && lastFromRank == rank) ||
                        (lastToFile == file && lastToRank == rank))) {
                    boardPaint.color = COLOR_LAST_MOVE
                    canvas.drawRect(x, y, x + squareSize, y + squareSize, boardPaint)
                }

                // Selected square.
                if (hasSelection && selectedFile == file && selectedRank == rank) {
                    boardPaint.color = COLOR_SELECTED
                    canvas.drawRect(x, y, x + squareSize, y + squareSize, boardPaint)
                }

                // Legal moves - O(1) mask lookup.
                if (showHints && legalTargetMask[file * 8 + rank]) {
                    val cx = x + squareSize / 2
                    val cy = y + squareSize / 2
                    val radius = squareSize * 0.15f
                    highlightPaint.color =
                        if (board.getPieceFast(file, rank) != null) COLOR_CAPTURE_HINT else COLOR_MOVE_HINT
                    canvas.drawCircle(cx, cy, radius, highlightPaint)
                }

                // Hint move.
                if (showHints && hasHint &&
                    ((hintFromFile == file && hintFromRank == rank) ||
                        (hintToFile == file && hintToRank == rank))) {
                    boardPaint.color = COLOR_HINT
                    canvas.drawRect(x, y, x + squareSize, y + squareSize, boardPaint)
                }

                // Draw piece (no Position allocation via getPieceFast).
                val piece = board.getPieceFast(file, rank)
                if (piece != null) {
                    // While a piece is sliding, skip origin/destination (drawn once on top).
                    if (!(isAnimating &&
                            ((animatingFromFile == file && animatingFromRank == rank) ||
                                (animatingToFile == file && animatingToRank == rank)))) {
                        drawPiece(canvas, piece, x, y, squareSize, squareSize)
                    }
                }
            }
        }

        // Thin outline on the frame for definition.
        boardPaint.style = Paint.Style.STROKE
        boardPaint.strokeWidth = 2f
        boardPaint.color = COLOR_FRAME_STROKE
        canvas.drawRect(bx0, by0, bx1, by1, boardPaint)
        boardPaint.style = Paint.Style.FILL

        // Draw the moving piece on top at its interpolated position.
        drawAnimatingPiece(canvas)
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, x: Float, y: Float, w: Float, h: Float) {
        // In a two-player local match the opponent sits on the far side of the phone,
        // so their pieces (opposite colour to the bottom player) are drawn upside down
        // to be readable by the top player. Solo games keep every piece upright.
        val flipped = twoPlayer && piece.color != playerColor
        if (flipped) {
            canvas.save()
            canvas.rotate(180f, x + w / 2f, y + h / 2f)
        }

        val text = piece.unicode()
        val centerX = x + w / 2
        val centerY = y + h / 2 + h * 0.08f // slight offset for visual center

        // Shadow
        piecePaint.color = COLOR_PIECE_SHADOW
        canvas.drawText(text, centerX + 2, centerY + 2, piecePaint)

        // Piece color
        val isWhite = piece.color == PieceColor.WHITE
        piecePaint.color = if (isWhite) Color.WHITE else COLOR_PIECE_BLACK

        // Stroke for white pieces
        if (isWhite) {
            piecePaint.style = Paint.Style.STROKE
            piecePaint.strokeWidth = 3f
            piecePaint.color = COLOR_PIECE_STROKE
            canvas.drawText(text, centerX, centerY, piecePaint)
            piecePaint.style = Paint.Style.FILL
            piecePaint.color = Color.WHITE
        }

        canvas.drawText(text, centerX, centerY, piecePaint)

        if (flipped) {
            canvas.restore()
        }
    }

    private fun drawCapturedArea(canvas: Canvas, isTop: Boolean) {
        val captured = if (isTop) capturedBlack else capturedWhite
        if (captured.isEmpty()) return

        val startY = if (isTop) {
            boardOffsetY - squareSize * 1.2f
        } else {
            boardOffsetY + squareSize * 8 + squareSize * 0.2f
        }

        var x = boardOffsetX
        val y = startY
        val pieceSize = squareSize * 0.6f

        for (piece in captured) {
            if (x + pieceSize > boardOffsetX + squareSize * 8) {
                break
            }

            val text = piece.unicode()
            val isWhite = piece.color == PieceColor.WHITE
            capturedPaint.color = if (isWhite) Color.WHITE else COLOR_PIECE_BLACK
            if (isWhite) {
                capturedPaint.style = Paint.Style.STROKE
                capturedPaint.strokeWidth = 2.5f
                capturedPaint.color = COLOR_PIECE_STROKE
                canvas.drawText(text, x + pieceSize / 2, y + pieceSize * 0.7f, capturedPaint)
                capturedPaint.style = Paint.Style.FILL
                capturedPaint.color = Color.WHITE
            }
            canvas.drawText(text, x + pieceSize / 2, y + pieceSize * 0.7f, capturedPaint)
            x += pieceSize * 1.1f
        }
    }

    /**
     * Draws the "Шах!" warning in the fixed band between the top captured pieces and the
     * board. Uses a cached [checkText] string and the shared [backdropPaint]; no per-frame
     * allocation (round-rect uses the float overload, not a RectF).
     */
    private fun drawCheckWarning(canvas: Canvas) {
        if (!playerInCheck) return
        val centerY = boardOffsetY - squareSize * 0.32f
        val centerX = width / 2f

        val pulse = checkPulse.coerceIn(0f, 1f)

        // Smooth colour change between a bright and a deep red, timed to the pulse.
        val textColor = lerpColor(COLOR_CHECK_FROM, COLOR_CHECK_TO, pulse)

        // Strong pulsing: noticeable scale and a clearly visible backdrop that breathes.
        val scale = 1f + pulse * 0.10f

        textPaint.textSize = squareSize * 0.5f
        textPaint.isFakeBoldText = true
        val halfWidth = textPaint.measureText(checkText) * scale / 2f
        val halfHeight = textPaint.textSize * scale / 2f

        val left = centerX - halfWidth - squareSize * 0.45f
        val right = centerX + halfWidth + squareSize * 0.45f
        val top = centerY - halfHeight - squareSize * 0.2f
        val bottom = centerY + halfHeight * 0.6f + squareSize * 0.2f

        // Soft pulsing rounded backdrop (reuses shared paint, float drawRoundRect overload).
        backdropPaint.color = COLOR_CHECK_BACKDROP
        backdropPaint.alpha = (150 + 95 * pulse).toInt()
        val radius = squareSize * 0.25f
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, backdropPaint)

        // Red edge shadow.
        backdropPaint.style = Paint.Style.STROKE
        backdropPaint.strokeWidth = squareSize * 0.05f
        backdropPaint.color = textColor
        backdropPaint.alpha = 255
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, backdropPaint)
        backdropPaint.style = Paint.Style.FILL

        textPaint.color = textColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(checkText, centerX, centerY + halfHeight * 0.4f, textPaint)
        textPaint.isFakeBoldText = false
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int =
        Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt().coerceIn(0, 255),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt().coerceIn(0, 255),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt().coerceIn(0, 255)
        )

    private fun drawCoordinates(canvas: Canvas) {
        textPaint.color = COLOR_COORD
        textPaint.textSize = 12f

        // Files (a-h)
        for (file in 0..7) {
            val x = boardOffsetX + file * squareSize + squareSize / 2
            val y = boardOffsetY + squareSize * 8 + 20
            val fileChar = ('a' + file).toChar().toString()
            canvas.drawText(fileChar, x, y, textPaint)
        }

        // Ranks (1-8)
        for (rank in 0..7) {
            val x = boardOffsetX - 15
            val y = boardOffsetY + rank * squareSize + squareSize / 2 + 5
            val rankChar = (8 - rank).toString()
            canvas.drawText(rankChar, x, y, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                val relX = x - boardOffsetX
                val relY = y - boardOffsetY
                // Ignore touches outside the board with an O(1) bounds check.
                if (relX >= 0 && relY >= 0 && relX < squareSize * 8 && relY < squareSize * 8) {
                    // Compute the on-screen cell directly from coordinates.
                    val df = (relX / squareSize).toInt()
                    val dr = (relY / squareSize).toInt()
                    // Map back through the board flip to the logical square.
                    val file = if (playerColor == PieceColor.BLACK) 7 - df else df
                    val rank = if (playerColor == PieceColor.BLACK) 7 - dr else dr
                    onSquareClickListener?.invoke(Position(file, rank))
                    return true
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return true
        }
    }

    override fun onDetachedFromWindow() {
        // Stop the check animation (and any sliding move animation) so no callback fires
        // after the view has left the window (e.g. the game screen was closed).
        animator.cancel()
        checkAnimator.cancel()
        checkPulse = 0f
        super.onDetachedFromWindow()
    }
}

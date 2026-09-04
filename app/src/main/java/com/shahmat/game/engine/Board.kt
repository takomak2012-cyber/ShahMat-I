package com.shahmat.game.engine

import com.shahmat.game.engine.PieceColor.BLACK
import com.shahmat.game.engine.PieceColor.WHITE
import com.shahmat.game.engine.PieceType.*
import kotlin.math.abs

class Board private constructor(
    private val squares: Array<Array<Piece?>>,
    whiteToMoveInit: Boolean,
    whiteKingsideCastleInit: Boolean,
    whiteQueensideCastleInit: Boolean,
    blackKingsideCastleInit: Boolean,
    blackQueensideCastleInit: Boolean,
    enPassantTargetInit: Position?,
    var halfmoveClock: Int,
    var fullmoveNumber: Int,
    // Whether this board (and its descendants) must accumulate a move history.
    // Real-game boards track history (for repetition/captured-piece display);
    // boards used inside the engine's search do not, which avoids copying the
    // whole history list on every searched node.
    private var trackHistory: Boolean = true
) {
    // These flags are part of the position identity and must invalidate the cached
    // Zobrist key whenever they change directly (e.g. tests setting up a position).
    var whiteToMove: Boolean = whiteToMoveInit
        set(value) { field = value; zKeyDirty = true }
    var whiteKingsideCastle: Boolean = whiteKingsideCastleInit
        set(value) { field = value; zKeyDirty = true }
    var whiteQueensideCastle: Boolean = whiteQueensideCastleInit
        set(value) { field = value; zKeyDirty = true }
    var blackKingsideCastle: Boolean = blackKingsideCastleInit
        set(value) { field = value; zKeyDirty = true }
    var blackQueensideCastle: Boolean = blackQueensideCastleInit
        set(value) { field = value; zKeyDirty = true }
    var enPassantTarget: Position? = enPassantTargetInit
        set(value) { field = value; zKeyDirty = true }

    // Cached Zobrist key of this position. Any direct mutation of the position
    // (setPiece, and the property setters above) flags it dirty so the next
    // zobristHash() recomputes it exactly once. During search each new node is
    // produced by copyBoard()+executeMove(); after executeMove the cache is clean,
    // so repeated hash reads on the same node are O(1) without rescanning the board.
    private var zKey: Long = 0L
    private var zKeyDirty: Boolean = true
    private companion object {
        // Pre-allocated direction offsets so the hot generators do not build a
        // temporary List/Pair for every node.
        val KNIGHT_OFFSETS = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(2, -1), intArrayOf(2, 1),
            intArrayOf(-1, -2), intArrayOf(-1, 2), intArrayOf(1, -2), intArrayOf(1, 2)
        )
        val DIAGONALS = arrayOf(
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
        )
        val ORTHOGONALS = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
        )
        val ALL_SLIDING = arrayOf(
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
        )
        val KING_OFFSETS = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1)
        )
        val PAWN_ATTACK_FILES = intArrayOf(-1, 1)
        val PROMOTION_PIECES = arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)

        // Zobrist hashing. Tables are filled once with a deterministic PRNG so that
        // equal positions always hash to the same Long (across runs and platforms).
        private val zPiece = Array(2) { Array(6) { LongArray(64) } }
        private val zSide = LongArray(2)
        private val zCastle = LongArray(4)
        private val zEnPassant = LongArray(8)
        private val zobristInitialized: Boolean

        init {
            var state = 0x9E3779B97F4A7C15UL
            fun rand(): Long {
                state = state xor (state shl 7)
                state = state xor (state shr 9)
                return state.toLong()
            }
            for (c in 0..1) for (t in 0..5) for (sq in 0..63) zPiece[c][t][sq] = rand()
            for (i in 0..1) zSide[i] = rand()
            for (i in 0..3) zCastle[i] = rand()
            for (i in 0..7) zEnPassant[i] = rand()
            zobristInitialized = true
        }
    }
    var lastMove: Move? = null
        private set

    private val moveHistory = ArrayList<Move>()

    // Appearance count of each Zobrist position key reached in the real game.
    // The starting position is counted as the first appearance. Repetition is then
    // detected in O(1) by looking up the current key, instead of replaying history.
    // Only maintained on real-game boards (see copyBoard / makeMove).
    private val positionCounts = HashMap<Long, Int>()

    constructor() : this(
        Array(8) { Array<Piece?>(8) { null } },
        true, true, true, true, true,
        null, 0, 1
    ) {
        setupInitialPosition()
        // The starting position is the first appearance of its key.
        positionCounts[zobristHash()] = 1
    }

    private fun setupInitialPosition() {
        // Black pieces (rank 0 and 1)
        squares[0][0] = Piece(ROOK, BLACK)
        squares[1][0] = Piece(KNIGHT, BLACK)
        squares[2][0] = Piece(BISHOP, BLACK)
        squares[3][0] = Piece(QUEEN, BLACK)
        squares[4][0] = Piece(KING, BLACK)
        squares[5][0] = Piece(BISHOP, BLACK)
        squares[6][0] = Piece(KNIGHT, BLACK)
        squares[7][0] = Piece(ROOK, BLACK)
        for (f in 0..7) squares[f][1] = Piece(PAWN, BLACK)

        // White pieces (rank 6 and 7)
        for (f in 0..7) squares[f][6] = Piece(PAWN, WHITE)
        squares[0][7] = Piece(ROOK, WHITE)
        squares[1][7] = Piece(KNIGHT, WHITE)
        squares[2][7] = Piece(BISHOP, WHITE)
        squares[3][7] = Piece(QUEEN, WHITE)
        squares[4][7] = Piece(KING, WHITE)
        squares[5][7] = Piece(BISHOP, WHITE)
        squares[6][7] = Piece(KNIGHT, WHITE)
        squares[7][7] = Piece(ROOK, WHITE)
    }

    fun getPiece(pos: Position): Piece? = squares[pos.file][pos.rank]

    // Allocation-free accessor used by the hot evaluation loop (avoids building a
    // Position object per cell). File 0..7, rank 0..7 in engine coordinates.
    fun getPieceFast(file: Int, rank: Int): Piece? = squares[file][rank]

    fun setPiece(pos: Position, piece: Piece?) {
        squares[pos.file][pos.rank] = piece
        zKeyDirty = true
    }

    fun findKing(color: PieceColor): Position {
        for (f in 0..7) {
            for (r in 0..7) {
                val p = squares[f][r]
                if (p != null && p.type == KING && p.color == color) {
                    return Position(f, r)
                }
            }
        }
        throw IllegalStateException("King not found for $color")
    }

    fun isInCheck(color: PieceColor): Boolean {
        val kingPos = findKing(color)
        val enemyColor = if (color == WHITE) BLACK else WHITE
        return isSquareAttacked(kingPos, enemyColor)
    }

    fun isSquareAttacked(pos: Position, byColor: PieceColor): Boolean {
        // Pawn attacks
        val pawnDir = if (byColor == WHITE) 1 else -1
        for (df in PAWN_ATTACK_FILES) {
            val attackPos = pos.translate(df, pawnDir)
            if (attackPos.isValid()) {
                val p = getPiece(attackPos)
                if (p != null && p.type == PAWN && p.color == byColor) return true
            }
        }

        // Knight attacks
        for (offset in KNIGHT_OFFSETS) {
            val attackPos = pos.translate(offset[0], offset[1])
            if (attackPos.isValid()) {
                val p = getPiece(attackPos)
                if (p != null && p.type == KNIGHT && p.color == byColor) return true
            }
        }

        // King attacks (adjacent squares)
        for (offset in KING_OFFSETS) {
            val attackPos = pos.translate(offset[0], offset[1])
            if (attackPos.isValid()) {
                val p = getPiece(attackPos)
                if (p != null && p.type == KING && p.color == byColor) return true
            }
        }

        // Sliding pieces (bishop, rook, queen)
        for (offset in ALL_SLIDING) {
            val df = offset[0]
            val dr = offset[1]
            var cur = pos.translate(df, dr)
            while (cur.isValid()) {
                val p = getPiece(cur)
                if (p != null) {
                    if (p.color == byColor) {
                        val isDiagonal = abs(df) == abs(dr)
                        val isOrthogonal = df == 0 || dr == 0
                        when (p.type) {
                            BISHOP -> if (isDiagonal) return true
                            ROOK -> if (isOrthogonal) return true
                            QUEEN -> return true
                            else -> {} // KING, PAWN, KNIGHT handled by adjacent squares above
                        }
                    }
                    break
                }
                cur = cur.translate(df, dr)
            }
        }
        return false
    }

    fun generateLegalMoves(): List<Move> {
        val pseudoMoves = generatePseudoLegalMoves()
        if (pseudoMoves.isEmpty()) return pseudoMoves
        val enemyColor = if (whiteToMove) PieceColor.WHITE else PieceColor.BLACK
        val scratch = copyBoard(trackHistory = false)
        val result = ArrayList<Move>(pseudoMoves.size)
        for (move in pseudoMoves) {
            val undo = scratch.makeTempMove(move)
            if (!scratch.isInCheck(enemyColor)) {
                result.add(move)
            }
            scratch.undoTempMove(move, undo)
        }
        return result
    }

    /**
     * Generates the legal capture moves of the side to move, specialised for the
     * quiescence search so that quiet (non-capturing) moves are never generated or
     * legality-checked. When the side to move is in check, ALL legal moves are
     * returned (captures plus quiet evasions), because a check must be resolved and
     * the attacker may only be blocked or captured by a quiet move.
     */
    fun generateCaptures(): List<Move> {
        val pseudo = if (isInCheck(if (whiteToMove) PieceColor.WHITE else PieceColor.BLACK)) {
            generatePseudoLegalMoves()
        } else {
            generatePseudoLegalCaptures()
        }
        if (pseudo.isEmpty()) return pseudo
        val enemyColor = if (whiteToMove) PieceColor.WHITE else PieceColor.BLACK
        val scratch = copyBoard(trackHistory = false)
        val result = ArrayList<Move>(pseudo.size)
        for (move in pseudo) {
            val undo = scratch.makeTempMove(move)
            if (!scratch.isInCheck(enemyColor)) {
                result.add(move)
            }
            scratch.undoTempMove(move, undo)
        }
        return result
    }

    // Generates only pseudo-legal capturing moves (including pawn captures, en passant
    // and promotion-due-to-capture). Quiet moves are deliberately skipped.
    private fun generatePseudoLegalCaptures(): List<Move> {
        val moves = ArrayList<Move>(16)
        val color = if (whiteToMove) PieceColor.WHITE else PieceColor.BLACK
        val pawnDir = if (color == WHITE) -1 else 1
        val promotionRank = if (color == WHITE) 0 else 7

        for (f in 0..7) {
            for (r in 0..7) {
                val piece = squares[f][r]
                if (piece == null || piece.color != color) continue
                val from = Position(f, r)

                when (piece.type) {
                    PieceType.PAWN -> {
                        for (df in PAWN_ATTACK_FILES) {
                            val capturePos = from.translate(df, pawnDir)
                            if (capturePos.isValid()) {
                                val target = getPiece(capturePos)
                                if (target != null && target.color != color) {
                                    if (capturePos.rank == promotionRank) {
                                        for (promo in PROMOTION_PIECES) {
                                            moves.add(Move(from, capturePos, piece, target, MoveType.CAPTURE, promo))
                                        }
                                    } else {
                                        moves.add(Move(from, capturePos, piece, target, MoveType.CAPTURE))
                                    }
                                }
                                // En passant
                                if (enPassantTarget != null && enPassantTarget == capturePos) {
                                    val capturedPawn = Piece(PieceType.PAWN, if (color == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE)
                                    moves.add(Move(from, capturePos, piece, capturedPawn, MoveType.EN_PASSANT))
                                }
                            }
                        }
                    }
                    PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN, PieceType.KING -> {
                        val offsets = when (piece.type) {
                            PieceType.KNIGHT -> KNIGHT_OFFSETS
                            PieceType.KING -> KING_OFFSETS
                            PieceType.BISHOP -> DIAGONALS
                            PieceType.ROOK -> ORTHOGONALS
                            PieceType.QUEEN -> ALL_SLIDING
                            else -> ALL_SLIDING
                        }
                        for (offset in offsets) {
                            if (piece.type == PieceType.BISHOP || piece.type == PieceType.ROOK || piece.type == PieceType.QUEEN) {
                                var cur = from.translate(offset[0], offset[1])
                                while (cur.isValid()) {
                                    val target = getPiece(cur)
                                    if (target != null) {
                                        if (target.color != color) {
                                            moves.add(Move(from, cur, piece, target, MoveType.CAPTURE))
                                        }
                                        break
                                    }
                                    cur = cur.translate(offset[0], offset[1])
                                }
                            } else {
                                val to = from.translate(offset[0], offset[1])
                                if (to.isValid()) {
                                    val target = getPiece(to)
                                    if (target != null && target.color != color) {
                                        moves.add(Move(from, to, piece, target, MoveType.CAPTURE))
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        return moves
    }

    private fun generatePseudoLegalMoves(): List<Move> {
        val moves = ArrayList<Move>(32)
        val color = if (whiteToMove) PieceColor.WHITE else PieceColor.BLACK
        val enemyColor = if (whiteToMove) PieceColor.BLACK else PieceColor.WHITE
        // In this board convention: rank 0 = black back rank, rank 7 = white back rank.
        // White pawns at rank 6 move toward rank 0 (pawnDir = -1); promote at rank 0.
        // Black pawns at rank 1 move toward rank 7 (pawnDir = +1); promote at rank 7.
        val pawnDir = if (color == WHITE) -1 else 1
        val startRank = if (color == WHITE) 6 else 1
        val promotionRank = if (color == WHITE) 0 else 7

        for (f in 0..7) {
            for (r in 0..7) {
                val piece = squares[f][r]
                if (piece == null || piece.color != color) continue
                val from = Position(f, r)

                when (piece.type) {
                    PAWN -> {
                        // Single push
                        val oneForward = from.translate(0, pawnDir)
                        if (oneForward.isValid() && getPiece(oneForward) == null) {
                            if (oneForward.rank == promotionRank) {
                                for (promo in PROMOTION_PIECES) {
                                    moves.add(Move(from, oneForward, piece, type = MoveType.PROMOTION, promotionType = promo))
                                }
                            } else {
                                moves.add(Move(from, oneForward, piece))
                                // Double push
                                if (from.rank == startRank) {
                                    val twoForward = from.translate(0, 2 * pawnDir)
                                    if (twoForward.isValid() && getPiece(twoForward) == null) {
                                        moves.add(Move(from, twoForward, piece, type = MoveType.DOUBLE_PAWN_PUSH))
                                    }
                                }
                            }
                        }
                        // Captures
                        for (df in PAWN_ATTACK_FILES) {
                            val capturePos = from.translate(df, pawnDir)
                            if (capturePos.isValid()) {
                                val target = getPiece(capturePos)
                                if (target != null && target.color != color) {
                                    if (capturePos.rank == promotionRank) {
                                        for (promo in PROMOTION_PIECES) {
                                            moves.add(Move(from, capturePos, piece, target, MoveType.CAPTURE, promo))
                                        }
                                    } else {
                                        moves.add(Move(from, capturePos, piece, target, MoveType.CAPTURE))
                                    }
                                }
                            }
                            // En passant
                            if (enPassantTarget != null && enPassantTarget == capturePos) {
                                val capturedPawn = Piece(PAWN, if (color == WHITE) BLACK else WHITE)
                                moves.add(Move(from, capturePos, piece, capturedPawn, MoveType.EN_PASSANT))
                            }
                        }
                    }
                    KNIGHT -> {
                        for (offset in KNIGHT_OFFSETS) {
                            val to = from.translate(offset[0], offset[1])
                            if (to.isValid()) {
                                val target = getPiece(to)
                                if (target == null || target.color != color) {
                                    moves.add(Move(from, to, piece, target, if (target != null) MoveType.CAPTURE else MoveType.NORMAL))
                                }
                            }
                        }
                    }
                    BISHOP, ROOK, QUEEN -> {
                        val dirs = when (piece.type) {
                            BISHOP -> DIAGONALS
                            ROOK -> ORTHOGONALS
                            QUEEN -> ALL_SLIDING
                            else -> ALL_SLIDING
                        }
                        for (offset in dirs) {
                            var cur = from.translate(offset[0], offset[1])
                            while (cur.isValid()) {
                                val target = getPiece(cur)
                                if (target == null) {
                                    moves.add(Move(from, cur, piece))
                                } else {
                                    if (target.color != color) {
                                        moves.add(Move(from, cur, piece, target, MoveType.CAPTURE))
                                    }
                                    break
                                }
                                cur = cur.translate(offset[0], offset[1])
                            }
                        }
                    }
                    KING -> {
                        for (offset in KING_OFFSETS) {
                            val to = from.translate(offset[0], offset[1])
                            if (to.isValid()) {
                                val target = getPiece(to)
                                if (target == null || target.color != color) {
                                    moves.add(Move(from, to, piece, target, if (target != null) MoveType.CAPTURE else MoveType.NORMAL))
                                }
                            }
                        }
                        // Castling
                        if (!isInCheck(color)) {
                            // Kingside: king casts to file 6, the rook must be on file 7.
                            if ((color == WHITE && whiteKingsideCastle) || (color == BLACK && blackKingsideCastle)) {
                                val f1 = from.translate(1, 0)
                                val f2 = from.translate(2, 0)
                                val rookPos = Position(7, from.rank)
                                val rook = getPiece(rookPos)
                                if (rook?.type == PieceType.ROOK && rook.color == color &&
                                    getPiece(f1) == null && getPiece(f2) == null &&
                                    !isSquareAttacked(f1, enemyColor) &&
                                    !isSquareAttacked(f2, enemyColor)) {
                                    moves.add(Move(from, f2, piece, type = MoveType.CASTLE_KINGSIDE))
                                }
                            }
                            // Queenside: king casts to file 2, the rook must be on file 0.
                            if ((color == PieceColor.WHITE && whiteQueensideCastle) || (color == PieceColor.BLACK && blackQueensideCastle)) {
                                val f1 = from.translate(-1, 0)
                                val f2 = from.translate(-2, 0)
                                val f3 = from.translate(-3, 0)
                                val rookPos = Position(0, from.rank)
                                val rook = getPiece(rookPos)
                                if (rook?.type == PieceType.ROOK && rook.color == color &&
                                    getPiece(f1) == null && getPiece(f2) == null && getPiece(f3) == null &&
                                    !isSquareAttacked(f1, enemyColor) &&
                                    !isSquareAttacked(f2, enemyColor)) {
                                    moves.add(Move(from, f2, piece, type = MoveType.CASTLE_QUEENSIDE))
                                }
                            }
                        }
                    }
                }
            }
        }
        return moves
    }

    fun makeMove(move: Move): Board {
        val newBoard = copyBoard()
        newBoard.executeMove(move)
        if (newBoard.whiteToMove) newBoard.fullmoveNumber++
        newBoard.lastMove = move
        // copyBoard() already copied moveHistory and positionCounts (when tracked) —
        // only append the new move and count the new position.
        if (newBoard.trackHistory) {
            newBoard.moveHistory.add(move)
            newBoard.recordPosition()
        }
        return newBoard
    }

    /**
     * Produces a successor position without accumulating a move history.
     *
     * Used by the engine's search, where the per-node history list is never read.
     * Skipping the history copy+append removes an O(history-length) allocation on
     * every node of the search tree, significantly reducing total allocation.
     */
    fun makeSearchMove(move: Move): Board {
        val newBoard = copyBoard(trackHistory = false)
        newBoard.executeMove(move)
        if (newBoard.whiteToMove) newBoard.fullmoveNumber++
        newBoard.lastMove = move
        return newBoard
    }

    // Registered as an appearance of the current position key (real-game boards only).
    private fun recordPosition() {
        val key = zobristHash()
        positionCounts[key] = (positionCounts[key] ?: 0) + 1
    }

    /**
     * Deep-copies the position. History is only copied when the requested
     * [trackHistory] flag is true — callers that produce a search board (which
     * must never accumulate a shared history list) pass `trackHistory = false` so
     * the potentially O(history-length) history copy is skipped entirely.
     */
    fun copyBoard(trackHistory: Boolean = this.trackHistory): Board {
        val b = Board(
            Array(8) { f -> Array(8) { r -> squares[f][r] } },
            whiteToMove, whiteKingsideCastle, whiteQueensideCastle,
            blackKingsideCastle, blackQueensideCastle,
            enPassantTarget, halfmoveClock, fullmoveNumber,
            trackHistory
        )
        b.lastMove = lastMove
        // The squares array and position flags are identical to this board, so the
        // cached Zobrist key carries over verbatim (whether clean or dirty).
        b.zKey = zKey
        b.zKeyDirty = zKeyDirty
        if (trackHistory) {
            b.moveHistory.addAll(moveHistory)
            b.positionCounts.putAll(positionCounts)
        }
        return b
    }

    private fun executeMove(move: Move) {
        val from = move.from
        val to = move.to
        val piece = move.piece

        // Handle castling
        if (move.type == MoveType.CASTLE_KINGSIDE) {
            val rookFrom = Position(7, from.rank)
            val rookTo = Position(5, from.rank)
            squares[rookTo.file][rookTo.rank] = squares[rookFrom.file][rookFrom.rank]
            squares[rookFrom.file][rookFrom.rank] = null
        } else if (move.type == MoveType.CASTLE_QUEENSIDE) {
            val rookFrom = Position(0, from.rank)
            val rookTo = Position(3, from.rank)
            squares[rookTo.file][rookTo.rank] = squares[rookFrom.file][rookFrom.rank]
            squares[rookFrom.file][rookFrom.rank] = null
        }

        // Handle en passant
        if (move.type == MoveType.EN_PASSANT) {
            // The captured pawn sits one step behind the landing square in the
            // direction the capturing pawn moved (white moves down => +1, black up => -1).
            val capturedPawnRank = to.rank + (if (piece.color == WHITE) 1 else -1)
            squares[to.file][capturedPawnRank] = null
        }

        // Move piece
        squares[to.file][to.rank] = if (move.isPromotion()) Piece(move.promotionType!!, piece.color) else piece
        squares[from.file][from.rank] = null

        // Update castling rights
        when (piece.type) {
            KING -> {
                if (piece.color == WHITE) { whiteKingsideCastle = false; whiteQueensideCastle = false }
                else { blackKingsideCastle = false; blackQueensideCastle = false }
            }
            ROOK -> {
                if (piece.color == WHITE) {
                    if (from.file == 0) whiteQueensideCastle = false
                    if (from.file == 7) whiteKingsideCastle = false
                } else {
                    if (from.file == 0) blackQueensideCastle = false
                    if (from.file == 7) blackKingsideCastle = false
                }
            }
            else -> {}
        }
        if (move.capturedPiece?.type == ROOK) {
            if (move.capturedPiece!!.color == WHITE) {
                if (to.file == 0) whiteQueensideCastle = false
                if (to.file == 7) whiteKingsideCastle = false
            } else {
                if (to.file == 0) blackQueensideCastle = false
                if (to.file == 7) blackKingsideCastle = false
            }
        }

        // En passant target: the square the double-pushing pawn skipped over.
        // The pawn moved by `pawnDir` (white=-1, black=+1), so the captured square
        // is one step further in that same direction.
        val pawnDir = if (piece.color == WHITE) -1 else 1
        enPassantTarget = if (move.type == MoveType.DOUBLE_PAWN_PUSH) {
            Position(from.file, from.rank + pawnDir)
        } else null

        // Halfmove clock
        if (piece.type == PAWN || move.isCapture()) halfmoveClock = 0 else halfmoveClock++

        // Flip the side to move and refresh the cached Zobrist key. Doing this here
        // (not in the callers) means a search node produced by copyBoard()+executeMove
        // already carries a clean key, so repeated hash reads on that node are O(1).
        whiteToMove = !whiteToMove
        zKey = computeZobrist()
        zKeyDirty = false
    }

    // ── make / undo temp move (scratch‑board path) ──────────────────────────
    // Lightweight in‑place move + undo used by generateLegalMoves to avoid
    // creating a full Board copy (with history) for every pseudo‑legal move.
    // Only safe on a scratch board that is never observed by other threads.

    internal class UndoState(
        val toPiece: Piece?,
        val epTarget: Position?,
        val halfmoveClock: Int,
        val wkCastle: Boolean, val wqCastle: Boolean,
        val bkCastle: Boolean, val bqCastle: Boolean,
        val whiteToMove: Boolean,
        val zKey: Long,
        val zKeyDirty: Boolean
    )

    internal fun makeTempMove(move: Move): UndoState {
        val state = UndoState(
            toPiece = squares[move.to.file][move.to.rank],
            epTarget = enPassantTarget,
            halfmoveClock = halfmoveClock,
            wkCastle = whiteKingsideCastle, wqCastle = whiteQueensideCastle,
            bkCastle = blackKingsideCastle, bqCastle = blackQueensideCastle,
            whiteToMove = whiteToMove,
            zKey = zKey, zKeyDirty = zKeyDirty
        )
        executeMove(move)
        return state
    }

    internal fun undoTempMove(move: Move, state: UndoState) {
        // Restore side to move (executeMove flipped it).
        whiteToMove = state.whiteToMove

        // Restore castling rights, ep target, halfmove clock, Zobrist cache.
        whiteKingsideCastle = state.wkCastle
        whiteQueensideCastle = state.wqCastle
        blackKingsideCastle = state.bkCastle
        blackQueensideCastle = state.bqCastle
        enPassantTarget = state.epTarget
        halfmoveClock = state.halfmoveClock
        zKey = state.zKey
        zKeyDirty = state.zKeyDirty

        // Restore squares — castling rook first (different file than from/to).
        when (move.type) {
            MoveType.CASTLE_KINGSIDE -> {
                squares[7][move.from.rank] = squares[5][move.from.rank]
                squares[5][move.from.rank] = null
            }
            MoveType.CASTLE_QUEENSIDE -> {
                squares[0][move.from.rank] = squares[3][move.from.rank]
                squares[3][move.from.rank] = null
            }
            MoveType.EN_PASSANT -> {
                val capturedRank = move.to.rank + if (move.piece.color == PieceColor.WHITE) 1 else -1
                squares[move.to.file][capturedRank] = Piece(PieceType.PAWN, move.capturedPiece!!.color)
            }
            else -> {}
        }
        // Restore main piece move (from ↔ to).
        squares[move.from.file][move.from.rank] = move.piece
        squares[move.to.file][move.to.rank] = state.toPiece
    }

    // Returns the move history of the game as UCI strings, oldest first. Used by the
    // opening book to identify the current opening position.
    fun getHistoryUci(): List<String> = moveHistory.map { it.toUci() }

    fun getGameResult(): GameResult? {
        val moves = generateLegalMoves()
        if (moves.isEmpty()) {
            // Нет доступных ходов — сторона, за которой ход, проигрывает (мат или пат
            // трактуются как поражение, чтобы в игре не было ничьей).
            return GameResult(if (whiteToMove) BLACK else WHITE, "Мат")
        }
        // Ничьих не бывает: правило 50 ходов и троекратное повторение засчитываются
        // как поражение той стороны, за которой ход.
        if (halfmoveClock >= 100) return GameResult(if (whiteToMove) BLACK else WHITE, "Правило 50 ходов")
        if (isThreefoldRepetition()) return GameResult(if (whiteToMove) BLACK else WHITE, "Троекратное повторение")
        return null
    }

    private fun isThreefoldRepetition(): Boolean {
        if (moveHistory.isEmpty()) return false
        // O(1): the occurrence count of each position is maintained incrementally on
        // makeMove (and the starting position counts as the first appearance). No
        // history replay or per-check board reconstruction is needed here.
        return (positionCounts[zobristHash()] ?: 0) >= 3
    }

    /**
     * Cached Zobrist hash of the current position. The value is computed once and then
     * kept fresh: state changes mark the cache dirty, and executeMove recomputes it after
     * each move, so during search repeated calls on the same board cost O(1).
     */
    fun zobristHash(): Long {
    if (zKeyDirty) {
        zKey = computeZobrist()
        zKeyDirty = false
    }
    return zKey
}

/**
     * Full Zobrist recompute from the board state — piece layout, colors, side to move,
     * castling rights and en passant target. This is the authoritative computation; the
     * cached key in [zobristHash] is refreshed from here after any direct mutation.
     */
    private fun computeZobrist(): Long {
    var key = 0L
    for (f in 0..7) {
        for (r in 0..7) {
            val piece = squares[f][r] ?: continue
            val colorIdx = if (piece.color == WHITE) 0 else 1
            val typeIdx = piece.type.ordinal
            key = key xor zPiece[colorIdx][typeIdx][f * 8 + r]
        }
    }
    if (!whiteToMove) key = key xor zSide[1]
    if (whiteKingsideCastle) key = key xor zCastle[0]
    if (whiteQueensideCastle) key = key xor zCastle[1]
    if (blackKingsideCastle) key = key xor zCastle[2]
    if (blackQueensideCastle) key = key xor zCastle[3]
    val ep = enPassantTarget
    if (ep != null) key = key xor zEnPassant[ep.file]
    return key
}

    fun undoMove() {
        // Full move list is stored in makeMove; undo is not needed by the engine,
        // which uses makeMove-based search. Kept for API completeness.
        if (moveHistory.isEmpty()) return
        moveHistory.removeAt(moveHistory.size - 1)
    }

    fun getAllPieces(): List<Pair<Position, Piece>> {
        val result = mutableListOf<Pair<Position, Piece>>()
        for (f in 0..7) for (r in 0..7) {
            val p = squares[f][r]
            if (p != null) result.add(Position(f, r) to p)
        }
        return result
    }

    // Allocation-free mobility counting used by the hot evaluation loop (no Position
    // objects are built).
    fun generatePseudoLegalMobilityFast(file: Int, rank: Int): Int {
        val piece = squares[file][rank] ?: return 0
        val color = piece.color
        val pawnDir = if (color == WHITE) -1 else 1
        var count = 0

        fun onBoard(ff: Int, rr: Int): Boolean = ff in 0..7 && rr in 0..7

        when (piece.type) {
            PieceType.PAWN -> {
                val fr = rank + pawnDir
                if (fr in 0..7 && getPieceFast(file, fr) == null) count++
                for (df in PAWN_ATTACK_FILES) {
                    val c = file + df
                    if (onBoard(c, fr)) {
                        val t = getPieceFast(c, fr)
                        val ep = enPassantTarget
                        val attackable = (t != null && t.color != color) || (ep != null && ep.file == c && ep.rank == fr)
                        if (attackable) count++
                    }
                }
            }
            PieceType.KNIGHT -> {
                for (offset in KNIGHT_OFFSETS) {
                    val to = file + offset[0]
                    val tr = rank + offset[1]
                    if (onBoard(to, tr)) {
                        val t = getPieceFast(to, tr)
                        if (t == null || t.color != color) count++
                    }
                }
            }
            PieceType.KING -> {
                for (offset in KING_OFFSETS) {
                    val to = file + offset[0]
                    val tr = rank + offset[1]
                    if (onBoard(to, tr)) {
                        val t = getPieceFast(to, tr)
                        if (t == null || t.color != color) count++
                    }
                }
            }
            PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN -> {
                val dirs = when (piece.type) {
                    PieceType.BISHOP -> DIAGONALS
                    PieceType.ROOK -> ORTHOGONALS
                    else -> ALL_SLIDING
                }
                for (offset in dirs) {
                    var cur = file + offset[0]
                    var cr = rank + offset[1]
                    while (onBoard(cur, cr)) {
                        val t = getPieceFast(cur, cr)
                        if (t == null) count++
                        else {
                            if (t.color != color) count++
                            break
                        }
                        cur += offset[0]
                        cr += offset[1]
                    }
                }
            }
            else -> {}
        }
        return count
    }

    fun getCapturedPieces(color: PieceColor): List<Piece> {
        val captured = mutableListOf<Piece>()
        for (m in moveHistory) {
            val cp = m.capturedPiece
            if (cp != null && cp.color == color) captured.add(cp)
        }
        return captured
    }
}

data class GameResult(
    val winner: PieceColor?,
    val reason: String
)
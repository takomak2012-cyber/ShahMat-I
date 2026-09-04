package com.shahmat.game.engine

enum class Difficulty(val depth: Int, val useOpeningBook: Boolean = true, val randomFactor: Double = 0.0) {
    LEARN(1, true, 1.5),
    BEGINNER(1, true, 1.0),
    INTERMEDIATE(3, true, 0.05),
    MASTER(5, false, 0.0)
}

class ChessEngine {
    private val random = java.util.Random()

    private val pawnTable = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0
    )

    private val knightTable = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50
    )

    private val bishopTable = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20
    )

    private val rookTable = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0
    )

    private val queenTable = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20
    )

    private val kingTableMid = intArrayOf(
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20
    )

    private val kingTableEnd = intArrayOf(
        -50, -40, -30, -20, -20, -30, -40, -50,
        -30, -20, -10, 0, 0, -10, -20, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -30, 0, 0, 0, 0, -30, -30,
        -50, -30, -30, -30, -30, -30, -30, -50
    )

    private val maxPly = 64
    private val killerMoves = Array(maxPly) { IntArray(2) { -1 } }

    // History heuristic: how often a (from,to,type,promotion) quiet move caused a beta
    // cutoff. Sized to the full move-key space so distinct promotions stay distinct.
    private val historyMoves = IntArray(64 * 64 * MOVE_TYPE_COUNT * PROMO_COUNT)

    // Scratch arrays reused across evaluation leaves so the hot pawn-structure evaluator
    // never allocates transient arrays. Safe: search is synchronous and single-threaded,
    // so these are only ever touched by one evaluate() invocation at a time.
    private val pawnFilesWhite = IntArray(8)
    private val pawnFilesBlack = IntArray(8)
    private val pawnCountWhite = IntArray(8)
    private val pawnCountBlack = IntArray(8)

    // Evaluation term weights (tuned by hand, tuned in small increments).

    // Set to true the moment the search exceeds its time budget. Every recursion level
    // checks it so an aborted subtree unwinds immediately instead of feeding bogus
    // partial scores upward; unfinished nodes are never written to the transposition
    // table, and a partially computed iteration never replaces the last completed one.
    @Volatile
    private var searchAborted = false

    // Optional hard override of the per-difficulty time budget (milliseconds). Used by
    // tests to force an immediate timeout; null keeps the normal difficulty budget.
    @Volatile
    internal var timeBudgetOverrideMs: Long? = null

    // Transposition table: bounded, key-verified, never stores mate-distance scores
    // (avoiding ply-distortion). Using an array avoids unbounded memory growth.
    internal class TtEntry(
        val key: Long,
        val depth: Int,
        val score: Int,
        val bound: Int, // EXACT, LOWERBOUND, UPPERBOUND
        val moveKey: Int
    )

    private val ttSize = (1 shl 18) // 262144 entries (~ a few MB)
    private val ttMask = ttSize - 1
    private val tt = arrayOfNulls<TtEntry?>(ttSize)

    // Exposed read-only view for white-box tests (size bound, mate-score threshold).
    internal val ttTableSize: Int get() = ttSize
    internal val ttTableMask: Int get() = ttMask
    internal val mateScore: Int get() = MATE
    internal val mateScoreFloor: Int get() = MATE - 300
    internal val boundExact: Int get() = TT_EXACT
    internal val boundLower: Int get() = TT_LOWER
    internal val boundUpper: Int get() = TT_UPPER

    // Test hook: expose the evaluated score so tests can compare symmetric positions,
    // the starting position, endgames and pawn-structure configurations.
    internal fun evaluateScore(board: Board): Int = evaluate(board)

    // Node counter for performance measurement. Incremented at every alpha-beta and
    // quiescence entry, reset by benchmarkSearch() before a timed search.
    internal var nodesVisited = 0L

    /** Timed-search measurement: counts nodes and wall time for a single findBestMove. */
    internal data class SearchBench(val move: Move?, val elapsedMs: Long, val nodes: Long) {
        val nodesPerSec: Double get() = if (elapsedMs > 0) nodes * 1000.0 / elapsedMs else 0.0
    }

    internal fun benchmarkSearch(board: Board, difficulty: Difficulty): SearchBench {
        nodesVisited = 0L
        val t0 = System.nanoTime()
        val move = try { findBestMove(board, difficulty) } catch (e: Exception) { e.printStackTrace(); null }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        return SearchBench(move, elapsedMs, nodesVisited)
    }

    private companion object {
        const val MATE = 100000
        const val INF = Int.MAX_VALUE / 2
        const val TT_EXACT = 0
        const val TT_LOWER = 1
        const val TT_UPPER = 2
        const val MOVE_TYPE_COUNT = 7
        const val PROMO_COUNT = 5 // none, Q, R, B, N
        // Piece values indexed by PieceType.ordinal (PAWN=0..KING=5). A flat array is
        // used instead of a Map so hot evaluation loops never pay a hash lookup.
        val pieceValueOf = intArrayOf(100, 320, 330, 500, 900, 20000)
        // Evaluation term weights (kept small; tuned individually per heuristic group).
        const val BISHOP_PAIR_BONUS = 40
        const val OUTPOST_PROTECTED = 30
        const val OUTPOST_PROTECTED_ATTACKABLE = 15
        const val OUTPOST_ALONE = 5
        const val ROOK_OPEN_FILE = 25
        const val ROOK_SEMI_OPEN_FILE = 12
        const val ROOK_SEVENTH_RANK = 30
        const val CENTER_PIECE_BONUS = 10
        const val DEVELOPMENT_BONUS = 8
        const val KING_SHELTER_PAWN = 8
        const val KING_UNSHELTERED = -20
        const val ENDGAME_PASSIVE_KING = -20
    }

    internal fun ttProbe(key: Long): TtEntry? = tt[(key and ttMask.toLong()).toInt()]?.takeIf { it.key == key }

    internal fun ttStore(key: Long, depth: Int, score: Int, bound: Int, bestMoveKey: Int) {
        // Do not store mate-distance scores in the table.
        if (score > MATE - 300 || score < -MATE + 300) return
        tt[(key and ttMask.toLong()).toInt()] = TtEntry(key, depth, score, bound, bestMoveKey)
    }

    internal fun moveKey(m: Move): Int {
        // Encode the full move identity so that e7e8=Q / e7e8=R / e7e8=B / e7e8=N
        // (same from+to, different promotion) receive DIFFERENT keys, and capture
        // vs. quiet vs. castling etc. do not alias each other.
        val fromIdx = m.from.file * 8 + m.from.rank
        val toIdx = m.to.file * 8 + m.to.rank
        val typeIdx = m.type.ordinal
        val promoIdx = m.promotionType?.let { it.ordinal + 1 } ?: 0
        return (((fromIdx * 64 + toIdx) * MOVE_TYPE_COUNT + typeIdx) * PROMO_COUNT + promoIdx)
    }

    fun findBestMove(board: Board, difficulty: Difficulty): Move? {
        try {
            val moves = board.generateLegalMoves()
            if (moves.isEmpty()) return null
            if (moves.size == 1) return moves[0]

            // Opening book for easier difficulties
            if (difficulty.useOpeningBook && board.fullmoveNumber <= 10) {
                val bookMove = getBookMove(board)
                if (bookMove != null) return bookMove
            }

            val random = java.util.Random()
            val maxDepth = difficulty.depth
            val baseTimeLimitMs = when (difficulty) {
                Difficulty.MASTER -> 4000L
                Difficulty.INTERMEDIATE -> 2500L
                Difficulty.BEGINNER -> 1500L
                else -> 800L
            }
            val timeLimitMs = timeBudgetOverrideMs ?: baseTimeLimitMs

            // Reset per-search history and the abort flag (a new search starts fresh).
            java.util.Arrays.fill(historyMoves, 0)
            searchAborted = false

            val timeout = System.currentTimeMillis() + timeLimitMs
            var bestMove: Move? = moves[0]

            // Iterative deepening within the time budget.
            for (depth in 1..maxDepth) {
                if (searchAborted) break
                var alpha = -INF
                var beta = INF
                var iterBest: Move? = null
                var iterBestScore = Int.MIN_VALUE

                for (move in orderMoves(board, moves, 0, ttProbe(board.zobristHash())?.moveKey)) {
                    if (searchAborted) break
                    if (System.currentTimeMillis() > timeout && depth > 1) {
                        searchAborted = true
                        break
                    }
                    val newBoard = board.makeSearchMove(move)
                    val score = -alphaBeta(newBoard, depth - 1, -beta, -alpha, 0, timeout)
                    if (score > iterBestScore) {
                        iterBestScore = score
                        iterBest = move
                    }
                    if (score > alpha) {
                        alpha = score
                        iterBest = move
                    }
                }
                // Only a FULLY COMPLETED iteration may replace the result. A partial
                // iteration (aborted by timeout) has no right to overwrite the last
                // fully-computed bestMove. bestMove therefore always holds a move from a
                // completed search (or the fallback first move).
                if (!searchAborted && iterBest != null) {
                    bestMove = iterBest
                }
                if (searchAborted || System.currentTimeMillis() > timeout) break
            }

            // Easier difficulties: pick uniformly at random among moves whose TRUE score
            // (computed with a full alpha-beta window, so no fail-low ambiguity) is within
            // a small window of the best. A narrow-window re-search would collapse every
            // inferior move to the same fail-low value, making the filter useless.
            if (difficulty.randomFactor > 0.0 && moves.size > 1) {
                val renderDepth = minOf(difficulty.depth, 2)
                if (!searchAborted && renderDepth >= 1) {
                    val trueScores = ArrayList<Pair<Move, Int>>(moves.size)
                    for (move in orderMoves(board, moves, 0, ttProbe(board.zobristHash())?.moveKey)) {
                        if (searchAborted || System.currentTimeMillis() > timeout) return bestMove
                        val newBoard = board.makeSearchMove(move)
                        trueScores.add(move to -alphaBeta(newBoard, renderDepth - 1, -INF, INF, 0, timeout))
                    }
                    val best = trueScores.maxOf { it.second }
                    val window = (difficulty.randomFactor * 100).toInt().coerceAtLeast(4)
                    val candidates = trueScores.filter { it.second >= best - window }
                    if (candidates.isNotEmpty()) {
                        return candidates[random.nextInt(candidates.size)].first
                    }
                }
            }
            return bestMove
        } catch (e: Exception) {
            e.printStackTrace()
            return try { board.generateLegalMoves().firstOrNull() } catch (t: Exception) { null }
        }
    }

    private fun alphaBeta(board: Board, depth: Int, alpha: Int, beta: Int, ply: Int, timeout: Long): Int {
        // Aborted subtree: unwind immediately. The return value is discarded by callers
        // (they check searchAborted), so a cheap constant is fine — no bogus score
        // propagates upward and nothing is written to the TT for this unfinished node.
        if (searchAborted || System.currentTimeMillis() > timeout) {
            searchAborted = true
            return 0
        }
        nodesVisited++

        val ttKey = board.zobristHash()
        val ttEntry = ttProbe(ttKey)
        if (ttEntry != null && ttEntry.depth >= depth) {
            when (ttEntry.bound) {
                TT_EXACT -> return ttEntry.score
                TT_LOWER -> if (ttEntry.score >= beta) return ttEntry.score
                TT_UPPER -> if (ttEntry.score <= alpha) return ttEntry.score
            }
        }

        if (depth == 0) return quiescenceSearch(board, alpha, beta, 0, timeout)

        val moves = board.generateLegalMoves()
        if (moves.isEmpty()) {
            // Mate or stalemate
            return if (board.isInCheck(if (board.whiteToMove) PieceColor.WHITE else PieceColor.BLACK)) {
                -(MATE - ply)  // prefer faster mates / delay getting mated
            } else {
                0
            }
        }

        val ordered = orderMoves(board, moves, ply, ttEntry?.moveKey)
        var a = alpha
        var score = Int.MIN_VALUE
        var bestMoveKey = ttEntry?.moveKey ?: -1
        var bound = TT_UPPER

        var firstMove = true
        for (move in ordered) {
            if (searchAborted) break
            val newBoard = board.makeSearchMove(move)
            var s: Int
            if (firstMove) {
                // Full-window research for the first (best-ordered) move.
                s = -alphaBeta(newBoard, depth - 1, -beta, -a, ply + 1, timeout)
            } else {
                // Principal Variation Search: search the remaining moves on a narrow
                // window; a fail-high is confirmed by re-searching with the full window.
                s = -alphaBeta(newBoard, depth - 1, -a - 1, -a, ply + 1, timeout)
                if (!searchAborted && s > a && s < beta) {
                    s = -alphaBeta(newBoard, depth - 1, -beta, -a, ply + 1, timeout)
                }
            }
            firstMove = false
            if (searchAborted) break
            if (s > score) {
                score = s
                bestMoveKey = moveKey(move)
                if (s > a) {
                    a = s
                    if (a >= beta) {
                        // Beta cutoff - save killer move and history heuristic score
                        if (!move.isCapture()) {
                            if (killerMoves[ply][0] != moveKey(move)) {
                                killerMoves[ply][1] = killerMoves[ply][0]
                                killerMoves[ply][0] = moveKey(move)
                            }
                            historyMoves[moveKey(move)] += depth * depth
                        }
                        bound = TT_LOWER
                        break
                    }
                }
            }
        }
        // Only store results in the transposition table from finished nodes. An aborted
        // (unfinished) subtree must never persist its partial EXACT/LOWER/UPPER score.
        if (!searchAborted) {
            if (bound == TT_LOWER) {
                ttStore(ttKey, depth, score, TT_LOWER, bestMoveKey)
            } else if (score <= a && a == alpha) {
                ttStore(ttKey, depth, score, TT_UPPER, bestMoveKey)
            } else {
                ttStore(ttKey, depth, score, TT_EXACT, bestMoveKey)
            }
        }
        return score
    }

    private fun orderMoves(board: Board, moves: List<Move>, ply: Int, ttBestMoveKey: Int?): List<Move> {
        if (moves.size < 2) return moves
        return moves.sortedWith(compareByDescending<Move> { moveOrderingValue(board, it, ply, ttBestMoveKey) })
    }

    private fun moveOrderingValue(board: Board, move: Move, ply: Int, ttBestMoveKey: Int?): Int {
        var value = 0
        if (ttBestMoveKey != null && ttBestMoveKey == moveKey(move)) value += 20000
        if (move.isCapture()) {
            val victim = move.capturedPiece?.let { pieceValueOf[it.type.ordinal] } ?: 0
            val attacker = pieceValueOf[move.piece.type.ordinal]
            value += 10000 + victim - attacker / 10  // MVV-LVA
            if (board.getPiece(move.to) == null && move.type == MoveType.EN_PASSANT) value += 100
        }
        if (killerMoves[ply][0] == moveKey(move)) value += 8000
        else if (killerMoves[ply][1] == moveKey(move)) value += 7000
        if (move.isPromotion()) value += 9000
        // History heuristic for quiet moves (bounded so it cannot outrank a capture).
        if (!move.isCapture() && !move.isPromotion()) {
            var h = historyMoves[moveKey(move)]
            if (h > 6000) h = 6000
            value += h
        }
        return value
    }

    private fun quiescenceSearch(board: Board, alpha: Int, beta: Int, ply: Int, timeout: Long): Int {
        // Respect the time budget so a long capture chain can never run far past the
        // allocated time (which was a cause of the AI feeling like a UI freeze).
        if (searchAborted || System.currentTimeMillis() > timeout) {
            searchAborted = true
            return 0
        }
        nodesVisited++
        // Hard cap on quiescence recursion depth so pathological capture chains can never
        // overflow the call stack (StackOverflowError is not caught by catch(Exception)).
        if (ply >= 32) return if (board.whiteToMove) evaluate(board) else -evaluate(board)
        // evaluate() is white-perspective, but this is a negamax routine: the score must
        // be from the SIDE TO MOVE's point of view, so it is flipped at every black node.
        val standPat = if (board.whiteToMove) evaluate(board) else -evaluate(board)
        if (standPat >= beta) return beta
        var a = alpha
        if (standPat > a) a = standPat

        val captures = board.generateCaptures()
            .sortedByDescending { (it.capturedPiece?.let { p -> pieceValueOf[p.type.ordinal] } ?: 0) }
        for (move in captures) {
            if (searchAborted) break
            // Delta pruning: if even the best possible gain from this capture cannot
            // improve on alpha, skip it. Safely prunes obviously losing recaptures in
            // tactical quiet positions without affecting mate detection (mate scores
            // come through the main alpha-beta at depth 0, not via delta-pruned nodes).
            val victimValue = move.capturedPiece?.let { pieceValueOf[it.type.ordinal] } ?: 0
            if (standPat + victimValue + 150 < a) continue
            val newBoard = board.makeSearchMove(move)
            val score = -quiescenceSearch(newBoard, -beta, -a, ply + 1, timeout)
            if (searchAborted) break
            if (score >= beta) return beta
            if (score > a) a = score
        }
        return a
    }

    private fun evaluate(board: Board): Int {
        // Pass 1: accumulate the full material for both sides before any phase decision,
        // so the endgame flag is independent of the cell-traversal order. Kings are
        // deliberately excluded — their huge nominal value would mask the true phase.
        var whiteMaterial = 0
        var blackMaterial = 0
        for (f in 0..7) {
            for (r in 0..7) {
                val pc = board.getPieceFast(f, r) ?: continue
                if (pc.type == PieceType.KING) continue
                if (pc.color == PieceColor.WHITE) whiteMaterial += pieceValueOf[pc.type.ordinal]
                else blackMaterial += pieceValueOf[pc.type.ordinal]
            }
        }
        val totalMaterial = whiteMaterial + blackMaterial
        val isEndgame = totalMaterial < 1500

        // Pass 2: apply material + piece-square tables using the phase computed above.
        var score = 0
        for (f in 0..7) {
            for (r in 0..7) {
                val pc = board.getPieceFast(f, r) ?: continue
                val idx = if (pc.color == PieceColor.WHITE) r * 8 + f else (7 - r) * 8 + f
                val pieceValue = pieceValueOf[pc.type.ordinal]
                val posValue = getPositionalValue(pc, idx, isEndgame)
                if (pc.color == PieceColor.WHITE) {
                    score += pieceValue + posValue
                } else {
                    score -= pieceValue + posValue
                }
            }
        }

        // Simplified mobility: cheap per-piece mobility without regenerating all legal moves.
        // (Generating legal moves here, at every search leaf, is far too slow.)
        score += evaluateMobility(board)

        // Pawn-structure terms: isolated/doubled pawns, passed pawn bonuses.
        score += evaluatePawnStructure(board, totalMaterial)

        // Minor-piece terms: bishop pair, knight outposts.
        score += evaluateMinorPieces(board)

        // Rook terms (open/semi-open files, 7th rank). Reuses the occupied-file arrays
        // already filled by evaluatePawnStructure for the same position.
        score += evaluateRooks(board)

        // Center control + piece development (middle-game oriented).
        score += evaluateCenterAndDevelopment(board, totalMaterial)

        // King shelter / safety (middle game) and endgame king-activity guidance.
        score += evaluateKingTerms(board, totalMaterial, isEndgame)

        return score
    }

    // Bishop pair + knight outposts. Both terms are symmetric under a color mirror.
    private fun evaluateMinorPieces(board: Board): Int {
        var whiteBishops = 0
        var blackBishops = 0
        var score = 0
        for (f in 0..7) {
            for (r in 0..7) {
                val pc = board.getPieceFast(f, r) ?: continue
                when (pc.type) {
                    PieceType.BISHOP -> if (pc.color == PieceColor.WHITE) whiteBishops++ else blackBishops++
                    PieceType.KNIGHT -> score += knightOutpostTerm(board, pc.color, f, r)
                    else -> {}
                }
            }
        }
        if (whiteBishops >= 2) score += BISHOP_PAIR_BONUS
        if (blackBishops >= 2) score -= BISHOP_PAIR_BONUS
        return score
    }

    // Outpost: knight deep on the opponent's side (white: ranks 1-2, black: ranks 5-6),
    // protected by a friendly pawn and (ideally) not capturable by an enemy pawn.
    private fun knightOutpostTerm(board: Board, color: PieceColor, f: Int, r: Int): Int {
        val white = color == PieceColor.WHITE
        val deep = if (white) r <= 2 else r >= 5
        if (!deep) return 0
        // Own pawn one step behind attacks the square (white pawn on r+1 defends r).
        var ownProtected = false
        // Enemy pawn one step further back (toward their side) attacks the square
        // (a black pawn on r-1 attacks white knight on r).
        var enemyCanAttack = false
        for (df in -1..1 step 2) {
            val pf = f + df
            if (pf !in 0..7) continue
            val ownRank = if (white) r + 1 else r - 1
            val enemyRank = if (white) r - 1 else r + 1
            if (ownRank in 0..7) {
                val p = board.getPieceFast(pf, ownRank)
                if (p != null && p.type == PieceType.PAWN && p.color == color) ownProtected = true
            }
            if (enemyRank in 0..7) {
                val p = board.getPieceFast(pf, enemyRank)
                if (p != null && p.type == PieceType.PAWN && p.color != color) enemyCanAttack = true
            }
        }
        return when {
            ownProtected && !enemyCanAttack -> OUTPOST_PROTECTED
            ownProtected -> OUTPOST_PROTECTED_ATTACKABLE
            else -> OUTPOST_ALONE
        }.let { if (white) it else -it }
    }

    // Rook terms: open file (no pawns of either side), semi-open file (no own pawn but
    // enemy pawn present), and rook infiltrating the 7th rank (the opponent's 2nd rank).
    // Assumes pawnFilesWhite/Black/pawnCount* are already filled by evaluatePawnStructure.
    private fun evaluateRooks(board: Board): Int {
        var score = 0
        for (f in 0..7) {
            for (r in 0..7) {
                val pc = board.getPieceFast(f, r) ?: continue
                if (pc.type != PieceType.ROOK) continue
                val white = pc.color == PieceColor.WHITE
                val noOwnPawn = if (white) pawnCountWhite[f] == 0 else pawnCountBlack[f] == 0
                val enemyPawn = if (white) pawnCountBlack[f] > 0 else pawnCountWhite[f] > 0
                if (noOwnPawn) {
                    if (enemyPawn) {
                        if (white) score += ROOK_SEMI_OPEN_FILE else score -= ROOK_SEMI_OPEN_FILE
                    } else {
                        // Fully open file: no pawns at all.
                        if (white) score += ROOK_OPEN_FILE else score -= ROOK_OPEN_FILE
                    }
                }
                // 7th rank: white rooks on rank 1, black rooks on rank 6.
                val seventh = if (white) r == 1 else r == 6
                if (seventh) {
                    if (white) score += ROOK_SEVENTH_RANK else score -= ROOK_SEVENTH_RANK
                }
            }
        }
        return score
    }

    // Center control (minor pieces sitting on the central four squares) and development
    // (minor pieces moved off the back rank). Both are only meaningful in the middle game.
    private fun evaluateCenterAndDevelopment(board: Board, totalMaterial: Int): Int {
        if (totalMaterial < 2000) return 0 // endgame: development/center less relevant
        var score = 0
        for (f in 0..7) {
            for (r in 0..7) {
                val pc = board.getPieceFast(f, r) ?: continue
                if (pc.type != PieceType.KNIGHT && pc.type != PieceType.BISHOP) continue
                val white = pc.color == PieceColor.WHITE
                // Center: d4/e4/d5/e5 in engine coords = files 3-4, ranks 3-4.
                var s = 0
                if (f in 3..4 && r in 3..4) s += CENTER_PIECE_BONUS
                // Developed: not sitting on the home back rank.
                val backRank = if (white) 7 else 0
                if (r != backRank) s += DEVELOPMENT_BONUS
                if (white) score += s else score -= s
            }
        }
        return score
    }

    // King terms: pawn shelter in front of the king and a penalty for a king wandering
    // out from its home in the middle game (king safety); in the endgame a king that
    // stays parked on its home rank is penalised (endgame activity).
    private fun evaluateKingTerms(board: Board, totalMaterial: Int, isEndgame: Boolean): Int {
        var score = 0
        for (f in 0..7) {
            for (r in 0..7) {
                val pc = board.getPieceFast(f, r) ?: continue
                if (pc.type != PieceType.KING) continue
                val white = pc.color == PieceColor.WHITE
                var s = 0
                if (!isEndgame && totalMaterial >= 2000) {
                    val shelterRank = if (white) r - 1 else r + 1
                    if (shelterRank in 0..7) {
                        for (df in -1..1) {
                            val pf = f + df
                            if (pf !in 0..7) continue
                            val shield = board.getPieceFast(pf, shelterRank)
                            if (shield != null && shield.type == PieceType.PAWN && shield.color == pc.color) {
                                s += KING_SHELTER_PAWN
                            }
                        }
                    }
                    val homeRank = if (white) 7 else 0
                    if (r != homeRank) s += KING_UNSHELTERED
                }
                if (isEndgame) {
                    val homeRank = if (white) 7 else 0
                    if (r == homeRank) s += ENDGAME_PASSIVE_KING
                }
                if (white) score += s else score -= s
            }
        }
        return score
    }

    private fun evaluatePawnStructure(board: Board, totalMaterial: Int): Int {
        // Reuse preallocated scratch arrays (zero-fill each call).
        pawnFilesWhite.fill(-1)
        pawnFilesBlack.fill(-1)
        pawnCountWhite.fill(0)
        pawnCountBlack.fill(0)
        var whiteScore = 0
        var blackScore = 0

        for (f in 0..7) {
            for (r in 0..7) {
                val piece = board.getPieceFast(f, r) ?: continue
                if (piece.type != PieceType.PAWN) continue
                if (piece.color == PieceColor.WHITE) {
                    // White advances toward rank 0 (smaller rank).
                    if (r < pawnFilesWhite[f] || pawnFilesWhite[f] < 0) pawnFilesWhite[f] = r
                    pawnCountWhite[f]++
                } else {
                    // Black advances toward rank 7 (larger rank).
                    if (r > pawnFilesBlack[f] || pawnFilesBlack[f] < 0) pawnFilesBlack[f] = r
                    pawnCountBlack[f]++
                }
            }
        }

        // Penalize isolated and doubled pawns, reward passed pawns.
        for (f in 0..7) {
            // White pawns
            if (pawnCountWhite[f] > 0) {
                val hasNeighbour = (f > 0 && pawnCountWhite[f - 1] > 0) || (f < 7 && pawnCountWhite[f + 1] > 0)
                if (!hasNeighbour) whiteScore -= 15
                if (pawnCountWhite[f] > 1) whiteScore -= 12 * (pawnCountWhite[f] - 1)
                val pawnRank = pawnFilesWhite[f]
                // Passed pawn: no black pawn on the same or adjacent files ahead of it
                // (ahead for white = smaller rank).
                val lower = if (f > 0) f - 1 else 0
                val upper = if (f < 7) f + 1 else 7
                var passed = true
                for (ff in lower..upper) {
                    if (pawnFilesBlack[ff] >= 0 && pawnFilesBlack[ff] < pawnRank) { passed = false; break }
                }
                if (passed) whiteScore += 40 + (7 - pawnRank) * 10
            }
            // Black pawns (symmetric)
            if (pawnCountBlack[f] > 0) {
                val hasNeighbour = (f > 0 && pawnCountBlack[f - 1] > 0) || (f < 7 && pawnCountBlack[f + 1] > 0)
                if (!hasNeighbour) blackScore -= 15
                if (pawnCountBlack[f] > 1) blackScore -= 12 * (pawnCountBlack[f] - 1)
                val pawnRank = pawnFilesBlack[f]
                val lower = if (f > 0) f - 1 else 0
                val upper = if (f < 7) f + 1 else 7
                var passed = true
                for (ff in lower..upper) {
                    if (pawnFilesWhite[ff] >= 0 && pawnFilesWhite[ff] > pawnRank) { passed = false; break }
                }
                if (passed) blackScore += 40 + pawnRank * 10
            }
        }

        // In the endgame a passed pawn is worth a bit more; scale slightly by material.
        val endgameFactor = if (totalMaterial < 2600) 1.4 else 1.0
        return ((whiteScore - blackScore) * endgameFactor).toInt()
    }

    private fun evaluateMobility(board: Board): Int {
        var mob = 0
        for (f in 0..7) {
            for (r in 0..7) {
                val piece = board.getPieceFast(f, r) ?: continue
                val moves = board.generatePseudoLegalMobilityFast(f, r)
                if (piece.color == PieceColor.WHITE) mob += moves else mob -= moves
            }
        }
        return mob * 2
    }

    private fun getPositionalValue(piece: Piece, idx: Int, isEndgame: Boolean): Int {
        return when (piece.type) {
            PieceType.PAWN -> pawnTable[idx]
            PieceType.KNIGHT -> knightTable[idx]
            PieceType.BISHOP -> bishopTable[idx]
            PieceType.ROOK -> rookTable[idx]
            PieceType.QUEEN -> queenTable[idx]
            PieceType.KING -> if (isEndgame) kingTableEnd[idx] else kingTableMid[idx]
        }
    }

    private fun getBookMove(board: Board): Move? {
        // Book key = the sequence of UCI moves already played, oldest first.
        val history = board.getHistoryUci()
        val key = history.joinToString(" ")
        val legalUci = board.generateLegalMoves().map { it.toUci() }.toSet()

        val candidates = openingBook[key]
        if (candidates == null || candidates.isEmpty()) return null
        val legal = candidates.filter { it in legalUci }
        if (legal.isEmpty()) return null

        // Pick randomly among legal book replies so play is not fully deterministic.
        val chosen = legal[random.nextInt(legal.size)]
        return board.generateLegalMoves().firstOrNull { it.toUci() == chosen }
    }

internal val openingBook = mapOf<String, List<String>>(
    // Book keys are the FULL alternating move history (oldest first) joined by a space,
    // in the engine's own coordinate system in which white starts on ranks 6-7 and black
    // on ranks 0-1 (toAlgebraic(rank) = rank + 1). This is a rank mirror of standard FIDE
    // coordinates: FIDE 1.e4 plays here as the white move "e7e5". Every key below must be
    // reachable move-by-move (each entry must be a legal move from the accumulated
    // position), and every reply must be one of the legal moves of the resulting position.
    "" to listOf("e7e5", "d7d5", "g8f6", "b8c6"), // 1.e4 / 1.d4 / 1.Nf3 / 1.Nc3

    // Black's replies to 1.e4.
    "e7e5" to listOf("e2e4", "c2c4", "e2e3", "c2c3", "g1f3", "d2d4"), // e5 / c5 / e6 / c6 / Nf6 / d5

    // White's 2nd move after 1.e4 e5.
    "e7e5 e2e4" to listOf("g8f6", "b8c6", "c7c5", "d7d5"), // Nf3 / Nc3 / c4 / d4

    // Black's replies to 1.e4 e5 2.Nf3.
    "e7e5 e2e4 g8f6" to listOf("b1c3", "g1f3", "d2d3"), // Nc6 / Nf6 / d6

    // White's 3rd move after 1.e4 e5 2.Nf3 Nc6 (Italian/Ruy Lopez themes).
    "e7e5 e2e4 g8f6 b1c3" to listOf("f8c5", "f8b4", "d7d5", "b8c6"), // Bc4 / Bb5 / d4 / Nc3

    // Black's replies to 1.e4 e5 2.Nc3.
    "e7e5 e2e4 b8c6" to listOf("g1f3", "d2d3", "e1e2"), // Nf6 / d6 / Be7

    // Black's replies to 1.d4.
    "d7d5" to listOf("g1f3", "d2d4", "f2f4", "e2e3", "g2g3"), // Nf6 / d5 / f5 / e6 / g6

    // White's 2nd move after 1.d4 d5.
    "d7d5 d2d4" to listOf("b8c6", "g8f6", "c8f5", "c7c5"), // Nc3 / Nf3 / Bf4 / c4

    // Black's replies to 1.d4 d5 2.Nf3.
    "d7d5 d2d4 g8f6" to listOf("c2c4", "e2e3", "g2g3", "c1f4"), // c5 / e6 / g6 / Bf5

    // White's 2nd move after 1.d4 Nf6.
    "d7d5 g1f3" to listOf("c7c5", "c8f5", "e7e6", "g7g6") // c4 / Bf4 / e3 / g3
)

    /** Test hook: exposes the opening book without leaking the top-level symbol. */
    internal val bookForTesting: Map<String, List<String>>
        get() = openingBook

    fun getHint(board: Board, difficulty: Difficulty): Move? {
        try {
            return findBestMove(board, difficulty)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
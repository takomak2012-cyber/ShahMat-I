package com.shahmat.game.engine

data class Position(
    val file: Int, // 0-7 (a-h)
    val rank: Int  // 0-7 (1-8)
) {
    companion object {
        fun fromAlgebraic(alg: String): Position? {
            if (alg.length != 2) return null
            val file = alg[0].code - 'a'.code
            val rank = alg[1].code - '1'.code
            if (file !in 0..7 || rank !in 0..7) return null
            return Position(file, rank)
        }
    }

    fun toAlgebraic(): String = "${('a' + file).toChar()}${rank + 1}"

    fun isValid(): Boolean = file in 0..7 && rank in 0..7

    fun translate(df: Int, dr: Int): Position = Position(file + df, rank + dr)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Position) return false
        return file == other.file && rank == other.rank
    }

    override fun hashCode(): Int = file * 8 + rank
}
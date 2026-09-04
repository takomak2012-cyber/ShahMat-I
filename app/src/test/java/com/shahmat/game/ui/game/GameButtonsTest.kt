package com.shahmat.game.ui.game

import com.shahmat.game.engine.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Etap 25 — scenarios 1-5. Verifies the level-dependent control visibility rules
 * (LEARN/BEGINNER expose both "Подсказка" and "Спросить"; INTERMEDIATE/MASTER expose
 * neither) that drive the real UI, without needing a device.
 */
class GameButtonsTest {

    @Test
    fun learnHasHintAndAsk() {
        val b = gameButtonsFor(Difficulty.LEARN)
        assertTrue("LEARN must show Подсказка", b.showHint)
        assertTrue("LEARN must show Спросить", b.showAsk)
    }

    @Test
    fun beginnerHasHintAndAsk() {
        val b = gameButtonsFor(Difficulty.BEGINNER)
        assertTrue("BEGINNER must show Подсказка", b.showHint)
        assertTrue("BEGINNER must show Спросить", b.showAsk)
    }

    @Test
    fun intermediateHasNeitherHintNorAsk() {
        val b = gameButtonsFor(Difficulty.INTERMEDIATE)
        assertFalse("INTERMEDIATE must NOT show Подсказка", b.showHint)
        assertFalse("INTERMEDIATE must NOT show Спросить", b.showAsk)
    }

    @Test
    fun masterHasNeitherHintNorAsk() {
        val b = gameButtonsFor(Difficulty.MASTER)
        assertFalse("MASTER must NOT show Подсказка", b.showHint)
        assertFalse("MASTER must NOT show Спросить", b.showAsk)
    }

    @Test
    fun everyDifficultyIsMappedExhaustively() {
        val mapped = Difficulty.values().associateWith { gameButtonsFor(it) }
        assertEquals(4, mapped.size)
        for (d in Difficulty.values()) {
            assertEquals("difficulty $d must be handled", mapped.containsKey(d), true)
        }
    }
}

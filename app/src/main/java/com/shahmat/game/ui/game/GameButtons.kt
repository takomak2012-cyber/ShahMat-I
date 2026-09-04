package com.shahmat.game.ui.game

import com.shahmat.game.engine.Difficulty

/**
 * Pure, device-free decision of which teaching controls are available for a level.
 *
 * - [showHint]: the "Подсказка" button is offered on the teaching-friendly levels.
 * - [showAsk]: the "Спросить" learning button, only on LEARN and BEGINNER.
 *
 * Kept as a pure function (no Android types) so the UI can use it directly and the
 * rule can be unit-tested without a device.
 */
data class GameButtons(val showHint: Boolean, val showAsk: Boolean)

fun gameButtonsFor(difficulty: Difficulty): GameButtons = when (difficulty) {
    Difficulty.LEARN, Difficulty.BEGINNER -> GameButtons(showHint = true, showAsk = true)
    Difficulty.INTERMEDIATE, Difficulty.MASTER -> GameButtons(showHint = false, showAsk = false)
}

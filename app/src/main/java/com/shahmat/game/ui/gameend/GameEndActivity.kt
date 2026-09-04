package com.shahmat.game.ui.gameend

import android.animation.ObjectAnimator
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.shahmat.game.R
import com.shahmat.game.ui.select.ColorSelectionActivity

class GameEndActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "extra_result"
        const val EXTRA_PLAYER_COLOR = "extra_player_color"
        const val EXTRA_TWO_PLAYER = "extra_two_player"
        const val EXTRA_WINNER_COLOR = "extra_winner_color"
        const val RESULT_VICTORY = "VICTORY"
        const val RESULT_DEFEAT = "DEFEAT"
    }

    /** How long the full-screen result banner stays up before the animation starts. */
    private val BANNER_DELAY_MS = 3000L

    private lateinit var fireworksView: FireworksView
    private lateinit var tvSadFace: TextView
    private lateinit var tvEndMessage: TextView
    private lateinit var tvResultBanner: TextView
    private lateinit var btnPlayAgain: android.widget.Button
    private var mediaPlayer: MediaPlayer? = null
    private var sadAnimators: MutableList<ObjectAnimator> = mutableListOf()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bannerRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_end)

        fireworksView = findViewById(R.id.fireworksView)
        tvSadFace = findViewById(R.id.tvSadFace)
        tvEndMessage = findViewById(R.id.tvEndMessage)
        tvResultBanner = findViewById(R.id.tvResultBanner)
        btnPlayAgain = findViewById(R.id.btnPlayAgain)

        val result = intent.getStringExtra(EXTRA_RESULT) ?: RESULT_DEFEAT
        val isTwoPlayer = intent.getBooleanExtra(EXTRA_TWO_PLAYER, false)
        val winnerColor = intent.getStringExtra(EXTRA_WINNER_COLOR)

        // "Играем ещё?" takes the player back to the colour picker to start a new game.
        btnPlayAgain.setOnClickListener {
            navigateTo(ColorSelectionActivity::class.java)
        }

        showBanner(result, isTwoPlayer, winnerColor)
        scheduleAnimation(result)
    }

    /** Shows "Победа" / "Вам мат" (solo) or "Белые/Чёрные победили" (two-player)
     *  full-screen; the win/lose animation is held back until [BANNER_DELAY_MS]. */
    private fun showBanner(result: String, isTwoPlayer: Boolean, winnerColor: String?) {
        val text = if (isTwoPlayer) {
            if (winnerColor == "BLACK") R.string.result_black_won else R.string.result_white_won
        } else if (result == RESULT_VICTORY) {
            R.string.result_victory
        } else {
            R.string.result_checkmate
        }
        tvResultBanner.setText(text)
        tvEndMessage.visibility = View.GONE
        tvSadFace.visibility = View.GONE
        fireworksView.visibility = View.GONE
        btnPlayAgain.visibility = View.GONE
        tvResultBanner.visibility = View.VISIBLE
    }

    private fun scheduleAnimation(result: String) {
        val runnable = Runnable {
            bannerRunnable = null
            tvResultBanner.visibility = View.GONE
            btnPlayAgain.visibility = View.VISIBLE
            if (result == RESULT_VICTORY) {
                showVictory()
            } else {
                showDefeat()
            }
        }
        bannerRunnable = runnable
        mainHandler.postDelayed(runnable, BANNER_DELAY_MS)
    }

    private fun showVictory() {
        tvEndMessage.setText(R.string.game_end_victory)
        tvEndMessage.visibility = View.VISIBLE
        fireworksView.visibility = View.VISIBLE
        tvSadFace.visibility = View.GONE
        fireworksView.startAnimation()
        playSound(R.raw.victory_fanfare)
    }

    private fun showDefeat() {
        tvEndMessage.setText(R.string.game_end_defeat)
        tvEndMessage.visibility = View.VISIBLE
        tvSadFace.visibility = View.VISIBLE
        fireworksView.visibility = View.GONE

        animateSadFace()
        playSound(R.raw.defeat_sad)
    }

    private fun animateSadFace() {
        val scaleDown = ObjectAnimator.ofFloat(tvSadFace, "scaleX", 1.15f, 0.85f, 1.0f)
        val scaleDownY = ObjectAnimator.ofFloat(tvSadFace, "scaleY", 1.15f, 0.85f, 1.0f)
        val breathe = ObjectAnimator.ofFloat(tvSadFace, "translationY", 0f, -30f, 0f)
        val duration = 3000L

        scaleDown.duration = duration
        scaleDownY.duration = duration
        breathe.duration = duration
        scaleDown.interpolator = AccelerateDecelerateInterpolator()
        scaleDownY.interpolator = AccelerateDecelerateInterpolator()
        breathe.interpolator = AccelerateDecelerateInterpolator()

        sadAnimators.add(scaleDown)
        sadAnimators.add(scaleDownY)
        sadAnimators.add(breathe)
        scaleDown.start()
        scaleDownY.start()
        breathe.start()
    }

    private fun playSound(rawRes: Int) {
        try {
            mediaPlayer = MediaPlayer.create(this, rawRes)
            if (mediaPlayer == null) {
                Log.w("ShahMatI", "MediaPlayer.create returned null for $rawRes")
                return
            }
            mediaPlayer!!.setOnCompletionListener { mp ->
                mp.release()
                if (mediaPlayer === mp) mediaPlayer = null
            }
            mediaPlayer!!.start()
        } catch (e: Exception) {
            Log.e("ShahMatI", "playSound error", e)
        }
    }

    private fun navigateTo(activityClass: Class<*>) {
        try {
            stopAllAnimations()
            releaseSound()

            val intent = Intent(this, activityClass)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        } catch (e: Exception) {
            Log.e("ShahMatI", "navigateTo error", e)
            finish()
        }
    }

    private fun stopAllAnimations() {
        try {
            fireworksView.stopAnimation()
        } catch (e: Exception) {
            Log.e("ShahMatI", "stop fireworks error", e)
        }
        for (a in sadAnimators) a.cancel()
        sadAnimators.clear()
    }

    private fun releaseSound() {
        try {
            mediaPlayer?.let { mp ->
                mp.setOnCompletionListener(null)
                mp.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("ShahMatI", "releaseSound error", e)
        }
    }

    override fun onDestroy() {
        bannerRunnable?.let { mainHandler.removeCallbacks(it) }
        bannerRunnable = null
        stopAllAnimations()
        releaseSound()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // ignore back so user watches the animation roulette
    }
}

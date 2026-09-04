package com.shahmat.game.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.shahmat.game.R
import com.shahmat.game.ui.select.ColorSelectionActivity

class SplashActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var authorText: TextView
    private val splashHandler = Handler(Looper.getMainLooper())
    private var transitionPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ShahMatI", "SplashActivity onCreate")
        try {
            setContentView(R.layout.activity_splash)
            titleText = findViewById(R.id.tvSplashTitle)
            authorText = findViewById(R.id.tvSplashAuthor)

            transitionPending = true
            splashHandler.postDelayed({
                transitionPending = false
                transitionToColorSelection()
            }, 2000L)
        } catch (e: Exception) {
            Log.e("ShahMatI", "SplashActivity error", e)
            transitionToColorSelection()
        }
    }

    override fun onDestroy() {
        if (transitionPending) {
            splashHandler.removeCallbacksAndMessages(null)
            transitionPending = false
        }
        super.onDestroy()
    }

    private fun transitionToColorSelection() {
        try {
            startActivity(Intent(this, ColorSelectionActivity::class.java))
            finish()
        } catch (e: Exception) {
            Log.e("ShahMatI", "Transition error", e)
        }
    }
}

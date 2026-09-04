package com.shahmat.game.ui.select

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shahmat.game.R
import com.shahmat.game.engine.PieceColor
import com.shahmat.game.ui.learn.LearnActivity

class ColorSelectionActivity : AppCompatActivity() {

    private lateinit var btnWhite: View
    private lateinit var btnBlack: View
    private lateinit var btnRules: android.widget.Button
    private lateinit var tvVersion: android.widget.TextView
    private var selectedColor: PieceColor = PieceColor.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ShahMatI", "ColorSelectionActivity onCreate")
        try {
            setContentView(R.layout.activity_color_selection)

            btnWhite = findViewById(R.id.btnWhitePieces)
            btnBlack = findViewById(R.id.btnBlackPieces)
            btnRules = findViewById(R.id.btnColorRules)
            tvVersion = findViewById(R.id.tvColorVersion)

            tvVersion.text = getString(R.string.app_version, com.shahmat.game.BuildConfig.VERSION_NAME)

            btnWhite.setOnClickListener {
                selectedColor = PieceColor.WHITE
                navigateToDifficulty()
            }

            btnBlack.setOnClickListener {
                selectedColor = PieceColor.BLACK
                navigateToDifficulty()
            }

            btnRules.setOnClickListener {
                try {
                    startActivity(Intent(this, LearnActivity::class.java))
                } catch (e: Exception) {
                    Log.e("ShahMatI", "Rules click error", e)
                }
            }

            applyNavigationBarInsets()
        } catch (e: Exception) {
            Log.e("ShahMatI", "ColorSelectionActivity error", e)
        }
    }

    private fun navigateToDifficulty() {
        try {
            val intent = Intent(this, DifficultySelectionActivity::class.java)
            intent.putExtra("playerColor", selectedColor.name)
            startActivity(intent)
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
            finish()
        } catch (e: Exception) {
            Log.e("ShahMatI", "Navigate error", e)
        }
    }

    private fun applyNavigationBarInsets() {
        try {
            val root = findViewById<View>(R.id.colorSelectRoot)
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                tvVersion.translationY = -bottom.toFloat()
                insets
            }
        } catch (e: Exception) {
            Log.e("ShahMatI", "Color insets error", e)
        }
    }
}

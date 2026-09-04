package com.shahmat.game.ui.select

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shahmat.game.BuildConfig
import com.shahmat.game.R
import com.shahmat.game.engine.Difficulty
import com.shahmat.game.engine.PieceColor
import com.shahmat.game.ui.game.GameActivity

class DifficultySelectionActivity : AppCompatActivity() {

    private lateinit var btnLearn: View
    private lateinit var btnBeginner: View
    private lateinit var btnIntermediate: View
    private lateinit var btnMaster: View
    private lateinit var btnTwoPlayer: View
    private lateinit var btnBack: View
    private lateinit var tvVersion: TextView
    private lateinit var difficultyScroll: View
    private var playerColor: PieceColor = PieceColor.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ShahMatI", "DifficultySelectionActivity onCreate")
        try {
            setContentView(R.layout.activity_difficulty_selection)

            btnLearn = findViewById(R.id.btnDifficultyLearn)
            btnBeginner = findViewById(R.id.btnDifficultyBeginner)
            btnIntermediate = findViewById(R.id.btnDifficultyIntermediate)
            btnMaster = findViewById(R.id.btnDifficultyMaster)
            btnTwoPlayer = findViewById(R.id.btnDifficultyTwoPlayer)
            btnBack = findViewById(R.id.btnDifficultyBack)
            tvVersion = findViewById(R.id.tvDifficultyVersion)
            difficultyScroll = findViewById(R.id.difficultyScroll)

            playerColor = try {
                PieceColor.valueOf(intent.getStringExtra("playerColor") ?: "WHITE")
            } catch (e: Exception) {
                PieceColor.WHITE
            }

            tvVersion.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)

            btnLearn.setOnClickListener { startGame(Difficulty.LEARN, false) }
            btnBeginner.setOnClickListener { startGame(Difficulty.BEGINNER, false) }
            btnIntermediate.setOnClickListener { startGame(Difficulty.INTERMEDIATE, false) }
            btnMaster.setOnClickListener { startGame(Difficulty.MASTER, false) }
            btnTwoPlayer.setOnClickListener { startGame(Difficulty.LEARN, true) }
            btnBack.setOnClickListener { finish() }

            applyNavigationBarInsets()
            Log.d("ShahMatI", "DifficultySelectionActivity ready")
        } catch (e: Exception) {
            Log.e("ShahMatI", "DifficultySelectionActivity error", e)
        }
    }

    private fun startGame(difficulty: Difficulty, twoPlayer: Boolean) {
        try {
            val intent = Intent(this, GameActivity::class.java)
            intent.putExtra("playerColor", playerColor.name)
            intent.putExtra("difficulty", difficulty.name)
            intent.putExtra("twoPlayer", twoPlayer)
            startActivity(intent)
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
            finish()
        } catch (e: Exception) {
            Log.e("ShahMatI", "Start game error", e)
        }
    }

    private fun applyNavigationBarInsets() {
        try {
            val root = findViewById<View>(R.id.difficultySelectRoot)
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom + dp(20))
                insets
            }
        } catch (e: Exception) {
            Log.e("ShahMatI", "Difficulty insets error", e)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

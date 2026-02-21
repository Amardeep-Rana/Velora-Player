package com.LegendAmardeep.veloraplayer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.LegendAmardeep.veloraplayer.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide system bars for a true full screen splash
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

        // 1. App Name Animation (After 500ms)
        Handler(Looper.getMainLooper()).postDelayed({
            binding.tvAppName.visibility = View.VISIBLE
            binding.tvAppName.alpha = 0f
            binding.tvAppName.translationY = 30f
            
            binding.tvAppName.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(800)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }, 500)

        // 2. "Made by" Animation (200ms after App Name starts, so 700ms total)
        Handler(Looper.getMainLooper()).postDelayed({
            binding.tvMadeBy.visibility = View.VISIBLE
            binding.tvMadeBy.alpha = 0f
            binding.tvMadeBy.translationY = 50f // Coming from bottom
            
            binding.tvMadeBy.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(1000)
                .setInterpolator(OvershootInterpolator(1.2f)) // Smooth bounce effect
                .start()
        }, 700)

        // Navigate to MainActivity after 3 seconds total
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 3000)
    }
}
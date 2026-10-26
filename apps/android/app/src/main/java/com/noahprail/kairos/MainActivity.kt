package com.noahprail.kairos

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.noahprail.kairos.ui.App
import com.noahprail.kairos.ui.KairosTheme

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) applyDemoIntent(intent)

        setContent {
            KairosTheme {
                App(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDemoIntent(intent)
    }

    /** Debug builds only: `--es demo <scene>` fills the app with fixtures (see DemoMode.kt). */
    private fun applyDemoIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val scene = intent.getStringExtra("demo") ?: return
        model.applyDemo(scene, intent.getStringExtra("screen") ?: "main")
    }
}

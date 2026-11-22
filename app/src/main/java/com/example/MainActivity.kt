package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.configureAppCheck
import com.example.briefing.BriefingWorker
import com.example.ui.AetherCockpitScreen
import com.example.ui.theme.Aether3DTheme
import com.example.ui.viewmodel.JarvisViewModel

class MainActivity : FragmentActivity() {

    private var pendingLaunchIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent before any UI or ViewModel creation
        configureAppCheck(this, intent)
        pendingLaunchIntent = intent

        enableEdgeToEdge()
        setContent {
            val jarvisViewModel: JarvisViewModel = viewModel()
            val uiState by jarvisViewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(pendingLaunchIntent) {
                val incoming = pendingLaunchIntent ?: return@LaunchedEffect
                val triggerBriefing = incoming.getBooleanExtra(BriefingWorker.EXTRA_TRIGGER_BRIEFING, false)
                val reminderTitle = incoming.getStringExtra(BriefingWorker.EXTRA_REMINDER_TITLE).orEmpty()
                if (triggerBriefing || reminderTitle.isNotBlank()) {
                    incoming.removeExtra(BriefingWorker.EXTRA_TRIGGER_BRIEFING)
                    incoming.removeExtra(BriefingWorker.EXTRA_REMINDER_TITLE)
                    pendingLaunchIntent = null
                    jarvisViewModel.runDailyBriefing(reminderTitle)
                }
            }

            Aether3DTheme(persona = uiState.preferences.persona) {
                AetherCockpitScreen(viewModel = jarvisViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
        pendingLaunchIntent = intent
    }
}

package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.components.nav.BackButton

/**
 * Assistant on-device automation scope editor (#187 v2). T8 lands the reachable route shell only — the
 * a11y-enable card, per-verb switches, allowed-packages editor, and TTL/maxSteps controls that persist
 * to `assistant.automationGrant` are added by the scope-editor task (T9). The route is wired now so the
 * navigation surface exists; this placeholder body carries no automation behavior.
 */
@Composable
fun AssistantAutomationPage(id: String) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automation") },
                navigationIcon = { BackButton() },
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("Automation scope")
        }
    }
}

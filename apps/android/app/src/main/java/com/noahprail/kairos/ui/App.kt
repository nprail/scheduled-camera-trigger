package com.noahprail.kairos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noahprail.kairos.AppModel
import com.noahprail.kairos.Link

/** The root: picks a screen from the connection state and overlays the error banner. */
@Composable
fun App(model: AppModel) {
    var route by model::route

    // leaving the device (disconnect, lost link) always returns to the main screen
    LaunchedEffect(model.link) {
        if (model.link != Link.Ready) route = "main"
    }
    BackHandler(enabled = route != "main") { route = "main" }
    // demo scenes have no device to disconnect from, so Back leaves them
    BackHandler(enabled = route == "main" && model.demo && model.link != Link.Disconnected) { model.disconnect() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = model.link to route,
                transitionSpec = {
                    val deeper = targetState.second != "main" && initialState.second == "main"
                    val shallower = targetState.second == "main" && initialState.second != "main"
                    when {
                        deeper -> slideInHorizontally { it / 4 } + fadeIn() togetherWith slideOutHorizontally { -it / 4 } + fadeOut()
                        shallower -> slideInHorizontally { -it / 4 } + fadeIn() togetherWith slideOutHorizontally { it / 4 } + fadeOut()
                        else -> fadeIn() togetherWith fadeOut()
                    }
                },
                contentKey = { (link, route) -> link::class to route },
                label = "screens",
            ) { (link, currentRoute) ->
                when (link) {
                    Link.Disconnected -> ScanScreen(model)
                    is Link.Connecting -> ConnectingScreen(link.name)
                    is Link.NeedsPairing -> PairingScreen(model, link.reason)
                    Link.Ready -> when (currentRoute) {
                        "config" -> ConfigEditorScreen(model, onBack = { route = "main" })
                        "logs" -> LogsScreen(model, onBack = { route = "main" })
                        else -> DeviceScreen(
                            model,
                            onEditConfig = { route = "config" },
                            onShowLogs = { route = "logs" },
                        )
                    }
                }
            }

            BannerView(model, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
fun BannerView(model: AppModel, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = model.banner != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null)
                Text(
                    model.banner ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                )
                IconButton(onClick = { model.banner = null }) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
        }
    }
}

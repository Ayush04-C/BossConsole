package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** A lifecycle status presented by the host without duplicating plugin state. */
internal enum class PluginHealthStatus {
    HEALTHY,
    NEEDS_ATTENTION,
    UNAVAILABLE,
}

/** Existing, deliberately narrow lifecycle operations that the center may invoke. */
internal enum class PluginHealthAction {
    ENABLE,
    RELOAD,
}

internal data class PluginHealthRow(
    val pluginId: String,
    val displayName: String,
    val status: PluginHealthStatus,
    val detail: String,
    val action: PluginHealthAction? = null,
)

internal class PluginHealthOperationState {
    var workingPluginId by mutableStateOf<String?>(null)
    var actionError by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberPluginHealthOperationState(): PluginHealthOperationState = remember { PluginHealthOperationState() }

/** Host-owned operational surface for plugin status and existing safe recovery actions. */
@Composable
internal fun PluginHealthCenterDialog(
    manager: DynamicPluginManager?,
    delegate: PluginLoaderDelegate?,
    onDismiss: () -> Unit,
) {
    val pluginStates by (manager?.pluginStates ?: return).collectAsState()
    val gates by PluginLoadGateRegistry.gates.collectAsState()
    val crashedPluginIds = PluginCrashRegistry.crashedPlugins.keys
    val inaccessible = manager.getInaccessiblePlugins().mapTo(mutableSetOf()) { it.pluginId }
    val incompatible = pluginStates.keys.filterTo(mutableSetOf()) { PluginCrashRegistry.isIncompatible(it) }
    val rows = pluginHealthRows(pluginStates, gates, crashedPluginIds, inaccessible, incompatible)
    val scope = rememberCoroutineScope()
    val operation = rememberPluginHealthOperationState()

    BossDialog(
        onDismissRequest = { if (operation.workingPluginId == null) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = operation.workingPluginId == null,
                dismissOnClickOutside = operation.workingPluginId == null,
                usePlatformDefaultWidth = false,
            ),
    ) {
        PluginHealthCenterCard(
            rows = rows,
            actionError = operation.actionError,
            workingPluginId = operation.workingPluginId,
            onDismiss = onDismiss,
            onAction = { row, action ->
                if (operation.workingPluginId == null) {
                    operation.workingPluginId = row.pluginId
                    operation.actionError = null
                    launchHealthAction(
                        scope = scope,
                        manager = manager,
                        delegate = delegate,
                        row = row,
                        action = action,
                        onFinished = { error ->
                            operation.actionError = error
                            operation.workingPluginId = null
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun PluginHealthCenterCard(
    rows: List<PluginHealthRow>,
    actionError: String?,
    workingPluginId: String?,
    onDismiss: () -> Unit,
    onAction: (PluginHealthRow, PluginHealthAction) -> Unit,
) {
    Card(
        modifier = Modifier.width(560.dp),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = BossTheme.colors.panel,
        elevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            PluginHealthHeader(actionError)
            Spacer(Modifier.height(14.dp))
            PluginHealthRows(rows, workingPluginId, onAction)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = workingPluginId == null) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun PluginHealthHeader(actionError: String?) {
    Text(
        text = "Plugin Health & Recovery",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = BossTheme.colors.textPrimary,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Current plugin lifecycle state. Recovery actions use the existing host flows.",
        color = BossTheme.colors.textSecondary,
        fontSize = 13.sp,
    )
    if (actionError != null) {
        Spacer(Modifier.height(10.dp))
        Text(actionError, color = BossTheme.colors.alert, fontSize = 13.sp)
    }
}

@Composable
private fun PluginHealthRows(
    rows: List<PluginHealthRow>,
    workingPluginId: String?,
    onAction: (PluginHealthRow, PluginHealthAction) -> Unit,
) {
    if (rows.isEmpty()) {
        Text(
            text = "No plugins are currently registered in this window.",
            color = BossTheme.colors.textSecondary,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.height(320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.pluginId }) { row ->
            PluginHealthRowCard(
                row = row,
                working = workingPluginId == row.pluginId,
                onAction = { action -> onAction(row, action) },
            )
        }
    }
}

private fun launchHealthAction(
    scope: kotlinx.coroutines.CoroutineScope,
    manager: DynamicPluginManager,
    delegate: PluginLoaderDelegate?,
    row: PluginHealthRow,
    action: PluginHealthAction,
    onFinished: (String?) -> Unit,
) {
    scope.launch {
        val result =
            runCatching {
                if (currentHealthAction(manager, row.pluginId) == action) {
                    // The delegate persists Enable and coordinates reload teardown and refresh.
                    val succeeded = when (action) {
                        PluginHealthAction.ENABLE -> delegate?.enablePlugin(row.pluginId) == true
                        PluginHealthAction.RELOAD -> delegate?.reloadPlugin(row.pluginId) != null
                    }
                    if (succeeded) Result.success(Unit) else Result.failure(IllegalStateException("Recovery failed"))
                } else {
                    Result.failure(IllegalStateException("Plugin health changed"))
                }
            }.getOrElse { Result.failure(it) }
        val verb = if (action == PluginHealthAction.ENABLE) "enable" else "reload"
        onFinished(
            if (result.isFailure) "Could not $verb this plugin. Check BOSS logs for details." else null,
        )
    }
}

/** Re-check action eligibility immediately before a user action can mutate plugin lifecycle. */
private fun currentHealthAction(
    manager: DynamicPluginManager,
    pluginId: String,
): PluginHealthAction? {
    val states = manager.pluginStates.value
    return pluginHealthRows(
        pluginStates = states,
        loadGates = PluginLoadGateRegistry.gates.value,
        crashedPluginIds = states.keys.filterTo(mutableSetOf()) { PluginCrashRegistry.hasCrashed(it) },
        inaccessiblePluginIds = manager.getInaccessiblePlugins().mapTo(mutableSetOf()) { it.pluginId },
        incompatiblePluginIds = states.keys.filterTo(mutableSetOf()) { PluginCrashRegistry.isIncompatible(it) },
    ).firstOrNull { it.pluginId == pluginId }?.action
}

@Composable
private fun PluginHealthRowCard(
    row: PluginHealthRow,
    working: Boolean,
    onAction: (PluginHealthAction) -> Unit,
) {
    Card(backgroundColor = BossTheme.colors.raised, elevation = 0.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.displayName,
                    fontWeight = FontWeight.Medium,
                    color = BossTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.pluginId,
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${row.status.label()} · ${row.detail}",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.action?.let { action ->
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onAction(action) },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossTheme.colors.signal),
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (action == PluginHealthAction.ENABLE) "Enable" else "Reload")
                    }
                }
            }
        }
    }
}

private fun PluginHealthStatus.label(): String =
    when (this) {
        PluginHealthStatus.HEALTHY -> "Healthy"
        PluginHealthStatus.NEEDS_ATTENTION -> "Needs attention"
        PluginHealthStatus.UNAVAILABLE -> "Unavailable"
    }

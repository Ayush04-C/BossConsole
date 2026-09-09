package ai.rever.boss.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The completed execution outcomes the local MCP activity view can represent. */
internal enum class McpActivityOutcome {
    SUCCESS,
    ERROR,
    TIMEOUT,
    CANCELLED,
}

/** Privacy-minimized metadata for one completed host-registry MCP invocation. */
internal data class McpActivityEvent(
    val sequence: Long,
    val completedAtEpochMs: Long,
    val durationMs: Long,
    val toolName: String,
    val providerId: String,
    val outcome: McpActivityOutcome,
)

/**
 * Small process-local history owned by [McpToolRegistryCore]. Snapshots are oldest-first and
 * immutable; [clear] deliberately leaves the process-lifetime sequence counter intact.
 */
internal class McpActivityStore(
    private val capacity: Int = CAPACITY,
) {
    private val lock = Any()
    private val entries = ArrayDeque<McpActivityEvent>()
    private var nextSequence = 0L
    private val _events = MutableStateFlow<List<McpActivityEvent>>(emptyList())

    val events: StateFlow<List<McpActivityEvent>> = _events.asStateFlow()

    fun append(
        completedAtEpochMs: Long,
        durationMs: Long,
        toolName: String,
        providerId: String,
        outcome: McpActivityOutcome,
    ): McpActivityEvent =
        synchronized(lock) {
            val event =
                McpActivityEvent(
                    sequence = ++nextSequence,
                    completedAtEpochMs = completedAtEpochMs,
                    durationMs = durationMs.coerceAtLeast(0),
                    toolName = toolName,
                    providerId = providerId,
                    outcome = outcome,
                )
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(event)
            _events.value = entries.toList()
            event
        }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _events.value = emptyList()
        }
    }

    internal companion object {
        const val CAPACITY = 100
    }
}

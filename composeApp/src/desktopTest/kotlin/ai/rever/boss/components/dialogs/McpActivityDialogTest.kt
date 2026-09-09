package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpActivityEvent
import ai.rever.boss.mcp.McpActivityOutcome
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class McpActivityDialogTest {
    private val events =
        listOf(
            event(1, "alpha", "provider.one", McpActivityOutcome.SUCCESS),
            event(2, "bravo", "provider.two", McpActivityOutcome.ERROR),
            event(3, "charlie", "provider.three", McpActivityOutcome.TIMEOUT),
            event(4, "delta", "provider.four", McpActivityOutcome.CANCELLED),
        )

    @Test
    fun `filters select approved outcomes newest first`() {
        assertEquals(listOf(4L, 3L, 2L, 1L), filterMcpActivity(events, McpActivityFilter.ALL, "").map { it.sequence })
        assertEquals(listOf(2L), filterMcpActivity(events, McpActivityFilter.ERRORS, "").map { it.sequence })
        assertEquals(listOf(3L), filterMcpActivity(events, McpActivityFilter.TIMEOUTS, "").map { it.sequence })
        assertEquals(listOf(4L), filterMcpActivity(events, McpActivityFilter.CANCELLED, "").map { it.sequence })
    }

    @Test
    fun `search uses raw tool and provider identifiers case insensitively`() {
        assertEquals(listOf(2L), filterMcpActivity(events, McpActivityFilter.ALL, "BRAVO").map { it.sequence })
        assertEquals(listOf(3L), filterMcpActivity(events, McpActivityFilter.ALL, "THREE").map { it.sequence })
    }

    @Test
    fun `formatters and display normalization are stable`() {
        assertEquals("999 ms", formatMcpActivityDuration(999))
        assertEquals("1.5 s", formatMcpActivityDuration(1_500))
        assertEquals("00:00:00.000", formatMcpActivityCompletion(0, ZoneId.of("UTC")))
        assertEquals("—", displayMcpActivityIdentifier("\u0000\n"))
        assertEquals("ab", displayMcpActivityIdentifier("a\u0000b"))
        assertEquals(160, displayMcpActivityIdentifier("x".repeat(200)).length)
    }

    private fun event(
        sequence: Long,
        tool: String,
        provider: String,
        outcome: McpActivityOutcome,
    ) = McpActivityEvent(
        sequence = sequence,
        completedAtEpochMs = 0L,
        durationMs = 1L,
        toolName = tool,
        providerId = provider,
        outcome = outcome,
    )
}

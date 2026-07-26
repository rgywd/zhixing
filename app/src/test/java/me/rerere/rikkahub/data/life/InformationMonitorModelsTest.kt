package me.rerere.rikkahub.data.life

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class InformationMonitorModelsTest {
    @Test
    fun `valid envelopes preserve the frozen schema`() {
        val status = InformationMonitorStatusEnvelope(
            schema = INFORMATION_MONITOR_SCHEMA,
            sources = listOf(source()),
        )
        val items = InformationMonitorItemsEnvelope(
            schema = INFORMATION_MONITOR_SCHEMA,
            items = listOf(item()),
        )
        val digest = InformationMonitorDigestEnvelope(
            schema = INFORMATION_MONITOR_SCHEMA,
            total = 1,
            highPriority = 1,
            channels = listOf(
                InformationMonitorDigestChannel(
                    channel = InformationMonitorChannel.EMAIL,
                    count = 1,
                    topItems = listOf(item()),
                ),
            ),
        )

        assertSame(status, status.requireValid())
        assertSame(items, items.requireValid())
        assertSame(digest, digest.requireValid())
    }

    @Test
    fun `unknown schema is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            InformationMonitorItemsEnvelope(
                schema = "information-monitor/v2",
                items = emptyList(),
            ).requireValid()
        }
    }

    @Test
    fun `timestamps must be UTC RFC3339`() {
        assertThrows(IllegalArgumentException::class.java) {
            InformationMonitorItemsEnvelope(
                schema = INFORMATION_MONITOR_SCHEMA,
                items = listOf(item().copy(occurredAt = "2026-07-26T20:00:00+08:00")),
            ).requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            InformationMonitorStatusEnvelope(
                schema = INFORMATION_MONITOR_SCHEMA,
                sources = listOf(source().copy(lastSucceededAt = "2026-07-26 12:00:00")),
            ).requireValid()
        }
    }

    @Test
    fun `digest item channel must match its group`() {
        assertThrows(IllegalArgumentException::class.java) {
            InformationMonitorDigestEnvelope(
                schema = INFORMATION_MONITOR_SCHEMA,
                total = 1,
                highPriority = 0,
                channels = listOf(
                    InformationMonitorDigestChannel(
                        channel = InformationMonitorChannel.FEISHU,
                        count = 1,
                        topItems = listOf(item()),
                    ),
                ),
            ).requireValid()
        }
    }

    @Test
    fun `digest counts must be internally consistent`() {
        assertThrows(IllegalArgumentException::class.java) {
            InformationMonitorDigestEnvelope(
                schema = INFORMATION_MONITOR_SCHEMA,
                total = 2,
                highPriority = 1,
                channels = listOf(
                    InformationMonitorDigestChannel(
                        channel = InformationMonitorChannel.EMAIL,
                        count = 1,
                        topItems = listOf(item()),
                    ),
                ),
            ).requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            InformationMonitorDigestEnvelope(
                schema = INFORMATION_MONITOR_SCHEMA,
                total = 0,
                highPriority = 0,
                channels = listOf(
                    InformationMonitorDigestChannel(
                        channel = InformationMonitorChannel.EMAIL,
                        count = 0,
                        topItems = listOf(item()),
                    ),
                ),
            ).requireValid()
        }
    }

    @Test
    fun `status never accepts negative item counts`() {
        assertThrows(IllegalArgumentException::class.java) {
            InformationMonitorStatusEnvelope(
                schema = INFORMATION_MONITOR_SCHEMA,
                sources = listOf(source().copy(itemCount24h = -1)),
            ).requireValid()
        }
    }

    private fun source() = InformationMonitorSourceStatus(
        sourceLabel = "工作邮箱",
        kind = InformationMonitorChannel.EMAIL,
        state = InformationMonitorSourceState.OK,
        lastSucceededAt = "2026-07-26T12:00:00Z",
        itemCount24h = 1,
    )

    private fun item() = InformationMonitorItem(
        id = "event-1",
        sourceLabel = "工作邮箱",
        channel = InformationMonitorChannel.EMAIL,
        occurredAt = "2026-07-26T12:00:00Z",
        sender = "example@example.com",
        title = "项目更新",
        summary = "项目已进入验收阶段。",
        actionItems = listOf("查看验收结果"),
        importance = InformationMonitorImportance.HIGH,
    )
}

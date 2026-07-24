package me.rerere.rikkahub.data.agenda

import java.util.concurrent.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCalendarRepositoryTest {
    @Test
    fun `permission race degrades calendar projection to empty`() {
        val events = readDeviceCalendarEventsOrEmpty {
            throw SecurityException("calendar permission was revoked")
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun `provider failure degrades calendar projection to empty`() {
        val events = readDeviceCalendarEventsOrEmpty {
            throw IllegalStateException("calendar provider unavailable")
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun `coroutine cancellation is not converted into an empty projection`() {
        assertThrows(CancellationException::class.java) {
            readDeviceCalendarEventsOrEmpty {
                throw CancellationException("cancelled")
            }
        }
    }
}

package me.rerere.rikkahub.data.agenda

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgendaReminderReconcilePolicyTest {
    @Test
    fun `task reminders still reconcile when plan reconciliation fails`() = runBlocking {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<AgendaReminderSource>()

        reconcileAgendaReminderSources(
            reconcilePlans = {
                calls += "plans"
                error("plan database unavailable")
            },
            reconcileTasks = {
                calls += "tasks"
            },
            onFailure = { source, _ -> failures += source },
        )

        assertEquals(listOf("plans", "tasks"), calls)
        assertEquals(listOf(AgendaReminderSource.PLANS), failures)
    }

    @Test
    fun `plan and task failures are reported independently`() = runBlocking {
        val failures = mutableListOf<AgendaReminderSource>()

        reconcileAgendaReminderSources(
            reconcilePlans = { error("plans") },
            reconcileTasks = { error("tasks") },
            onFailure = { source, _ -> failures += source },
        )

        assertEquals(
            listOf(AgendaReminderSource.PLANS, AgendaReminderSource.TASKS),
            failures,
        )
    }

    @Test
    fun `cancellation stops reconciliation without being reported as a source failure`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<AgendaReminderSource>()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                reconcileAgendaReminderSources(
                    reconcilePlans = {
                        calls += "plans"
                        throw CancellationException("receiver cancelled")
                    },
                    reconcileTasks = { calls += "tasks" },
                    onFailure = { source, _ -> failures += source },
                )
            }
        }

        assertEquals(listOf("plans"), calls)
        assertEquals(emptyList<AgendaReminderSource>(), failures)
    }
}

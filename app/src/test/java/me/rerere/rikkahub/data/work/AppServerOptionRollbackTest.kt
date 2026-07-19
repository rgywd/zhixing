package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AppServerOptionRollbackTest {
    private val accepted = WorkRepositoryPreferences(
        model = "gpt-5.6-luna",
        effortByModel = mapOf("gpt-5.6-luna" to "high"),
        permission = "default",
        fastMode = false,
    )
    private val current = WorkRepositoryPreferences(
        model = "gpt-5.6-terra",
        effortByModel = mapOf("gpt-5.6-terra" to "xhigh"),
        permission = "full-access",
        fastMode = true,
    )

    @Test
    fun `model rejection rolls back model without changing unrelated toggles`() {
        val restored = AppServerOptionRollback.rollback(
            current,
            accepted,
            AppServerRpcException(-32000, "unknown model gpt-5.6-terra"),
        )

        assertEquals(accepted.model, restored.model)
        assertEquals(current.permission, restored.permission)
        assertEquals(current.fastMode, restored.fastMode)
    }

    @Test
    fun `invalid params rolls back all optimistic options`() {
        val restored = AppServerOptionRollback.rollback(
            current,
            accepted,
            AppServerRpcException(-32602, "Invalid params", JsonPrimitive("sandboxPolicy")),
        )

        assertEquals(accepted, restored)
    }

    @Test
    fun `transport errors preserve optimistic options`() {
        assertEquals(
            current,
            AppServerOptionRollback.rollback(current, accepted, AppServerTransportException("offline")),
        )
    }
}

package me.rerere.rikkahub.data.work

import me.rerere.rikkahub.data.workflow.codex.CodexModelOption
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledCodexCatalogTest {
    @Test
    fun `bundled models remain usable without a server catalog`() {
        val models = BundledCodexCatalog.merge(null)

        assertEquals("gpt-5.6-sol", models.first().id)
        assertTrue(models.first().isDefault)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max", "ultra"), models.first().efforts())
        assertTrue(models.first().serviceTiers.any { it.id == "priority" })
        assertFalse(models.last().inputModalities.contains("image"))
    }

    @Test
    fun `fresh server values override bundled values and append new models`() {
        val serverSol = BundledCodexCatalog.models.first().copy(displayName = "Server Sol")
        val newModel = serverSol.copy(
            id = "future-model",
            model = "future-model",
            displayName = "Future Model",
            isDefault = false,
        )

        val merged = BundledCodexCatalog.merge(listOf(serverSol, newModel))

        assertEquals("Server Sol", merged.first { it.id == "gpt-5.6-sol" }.displayName)
        assertEquals("Future Model", merged.last().displayName)
        assertTrue(merged.any { it.id == "gpt-5.6-luna" })
    }

    @Test
    fun `backoff caps and supports deterministic jitter`() {
        val policy = AppServerBackoffPolicy()

        assertEquals(800L, policy.delayMs(0, jitterUnit = 0.0))
        assertEquals(1_000L, policy.delayMs(0, jitterUnit = 0.5))
        assertEquals(36_000L, policy.delayMs(99, jitterUnit = 1.0))
    }

    @Test
    fun `compatibility gate accepts reviewed initialize for text only without supervisor`() {
        val unknown = AppServerCompatibilityGate.evaluate(AppServerRuntimeFacts(null, null))
        val textOnly = AppServerCompatibilityGate.evaluate(
            AppServerRuntimeFacts(
                codexVersion = AppServerCompatibilityGate.REVIEWED_CODEX_VERSION,
                schemaHash = AppServerCompatibilityGate.REVIEWED_SCHEMA_HASH,
                methods = AppServerCompatibilityGate.requiredWriteMethods,
            )
        )
        val full = AppServerCompatibilityGate.evaluate(
            AppServerRuntimeFacts(
                codexVersion = AppServerCompatibilityGate.REVIEWED_CODEX_VERSION,
                schemaHash = AppServerCompatibilityGate.REVIEWED_SCHEMA_HASH,
                methods = AppServerCompatibilityGate.requiredWriteMethods,
                attachmentSupervisorReady = true,
            )
        )

        assertEquals(AppServerCompatibilityLevel.READ_ONLY, unknown.level)
        assertEquals(AppServerCompatibilityLevel.TEXT_ONLY, textOnly.level)
        assertEquals(AppServerCompatibilityLevel.FULL, full.level)
        val direct = AppServerCompatibilityGate.evaluateInitialize(buildJsonObject {
            put(
                "userAgent",
                "Codex Desktop/0.144.0 (Windows 10.0.26200; x86_64) unknown (zhixing_android; 0.2.2)",
            )
            put("codexHome", "C:/Users/test/.codex")
            put("platformFamily", "windows")
            put("platformOs", "windows")
        })
        val unreviewed = AppServerCompatibilityGate.evaluateInitialize(buildJsonObject {
            put(
                "userAgent",
                "Codex Desktop/0.145.0 (Windows 10.0.26200; x86_64) unknown (zhixing_android; 0.2.2)",
            )
            put("codexHome", "C:/Users/test/.codex")
            put("platformFamily", "windows")
            put("platformOs", "windows")
        })
        val spoofed = AppServerCompatibilityGate.evaluateInitialize(buildJsonObject {
            put("userAgent", "prefix Codex Desktop/0.144.0 (Windows; x86_64) unknown (test; 0)")
            put("codexHome", "C:/Users/test/.codex")
            put("platformFamily", "windows")
            put("platformOs", "windows")
        })
        val missingField = AppServerCompatibilityGate.evaluateInitialize(buildJsonObject {
            put(
                "userAgent",
                "Codex Desktop/0.144.0 (Windows 10.0.26200; x86_64) unknown (zhixing_android; 0.2.2)",
            )
            put("codexHome", "C:/Users/test/.codex")
            put("platformFamily", "windows")
        })

        assertEquals(AppServerCompatibilityLevel.TEXT_ONLY, direct.level)
        assertEquals(AppServerCompatibilityLevel.INCOMPATIBLE, unreviewed.level)
        assertEquals(AppServerCompatibilityLevel.READ_ONLY, spoofed.level)
        assertEquals(AppServerCompatibilityLevel.READ_ONLY, missingField.level)
        assertEquals(
            AppServerCompatibilityLevel.INCOMPATIBLE,
            AppServerCompatibilityGate.evaluateWithSupervisor(unreviewed, AppServerRuntimeFacts(
                codexVersion = AppServerCompatibilityGate.REVIEWED_CODEX_VERSION,
                schemaHash = AppServerCompatibilityGate.REVIEWED_SCHEMA_HASH,
                methods = AppServerCompatibilityGate.requiredWriteMethods,
                attachmentSupervisorReady = true,
            )).level,
        )
    }

    @Test
    fun `compatibility gate blocks an unreviewed schema`() {
        val result = AppServerCompatibilityGate.evaluate(
            AppServerRuntimeFacts(
                codexVersion = AppServerCompatibilityGate.REVIEWED_CODEX_VERSION,
                schemaHash = "different",
                methods = AppServerCompatibilityGate.requiredWriteMethods,
            )
        )

        assertEquals(AppServerCompatibilityLevel.INCOMPATIBLE, result.level)
    }

    @Test
    fun `supervisor status is mapped into compatibility facts`() {
        val facts = SupervisorRuntimeFactsMapper.map(buildJsonObject {
            put("appServer", buildJsonObject {
                put("running", true)
                put("codexVersion", AppServerCompatibilityGate.REVIEWED_CODEX_VERSION)
                put("schemaHash", AppServerCompatibilityGate.REVIEWED_SCHEMA_HASH)
                put("methods", buildJsonArray {
                    AppServerCompatibilityGate.requiredWriteMethods.forEach { add(JsonPrimitive(it)) }
                })
            })
        })

        assertEquals(AppServerCompatibilityGate.REVIEWED_CODEX_VERSION, facts.codexVersion)
        assertEquals(AppServerCompatibilityGate.requiredWriteMethods, facts.methods)
        assertTrue(facts.attachmentSupervisorReady)
        assertEquals(AppServerCompatibilityLevel.FULL, AppServerCompatibilityGate.evaluate(facts).level)
    }

    @Test
    fun `model reroute notification updates runtime selection`() {
        val current = me.rerere.rikkahub.data.workflow.codex.CodexRuntimeSettingsState(model = "old")
        val updated = AppServerRuntimeMapper.applyNotification(
            current,
            AppServerNotification(
                "model/rerouted",
                buildJsonObject {
                    put("threadId", "thread-1")
                    put("turnId", "turn-1")
                    put("fromModel", "old")
                    put("toModel", "new")
                },
            ),
        )

        assertEquals("new", updated.model)
    }
}

private fun CodexModelOption.efforts(): List<String> = supportedReasoningEfforts.map { it.reasoningEffort }

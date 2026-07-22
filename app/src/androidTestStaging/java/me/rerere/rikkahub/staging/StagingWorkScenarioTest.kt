package me.rerere.rikkahub.staging

import android.os.BaseBundle
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppIdentity
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.work.PhoneWorkCredentialStore
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import me.rerere.rikkahub.data.work.PhoneWorkSessionCreator
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

@RunWith(AndroidJUnit4::class)
class StagingWorkBootstrapTest {
    @Test
    fun importWorkCredentialsFromEphemeralFile() {
        requireStagingBuild()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bootstrapFile = File(context.noBackupFilesDir, BOOTSTRAP_FILE)
        val result = runCatching {
            val input = Json.decodeFromString<WorkBootstrapInput>(bootstrapFile.readText())
            GlobalContext.get().get<PhoneWorkCredentialStore>().save(input.baseUrl, input.token)
            StagingScenarioResult(
                ok = true,
                scenario = "work-bootstrap",
                applicationId = context.packageName,
                versionName = installedVersionName(),
                channel = AppIdentity.distributionChannel,
            )
        }.getOrElse { error ->
            StagingScenarioResult.failed("work-bootstrap", error)
        }
        bootstrapFile.delete()
        publish(result)
        if (!result.ok) fail(result.errorMessage)
    }
}

@RunWith(AndroidJUnit4::class)
class StagingWorkTitleScenarioTest {
    @Test
    fun createSessionAndReadBackTitle() = runBlocking {
        requireStagingBuild()
        val args = InstrumentationRegistry.getArguments()
        val result = runCatching {
            val message = decodeArgument(args, "messageBase64")
            require(message.isNotBlank()) { "message must not be blank" }
            val koin = GlobalContext.get()
            val repository = koin.get<PhoneWorkRepository>()
            check(repository.credentials.connection.value.configured) {
                "Staging Work is not configured; call /v1/config/work first"
            }
            val repo = repository.refreshCatalog().repos.firstOrNull { it.available }
                ?: error("No available Work repository")
            val model = repo.models.firstOrNull() ?: "gpt-5.6-sol"
            val effort = repo.reasoningEfforts.firstOrNull { it == "high" }
                ?: repo.reasoningEfforts.firstOrNull()
                ?: "high"
            val created = koin.get<PhoneWorkSessionCreator>().create(
                repo = repo,
                model = model,
                reasoningEffort = effort,
                message = message,
            )
            repository.refreshSessions()
            val stored = repository.sessionSnapshot(created.id)
                ?: error("Created Work session was not cached")
            check(stored.title.isNotBlank()) { "Work title is blank" }
            check(stored.title != stored.repoName) {
                "Work title fell back to repository name: ${stored.repoName}"
            }
            StagingScenarioResult(
                ok = true,
                scenario = "work-title",
                applicationId = InstrumentationRegistry.getInstrumentation().targetContext.packageName,
                versionName = installedVersionName(),
                channel = AppIdentity.distributionChannel,
                sessionId = stored.id,
                title = stored.title,
                repoName = stored.repoName,
            )
        }.getOrElse { error ->
            StagingScenarioResult.failed("work-title", error)
        }
        publish(result)
        if (!result.ok) fail(result.errorMessage)
    }
}

@Serializable
private data class WorkBootstrapInput(
    val baseUrl: String,
    val token: String,
)

@Serializable
private data class StagingScenarioResult(
    val ok: Boolean,
    val scenario: String,
    val applicationId: String,
    val versionName: String,
    val channel: String,
    val sessionId: String? = null,
    val title: String? = null,
    val repoName: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun failed(scenario: String, error: Throwable) = StagingScenarioResult(
            ok = false,
            scenario = scenario,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = installedVersionName(),
            channel = AppIdentity.distributionChannel,
            errorCode = error::class.java.simpleName,
            errorMessage = if (scenario == "work-bootstrap") {
                "Work credential bootstrap failed"
            } else {
                error.message?.take(500) ?: "Scenario failed"
            },
        )
    }
}

private fun requireStagingBuild() {
    check(AppIdentity.stagingTestDriverEnabled) { "Staging test driver is disabled" }
    check(BuildConfig.APPLICATION_ID == STAGING_PACKAGE) { "Unexpected package: ${BuildConfig.APPLICATION_ID}" }
}

private fun installedVersionName(): String {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

private fun decodeArgument(args: BaseBundle, key: String): String {
    val encoded = args.getString(key).orEmpty()
    require(encoded.isNotBlank()) { "$key is required" }
    return Base64.decode(encoded, Base64.NO_WRAP).toString(Charsets.UTF_8)
}

private fun publish(result: StagingScenarioResult) {
    val json = Json.encodeToString(result)
    val encoded = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
    InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        Bundle().apply { putString(RESULT_KEY, encoded) },
    )
}

private const val STAGING_PACKAGE = "dev.sundby.zhixing.staging"
private const val RESULT_KEY = "zhixingResult"
private const val BOOTSTRAP_FILE = "staging-work-bootstrap.json"

package me.rerere.rikkahub.data.work

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** Signature permission plus exact peer identity: a same-signed unrelated app cannot export secrets. */
class WorkConfigExportProvider : ContentProvider(), KoinComponent {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = requireNotNull(context)
        val expectedPeer = app.packageName.replace("dev.sundby.zhixing", "dev.sundby.zhixing.work")
        val callers = app.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (expectedPeer !in callers || app.packageManager.checkSignatures(app.packageName, expectedPeer) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Only the paired Work application can import settings")
        }
        require(method == "import-config-v1") { "Unsupported configuration transfer" }
        return runBlocking(Dispatchers.IO) {
            // Providers are published before Application.onCreate. Posting to main also waits for its DI initialization.
            val (store, credentials, repos) = withContext(Dispatchers.Main) {
                Triple(get<SettingsStore>(), get<PhoneWorkCredentialStore>(), get<PhoneWorkRepoPreferenceStore>())
            }
            val settings = store.settingsFlowRaw.first()
            val preferences = app.getSharedPreferences("rikkahub.preferences", android.content.Context.MODE_PRIVATE)
            val payload = WorkConfigTransfer.from(settings, credentials.connection.value.baseUrl, credentials.token().orEmpty()).copy(
                colorMode = preferences.getString("colorMode", "SYSTEM") ?: "SYSTEM",
                amoledDark = preferences.getBoolean("amoledDark", false),
                repoPreferences = repos.state.value,
            )
            Bundle().apply { putString("config", Json.encodeToString(payload)) }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

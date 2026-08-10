package me.rerere.rikkahub.data.db

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_32_33
import me.rerere.rikkahub.data.db.migrations.Migration_33_34
import me.rerere.rikkahub.data.db.migrations.Migration_36_37
import me.rerere.rikkahub.data.db.migrations.Migration_37_38
import me.rerere.rikkahub.data.db.migrations.Migration_38_39
import me.rerere.rikkahub.data.db.migrations.Migration_39_40
import me.rerere.rikkahub.data.db.migrations.Migration_40_41
import me.rerere.rikkahub.data.db.migrations.Migration_41_42
import me.rerere.rikkahub.data.db.migrations.Migration_42_43

internal fun createAppDatabase(
    context: Context,
    databaseName: String = APP_DATABASE_NAME,
): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .addMigrations(
        Migration_6_7,
        Migration_11_12,
        Migration_13_14,
        Migration_14_15,
        Migration_15_16,
        Migration_32_33,
        Migration_33_34,
        Migration_36_37,
        Migration_37_38,
        Migration_38_39,
        Migration_39_40,
        Migration_40_41,
        Migration_41_42,
        Migration_42_43,
    )
    .addCallback(object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            val dictDir = SimpleDictManager.extractDict(context)
            val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
            cursor.use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                    if (!success) {
                        Log.e(
                            "AppDatabaseFactory",
                            "jieba_dict failed: $result, path=${dictDir.absolutePath}",
                        )
                    }
                }
            }
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                    text,
                    node_id UNINDEXED,
                    message_id UNINDEXED,
                    conversation_id UNINDEXED,
                    title UNINDEXED,
                    update_at UNINDEXED,
                    tokenize = 'simple'
                )
                """.trimIndent()
            )
        }
    })
    .openHelperFactory(
        RequerySQLiteOpenHelperFactory(
            listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null,
                        )
                    )
                    options
                }
            )
        )
    )
    .build()

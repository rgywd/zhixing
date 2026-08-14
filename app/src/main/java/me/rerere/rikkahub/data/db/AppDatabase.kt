package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.AgendaPlanDAO
import me.rerere.rikkahub.data.db.dao.AgendaTaskDAO
import me.rerere.rikkahub.data.db.dao.AssistantTaskDAO
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryDocumentDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MonthlyLedgerDAO
import me.rerere.rikkahub.data.db.dao.PhoneWorkDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.AgendaPlanEntity
import me.rerere.rikkahub.data.db.entity.AgendaPlanStageEntity
import me.rerere.rikkahub.data.db.entity.AgendaTaskEntity
import me.rerere.rikkahub.data.db.entity.AssistantTaskEntity
import me.rerere.rikkahub.data.db.entity.AssistantTaskEventEntity
import me.rerere.rikkahub.data.db.entity.AssistantTaskLinkEntity
import me.rerere.rikkahub.data.db.entity.AssistantRuntimeContextEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryDocumentEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.MonthlyLedgerChannelEntity
import me.rerere.rikkahub.data.db.entity.MonthlyLedgerSummaryEntity
import me.rerere.rikkahub.data.db.entity.PhoneWorkEventEntity
import me.rerere.rikkahub.data.db.entity.PhoneWorkSessionEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_35_36
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.utils.JsonInstant

internal const val APP_DATABASE_NAME = "zhixing"
internal const val APP_DATABASE_VERSION = 48

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        MemoryDocumentEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        PhoneWorkSessionEntity::class,
        PhoneWorkEventEntity::class,
        AgendaTaskEntity::class,
        AgendaPlanEntity::class,
        AgendaPlanStageEntity::class,
        MonthlyLedgerSummaryEntity::class,
        MonthlyLedgerChannelEntity::class,
        AssistantTaskEntity::class,
        AssistantTaskEventEntity::class,
        AssistantTaskLinkEntity::class,
        AssistantRuntimeContextEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        AutoMigration(from = 34, to = 35),
        AutoMigration(from = 35, to = 36, spec = Migration_35_36::class),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun memoryDocumentDao(): MemoryDocumentDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun phoneWorkDao(): PhoneWorkDAO

    abstract fun agendaTaskDao(): AgendaTaskDAO

    abstract fun agendaPlanDao(): AgendaPlanDAO

    abstract fun monthlyLedgerDao(): MonthlyLedgerDAO

    abstract fun assistantTaskDao(): AssistantTaskDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}

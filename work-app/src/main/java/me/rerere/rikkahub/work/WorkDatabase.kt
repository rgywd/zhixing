package me.rerere.rikkahub.work

import androidx.room.Database
import androidx.room.RoomDatabase
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.PhoneWorkDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.PhoneWorkEventEntity
import me.rerere.rikkahub.data.db.entity.PhoneWorkSessionEntity

@Database(entities = [PhoneWorkSessionEntity::class, PhoneWorkEventEntity::class, ManagedFileEntity::class], version = 1)
abstract class WorkDatabase : RoomDatabase() {
    abstract fun sessions(): PhoneWorkDAO
    abstract fun files(): ManagedFileDAO
}

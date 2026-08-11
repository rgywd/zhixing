package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    primaryKeys = ["scope_id", "path"],
    indices = [Index(value = ["scope_id", "state", "updated_at"])],
)
data class MemoryDocumentEntity(
    @ColumnInfo("scope_id")
    val scopeId: String,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("description")
    val description: String,
    @ColumnInfo("aliases_json", defaultValue = "'[]'")
    val aliasesJson: String = "[]",
    @ColumnInfo("content", defaultValue = "''")
    val content: String = "",
    @ColumnInfo("sources_json", defaultValue = "'[]'")
    val sourcesJson: String = "[]",
    @ColumnInfo("version", defaultValue = "1")
    val version: Long = 1,
    @ColumnInfo("state", defaultValue = "'ACTIVE'")
    val state: String = "ACTIVE",
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0,
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0,
)

package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["project_id"])]
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "project_id")
    val projectId: Long,
    val idx: Int,
    val start: Double,
    val end: Double,
    val text: String = "",
    val translation: String? = null,
    val gender: String = "female", // "female" or "male"
    @ColumnInfo(name = "audio_path")
    val audioPath: String? = null,
    @ColumnInfo(name = "audio_duration")
    val audioDuration: Double? = null,
    @ColumnInfo(name = "custom_speed")
    val customSpeed: Float? = null
)

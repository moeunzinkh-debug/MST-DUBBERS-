package com.example.data.db

data class ProjectWithStats(
    val id: Long,
    val name: String,
    val videoPath: String,
    val videoMime: String,
    val duration: Double,
    val createdAt: Long,
    val segmentCount: Int,
    val translatedCount: Int,
    val voicedCount: Int
)

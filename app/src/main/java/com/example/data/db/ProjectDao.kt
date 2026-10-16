package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeProjectById(id: Long): Flow<ProjectEntity?>

    @Query("""
        SELECT 
            p.id, 
            p.name, 
            p.video_path AS videoPath, 
            p.video_mime AS videoMime, 
            p.duration, 
            p.created_at AS createdAt,
            COUNT(s.id) AS segmentCount,
            SUM(CASE WHEN s.translation IS NOT NULL AND s.translation != '' THEN 1 ELSE 0 END) AS translatedCount,
            SUM(CASE WHEN s.audio_path IS NOT NULL AND s.audio_path != '' THEN 1 ELSE 0 END) AS voicedCount
        FROM projects p
        LEFT JOIN segments s ON s.project_id = p.id
        GROUP BY p.id
        ORDER BY p.created_at DESC
    """)
    fun observeAllProjectsWithStats(): Flow<List<ProjectWithStats>>
}

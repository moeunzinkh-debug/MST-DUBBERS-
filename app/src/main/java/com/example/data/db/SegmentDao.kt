package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: SegmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Update
    suspend fun updateSegment(segment: SegmentEntity)

    @Delete
    suspend fun deleteSegment(segment: SegmentEntity)

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun deleteSegmentById(id: Long)

    @Query("DELETE FROM segments WHERE project_id = :projectId")
    suspend fun deleteSegmentsByProjectId(projectId: Long)

    @Query("SELECT * FROM segments WHERE project_id = :projectId ORDER BY idx ASC")
    fun observeSegmentsForProject(projectId: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE project_id = :projectId ORDER BY idx ASC")
    suspend fun getSegmentsForProject(projectId: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun getSegmentById(id: Long): SegmentEntity?
}

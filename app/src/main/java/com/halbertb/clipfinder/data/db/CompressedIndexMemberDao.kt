package com.halbertb.clipfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CompressedIndexMemberDao {
    @Query("SELECT * FROM compressed_index_members WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getById(mediaId: Long): CompressedIndexMemberEntity?

    @Query("SELECT COUNT(*) FROM compressed_index_members")
    suspend fun count(): Long

    @Query("SELECT mediaId FROM compressed_index_members")
    suspend fun getAllMediaIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompressedIndexMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CompressedIndexMemberEntity>)

    @Query("DELETE FROM compressed_index_members WHERE mediaId IN (:ids)")
    suspend fun deleteByMediaIds(ids: List<Long>)

    @Query("DELETE FROM compressed_index_members")
    suspend fun clear()
}

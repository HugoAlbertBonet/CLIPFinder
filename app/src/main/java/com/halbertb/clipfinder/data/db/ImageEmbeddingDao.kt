package com.halbertb.clipfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImageEmbeddingDao {
    @Query("SELECT * FROM image_embeddings WHERE mediaId = :id LIMIT 1")
    suspend fun getById(id: Long): ImageEmbeddingEntity?

    @Query("SELECT COUNT(*) FROM image_embeddings")
    suspend fun count(): Long

    @Query("SELECT * FROM image_embeddings")
    suspend fun getAll(): List<ImageEmbeddingEntity>

    @Query("SELECT mediaId FROM image_embeddings")
    suspend fun getAllMediaIds(): List<Long>

    @Query("DELETE FROM image_embeddings WHERE mediaId IN (:ids)")
    suspend fun deleteByMediaIds(ids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImageEmbeddingEntity)
}

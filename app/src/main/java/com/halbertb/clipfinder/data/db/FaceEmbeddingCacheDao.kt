package com.halbertb.clipfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FaceEmbeddingCacheDao {
    @Query(
        """
        SELECT * FROM face_embedding_cache
        WHERE mediaId = :mediaId
          AND dateModifiedSec = :dateModifiedSec
          AND cacheVersion = :cacheVersion
        LIMIT 1
        """,
    )
    suspend fun get(mediaId: Long, dateModifiedSec: Long, cacheVersion: Int): FaceEmbeddingCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FaceEmbeddingCacheEntity)

    @Query("DELETE FROM face_embedding_cache WHERE mediaId IN (:mediaIds)")
    suspend fun deleteByMediaIds(mediaIds: List<Long>)

    @Query("DELETE FROM face_embedding_cache")
    suspend fun deleteAll()
}

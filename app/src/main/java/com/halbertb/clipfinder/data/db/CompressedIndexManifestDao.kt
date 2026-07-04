package com.halbertb.clipfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CompressedIndexManifestDao {
    @Query("SELECT * FROM compressed_index_manifest WHERE id = 1 LIMIT 1")
    suspend fun get(): CompressedIndexManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompressedIndexManifestEntity)

    @Query("DELETE FROM compressed_index_manifest")
    suspend fun clear()
}

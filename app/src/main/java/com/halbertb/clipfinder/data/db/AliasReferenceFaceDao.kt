package com.halbertb.clipfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AliasReferenceFaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AliasReferenceFaceEntity>)

    @Query("SELECT * FROM alias_reference_faces WHERE aliasId = :aliasId")
    suspend fun getByAliasId(aliasId: Long): List<AliasReferenceFaceEntity>

    @Query("DELETE FROM alias_reference_faces WHERE aliasId = :aliasId")
    suspend fun deleteByAliasId(aliasId: Long)

    @Query("DELETE FROM alias_reference_faces")
    suspend fun deleteAll()
}

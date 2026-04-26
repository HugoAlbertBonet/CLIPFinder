package com.halbertb.clipfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AliasRefinementStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AliasRefinementStateEntity)

    @Query("SELECT * FROM alias_refinement_state WHERE aliasId = :aliasId LIMIT 1")
    suspend fun getByAliasId(aliasId: Long): AliasRefinementStateEntity?

    @Query("DELETE FROM alias_refinement_state WHERE aliasId = :aliasId")
    suspend fun deleteByAliasId(aliasId: Long)

    @Query("DELETE FROM alias_refinement_state")
    suspend fun deleteAll()
}

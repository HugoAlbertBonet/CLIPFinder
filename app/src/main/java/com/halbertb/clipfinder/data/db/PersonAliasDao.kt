package com.halbertb.clipfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PersonAliasDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PersonAliasEntity): Long

    @Update
    suspend fun update(entity: PersonAliasEntity)

    @Query("SELECT * FROM person_aliases ORDER BY alias COLLATE NOCASE ASC")
    suspend fun getAll(): List<PersonAliasEntity>

    @Query("SELECT * FROM person_aliases WHERE aliasId = :aliasId LIMIT 1")
    suspend fun getById(aliasId: Long): PersonAliasEntity?

    @Query("SELECT * FROM person_aliases WHERE normalizedAlias = :normalizedAlias LIMIT 1")
    suspend fun getByNormalizedAlias(normalizedAlias: String): PersonAliasEntity?

    @Query("DELETE FROM person_aliases WHERE aliasId = :aliasId")
    suspend fun deleteById(aliasId: Long)

    @Query("DELETE FROM person_aliases")
    suspend fun deleteAll()

    @Query("UPDATE person_aliases SET matchThreshold = :threshold, updatedAtEpochMs = :updatedAtEpochMs WHERE aliasId = :aliasId")
    suspend fun updateThreshold(aliasId: Long, threshold: Float, updatedAtEpochMs: Long)
}

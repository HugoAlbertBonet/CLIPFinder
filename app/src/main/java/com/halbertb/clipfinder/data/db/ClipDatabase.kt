package com.halbertb.clipfinder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ImageEmbeddingEntity::class], version = 1, exportSchema = false)
abstract class ClipDatabase : RoomDatabase() {
    abstract fun imageEmbeddingDao(): ImageEmbeddingDao

    companion object {
        fun build(context: Context): ClipDatabase =
            Room.databaseBuilder(context, ClipDatabase::class.java, "clipfinder.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

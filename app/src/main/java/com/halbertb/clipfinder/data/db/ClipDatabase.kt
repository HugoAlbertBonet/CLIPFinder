package com.halbertb.clipfinder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ImageEmbeddingEntity::class,
        PersonAliasEntity::class,
        AliasReferenceFaceEntity::class,
        AliasPhotoMembershipEntity::class,
        AliasRefinementStateEntity::class,
        FaceEmbeddingCacheEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class ClipDatabase : RoomDatabase() {
    abstract fun imageEmbeddingDao(): ImageEmbeddingDao
    abstract fun personAliasDao(): PersonAliasDao
    abstract fun aliasReferenceFaceDao(): AliasReferenceFaceDao
    abstract fun aliasPhotoMembershipDao(): AliasPhotoMembershipDao
    abstract fun aliasRefinementStateDao(): AliasRefinementStateDao
    abstract fun faceEmbeddingCacheDao(): FaceEmbeddingCacheDao

    companion object {
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `person_aliases` (
                            `aliasId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `alias` TEXT NOT NULL,
                            `normalizedAlias` TEXT NOT NULL,
                            `createdAtEpochMs` INTEGER NOT NULL,
                            `updatedAtEpochMs` INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_person_aliases_normalizedAlias` ON `person_aliases` (`normalizedAlias`)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alias_reference_faces` (
                            `referenceId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `aliasId` INTEGER NOT NULL,
                            `embedding` BLOB NOT NULL,
                            `sourceUri` TEXT,
                            `createdAtEpochMs` INTEGER NOT NULL,
                            FOREIGN KEY(`aliasId`) REFERENCES `person_aliases`(`aliasId`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alias_reference_faces_aliasId` ON `alias_reference_faces` (`aliasId`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alias_photo_memberships` (
                            `aliasId` INTEGER NOT NULL,
                            `mediaId` INTEGER NOT NULL,
                            `confidence` REAL NOT NULL,
                            `status` TEXT NOT NULL,
                            `provenance` TEXT NOT NULL,
                            `faceCount` INTEGER NOT NULL,
                            `updatedAtEpochMs` INTEGER NOT NULL,
                            PRIMARY KEY(`aliasId`, `mediaId`),
                            FOREIGN KEY(`aliasId`) REFERENCES `person_aliases`(`aliasId`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alias_photo_memberships_aliasId` ON `alias_photo_memberships` (`aliasId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alias_photo_memberships_mediaId` ON `alias_photo_memberships` (`mediaId`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alias_refinement_state` (
                            `aliasId` INTEGER NOT NULL,
                            `lastProcessedMediaId` INTEGER,
                            `processedCount` INTEGER NOT NULL,
                            `totalCount` INTEGER NOT NULL,
                            `running` INTEGER NOT NULL,
                            `updatedAtEpochMs` INTEGER NOT NULL,
                            PRIMARY KEY(`aliasId`)
                        )
                        """.trimIndent(),
                    )
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `face_embedding_cache` (
                            `mediaId` INTEGER NOT NULL,
                            `dateModifiedSec` INTEGER NOT NULL,
                            `cacheVersion` INTEGER NOT NULL,
                            `embeddingsBlob` BLOB NOT NULL,
                            `faceCount` INTEGER NOT NULL,
                            `indexedAtEpochMs` INTEGER NOT NULL,
                            PRIMARY KEY(`mediaId`, `dateModifiedSec`, `cacheVersion`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_face_embedding_cache_mediaId` ON `face_embedding_cache` (`mediaId`)")
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `person_aliases` ADD COLUMN `matchThreshold` REAL NOT NULL DEFAULT 0.78")
                }
            }

        fun build(context: Context): ClipDatabase =
            Room.databaseBuilder(context, ClipDatabase::class.java, "clipfinder.db")
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}

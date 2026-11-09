package dev.whistle.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CursorEntity::class, WorkspaceEntity::class, TabEntity::class, PaneEntity::class,
        LayoutPaneEntity::class, MessageEntity::class, ChatMetaEntity::class, SnippetEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class HerdrDb : RoomDatabase() {
    abstract fun sync(): SyncDao
    abstract fun machines(): MachineDao
    abstract fun messages(): MessageDao
    abstract fun snippets(): SnippetDao

    companion object {
        /**
         * Snippets are user content, so schema changes must migrate, never drop: add a Migration
         * for every version bump (re-derivable cache tables may be dropped and recreated inside it).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `snippet` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `text` TEXT NOT NULL)",
                )
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3)

        fun open(context: Context): HerdrDb =
            Room.databaseBuilder(context, HerdrDb::class.java, "herdr-cache.db")
                .addMigrations(*MIGRATIONS)
                // v1 predates snippets (dev builds only): the cache is re-derivable from the bridge.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                .build()
    }
}

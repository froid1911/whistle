package dev.whistle.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SnippetMigrationTest {
    @Test fun migration2to3keepsCacheAndAddsSnippets() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("mig.db")
        // 1. Build the current schema, then turn it into a v2 file (no snippet table) holding a cached row.
        Room.databaseBuilder(ctx, HerdrDb::class.java, "mig.db").addMigrations(*HerdrDb.MIGRATIONS).build().let { db -> try {
            db.openHelper.writableDatabase.apply {
                execSQL("DROP TABLE snippet")
                execSQL("INSERT INTO workspace (machineId,id,number,label,agentStatus,activeTabId) VALUES ('m1','w1',1,'ws',NULL,NULL)")
                version = 2
            }
        } finally { db.close() } }
        // 2. Reopen: the migration runs, the cached row survives, the snippet table is usable.
        Room.databaseBuilder(ctx, HerdrDb::class.java, "mig.db").addMigrations(*HerdrDb.MIGRATIONS).build().let { db -> try {
            assertEquals(1, db.machines().workspaces("m1").first().size)
            val id = db.snippets().upsert(SnippetEntity(title = "t", text = "body"))
            assertEquals(listOf("body"), db.snippets().all().first().map { it.text })
            assertEquals(true, id > 0)
        } finally { db.close() } }
    }
}

package dev.whistle.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Writes used by the sync applier; always called inside one transaction per applied frame. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM cursor WHERE machineId = :m")
    suspend fun cursor(m: String): CursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCursor(c: CursorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspaces(rows: List<WorkspaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTabs(rows: List<TabEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPanes(rows: List<PaneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLayoutPanes(rows: List<LayoutPaneEntity>)

    @Query("DELETE FROM workspace WHERE machineId = :m AND id = :id")
    suspend fun deleteWorkspace(m: String, id: String)

    @Query("DELETE FROM tab WHERE machineId = :m AND id = :id")
    suspend fun deleteTab(m: String, id: String)

    @Query("DELETE FROM pane WHERE machineId = :m AND id = :id")
    suspend fun deletePane(m: String, id: String)

    @Query("DELETE FROM layout_pane WHERE machineId = :m AND tabId = :tabId")
    suspend fun deleteLayout(m: String, tabId: String)

    @Query("UPDATE pane SET agentStatus = :status WHERE machineId = :m AND id = :paneId")
    suspend fun setPaneStatus(m: String, paneId: String, status: String?)

    @Query("DELETE FROM workspace WHERE machineId = :m")
    suspend fun clearWorkspaces(m: String)

    @Query("DELETE FROM tab WHERE machineId = :m")
    suspend fun clearTabs(m: String)

    @Query("DELETE FROM pane WHERE machineId = :m")
    suspend fun clearPanes(m: String)

    @Query("DELETE FROM layout_pane WHERE machineId = :m")
    suspend fun clearLayouts(m: String)
}

@Dao
interface MachineDao {
    @Query("SELECT * FROM workspace WHERE machineId = :m ORDER BY number, id")
    fun workspaces(m: String): Flow<List<WorkspaceEntity>>

    /** Tabs of a workspace that exists (orphans hidden). */
    @Query(
        """SELECT t.* FROM tab t JOIN workspace w ON w.machineId = t.machineId AND w.id = t.workspaceId
           WHERE t.machineId = :m AND t.workspaceId = :wsId ORDER BY t.number, t.id""",
    )
    fun tabs(m: String, wsId: String): Flow<List<TabEntity>>

    /** Panes of a tab that exists (orphans hidden), ordered by layout y, x; unplaced panes last. */
    @Query(
        """SELECT p.*, l.x AS x, l.y AS y, l.w AS w, l.h AS h FROM pane p
           JOIN tab t ON t.machineId = p.machineId AND t.id = p.tabId
           LEFT JOIN layout_pane l ON l.machineId = p.machineId AND l.tabId = p.tabId AND l.paneId = p.id
           WHERE p.machineId = :m AND p.tabId = :tabId
           ORDER BY l.y IS NULL, l.y, l.x, p.id""",
    )
    fun panes(m: String, tabId: String): Flow<List<PaneRow>>

    @Query(
        """SELECT p.*, l.x AS x, l.y AS y, l.w AS w, l.h AS h FROM pane p
           JOIN tab t ON t.machineId = p.machineId AND t.id = p.tabId
           LEFT JOIN layout_pane l ON l.machineId = p.machineId AND l.tabId = p.tabId AND l.paneId = p.id
           WHERE p.machineId = :m AND p.id = :paneId""",
    )
    fun pane(m: String, paneId: String): Flow<PaneRow?>

    /** Agent panes of visible tabs (the share picker). */
    @Query(
        """SELECT p.*, l.x AS x, l.y AS y, l.w AS w, l.h AS h FROM pane p
           JOIN tab t ON t.machineId = p.machineId AND t.id = p.tabId
           LEFT JOIN layout_pane l ON l.machineId = p.machineId AND l.tabId = p.tabId AND l.paneId = p.id
           WHERE p.machineId = :m AND p.kind = 'agent'
           ORDER BY p.workspaceId, p.tabId, l.y IS NULL, l.y, l.x, p.id""",
    )
    fun agentPanes(m: String): Flow<List<PaneRow>>

    /** Workspace + status of every visible pane of a machine (for list rollups). */
    @Query(
        """SELECT p.workspaceId AS workspaceId, p.agentStatus AS agentStatus FROM pane p
           JOIN tab t ON t.machineId = p.machineId AND t.id = p.tabId
           WHERE p.machineId = :m""",
    )
    fun paneStatuses(m: String): Flow<List<PaneStatusRow>>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM message WHERE machineId = :m AND sessionKey = :sk ORDER BY seq, id")
    fun messages(m: String, sk: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE machineId = :m AND sessionKey = :sk AND id = :id")
    suspend fun get(m: String, sk: String, id: String): MessageEntity?

    @Query("SELECT MIN(seq) FROM message WHERE machineId = :m AND sessionKey = :sk")
    suspend fun minSeq(m: String, sk: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: MessageEntity)

    @Query("DELETE FROM message WHERE machineId = :m AND sessionKey = :sk AND seq < :seq")
    suspend fun deleteOlderThan(m: String, sk: String, seq: Long)

    @Query("DELETE FROM message WHERE machineId = :m")
    suspend fun clearMessages(m: String)

    @Query("SELECT * FROM chat_meta WHERE machineId = :m AND sessionKey = :sk")
    suspend fun meta(m: String, sk: String): ChatMetaEntity?

    @Query("SELECT * FROM chat_meta WHERE machineId = :m AND sessionKey = :sk")
    fun metaFlow(m: String, sk: String): Flow<ChatMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(row: ChatMetaEntity)

    @Query("UPDATE chat_meta SET stale = 1 WHERE machineId = :m")
    suspend fun markAllStale(m: String)

    @Query("DELETE FROM chat_meta WHERE machineId = :m")
    suspend fun clearMeta(m: String)

    /** Upsert from a `change` by (machine, session, id); the ordering seq of an existing message never changes. */
    suspend fun upsert(row: MessageEntity) {
        val old = get(row.machineId, row.sessionKey, row.id)
        insert(if (old != null) row.copy(seq = old.seq) else row)
    }

    /**
     * Upsert of a `chat.history` row snapshotted when the cursor was [snapshotSeq]: skipped if a
     * later applied change already wrote this message (the page would roll it back).
     */
    suspend fun upsertFromHistory(row: MessageEntity, snapshotSeq: Long) {
        val old = get(row.machineId, row.sessionKey, row.id)
        if (old != null && old.changeSeq > snapshotSeq) return
        insert(row.copy(seq = old?.seq ?: row.seq, changeSeq = old?.changeSeq ?: 0))
    }
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippet ORDER BY title COLLATE NOCASE, id")
    fun all(): Flow<List<SnippetEntity>>

    /** Insert (id 0) or replace; returns the row id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SnippetEntity): Long

    @Query("DELETE FROM snippet WHERE id = :id")
    suspend fun delete(id: Long)
}

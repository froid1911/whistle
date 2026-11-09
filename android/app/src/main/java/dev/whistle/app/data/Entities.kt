package dev.whistle.app.data

import androidx.room.Entity
import androidx.room.Index
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.WLayoutPane
import dev.whistle.app.protocol.WMessage
import dev.whistle.app.protocol.WPane
import dev.whistle.app.protocol.WTab
import dev.whistle.app.protocol.WWorkspace
import dev.whistle.app.protocol.WireJson
import kotlinx.serialization.json.JsonElement

/*
 * Every row is scoped by machineId. Rows are stored exactly as the change log delivers them:
 * no foreign keys, because the log is ordered per pane, not globally (a pane can reference a
 * tab that is not known yet, a tab can be removed before its panes). UI queries join instead.
 */

/** Resume cursor: last seq APPLIED (written in the same transaction as the data it covers). */
@Entity(tableName = "cursor", primaryKeys = ["machineId"])
data class CursorEntity(val machineId: String, val serverId: String, val seq: Long)

@Entity(tableName = "workspace", primaryKeys = ["machineId", "id"])
data class WorkspaceEntity(
    val machineId: String,
    val id: String,
    val number: Int,
    val label: String,
    val agentStatus: String?,
    val activeTabId: String?,
)

@Entity(tableName = "tab", primaryKeys = ["machineId", "id"], indices = [Index("machineId", "workspaceId")])
data class TabEntity(
    val machineId: String,
    val id: String,
    val workspaceId: String,
    val number: Int,
    val label: String,
    val agentStatus: String?,
)

@Entity(tableName = "pane", primaryKeys = ["machineId", "id"], indices = [Index("machineId", "tabId")])
data class PaneEntity(
    val machineId: String,
    val id: String,
    val tabId: String,
    val workspaceId: String,
    val label: String,
    val cwd: String,
    val kind: String,
    val agent: String?,
    val agentStatus: String?,
    val sessionKey: String?,
    val hasTranscript: Boolean,
)

@Entity(tableName = "layout_pane", primaryKeys = ["machineId", "tabId", "paneId"])
data class LayoutPaneEntity(
    val machineId: String,
    val tabId: String,
    val paneId: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

@Entity(
    tableName = "message",
    primaryKeys = ["machineId", "sessionKey", "id"],
    indices = [Index("machineId", "sessionKey", "seq")],
)
data class MessageEntity(
    val machineId: String,
    val sessionKey: String,
    val id: String,
    /** Ordering seq (change-log seq of the first insert); kept stable on update. */
    val seq: Long,
    val ts: String,
    val role: String,
    val text: String,
    val toolName: String?,
    val toolCallId: String?,
    val inputJson: String?,
    val isError: Boolean,
    val truncated: Boolean,
    /**
     * Seq of the change that last wrote this row (0 if written from `chat.history`). A history page
     * snapshotted at cursor c0 must not overwrite a row with changeSeq > c0 (it would be older data).
     */
    val changeSeq: Long = 0,
)

/** Per-chat history bookkeeping. No row = latest page never fetched. */
@Entity(tableName = "chat_meta", primaryKeys = ["machineId", "sessionKey"])
data class ChatMetaEntity(
    val machineId: String,
    val sessionKey: String,
    /** Older messages exist before the oldest cached one (from the last `chat.history`). */
    val more: Boolean,
    /** Set by a resync: the latest page must be refetched before the cache is trusted. */
    val stale: Boolean,
)

/** Pane row joined with its layout cell (null if the tab has no layout entry for it). */
data class PaneRow(
    val machineId: String,
    val id: String,
    val tabId: String,
    val workspaceId: String,
    val label: String,
    val cwd: String,
    val kind: String,
    val agent: String?,
    val agentStatus: String?,
    val sessionKey: String?,
    val hasTranscript: Boolean,
    val x: Int?,
    val y: Int?,
    val w: Int?,
    val h: Int?,
)

data class PaneStatusRow(val workspaceId: String, val agentStatus: String?)

// ---- UI models ---------------------------------------------------------------

/** A workspace row: [status] is the rollup of its panes, see [rollupStatus]. */
data class WorkspaceSummaryUi(val workspace: WorkspaceUi, val paneCount: Int, val status: AgentStatus)

/**
 * The most attention-worthy pane status: blocked > working > done > idle. Panes without an
 * agent status (shells) don't count; with none left, herdr's own [fallback] rollup is used.
 */
fun rollupStatus(panes: Collection<AgentStatus>, fallback: AgentStatus): AgentStatus =
    listOf(AgentStatus.BLOCKED, AgentStatus.WORKING, AgentStatus.DONE, AgentStatus.IDLE)
        .firstOrNull { it in panes } ?: fallback

data class WorkspaceUi(val id: String, val number: Int, val label: String, val agentStatus: AgentStatus, val activeTabId: String?)

data class TabUi(val id: String, val workspaceId: String, val number: Int, val label: String, val agentStatus: AgentStatus)

data class PaneUi(
    val id: String,
    val tabId: String,
    val workspaceId: String,
    val label: String,
    val cwd: String,
    val kind: String,
    val agent: String?,
    val agentStatus: AgentStatus,
    val sessionKey: String?,
    val hasTranscript: Boolean,
    /** Layout cell; null if the tab's layout does not (yet) list this pane. */
    val x: Int?,
    val y: Int?,
    val w: Int?,
    val h: Int?,
)

// ---- mapping -----------------------------------------------------------------

fun WWorkspace.toEntity(m: String) = WorkspaceEntity(m, id, number, label, agentStatus, activeTabId)
fun WTab.toEntity(m: String) = TabEntity(m, id, workspaceId, number, label, agentStatus)
fun WPane.toEntity(m: String) =
    PaneEntity(m, id, tabId, workspaceId, label, cwd, kind, agent, agentStatus, sessionKey, hasTranscript)
fun WLayoutPane.toEntity(m: String, tabId: String) = LayoutPaneEntity(m, tabId, paneId, x, y, w, h)

fun WMessage.toEntity(m: String, sessionKey: String = this.sessionKey, changeSeq: Long = 0) = MessageEntity(
    m, sessionKey, id, seq, ts, role, text, toolName, toolCallId,
    input?.let { WireJson.encodeToString(JsonElement.serializer(), it) } ?: inputJson, isError, truncated, changeSeq,
)

/** Keeps the input as its raw string ([WMessage.inputJson]); decoded lazily via [WMessage.toolInput]. */
fun MessageEntity.toWire() = WMessage(
    id, seq, sessionKey, ts, role, text, toolName, toolCallId,
    input = null, isError = isError, truncated = truncated, inputJson = inputJson,
)

fun WorkspaceEntity.toUi() = WorkspaceUi(id, number, label, AgentStatus.parse(agentStatus), activeTabId)
fun TabEntity.toUi() = TabUi(id, workspaceId, number, label, AgentStatus.parse(agentStatus))
fun PaneRow.toUi() =
    PaneUi(id, tabId, workspaceId, label, cwd, kind, agent, AgentStatus.parse(agentStatus), sessionKey, hasTranscript, x, y, w, h)

/** Freshness of a chat's cached history. */
sealed interface ChatState {
    /** Latest page fetched since the last resync; changes keep it current. */
    data object Fresh : ChatState

    /** Never fetched, or a resync made the cache untrusted (may be missing messages). */
    data object Stale : ChatState

    /** The latest page is being fetched. */
    data object Loading : ChatState

    /** The last fetch failed with [code] (wire error code); retried on the next Synced / ensureHistory. */
    data class Error(val code: String) : ChatState
}

/** A user-written reusable prompt. User content: never dropped by a cache reset, see [HerdrDb.MIGRATIONS]. */
@Entity(tableName = "snippet")
data class SnippetEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val text: String,
)

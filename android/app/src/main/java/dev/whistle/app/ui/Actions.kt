package dev.whistle.app.ui

import dev.whistle.app.data.HerdrRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Agent kinds `agent.start` accepts from the phone. */
val AGENT_KINDS = listOf("claude", "codex", "omp")

/**
 * herdr lifecycle commands of one machine (wire-protocol "Commands"). `focus` is never sent:
 * the phone must not steal the desktop's focus, and the bridge forces it false anyway.
 * Every function throws [dev.whistle.app.protocol.BridgeError] on failure.
 */
class HerdrActions(private val repo: HerdrRepository, private val machineId: String) {
    private suspend fun cmd(method: String, params: JsonObjectBuilder.() -> Unit): JsonElement =
        repo.cmd(machineId, method, buildJsonObject(params))

    private fun JsonObjectBuilder.putIfNotBlank(key: String, value: String?) {
        if (!value.isNullOrBlank()) put(key, value.trim())
    }

    suspend fun createWorkspace(cwd: String?, label: String?) =
        cmd("workspace.create") { putIfNotBlank("cwd", cwd); putIfNotBlank("label", label) }

    suspend fun renameWorkspace(id: String, label: String) =
        cmd("workspace.rename") { put("workspace_id", id); put("label", label.trim()) }

    suspend fun closeWorkspace(id: String) = cmd("workspace.close") { put("workspace_id", id) }

    /** Returns the new tab's id when herdr reports it. */
    suspend fun createTab(workspaceId: String, cwd: String?, label: String?): String? =
        cmd("tab.create") {
            put("workspace_id", workspaceId); putIfNotBlank("cwd", cwd); putIfNotBlank("label", label)
        }.idAt("tab", "tab_id")

    suspend fun renameTab(id: String, label: String) = cmd("tab.rename") { put("tab_id", id); put("label", label.trim()) }

    suspend fun closeTab(id: String) = cmd("tab.close") { put("tab_id", id) }

    /** [direction] is `right` or `down`. Returns the new pane's id when herdr reports it. */
    suspend fun splitPane(paneId: String, direction: String): String? =
        cmd("pane.split") { put("target_pane_id", paneId); put("direction", direction) }.idAt("pane", "pane_id")

    suspend fun renamePane(id: String, label: String) = cmd("pane.rename") { put("pane_id", id); put("label", label.trim()) }

    suspend fun closePane(id: String) = cmd("pane.close") { put("pane_id", id) }

    suspend fun startAgent(paneId: String, kind: String, name: String) =
        cmd("agent.start") { put("pane_id", paneId); put("kind", kind); put("name", name.trim().ifEmpty { kind }) }

    private fun JsonElement.idAt(obj: String, key: String): String? =
        ((this as? JsonObject)?.get(obj) as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.content }
}

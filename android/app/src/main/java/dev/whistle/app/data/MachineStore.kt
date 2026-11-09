package dev.whistle.app.data

import android.content.SharedPreferences
import dev.whistle.app.protocol.Machine
import dev.whistle.app.protocol.WireJson
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The user's machines. Kept in SharedPreferences rather than the Room cache, which is dropped on
 * schema changes; this is configuration, not re-derivable data. Starts empty (first run: pair a machine).
 */
class MachineStore(private val prefs: SharedPreferences) {
    /** Pairing fields were added in 0.4.0; rows written before decode with them null (unpaired). */
    @Serializable
    internal data class Row(
        val id: String,
        val name: String,
        val baseUrl: String,
        val deviceId: String? = null,
        val keyAlias: String? = null,
        val bridgeKey: String? = null,
        val deviceName: String? = null,
    )

    private val serializer = ListSerializer(Row.serializer())
    private val _machines = MutableStateFlow(load())
    val machines: StateFlow<List<Machine>> = _machines.asStateFlow()

    fun get(id: String): Machine? = _machines.value.firstOrNull { it.id == id }

    /** Inserts or replaces by id. */
    @Synchronized
    fun upsert(m: Machine) {
        val list = _machines.value.toMutableList()
        val i = list.indexOfFirst { it.id == m.id }
        if (i >= 0) list[i] = m else list += m
        save(list)
    }

    @Synchronized
    fun remove(id: String) = save(_machines.value.filterNot { it.id == id })

    fun newId(): String = UUID.randomUUID().toString()

    private fun load(): List<Machine> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { WireJson.decodeFromString(serializer, raw) }.getOrNull()
            ?.map { Machine(it.id, it.name, it.baseUrl, it.deviceId, it.keyAlias, it.bridgeKey, it.deviceName) }
            ?: emptyList()
    }

    private fun save(list: List<Machine>) {
        prefs.edit().putString(KEY, WireJson.encodeToString(serializer, list.map { Row(it.id, it.name, it.baseUrl, it.deviceId, it.keyAlias, it.bridgeKey, it.deviceName) })).apply()
        _machines.value = list
    }

    private companion object {
        const val KEY = "machines"
    }
}

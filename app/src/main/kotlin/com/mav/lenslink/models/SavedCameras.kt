/*
 * SavedCameras.kt — one-tap connect bookkeeping:
 *   • every successful Wi-Fi connection is recorded here (named after the
 *     camera's model string), so the Connect tab can offer the body as a
 *     one-tap entry afterwards
 *   • an entry carries an arbitrary number of addresses ("host" or
 *     "host:port"); the FIRST one is the primary used for one-tap connect,
 *     and a successful connection promotes its address to primary
 *   • persisted as JSON in SharedPreferences — the camera's static IP makes
 *     this stable across sessions
 */
package com.mav.lenslink.models

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A PTP/IP endpoint: bare host (default port) or explicit "host:port". */
data class CameraAddress(val host: String, val port: Int) {
    override fun toString(): String =
        if (port == DEFAULT_PORT) host else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 15740

        /** Accepts "192.168.1.1" or "192.168.1.1:15740". */
        fun parse(raw: String): CameraAddress? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val colon = trimmed.lastIndexOf(':')
            val host: String
            val port: Int
            if (colon > 0) {
                host = trimmed.substring(0, colon).trim()
                port = trimmed.substring(colon + 1).trim().toIntOrNull()
                    ?: return null
            } else {
                host = trimmed
                port = DEFAULT_PORT
            }
            if (host.isEmpty()) return null
            return CameraAddress(host, port)
        }
    }
}

/** One saved camera: an identifier plus the addresses it was reached on. */
class SavedCamera(
    val id: String,
    name: String,
    addresses: List<CameraAddress> = emptyList(),
) {
    var name by mutableStateOf(name)
    val addresses = mutableStateListOf<CameraAddress>().apply { addAll(addresses) }

    /** Epoch millis of the last successful connection through this entry. */
    var lastUsed: Long = 0L
        private set

    fun touch(time: Long) {
        lastUsed = time
    }
}

/**
 * Store of [SavedCamera]s, observable from Compose (snapshot lists) and
 * persisted to SharedPreferences as JSON. Owned by LensLinkApplication.
 */
class SavedCameraStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val cameras = mutableStateListOf<SavedCamera>()

    init {
        load()
    }

    /** The entry (if any) that lists this endpoint. */
    fun find(host: String, port: Int): SavedCamera? =
        cameras.firstOrNull { c -> c.addresses.any { it.host == host && it.port == port } }

    /**
     * Records a successful Wi-Fi connection: creates the entry when missing
     * (named after [suggestedName], usually the camera model), promotes the
     * working address to primary and reorders the list by recency.
     */
    fun recordSuccess(host: String, port: Int, suggestedName: String?) {
        val address = CameraAddress(host, port)
        val entry = find(host, port)
            ?: SavedCamera(
                id = UUID.randomUUID().toString(),
                name = suggestedName?.takeIf { it.isNotBlank() }
                    ?: "Camera ${cameras.size + 1}",
            ).also { cameras.add(it) }
        entry.addresses.remove(address)
        entry.addresses.add(0, address)
        entry.touch(System.currentTimeMillis())
        cameras.sortByDescending { it.lastUsed }
        save()
    }

    /** Manual entry from the Connect tab. */
    fun add(name: String, addresses: List<CameraAddress>): SavedCamera {
        val entry = SavedCamera(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Camera ${cameras.size + 1}" },
            addresses = addresses,
        )
        cameras.add(entry)
        save()
        return entry
    }

    fun remove(id: String) {
        cameras.removeAll { it.id == id }
        save()
    }

    fun save() {
        val array = JSONArray()
        cameras.forEach { cam ->
            array.put(
                JSONObject()
                    .put("id", cam.id)
                    .put("name", cam.name)
                    .put("lastUsed", cam.lastUsed)
                    .put(
                        "addresses",
                        JSONArray().apply { cam.addresses.forEach { put(it.toString()) } },
                    ),
            )
        }
        prefs.edit().putString(KEY_CAMERAS, array.toString()).apply()
    }

    private fun load() {
        cameras.clear()
        runCatching {
            val array = JSONArray(prefs.getString(KEY_CAMERAS, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val parsed = mutableListOf<CameraAddress>()
                val addresses = obj.optJSONArray("addresses") ?: JSONArray()
                for (j in 0 until addresses.length()) {
                    CameraAddress.parse(addresses.optString(j))?.let { parsed.add(it) }
                }
                val entry = SavedCamera(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    name = obj.optString("name", "Camera"),
                    addresses = parsed,
                )
                entry.touch(obj.optLong("lastUsed", 0L))
                cameras.add(entry)
            }
        }
    }

    private companion object {
        const val PREFS = "lenslink"
        const val KEY_CAMERAS = "saved_cameras"
    }
}

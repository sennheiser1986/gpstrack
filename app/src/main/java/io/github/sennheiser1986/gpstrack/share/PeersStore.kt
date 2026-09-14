package io.github.sennheiser1986.gpstrack.share

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A peer this device is watching: another GPS Track install whose QR code has been scanned.
 *
 * @property id the peer's sharing id (what was in their QR).
 * @property label the name to show for them on the map; taken from the QR, editable later.
 * @property visibleOnMap whether the reader currently wants to see this peer on the Map tab. Only
 *   visible peers are asked about when syncing, so hiding a peer also stops fetching their
 *   position.
 */
data class Peer(
    val id: String,
    val label: String,
    val visibleOnMap: Boolean,
)

/**
 * Persists the list of scanned peers as JSON in shared preferences, in the order they were
 * added.
 */
class PeersStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Adds any account-followed devices reported by the server that are not saved yet, so a
     * follow made on the web page shows up in this app without a QR scan. Existing peers keep
     * their label and visibility.
     *
     * @param followed server-reported (id, label) pairs.
     * @param ownInstanceId this device's own id, which is never added.
     * @return true when at least one peer was added.
     */
    fun mergeAccountFollows(followed: List<Pair<String, String>>, ownInstanceId: String): Boolean {
        val existing = peers()
        val known = existing.map { it.id }.toHashSet()
        val fresh = followed
            .filter { (id, _) -> id != ownInstanceId && id !in known }
            .map { (id, label) -> Peer(id = id, label = label.ifBlank { id.take(8) }, visibleOnMap = true) }
        if (fresh.isEmpty()) return false
        write(existing + fresh)
        bumpRevision()
        return true
    }

    /**
     * Reads the saved peers.
     *
     * @return the peers in insertion order; empty when none have been scanned.
     */
    fun peers(): List<Peer> {
        val encoded = preferences.getString(KEY_PEERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Peer(
                    id = id,
                    label = entry.optString("label").ifBlank { id.take(8) },
                    visibleOnMap = entry.optBoolean("visible", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Adds a peer, or refreshes the label of one already saved. Never adds this device itself.
     *
     * @param id the peer's sharing id.
     * @param label the name from the peer's QR.
     * @param ownInstanceId this device's own id, which is rejected.
     */
    fun addOrUpdatePeer(id: String, label: String, ownInstanceId: String) {
        if (id == ownInstanceId) return
        val existing = peers()
        val updated = if (existing.any { it.id == id }) {
            existing.map { if (it.id == id) it.copy(label = label) else it }
        } else {
            existing + Peer(id = id, label = label, visibleOnMap = true)
        }
        write(updated)
    }

    /**
     * Adopts the friendly name a peer is currently broadcasting, so a peer added by bare id
     * stops showing as a short id fragment once they come online.
     *
     * @param id the peer to relabel.
     * @param label the name the peer is broadcasting.
     * @return true when a stored label actually changed.
     */
    fun updateLabelFromPeer(id: String, label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return false
        val current = peers()
        if (current.none { it.id == id && it.label != trimmed }) return false
        write(current.map { if (it.id == id) it.copy(label = trimmed) else it })
        return true
    }

    /**
     * Removes a peer.
     *
     * @param id the peer to forget.
     */
    fun removePeer(id: String) {
        write(peers().filterNot { it.id == id })
    }

    /**
     * Shows or hides a peer on the map.
     *
     * @param id the peer to change.
     * @param visible true to show them and resume fetching their position.
     */
    fun setVisible(id: String, visible: Boolean) {
        write(peers().map { if (it.id == id) it.copy(visibleOnMap = visible) else it })
    }

    /**
     * The ids of the peers currently shown on the map, i.e. the ones to ask the server about.
     *
     * @return the visible peers' ids.
     */
    fun watchedIds(): List<String> = peers().filter { it.visibleOnMap }.map { it.id }

    /**
     * Serialises the peer list back to preferences.
     *
     * @param peers the list to store.
     */
    private fun bumpRevision() {
        revisionFlow.value++
    }

    private fun write(peers: List<Peer>) {
        val array = JSONArray()
        peers.forEach { peer ->
            array.put(
                JSONObject()
                    .put("id", peer.id)
                    .put("label", peer.label)
                    .put("visible", peer.visibleOnMap),
            )
        }
        preferences.edit().putString(KEY_PEERS, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "track_recorder_peers"
        private const val KEY_PEERS = "peers"

        private val revisionFlow = kotlinx.coroutines.flow.MutableStateFlow(0)

        /**
         * Bumped whenever a [PeersStore] instance changes the stored list, so an observer in
         * another part of the process (the ViewModel) can re-read it.
         */
        val revision: kotlinx.coroutines.flow.StateFlow<Int> = revisionFlow
    }
}

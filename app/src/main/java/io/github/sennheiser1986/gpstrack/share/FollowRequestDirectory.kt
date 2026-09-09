package io.github.sennheiser1986.gpstrack.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A web user who wants to follow — or already follows — this device, as reported by the
 * sharing server.
 *
 * @property id the server-side follow id; used to send an approve/deny decision back.
 * @property user the web user's name.
 * @property requestedAtMillis when the request was made, in milliseconds since the epoch (0 for
 *   an already-approved follower).
 */
data class FollowRequest(
    val id: Long,
    val user: String,
    val requestedAtMillis: Long,
)

/**
 * Process-wide, in-memory view of web follow requests for this device. The sharing server is
 * the source of truth; every `/sync` refreshes [pending] and [followers]. When the reader taps
 * Allow/Deny, the choice is queued in [decide] and sent on the next sync, then acknowledged.
 */
object FollowRequestDirectory {

    private val _pending = MutableStateFlow<List<FollowRequest>>(emptyList())

    /** Requests still awaiting the reader's decision. */
    val pending: StateFlow<List<FollowRequest>> = _pending.asStateFlow()

    private val _followers = MutableStateFlow<List<FollowRequest>>(emptyList())

    /** Web users whose follow is currently approved. */
    val followers: StateFlow<List<FollowRequest>> = _followers.asStateFlow()

    private val decisions = linkedMapOf<Long, String>()
    private val lock = Any()

    /**
     * Replaces the pending and approved lists with the latest sync result. Requests the reader
     * has locally decided but not yet synced are dropped from [pending] so they do not reappear
     * for a moment.
     *
     * @param pending the server's current pending requests.
     * @param followers the server's current approved followers.
     */
    fun replaceAll(pending: List<FollowRequest>, followers: List<FollowRequest>) {
        synchronized(lock) {
            _pending.value = pending.filterNot { decisions.containsKey(it.id) }
            _followers.value = followers
        }
    }

    /**
     * Queues the reader's choice for a request and removes it from [pending] immediately.
     *
     * @param id the follow id.
     * @param approve true to allow, false to deny.
     */
    fun decide(id: Long, approve: Boolean) {
        synchronized(lock) {
            decisions[id] = if (approve) "approved" else "denied"
            _pending.value = _pending.value.filterNot { it.id == id }
        }
    }

    /**
     * The decisions not yet delivered to the server, keyed by follow id as a string (the wire
     * format).
     *
     * @return a snapshot map, possibly empty.
     */
    fun snapshotDecisions(): Map<String, String> = synchronized(lock) {
        decisions.entries.associate { it.key.toString() to it.value }
    }

    /**
     * Drops decisions the server has now acknowledged.
     *
     * @param ids the follow ids that were sent successfully.
     */
    fun acknowledge(ids: Collection<Long>) {
        synchronized(lock) { ids.forEach(decisions::remove) }
    }

    /** Whether there are decisions still to deliver (keeps the sync service running). */
    fun hasUndeliveredDecisions(): Boolean = synchronized(lock) { decisions.isNotEmpty() }

    /** Clears everything, e.g. when sharing is switched off. */
    fun clear() {
        synchronized(lock) {
            _pending.value = emptyList()
            _followers.value = emptyList()
            decisions.clear()
        }
    }
}

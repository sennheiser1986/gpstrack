package io.github.sennheiser1986.gpstrack.share

import io.github.sennheiser1986.gpstrack.data.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One sync exchange with the sharing server: this device's own position (when broadcasting),
 * the ids it wants positions for, and any decisions on web follow requests.
 *
 * @property instanceId this device's sharing id.
 * @property label this device's display name.
 * @property broadcasting whether [position] should be published; when false the server only
 *   answers the [watching] query and does not store anything for this device.
 * @property position this device's current coordinate, or null when unknown.
 * @property timeMillis when [position] was recorded, in milliseconds since the epoch.
 * @property watching ids of the peers to return positions for.
 * @property followDecisions follow id (as a string) to "approved"/"denied" for web follow
 *   requests the reader has just allowed or denied.
 */
data class SyncRequest(
    val instanceId: String,
    val label: String,
    val broadcasting: Boolean,
    val position: LatLon?,
    val timeMillis: Long?,
    val watching: List<String>,
    val followDecisions: Map<String, String> = emptyMap(),
)

/**
 * What the sharing server returns from one sync.
 *
 * @property peers watched peer id to its latest position.
 * @property followRequests web users awaiting this device owner's decision.
 * @property followers web users whose follow is currently approved.
 */
data class SyncResult(
    val peers: Map<String, PeerLocation>,
    val followRequests: List<FollowRequest>,
    val followers: List<FollowRequest>,
)

/** Raised when the sharing server cannot be reached or returns something unusable. */
class ShareUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Talks to the GPS Track sharing server. The server is a thin relay: `POST /sync` stores
 * the caller's position (if it is broadcasting) and echoes back the latest position of every id
 * in `watching`. See `server/main.py`.
 */
object LocationShareClient {

    private const val USER_AGENT = "GPSTrack/1.0 (Android)"
    private const val CONNECT_TIMEOUT_MILLIS = 8_000
    private const val READ_TIMEOUT_MILLIS = 8_000

    /**
     * Performs one sync.
     *
     * @param serverBaseUrl the server root, without a trailing slash.
     * @param request the payload to send.
     * @return the peers, follow requests and approved followers the server reported.
     * @throws ShareUnavailableException on any network or protocol failure.
     */
    fun sync(serverBaseUrl: String, request: SyncRequest): SyncResult {
        val body = JSONObject().apply {
            put("id", request.instanceId)
            put("label", request.label)
            put("broadcasting", request.broadcasting)
            if (request.broadcasting && request.position != null) {
                put("lat", request.position.latitude)
                put("lon", request.position.longitude)
                put("time", (request.timeMillis ?: System.currentTimeMillis()) / 1000.0)
            }
            put("watching", JSONArray(request.watching))
            if (request.followDecisions.isNotEmpty()) {
                put("follow_decisions", JSONObject(request.followDecisions as Map<*, *>))
            }
        }

        val connection = (URL("$serverBaseUrl/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw ShareUnavailableException("Sharing server returned HTTP $status")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            SyncResult(
                peers = parsePeers(json.optJSONObject("peers")),
                followRequests = parseFollowRequests(json.optJSONArray("follow_requests")),
                followers = parseFollowRequests(json.optJSONArray("followers")),
            )
        } catch (error: ShareUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw ShareUnavailableException("Could not reach the sharing server", error)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Turns the server's `peers` object into a typed map.
     *
     * @param peers the JSON object keyed by peer id, or null.
     * @return peer id to [PeerLocation]; entries missing coordinates are skipped.
     */
    private fun parsePeers(peers: JSONObject?): Map<String, PeerLocation> {
        if (peers == null) return emptyMap()
        val result = mutableMapOf<String, PeerLocation>()
        for (id in peers.keys()) {
            val entry = peers.optJSONObject(id) ?: continue
            if (!entry.has("lat") || !entry.has("lon")) continue
            result[id] = PeerLocation(
                position = LatLon(entry.getDouble("lat"), entry.getDouble("lon")),
                timeMillis = (entry.optDouble("time", 0.0) * 1000).toLong(),
                label = entry.optString("label").takeIf { it.isNotBlank() },
            )
        }
        return result
    }

    /**
     * Turns a `follow_requests` / `followers` array into typed [FollowRequest]s.
     *
     * @param array the JSON array, or null.
     * @return the requests; entries without an id are skipped.
     */
    private fun parseFollowRequests(array: JSONArray?): List<FollowRequest> {
        if (array == null) return emptyList()
        val result = mutableListOf<FollowRequest>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            if (!entry.has("id")) continue
            result += FollowRequest(
                id = entry.getLong("id"),
                user = entry.optString("user").ifBlank { "someone" },
                requestedAtMillis = (entry.optDouble("requested_at", 0.0) * 1000).toLong(),
            )
        }
        return result
    }
}

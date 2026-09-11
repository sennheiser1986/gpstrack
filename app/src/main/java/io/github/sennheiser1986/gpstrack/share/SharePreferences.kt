package io.github.sennheiser1986.gpstrack.share

import android.content.Context
import android.provider.Settings
import android.util.Base64
import io.github.sennheiser1986.gpstrack.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * All persisted sharing settings for this install:
 *  - the [instanceId], a random id minted once and never changed; it is what a peer scans and
 *    what the server keys this device's position under.
 *  - the [displayName] peers see next to this device on their map.
 *  - the sharing server [serverUrl].
 *  - whether the location [broadcast][isBroadcasting] is currently switched on.
 */
class SharePreferences(context: Context) {

    private val appContext = context.applicationContext

    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Returns this device's permanent sharing id, deriving it on first call. The id is the
     * SHA-256 of the device's per-app [Settings.Secure.ANDROID_ID] plus the application id,
     * URL-safe base64 without padding (43 characters) — so reinstalling the app (or clearing
     * its data) on the same phone always comes back with the same identity, the two build
     * flavors stay distinct, and the raw device id is never exposed. It is a bearer token:
     * anyone who learns it can see this device while it broadcasts, and it only changes with a
     * factory reset. Ids stored by older versions (random tokens or UUIDs) are kept as-is.
     *
     * @return the sharing id.
     */
    fun instanceId(): String {
        preferences.getString(KEY_INSTANCE_ID, null)?.let { return it }
        val fresh = deriveInstanceId() ?: generateRandomInstanceId()
        preferences.edit().putString(KEY_INSTANCE_ID, fresh).apply()
        return fresh
    }

    /**
     * Derives the device-stable sharing id.
     *
     * @return the 43-character token, or null when the platform reports no usable device id.
     */
    private fun deriveInstanceId(): String? {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("gpstrack-share-id:$androidId:${appContext.packageName}".toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Fallback when the device id is unavailable: 32 random bytes as URL-safe base64 without
     * padding or line breaks. Such an id is stored and so survives updates, but not reinstalls.
     *
     * @return a 43-character token.
     */
    private fun generateRandomInstanceId(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Reads the name shown to peers.
     *
     * @return the stored name, or a generic default.
     */
    fun displayName(): String =
        preferences.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_DISPLAY_NAME

    /**
     * Stores the name shown to peers.
     *
     * @param name the new name; blank falls back to the default on read.
     */
    fun setDisplayName(name: String) {
        preferences.edit().putString(KEY_DISPLAY_NAME, name.trim()).apply()
    }

    /**
     * Reads the sharing server base URL, without a trailing slash.
     *
     * @return the stored URL, or the build-time default.
     */
    fun serverUrl(): String =
        (preferences.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_SHARE_SERVER_URL).trimEnd('/')

    /**
     * Stores the sharing server base URL.
     *
     * @param url the new URL; a trailing slash is dropped on read.
     */
    fun setServerUrl(url: String) {
        preferences.edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    /**
     * Reads whether this device is broadcasting its position.
     *
     * @return true when the broadcast switch is on.
     */
    fun isBroadcasting(): Boolean = preferences.getBoolean(KEY_BROADCASTING, false)

    /**
     * Stores the broadcast switch.
     *
     * @param enabled true to send this device's position to the server.
     */
    fun setBroadcasting(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_BROADCASTING, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "track_recorder_share"
        const val KEY_INSTANCE_ID = "instance_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_BROADCASTING = "broadcasting"
        const val DEFAULT_DISPLAY_NAME = "My device"
    }
}

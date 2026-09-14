package io.github.sennheiser1986.gpstrack.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide flag raised when the sharing server refuses this device's token, so the Share
 * tab can surface "sign in required" the moment a background sync hits a 401.
 */
object ShareAuthState {

    private val _authRequired = MutableStateFlow(false)

    /** True when the last sync was rejected for want of a valid sign-in. */
    val authRequired: StateFlow<Boolean> = _authRequired.asStateFlow()

    /** Raises the flag; called by the sync loop on a 401. */
    fun reject() {
        _authRequired.value = true
    }

    /** Clears the flag; called after a successful sign-in or sync. */
    fun accept() {
        _authRequired.value = false
    }
}

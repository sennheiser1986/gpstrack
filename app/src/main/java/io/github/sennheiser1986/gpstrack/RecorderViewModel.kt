package io.github.sennheiser1986.gpstrack

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sennheiser1986.gpstrack.data.ActivityType
import io.github.sennheiser1986.gpstrack.data.BackupCodec
import io.github.sennheiser1986.gpstrack.data.BackupTrack
import io.github.sennheiser1986.gpstrack.data.ExportFormat
import io.github.sennheiser1986.gpstrack.data.GpxImporter
import io.github.sennheiser1986.gpstrack.data.Track
import io.github.sennheiser1986.gpstrack.data.TrackExporter
import io.github.sennheiser1986.gpstrack.data.TrackPoint
import io.github.sennheiser1986.gpstrack.data.TrackStatistics
import io.github.sennheiser1986.gpstrack.data.computeStatistics
import io.github.sennheiser1986.gpstrack.map.OfflineMap
import io.github.sennheiser1986.gpstrack.map.OfflineMapDownloadWorker
import io.github.sennheiser1986.gpstrack.map.OfflineMapRepository
import io.github.sennheiser1986.gpstrack.map.OfflineMapState
import io.github.sennheiser1986.gpstrack.record.LocationRecordingService
import io.github.sennheiser1986.gpstrack.record.RecordPreferences
import io.github.sennheiser1986.gpstrack.record.RecordingState
import io.github.sennheiser1986.gpstrack.share.FollowRequestDirectory
import io.github.sennheiser1986.gpstrack.share.LocationShareService
import io.github.sennheiser1986.gpstrack.share.Peer
import io.github.sennheiser1986.gpstrack.share.PeerDirectory
import io.github.sennheiser1986.gpstrack.share.PeersStore
import io.github.sennheiser1986.gpstrack.share.ShareCodec
import io.github.sennheiser1986.gpstrack.share.SharePreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.work.WorkInfo
import kotlinx.coroutines.launch

/**
 * A track whose file contents have been prepared and are waiting for the reader to pick a
 * destination in the system document picker.
 *
 * @property fileName suggested file name, including the extension.
 * @property mimeType MIME type for the document picker.
 * @property content the file body to write once a destination is chosen.
 */
data class PendingExport(
    val fileName: String,
    val mimeType: String,
    val content: String,
)

/**
 * Backs [MainActivity]. It exposes the track list and recording state for the interface, drives
 * the recording and sharing foreground services, and prepares track exports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as TrackRecorderApp).repository
    private val sharePreferences = SharePreferences(application)
    private val peersStore = PeersStore(application)
    private val recordPreferences = RecordPreferences(application)

    /** This install's permanent sharing id. */
    val instanceId: String = sharePreferences.instanceId()

    // --- Tracks -----------------------------------------------------------------------------

    /** Every recorded track, newest first. */
    val tracks: StateFlow<List<Track>> = repository.tracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedTrackId = MutableStateFlow<Long?>(null)

    /** The track open on the detail screen, or null when the list is showing. */
    val selectedTrackId: StateFlow<Long?> = _selectedTrackId.asStateFlow()

    /** The open track's row, or null. */
    val selectedTrack: StateFlow<Track?> = _selectedTrackId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.track(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The open track's fixes in recording order. */
    val selectedTrackPoints: StateFlow<List<TrackPoint>> = _selectedTrackId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.points(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Figures derived from [selectedTrackPoints]. */
    val selectedTrackStatistics: StateFlow<TrackStatistics> = selectedTrackPoints
        .map { computeStatistics(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackStatistics.EMPTY)

    private val _pendingExport = MutableStateFlow<PendingExport?>(null)

    /** A prepared export waiting for the document picker, or null. */
    val pendingExport: StateFlow<PendingExport?> = _pendingExport.asStateFlow()

    // --- Live recording state (mirrors of RecordingState) ----------------------------------

    /** Id of the track being recorded, or null. */
    val activeTrackId: StateFlow<Long?> = RecordingState.activeTrackId

    /** The growing trail of the active recording. */
    val liveTrail = RecordingState.liveTrail

    /** Distance of the active recording in metres. */
    val liveDistanceMeters = RecordingState.liveDistanceMeters

    /** Auto-paused moving time of the active recording, in milliseconds. */
    val liveMovingMillis = RecordingState.liveMovingMillis

    /** Start time of the active recording, or null. */
    val recordingStartedAtMillis = RecordingState.startedAtMillis

    private val _activityType = MutableStateFlow(recordPreferences.lastActivityType())

    /** The activity type the next recording will be stored as. */
    val activityType: StateFlow<ActivityType> = _activityType.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)

    /** A one-shot message (import/backup results) for the activity to toast, or null. */
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    /** The latest known position of this device. */
    val currentLocation = RecordingState.currentLocation

    // --- Sharing --------------------------------------------------------------------------

    private val _broadcasting = MutableStateFlow(sharePreferences.isBroadcasting())

    /** Whether this device is publishing its position. */
    val broadcasting: StateFlow<Boolean> = _broadcasting.asStateFlow()

    private val _displayName = MutableStateFlow(sharePreferences.displayName())

    /** The name peers see for this device. */
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _serverUrl = MutableStateFlow(sharePreferences.serverUrl())

    /** The sharing server base URL. */
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _peers = MutableStateFlow(peersStore.peers())

    /** The peers whose codes have been scanned, in the order added. */
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** Peer id to that peer's latest reported position. */
    val peerLocations = PeerDirectory.locations

    /** Web users awaiting this device owner's allow/deny decision. */
    val followRequests = FollowRequestDirectory.pending

    /** Web users whose follow of this device is currently approved. */
    val webFollowers = FollowRequestDirectory.followers

    /** The QR payload string encoding this device's id and name. */
    val qrPayload: StateFlow<String> = _displayName
        .map { ShareCodec.encode(instanceId, it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ShareCodec.encode(instanceId, sharePreferences.displayName()),
        )

    // --- Offline map ------------------------------------------------------------------------

    /** What the offline map (Belgium) is doing: absent, downloading, ready or failed. */
    val offlineMapState: StateFlow<OfflineMapState> =
        OfflineMapRepository.workInfo(application)
            .map { infos -> offlineMapStateFrom(infos) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                if (OfflineMap.isReady(application)) {
                    OfflineMapState.Ready(OfflineMap.sizeBytes(application))
                } else {
                    OfflineMapState.Absent
                },
            )

    /** True once a usable offline map is present, so the maps can switch to it. */
    val offlineMapReady: StateFlow<Boolean> = offlineMapState
        .map { it is OfflineMapState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineMap.isReady(application))

    /**
     * Folds the download job's [WorkInfo]s and the file's presence into an [OfflineMapState].
     *
     * @param infos the unique work's infos (zero or one).
     * @return the state to show.
     */
    private fun offlineMapStateFrom(infos: List<WorkInfo>): OfflineMapState {
        val info = infos.firstOrNull()
        return when {
            OfflineMap.isReady(getApplication()) -> OfflineMapState.Ready(OfflineMap.sizeBytes(getApplication()))
            info?.state == WorkInfo.State.RUNNING ->
                OfflineMapState.Downloading(info.progress.getInt(OfflineMapDownloadWorker.KEY_PERCENT, -1))
            info?.state == WorkInfo.State.ENQUEUED -> OfflineMapState.Downloading(-1)
            info?.state == WorkInfo.State.FAILED -> OfflineMapState.Failed
            else -> OfflineMapState.Absent
        }
    }

    /** Starts (or resumes) the offline-map download. */
    fun downloadOfflineMap() = OfflineMapRepository.start(getApplication())

    /** Cancels an in-progress offline-map download. */
    fun cancelOfflineMapDownload() = OfflineMapRepository.cancel(getApplication())

    /** Deletes the offline map so the app goes back to online tiles. */
    fun deleteOfflineMap() {
        OfflineMapRepository.cancel(getApplication())
        OfflineMap.delete(getApplication())
        // Nudge the state flow: cancelUniqueWork clears the WorkInfo, which re-emits.
    }

    init {
        // Close any track left open by a recording that was killed before it could stop. This
        // process is fresh (the ViewModel is being created), so nothing is recording right now.
        if (RecordingState.activeTrackId.value == null) {
            viewModelScope.launch { repository.reconcileOpenTracks() }
        }

        // If sharing was left on (broadcast, or at least one visible peer), make sure the
        // service is running again after a process restart. Nothing to do otherwise.
        if (_broadcasting.value || peersStore.watchedIds().isNotEmpty()) {
            LocationShareService.sync(getApplication())
        }

        // Adopt the friendly name each peer broadcasts, so a peer added by bare id (paste, or a
        // QR without a name) stops showing as a short id fragment once they come online.
        viewModelScope.launch {
            PeerDirectory.locations.collect { locations ->
                var changed = false
                locations.forEach { (id, location) ->
                    val name = location.label?.trim().orEmpty()
                    if (name.isNotEmpty() && peersStore.updateLabelFromPeer(id, name)) changed = true
                }
                if (changed) _peers.value = peersStore.peers()
            }
        }
    }

    /**
     * Seeds [RecordingState.currentLocation] from the platform's last known fix, so the Record
     * and Map maps open near the reader instead of on the Gulf of Guinea. Does nothing without
     * location permission or a cached fix.
     */
    fun primeLastKnownLocation() {
        if (RecordingState.currentLocation.value != null) return
        val manager = getApplication<Application>()
            .getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        )
        val best = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time } ?: return
        RecordingState.updateCurrentLocation(io.github.sennheiser1986.gpstrack.data.LatLon(best.latitude, best.longitude))
    }

    // --- Recording actions ---------------------------------------------------------------

    /**
     * Picks the activity type for the next recording and remembers it as the default.
     *
     * @param type the chosen type.
     */
    fun setActivityType(type: ActivityType) {
        recordPreferences.setLastActivityType(type)
        _activityType.value = type
    }

    /**
     * Starts recording a new track with the currently selected activity type.
     *
     * @param name reader-chosen name, or null to name it after the current time.
     */
    fun startRecording(name: String?) {
        LocationRecordingService.start(
            getApplication(),
            name?.takeIf { it.isNotBlank() },
            _activityType.value,
        )
    }

    /** Stops the current recording; an empty track is discarded by the service. */
    fun stopRecording() {
        LocationRecordingService.stop(getApplication())
    }

    // --- Track list / detail actions ---------------------------------------------------

    /**
     * Opens a track on the detail screen.
     *
     * @param trackId the track to open.
     */
    fun openTrack(trackId: Long) {
        _selectedTrackId.value = trackId
    }

    /** Closes the detail screen and returns to the list. */
    fun closeTrack() {
        _selectedTrackId.value = null
    }

    /**
     * Renames a track.
     *
     * @param trackId the track to rename.
     * @param newName the new name; ignored when blank.
     */
    fun renameTrack(trackId: Long, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { repository.renameTrack(trackId, newName.trim()) }
    }

    /**
     * Deletes a track and returns to the list if it was open.
     *
     * @param trackId the track to delete.
     */
    fun deleteTrack(trackId: Long) {
        viewModelScope.launch { repository.deleteTrack(trackId) }
        if (_selectedTrackId.value == trackId) closeTrack()
    }

    /**
     * Prepares the open track for export in the given format and raises [pendingExport] so the
     * activity can open the document picker.
     *
     * @param format the target file format.
     */
    fun requestExport(format: ExportFormat) {
        val track = selectedTrack.value ?: return
        viewModelScope.launch {
            val points = repository.pointsOnce(track.id)
            _pendingExport.value = PendingExport(
                fileName = TrackExporter.suggestedFileName(format, track),
                mimeType = format.mimeType,
                content = TrackExporter.export(format, track, points),
            )
        }
    }

    /** Clears [pendingExport] once the file has been written or the picker was cancelled. */
    fun exportHandled() {
        _pendingExport.value = null
    }

    /**
     * Reclassifies a track's activity type.
     *
     * @param trackId the track to change.
     * @param type the new type.
     */
    fun setTrackActivityType(trackId: Long, type: ActivityType) {
        viewModelScope.launch { repository.setActivityType(trackId, type) }
    }

    /**
     * Imports the tracks in a GPX file.
     *
     * @param content the file bytes as read from the picker.
     * @param fallbackName name to use when the file carries none (usually the file name).
     */
    fun importGpx(content: ByteArray, fallbackName: String) {
        viewModelScope.launch {
            val imported = GpxImporter.parse(content.inputStream())
            if (imported == null) {
                _userMessage.value = "That file is not a readable GPX track"
                return@launch
            }
            repository.importTrack(
                name = imported.name ?: fallbackName,
                activityType = _activityType.value,
                points = imported.points,
            )
            _userMessage.value = "Imported ${imported.points.size} points"
        }
    }

    /**
     * Prepares a backup of the whole database and raises [pendingExport] so the activity can
     * open the document picker.
     */
    fun requestBackup() {
        viewModelScope.launch {
            val tracks = repository.allTracksOnce().filter { !it.isRecording }
            val entries = tracks.map { BackupTrack(it, repository.pointsOnce(it.id)) }
            val date = java.time.LocalDate.now()
            _pendingExport.value = PendingExport(
                fileName = "gpstrack-backup-%04d%02d%02d.json".format(
                    date.year, date.monthValue, date.dayOfMonth,
                ),
                mimeType = "application/json",
                content = BackupCodec.encode(entries),
            )
        }
    }

    /**
     * Restores tracks from a backup file, skipping tracks that already exist (same name and
     * start time).
     *
     * @param content the file text as read from the picker.
     */
    fun restoreBackup(content: String) {
        viewModelScope.launch {
            val entries = BackupCodec.decode(content)
            if (entries == null) {
                _userMessage.value = "That file is not a GPS Track backup"
                return@launch
            }
            // importTrack stores the first fix's time as the start, so that is the stable
            // identity a restored track keeps across repeated restores.
            val existing = repository.allTracksOnce()
                .map { it.name to it.startedAtMillis }
                .toHashSet()
            var imported = 0
            entries.forEach { entry ->
                val key = entry.track.name to entry.points.first().timestampMillis
                if (key in existing) return@forEach
                repository.importTrack(
                    name = entry.track.name,
                    activityType = entry.track.activity,
                    points = entry.points,
                )
                imported++
            }
            _userMessage.value = when {
                imported == 0 -> "Nothing to restore — all ${entries.size} tracks already present"
                imported < entries.size -> "Restored $imported tracks (${entries.size - imported} already present)"
                else -> "Restored $imported tracks"
            }
        }
    }

    /** Clears [userMessage] once it has been shown. */
    fun userMessageShown() {
        _userMessage.value = null
    }

    // --- Sharing actions --------------------------------------------------------------

    /**
     * Turns the position broadcast on or off and updates the sharing service.
     *
     * @param enabled true to publish this device's position.
     */
    fun setBroadcasting(enabled: Boolean) {
        sharePreferences.setBroadcasting(enabled)
        _broadcasting.value = enabled
        refreshSharingService()
    }

    /**
     * Sets the name peers see for this device.
     *
     * @param name the new name.
     */
    fun setDisplayName(name: String) {
        sharePreferences.setDisplayName(name)
        _displayName.value = sharePreferences.displayName()
    }

    /**
     * Sets the sharing server base URL.
     *
     * @param url the new URL.
     */
    fun setServerUrl(url: String) {
        sharePreferences.setServerUrl(url)
        _serverUrl.value = sharePreferences.serverUrl()
        refreshSharingService()
    }

    /**
     * Adds a peer from a scanned QR payload.
     *
     * @param scannedText the raw text the scanner returned.
     * @return true when the text was a valid code and the peer was added.
     */
    fun addScannedPeer(scannedText: String): Boolean {
        val peer = ShareCodec.decode(scannedText) ?: return false
        peersStore.addOrUpdatePeer(peer.id, peer.label, instanceId)
        _peers.value = peersStore.peers()
        refreshSharingService()
        return true
    }

    /**
     * Shows or hides a peer on the map.
     *
     * @param peerId the peer to change.
     * @param visible true to show them and resume fetching their position.
     */
    fun setPeerVisible(peerId: String, visible: Boolean) {
        peersStore.setVisible(peerId, visible)
        _peers.value = peersStore.peers()
        refreshSharingService()
    }

    /**
     * Forgets a peer.
     *
     * @param peerId the peer to remove.
     */
    fun removePeer(peerId: String) {
        peersStore.removePeer(peerId)
        _peers.value = peersStore.peers()
        refreshSharingService()
    }

    /**
     * Allows or denies a web user's request to follow this device. The choice is queued and
     * sent to the server on the next sync.
     *
     * @param followId the server-side follow id from the request.
     * @param approve true to allow, false to deny.
     */
    fun decideFollowRequest(followId: Long, approve: Boolean) {
        FollowRequestDirectory.decide(followId, approve)
        LocationShareService.sync(getApplication())
    }

    /**
     * Starts, refreshes or stops [LocationShareService] to match the current settings: it runs
     * while broadcasting is on or at least one peer is visible, and is stopped otherwise.
     */
    private fun refreshSharingService() {
        val shouldRun = _broadcasting.value || peersStore.watchedIds().isNotEmpty()
        if (shouldRun) {
            LocationShareService.sync(getApplication())
        } else {
            LocationShareService.stop(getApplication())
        }
    }
}

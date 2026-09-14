package io.github.sennheiser1986.gpstrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sennheiser1986.gpstrack.record.LocationRecordingService
import io.github.sennheiser1986.gpstrack.share.ShareCodec
import io.github.sennheiser1986.gpstrack.ui.ManualScreen
import io.github.sennheiser1986.gpstrack.ui.MapScreen
import io.github.sennheiser1986.gpstrack.ui.RecordScreen
import io.github.sennheiser1986.gpstrack.ui.ShareScreen
import io.github.sennheiser1986.gpstrack.ui.TrackDetailScreen
import io.github.sennheiser1986.gpstrack.ui.TrackRecorderTheme
import io.github.sennheiser1986.gpstrack.ui.TracksScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

/**
 * Reports whether the app is already excluded from battery optimisation.
 *
 * @param context any context.
 * @return true when the system will not doze-kill the app's services.
 */
private fun isBatteryExempt(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.content.Context.POWER_SERVICE)
        as? android.os.PowerManager ?: return true
    return manager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Launches the first intent in [intents] that opens without throwing, or toasts when none
 * does — a button must never silently do nothing. (Resolution is not pre-checked: package
 * visibility filtering makes ``resolveActivity`` unreliable for Settings screens.)
 *
 * @param context an activity context.
 * @param intents candidate intents, most specific first.
 */
private fun startFirstThatOpens(context: android.content.Context, vararg intents: Intent) {
    for (intent in intents) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    Toast.makeText(context, "Could not open the settings screen on this device", Toast.LENGTH_LONG).show()
}

/**
 * Opens the screen for excluding the app from battery optimisation. Samsung's One UI silently
 * drops the standard direct dialog, and its real control lives on the app's own settings page
 * (Battery → Unrestricted), so Samsung goes straight there; everyone else gets the system
 * dialog with the list screen and the app page as fallbacks.
 *
 * @param context an activity context.
 */
private fun requestBatteryExemption(context: android.content.Context) {
    val appDetails = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        startFirstThatOpens(
            context,
            appDetails,
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        return
    }
    startFirstThatOpens(
        context,
        Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ),
        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails,
    )
}

/**
 * Opens the app's system settings page, from which Permissions → Location → "Allow all the
 * time" is reachable. A permission *request* is useless here: once background location has
 * been denied (or "While using the app" chosen), Android turns further requests into silent
 * no-ops, so the settings page is the only reliable route.
 *
 * @param context an activity context.
 */
private fun openAppSettings(context: android.content.Context) {
    startFirstThatOpens(
        context,
        Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
}

/**
 * Reports whether "Allow all the time" location access is held. Below Android 10 the concept
 * does not exist and foreground permission suffices.
 *
 * @param context any context.
 * @return true when background location is granted (or not a thing on this OS).
 */
private fun hasBackgroundLocation(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

/** The tabs the app is divided into. */
private enum class AppTab(
    /** Wording shown under the tab icon. */
    val label: String,
    /** Icon shown in the navigation bar. */
    val icon: ImageVector,
) {
    RECORD("Record", Icons.Filled.FiberManualRecord),
    TRACKS("Tracks", Icons.Filled.Route),
    MAP("Map", Icons.Filled.Map),
    SHARE("Share", Icons.Filled.Share),
    MANUAL("Manual", Icons.Filled.Info),
}

/** Single activity that hosts every screen. */
class MainActivity : ComponentActivity() {

    /** The most recent `gpstrack://peer?...` link the activity was opened with, if any. */
    private val pendingPeerLink = mutableStateOf<String?>(null)

    /** A tab the activity was asked to open, set from an [EXTRA_OPEN_TAB] intent extra. */
    private val pendingTab = mutableStateOf<AppTab?>(null)

    /**
     * Installs the Compose interface and picks up a sharing link the activity was launched with.
     *
     * @param savedInstanceState previously saved state, or null on a cold start.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPeerLink.value = peerLinkFrom(intent)
        pendingTab.value = tabFrom(intent)
        setContent {
            TrackRecorderTheme {
                TrackRecorderRoot(
                    pendingPeerLink = pendingPeerLink.value,
                    onPeerLinkHandled = { pendingPeerLink.value = null },
                    pendingTab = pendingTab.value,
                    onTabHandled = { pendingTab.value = null },
                )
            }
        }
    }

    /**
     * Picks up a sharing link when the activity is already running (launchMode singleTop).
     *
     * @param intent the new intent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        peerLinkFrom(intent)?.let { pendingPeerLink.value = it }
        tabFrom(intent)?.let { pendingTab.value = it }
    }

    /**
     * Reads a requested tab from an [EXTRA_OPEN_TAB] extra (an [AppTab] name).
     *
     * @param intent the intent to inspect, or null.
     * @return the tab, or null when the extra is absent or unrecognised.
     */
    private fun tabFrom(intent: Intent?): AppTab? {
        val name = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: return null
        return AppTab.entries.firstOrNull { it.name == name }
    }

    companion object {
        /** Intent extra carrying an [AppTab] name to open the app on. */
        const val EXTRA_OPEN_TAB = "io.github.sennheiser1986.gpstrack.OPEN_TAB"

        /** [EXTRA_OPEN_TAB] value for the Share tab. */
        const val TAB_SHARE = "SHARE"
    }

    /**
     * Extracts a `gpstrack://peer?...` link from a VIEW intent.
     *
     * @param intent the intent to inspect, or null.
     * @return the link as a string, or null when the intent carries none.
     */
    private fun peerLinkFrom(intent: Intent?): String? {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        return if (data.scheme == "gpstrack" && data.host == "peer") data.toString() else null
    }
}

/**
 * The whole interface: a title bar, a bottom navigation bar and the five tab screens, wired to
 * [RecorderViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackRecorderRoot(
    pendingPeerLink: String? = null,
    onPeerLinkHandled: () -> Unit = {},
    pendingTab: AppTab? = null,
    onTabHandled: () -> Unit = {},
) {
    val viewModel: RecorderViewModel = viewModel()
    val context = LocalContext.current

    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val selectedTrackId by viewModel.selectedTrackId.collectAsStateWithLifecycle()
    val selectedTrack by viewModel.selectedTrack.collectAsStateWithLifecycle()
    val selectedTrackPoints by viewModel.selectedTrackPoints.collectAsStateWithLifecycle()
    val selectedTrackStatistics by viewModel.selectedTrackStatistics.collectAsStateWithLifecycle()
    val pendingExport by viewModel.pendingExport.collectAsStateWithLifecycle()

    val activeTrackId by viewModel.activeTrackId.collectAsStateWithLifecycle()
    val liveTrail by viewModel.liveTrail.collectAsStateWithLifecycle()
    val liveDistanceMeters by viewModel.liveDistanceMeters.collectAsStateWithLifecycle()
    val liveMovingMillis by viewModel.liveMovingMillis.collectAsStateWithLifecycle()
    val recordingStartedAtMillis by viewModel.recordingStartedAtMillis.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val activityType by viewModel.activityType.collectAsStateWithLifecycle()

    val broadcasting by viewModel.broadcasting.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val peerLocations by viewModel.peerLocations.collectAsStateWithLifecycle()
    val peerOwners by viewModel.peerOwners.collectAsStateWithLifecycle()
    val followRequests by viewModel.followRequests.collectAsStateWithLifecycle()
    val accountName by viewModel.accountName.collectAsStateWithLifecycle()
    val authRequired by viewModel.authRequired.collectAsStateWithLifecycle()
    val signingIn by viewModel.signingIn.collectAsStateWithLifecycle()
    val webFollowers by viewModel.webFollowers.collectAsStateWithLifecycle()
    val qrPayload by viewModel.qrPayload.collectAsStateWithLifecycle()
    val offlineRegions by viewModel.offlineRegions.collectAsStateWithLifecycle()
    val offlineDownloads by viewModel.offlineDownloads.collectAsStateWithLifecycle()
    val offlineMapVersion by viewModel.offlineMapVersion.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.RECORD) }

    // A coarse clock so "last seen" labels tick without redrawing everything every frame.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(10_000)
        }
    }

    // Foreground permissions the app needs before recording or broadcasting is useful.
    val foregroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* The services check permission again themselves; nothing to do here. */ }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        foregroundPermissionLauncher.launch(wanted)
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Recording still works in the foreground if this is declined. */ }

    // Add a followed peer when the app was opened from a sharing link.
    LaunchedEffect(pendingPeerLink) {
        val link = pendingPeerLink ?: return@LaunchedEffect
        val added = viewModel.addScannedPeer(link)
        Toast.makeText(
            context,
            if (added) "Peer added from link" else "That link is not a GPS Track code",
            Toast.LENGTH_SHORT,
        ).show()
        if (added) selectedTab = AppTab.MAP
        onPeerLinkHandled()
    }

    // Give the Record and Map maps something to centre on before the first live fix arrives.
    LaunchedEffect(Unit) {
        viewModel.primeLastKnownLocation()
    }

    // Open a tab the app was launched into, e.g. from a follow-request notification.
    LaunchedEffect(pendingTab) {
        val tab = pendingTab ?: return@LaunchedEffect
        selectedTab = tab
        onTabHandled()
    }

    /**
     * Whether foreground location permission is currently held. Recomputed on each recomposition,
     * which covers returning from the system permission dialog.
     */
    fun hasForegroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether the "all the time" background location grant is already held. Below Android 10 the
     * concept does not exist and foreground permission is enough.
     */
    fun hasBackgroundLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    val requestForegroundLocation = {
        foregroundPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    /**
     * Runs a decoded sharing string (id, link, or QR text) through the view model and reports
     * the outcome, jumping to the Map tab when a peer was added.
     *
     * @param payload the text to interpret.
     * @param sourceLabel wording for the failure toast, e.g. "code" or "image".
     */
    fun consumePeerPayload(payload: String?, sourceLabel: String) {
        val added = !payload.isNullOrBlank() && viewModel.addScannedPeer(payload)
        Toast.makeText(
            context,
            if (added) "Peer added" else "No GPS Track $sourceLabel found",
            Toast.LENGTH_SHORT,
        ).show()
        if (added) selectedTab = AppTab.MAP
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { consumePeerPayload(it, "code") }
    }

    // Pick a saved QR image and read a sharing code out of it.
    val pickQrImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bitmap = ShareCodec.readImageForScanning(context, uri)
        consumePeerPayload(bitmap?.let { ShareCodec.decodeQrPayload(it) }, "code in that image")
    }

    // CreateDocument writes the prepared export to wherever the reader points it. The concrete
    // file type is carried by the suggested file name's extension.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val export = pendingExport
        if (uri != null && export != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(export.content.toByteArray())
                }
            }
            Toast.makeText(context, "Saved ${export.fileName}", Toast.LENGTH_SHORT).show()
        }
        viewModel.exportHandled()
    }
    LaunchedEffect(pendingExport) {
        pendingExport?.let { exportLauncher.launch(it.fileName) }
    }

    // Pick a GPX file and import it as a new track.
    val importGpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            Toast.makeText(context, "Could not read that file", Toast.LENGTH_SHORT).show()
        } else {
            val fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.removeSuffix(".gpx")
                ?.takeIf { it.isNotBlank() }
                ?: "Imported track"
            viewModel.importGpx(bytes, fallbackName)
        }
    }

    // Pick a backup file and restore the tracks it holds.
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text == null) {
            Toast.makeText(context, "Could not read that file", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.restoreBackup(text)
        }
    }

    // Toast one-shot results (imports, restores) raised by the view model.
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    LaunchedEffect(userMessage) {
        userMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.userMessageShown()
        }
    }

    // Whether the app is excluded from battery optimisation; refreshed on every resume so
    // returning from the system dialog updates the Manual tab card.
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var backgroundLocationGranted by remember { mutableStateOf(hasBackgroundLocation(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    batteryExempt = isBatteryExempt(context)
                    backgroundLocationGranted = hasBackgroundLocation(context)
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> viewModel.setAppForeground(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> viewModel.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isRecording = activeTrackId != null
    val openTrack = selectedTrack.takeIf { selectedTrackId != null }

    Scaffold(
        topBar = {
            // The tab screens carry no title bar so the map gets the full height; the track
            // detail screen keeps one for its back button.
            if (openTrack != null) {
                TopAppBar(
                    title = { Text("Track") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.closeTrack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (openTrack == null) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)

        if (openTrack != null) {
            Box(contentModifier) {
                TrackDetailScreen(
                    track = openTrack,
                    points = selectedTrackPoints,
                    statistics = selectedTrackStatistics,
                    onRename = { viewModel.renameTrack(openTrack.id, it) },
                    onDelete = { viewModel.deleteTrack(openTrack.id) },
                    onExport = { viewModel.requestExport(it) },
                    onSetActivityType = { viewModel.setTrackActivityType(openTrack.id, it) },
                    offlineMapVersion = offlineMapVersion,
                )
            }
            return@Scaffold
        }

        Box(contentModifier) {
            when (selectedTab) {
                AppTab.RECORD -> RecordScreen(
                    isRecording = isRecording,
                    liveTrail = liveTrail,
                    currentLocation = currentLocation,
                    liveDistanceMeters = liveDistanceMeters,
                    liveMovingMillis = liveMovingMillis,
                    startedAtMillis = recordingStartedAtMillis,
                    activityType = activityType,
                    onActivityTypeChange = { viewModel.setActivityType(it) },
                    onStart = { name ->
                        when {
                            !hasForegroundLocationPermission() -> requestForegroundLocation()
                            // Ask for background location once, so recording survives the app
                            // being dismissed. Recording still starts if it is declined.
                            !hasBackgroundLocationPermission() ->
                                backgroundPermissionLauncher.launch(
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                )
                            else -> Unit
                        }
                        if (hasForegroundLocationPermission()) {
                            viewModel.startRecording(
                                name ?: LocationRecordingService.defaultTrackName(),
                            )
                        }
                    },
                    onStop = { viewModel.stopRecording() },
                    offlineMapVersion = offlineMapVersion,
                )

                AppTab.TRACKS -> TracksScreen(
                    tracks = tracks,
                    onOpen = { viewModel.openTrack(it) },
                    onImportGpx = {
                        importGpxLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
                    },
                )

                AppTab.MAP -> MapScreen(
                    me = currentLocation,
                    peers = peers,
                    peerLocations = peerLocations,
                    peerOwners = peerOwners,
                    nowMillis = nowMillis,
                    onPeerVisibleChange = { id, visible -> viewModel.setPeerVisible(id, visible) },
                    offlineMapVersion = offlineMapVersion,
                )

                AppTab.SHARE -> ShareScreen(
                    instanceId = viewModel.instanceId,
                    qrPayload = qrPayload,
                    displayName = displayName,
                    onDisplayNameChange = { viewModel.setDisplayName(it) },
                    broadcasting = broadcasting,
                    onBroadcastChange = { wantOn ->
                        if (wantOn && !hasForegroundLocationPermission()) {
                            requestForegroundLocation()
                        } else {
                            viewModel.setBroadcasting(wantOn)
                        }
                    },
                    serverUrl = serverUrl,
                    onServerUrlChange = { viewModel.setServerUrl(it) },
                    accountName = accountName,
                    authRequired = authRequired,
                    signingIn = signingIn,
                    onSignIn = { username, password -> viewModel.serverSignIn(username, password) },
                    onSignOut = { viewModel.serverSignOut() },
                    peers = peers,
                    peerLocations = peerLocations,
                    nowMillis = nowMillis,
                    followRequests = followRequests,
                    webFollowers = webFollowers,
                    onDecideFollow = { id, approve -> viewModel.decideFollowRequest(id, approve) },
                    onScan = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setBeepEnabled(false)
                                .setPrompt("Scan a GPS Track sharing code"),
                        )
                    },
                    onScanImage = {
                        pickQrImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onAddById = { consumePeerPayload(it, "ID") },
                    onPeerVisibleChange = { id, visible -> viewModel.setPeerVisible(id, visible) },
                    onRemovePeer = { viewModel.removePeer(it) },
                )

                AppTab.MANUAL -> ManualScreen(
                    offlineRegions = offlineRegions,
                    offlineDownloads = offlineDownloads,
                    catalog = catalog,
                    onOpenCatalog = { viewModel.openCatalog(it) },
                    onCloseCatalog = { viewModel.closeCatalog() },
                    onDownloadRegion = { viewModel.downloadRegion(it) },
                    onCancelDownload = { viewModel.cancelRegionDownload(it) },
                    onDeleteRegion = { viewModel.deleteRegion(it) },
                    batteryExempt = batteryExempt,
                    onRequestBatteryExemption = { requestBatteryExemption(context) },
                    backgroundLocationGranted = backgroundLocationGranted,
                    onRequestBackgroundLocation = { openAppSettings(context) },
                    onBackup = { viewModel.requestBackup() },
                    onRestore = { restoreBackupLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                )
            }
        }
    }
}

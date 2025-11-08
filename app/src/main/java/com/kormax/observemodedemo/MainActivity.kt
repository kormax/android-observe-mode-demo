package com.kormax.observemodedemo

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.nfc.NfcAdapter
import android.nfc.NfcAdapter.FLAG_LISTEN_KEEP
import android.nfc.NfcAdapter.FLAG_READER_DISABLE
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kormax.observemodedemo.ui.theme.ObserveModeDemoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val TAG = this::class.java.simpleName

    private var errors: List<String> = listOf()
    private var observeModeEnabledState = mutableStateOf(false)
    private val component =
        ComponentName(
            "com.kormax.observemodedemo",
            "com.kormax.observemodedemo.ObserveModeHostApduService",
        )
    private val sortThreshold = 16
    private val sampleThreshold = 64
    private val wrapThreshold = 1_000_000L * 3 // 3 seconds
    private val exportFileTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    private enum class ExportShareMode {
        TEXT,
        FILE,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = this

        setContent {
            EnforceScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()

            var errors: List<String> by remember { mutableStateOf(this@MainActivity.errors) }

            var loopEvents: List<PollingLoopEvent> by remember { mutableStateOf(listOf()) }

            var currentLoop: List<Loop> by remember { mutableStateOf(listOf()) }

            var observeModeEnabled by remember { mutableStateOf(false) }

            // Derive compose state from activity's mutable state - ensures recomposition
            // whenever the activity state changes (from initializeNfc or button toggle)
            val observeModeEnabledDerived by remember {
                derivedStateOf { this@MainActivity.observeModeEnabledState.value }
            }

            var exportMenuExpanded by remember { mutableStateOf(false) }
            var modeMenuExpanded by remember { mutableStateOf(false) }
            var currentMode: DisplayMode by remember { mutableStateOf(DisplayMode.LOOP) }

            LaunchedEffect(loopEvents.isEmpty()) {
                if (loopEvents.isEmpty()) {
                    exportMenuExpanded = false
                }
            }

            fun sharePollingData(mode: ExportShareMode) {
                exportMenuExpanded = false
                val eventsSnapshot = loopEvents.toList()
                if (eventsSnapshot.isEmpty()) {
                    return
                }
                scope.launch {
                    val payloadResult =
                        withContext(Dispatchers.Default) {
                            runCatching {
                                buildPollingExportPayload(
                                    appContext,
                                    eventsSnapshot,
                                )
                            }
                        }
                    val payload = payloadResult.getOrNull()
                    if (payload == null) {
                        Log.e(
                            TAG,
                            "Unable to export polling data",
                            payloadResult.exceptionOrNull(),
                        )
                        snackbarHostState.showSnackbar(
                            appContext.getString(R.string.export_share_failure)
                        )
                        return@launch
                    }

                    val shareTitle = appContext.getString(R.string.export_share_title)

                    when (mode) {
                        ExportShareMode.TEXT -> {
                            val chooser =
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, payload)
                                        putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                                        putExtra(Intent.EXTRA_TITLE, shareTitle)
                                    },
                                    shareTitle,
                                )

                            try {
                                appContext.startActivity(chooser)
                            } catch (error: ActivityNotFoundException) {
                                Log.w(
                                    TAG,
                                    "No handler available for export",
                                    error,
                                )
                                snackbarHostState.showSnackbar(
                                    appContext.getString(R.string.export_share_unavailable)
                                )
                            }
                        }

                        ExportShareMode.FILE -> {
                            val fileUriResult =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val exportsDir =
                                            File(appContext.cacheDir, "exports").apply {
                                                if (!exists()) {
                                                    mkdirs()
                                                }
                                            }
                                        val timestamp =
                                            LocalDateTime.now()
                                                .format(exportFileTimestampFormatter)
                                        val exportFile =
                                            File(
                                                exportsDir,
                                                "observe-mode-export-$timestamp.json",
                                            )
                                        exportFile.writeText(payload)
                                        FileProvider.getUriForFile(
                                            appContext,
                                            "${appContext.packageName}.fileprovider",
                                            exportFile,
                                        )
                                    }
                                }
                            val fileUri = fileUriResult.getOrNull()
                            if (fileUri == null) {
                                Log.e(
                                    TAG,
                                    "Unable to write export file",
                                    fileUriResult.exceptionOrNull(),
                                )
                                snackbarHostState.showSnackbar(
                                    appContext.getString(R.string.export_share_failure)
                                )
                                return@launch
                            }

                            val chooser =
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, fileUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                                        putExtra(Intent.EXTRA_TITLE, shareTitle)
                                    },
                                    shareTitle,
                                )

                            try {
                                appContext.startActivity(chooser)
                            } catch (error: ActivityNotFoundException) {
                                Log.w(
                                    TAG,
                                    "No handler available for export file",
                                    error,
                                )
                                snackbarHostState.showSnackbar(
                                    appContext.getString(R.string.export_share_unavailable)
                                )
                            }
                        }
                    }
                }
            }

            SystemBroadcastReceiver(Constants.POLLING_LOOP_EVENT_ACTION) { intent ->
                val event =
                    intent?.getParcelableExtra<PollingFrameNotification>(
                        Constants.POLLING_LOOP_EVENT_DATA_KEY
                    ) ?: return@SystemBroadcastReceiver

                loopEvents += event.frames.map { PollingLoopEvent(it, -1, event.at) }

                val toSort = loopEvents.takeLast(maxOf(sortThreshold, event.frames.size) + 1)

                // HostEmulation manager may deliver "Expeditable" frame types (F, U) in front of
                // all of the other frames regardless of the original order.
                // Because of that, we have to re-sort the received loop events back after the fact.
                // What complicates the task, is that the timestamp value may wrap back
                // due to specifics of internal timer implementation on the NFC controller
                // To handle that, we can sample N last frames, find out which ones were not wrapped
                // by checking that they are not M seconds earlier than the first frame, and split
                // frames into wrapped and not-wrapped after the fact
                // Inside of those two groups, we can restore the order by sorting via timestamps.
                // For the resulting events, re-calculate the delta from the previous event,
                // and replace last N events with the updated ones
                if (toSort.isNotEmpty()) {
                    val first = toSort.first()

                    val (wrapped, notWrapped) =
                        toSort.drop(1).partition { it.timestamp < first.timestamp - wrapThreshold }

                    if (wrapped.isNotEmpty()) {
                        Log.i(
                            TAG,
                            "Loop wrapped" +
                                " from ${notWrapped.size} ${
                                        mapPollingLoopEventsToString(
                                            notWrapped
                                        )
                                    }" +
                                " to ${wrapped.size} ${mapPollingLoopEventsToString(wrapped)}",
                        )
                    }

                    val sorted =
                        (notWrapped.sortedBy { it.timestamp } + wrapped.sortedBy { it.timestamp })

                    // Update deltas for sorted elements
                    var previousTimestamp = first.timestamp
                    val updated =
                        sorted
                            .mapIndexed { index, element ->
                                val delta = (element.timestamp - previousTimestamp).coerceAtLeast(0)
                                previousTimestamp = element.timestamp
                                element.withDelta(delta)
                            }
                            .toMutableList()

                    loopEvents = loopEvents.dropLast(updated.size) + updated
                }

                errors = emptyList()

                // Sample 64 last frames.
                val sample = loopEvents.takeLast(sampleThreshold).toTypedArray()

                // Find a sequence that repeats at least two times back-to-back
                val unalignedLoop =
                    largestRepeatingSequence(
                        sample,
                        { it1, it2 -> it1.type == it2.type && it1.data.contentEquals(it2.data) },
                    )
                // Attempt to align polling loop sequence based on general assumptions about polling
                // loops
                val loop = alignPollingLoop(unalignedLoop)

                if (loop.isNotEmpty()) {
                    currentLoop = mapPollingEventsToLoopActivity(loop)
                }
            }

            ObserveModeDemoTheme {
                Scaffold(
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            colors =
                                TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    titleContentColor = MaterialTheme.colorScheme.primary,
                                ),
                            title = { Text("NFC Observer") },
                            actions = {
                                Box {
                                    IconButton(
                                        enabled = loopEvents.isNotEmpty(),
                                        onClick = { exportMenuExpanded = true },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Share,
                                            contentDescription =
                                                appContext.getString(
                                                    R.string.export_share_content_description
                                                ),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = exportMenuExpanded && loopEvents.isNotEmpty(),
                                        onDismissRequest = { exportMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    appContext.getString(
                                                        R.string.export_share_text_option
                                                    )
                                                )
                                            },
                                            onClick = { sharePollingData(ExportShareMode.TEXT) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.Share,
                                                    contentDescription = null,
                                                )
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    appContext.getString(
                                                        R.string.export_share_file_option
                                                    )
                                                )
                                            },
                                            onClick = { sharePollingData(ExportShareMode.FILE) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.Email,
                                                    contentDescription = null,
                                                )
                                            },
                                        )
                                    }
                                }
                                Box {
                                    IconButton(onClick = { modeMenuExpanded = true }) {
                                        Icon(
                                            imageVector =
                                                if (modeMenuExpanded) Icons.Outlined.CheckCircle
                                                else Icons.Outlined.Info,
                                            contentDescription = "Toggle detailed info",
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = modeMenuExpanded,
                                        onDismissRequest = { modeMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            { Text("History") },
                                            onClick = {
                                                currentMode = DisplayMode.HISTORY
                                                modeMenuExpanded = false
                                            },
                                            trailingIcon = {
                                                if (currentMode == DisplayMode.HISTORY) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.CheckCircle,
                                                        contentDescription = "Activated",
                                                    )
                                                }
                                            },
                                        )

                                        Divider()

                                        DropdownMenuItem(
                                            { Text("Loop") },
                                            onClick = {
                                                currentMode = DisplayMode.LOOP
                                                modeMenuExpanded = false
                                            },
                                            trailingIcon = {
                                                if (currentMode == DisplayMode.LOOP) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.CheckCircle,
                                                        contentDescription = "Activated",
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                                IconButton(onClick = { loopEvents = listOf() }) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Clear polling frame history",
                                    )
                                }
                            },
                        )
                    },
                ) { innerPadding ->
                    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        Column(modifier = Modifier.weight(8f).fillMaxWidth()) {
                            if (errors.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    for (error in errors) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Warning,
                                                contentDescription = "Warning",
                                            )
                                            Text(text = error)
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    userScrollEnabled = true,
                                ) {
                                    when (currentMode) {
                                        DisplayMode.LOOP ->
                                            items(
                                                currentLoop.size,
                                                {
                                                    "${loopEvents.getOrNull(it)?.at}:${
                                                    loopEvents.getOrNull(
                                                        it
                                                    )?.timestamp
                                                }:${it}"
                                                },
                                            ) { index ->
                                                val loop =
                                                    currentLoop.getOrNull(index) ?: return@items
                                                PollingLoopItem(loop = loop)
                                            }

                                        DisplayMode.HISTORY ->
                                            items(
                                                loopEvents.size,
                                                {
                                                    "${loopEvents.getOrNull(it)?.at}:${
                                                    loopEvents.getOrNull(
                                                        it
                                                    )?.timestamp
                                                }:${it}"
                                                },
                                            ) { index ->
                                                val event =
                                                    loopEvents.getOrNull(index) ?: return@items
                                                PollingEventItem(
                                                    event = event,
                                                    display = "timestamp",
                                                )
                                            }
                                    }
                                }
                            }
                        }
                        Divider()
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ) {
                            Text(
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                text = if (observeModeEnabledDerived)
                                    "Observe Mode: ON"
                                else
                                    "Observe Mode: OFF",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        Column(
                            modifier =
                                Modifier.weight(1f)
                                    .fillMaxWidth()
                                    .background(color = Color.Transparent),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val nfcAdapter: NfcAdapter? =
                                            try {
                                                NfcAdapter.getDefaultAdapter(appContext)
                                            } catch (_: Exception) {
                                                null
                                            }

                                        if (nfcAdapter == null) {
                                            return@launch
                                        }

                                        val enabled = nfcAdapter.isObserveModeEnabled

                                        val success = nfcAdapter.setObserveModeEnabled(!enabled)

                                        if (success) {
                                            observeModeEnabled = !enabled
                                            this@MainActivity.observeModeEnabledState.value = !enabled
                                        }

                                        snackbarHostState.showSnackbar(
                                            "Observe mode " +
                                                (if (!enabled) "enabled" else "disabled") +
                                                " " +
                                                (if (success) "successfully" else "unsuccessfully")
                                        )
                                    }
                                }
                            ) {
                                Text("Toggle Observe Mode")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            initializeNfc()
        }
    }

    private suspend fun initializeNfc() {
        errors = mutableListOf()

        val nfcAdapter: NfcAdapter? =
            try {
                NfcAdapter.getDefaultAdapter(this@MainActivity)
            } catch (_: Exception) {
                null
            }

        if (nfcAdapter == null) {
            errors += "Unable to get NfcAdapter"
            return
        } else if (!nfcAdapter.isEnabled) {
            errors += "NFC is disabled"
            return
        }

        val cardEmulation =
            try {
                CardEmulation.getInstance(nfcAdapter)
            } catch (_: Exception) {
                Log.e(TAG, "CardEmulation is unavailable")
                null
            }

        if (cardEmulation == null) {
            errors += "Unable to get CardEmulation"
            return
        }

        if (!cardEmulation.setPreferredService(this@MainActivity, component)) {
            errors += "Unable to set preferred service"
        }

        try {
            var observeModeSetSuccess = false
            val observeModeEnabledBefore = nfcAdapter.isObserveModeEnabled
            if (!observeModeEnabledBefore) {
                // Attempt up to 1 second after resume
                repeat(10) {
                    if (nfcAdapter.setObserveModeEnabled(true)) {
                        observeModeSetSuccess = true
                        return@repeat
                    }
                    delay(100)
                }
                if (!observeModeSetSuccess) {
                    errors += "Unable to enable Observe Mode"
                }
            }

            this@MainActivity.observeModeEnabledState.value = observeModeSetSuccess || nfcAdapter.isObserveModeEnabled

            if (!cardEmulation.removeAidsForService(component, "payment")) {
                errors += "Unable to remove AID for service"
            }

            if (
                !cardEmulation.registerAidsForService(
                    component,
                    "payment",
                    listOf("A0C0FFEEC0FFEE"),
                )
            ) {
                errors += "Unable to register AID for service"
            }

            try {
                nfcAdapter.setDiscoveryTechnology(this@MainActivity, FLAG_READER_DISABLE, FLAG_LISTEN_KEEP)
            } catch (_: Exception) {
                errors += "Unable to set discovery technology"
            }
        } catch (e: Exception) {
            errors += "${e}"
        }
    }
}

@Composable
fun PollingLoopItem(loop: Loop) {
    return Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = mapDeltaToTimeText(loop.startDelta)
            // fontSize = 12.sp
        )

        ElevatedCard(
            colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (event in loop.events) {
                    // Decide if we want to display delta values between frame blocks or in them
                    /*Text(
                        text = mapTimestampToTimeText(event.delta),
                        fontSize = 12.sp
                    )*/
                    PollingEventItem(event = event, display = "delta")
                }
                Text(
                    text = mapDeltaToTimeText(loop.endDelta)
                    // fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun PollingEventItem(event: PollingLoopEvent, display: String = "delta") {
    val (typeName, color) = mapPollingFrameTypeToNameAndColor(event.type)
    val dataGainDisplayed = typeName !in Constants.POLLING_FRAME_TYPES_WITHOUT_GAIN_AND_DATA

    val (type, delta, gain) =
        Triple(
            typeName,
            when (display) {
                "delta" -> mapDeltaToTimeText(event.delta)
                "timestamp" -> event.timestamp.toString()
                else -> ""
            },
            mapVendorSpecificGainToPowerPercentage(event.vendorSpecificGain),
        )

    ElevatedCard(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceVariant
                        .copy(alpha = 0.8f)
                        .compositeOver(color.copy(alpha = 0.2f))
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier =
            Modifier.fillMaxWidth()
                .wrapContentHeight()
                .border(
                    width = 1.dp,
                    color = color.copy(alpha = 0.8f),
                    shape = MaterialTheme.shapes.medium,
                ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().width(IntrinsicSize.Max)) {
            Row(
                modifier =
                    Modifier.padding(
                            bottom = if (dataGainDisplayed) 4.dp else 10.dp,
                            top = 10.dp,
                            start = 10.dp,
                            end = 10.dp,
                        )
                        .fillMaxWidth()
            ) {
                Text(text = type, modifier = Modifier.width(32.dp))

                Text(text = event.name)
                if (display != "") {
                    Text(
                        textAlign = TextAlign.End,
                        text = delta,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (dataGainDisplayed) {
                Divider(modifier = Modifier.fillMaxWidth(), color = color.copy(alpha = 0.6f))
                Row(
                    modifier =
                        Modifier.padding(top = 4.dp, bottom = 10.dp, start = 10.dp, end = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(modifier = Modifier.weight(1f), text = event.data.toHexString())
                    Text(text = gain)
                }
            }
        }
    }
}

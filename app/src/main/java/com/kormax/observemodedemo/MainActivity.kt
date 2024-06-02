package com.kormax.observemodedemo


import android.content.ComponentName
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
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kormax.observemodedemo.ui.theme.ObserveModeDemoTheme
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    private val TAG = this::class.java.simpleName

    private var errors: List<String> = listOf()
    private val component = ComponentName(
        "com.kormax.observemodedemo", "com.kormax.observemodedemo.ObserveModeHostApduService"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = this


        setContent {
            EnforceScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()

            var errors: List<String> by remember {
                mutableStateOf(
                    this.errors
                )
            }

            var loopEvents: List<PollingLoopEvent> by remember {
                mutableStateOf(
                    listOf()
                )
            }

            var currentLoop: List<Loop> by remember {
                mutableStateOf(listOf())
            }

            var eventsSinceLastUpdate: Int by remember {
                mutableStateOf(Int.MAX_VALUE)
            }

            var modeMenuExpanded by remember { mutableStateOf(false) }
            var currentMode: DisplayMode by remember { mutableStateOf(DisplayMode.LOOP) }

            SystemBroadcastReceiver(Constants.POLLING_LOOP_EVENT_ACTION) { intent ->
                val event =
                    intent?.getParcelableExtra<PollingLoopEvent>(Constants.POLLING_LOOP_EVENT_DATA_KEY)
                        ?: return@SystemBroadcastReceiver;
                Log.i(TAG, "Adding new polling frame")
                loopEvents += event
                errors = emptyList()
                // We update loop info only if more than two events were received, or if a field off event occured
                if (event.type == PollingLoopEvent.OFF || eventsSinceLastUpdate > 2) {
                    eventsSinceLastUpdate = 0
                    val rawPollingFrames = loopEvents.takeLast(48).toTypedArray()

                    val unalignedLoop = largestRepeatingSequence(
                        rawPollingFrames,
                        { it1, it2 -> it1.type == it2.type && it1.data.contentEquals(it2.data) },
                    )
                    val loop = alignPollingLoop(unalignedLoop)

                    if (loop.isNotEmpty()) {
                        currentLoop = mapPollingEventsToLoopActivity(loop)
                    }
                } else {
                    eventsSinceLastUpdate += 1
                }
            }

            ObserveModeDemoTheme {
                Scaffold(snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState)
                }, topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.primary,
                        ),
                        title = {
                            Text("NFC Observer")
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { modeMenuExpanded = true }) {
                                    Icon(
                                        imageVector = if (modeMenuExpanded) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                                        contentDescription = "Toggle detailed info"
                                    )
                                }
                                DropdownMenu(expanded = modeMenuExpanded,
                                    onDismissRequest = { modeMenuExpanded = false }) {
                                    DropdownMenuItem({ Text("History") }, onClick = {
                                        currentMode = DisplayMode.HISTORY
                                        modeMenuExpanded = false
                                    }, trailingIcon = {
                                        if (currentMode == DisplayMode.HISTORY) {
                                            Icon(
                                                imageVector = Icons.Outlined.CheckCircle,
                                                contentDescription = "Activated"
                                            )
                                        }
                                    })

                                    Divider()

                                    DropdownMenuItem({ Text("Loop") }, onClick = {
                                        currentMode = DisplayMode.LOOP
                                        modeMenuExpanded = false
                                    }, trailingIcon = {
                                        if (currentMode == DisplayMode.LOOP) {
                                            Icon(
                                                imageVector = Icons.Outlined.CheckCircle,
                                                contentDescription = "Activated"
                                            )
                                        }
                                    })
                                }
                            }
                            IconButton(onClick = {
                                loopEvents = listOf()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Clear polling frame history"
                                )
                            }
                        },
                    )
                }) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(8f)
                                .fillMaxWidth()
                        ) {
                            if (errors.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    for (error in errors) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Warning,
                                                contentDescription = "Warning"
                                            )
                                            Text(
                                                text = error
                                            )
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    userScrollEnabled = true
                                ) {

                                    when (currentMode) {
                                        DisplayMode.LOOP -> items(currentLoop.size,
                                            { "${loopEvents.getOrNull(it)?.timestamp?.nano}:${it}" }) { index ->
                                            val loop = currentLoop.getOrNull(index) ?: return@items
                                            PollingLoopItem(
                                                loop = loop
                                            )
                                        }

                                        DisplayMode.HISTORY -> items(loopEvents.size,
                                            { "${loopEvents.getOrNull(it)?.timestamp?.nano}" }) { index ->
                                            val event = loopEvents.getOrNull(index) ?: return@items
                                            PollingEventItem(
                                                event = event
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Divider()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(color = Color.Transparent),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(onClick = {

                                scope.launch {
                                    val nfcAdapter: NfcAdapter? = try {
                                        NfcAdapter.getDefaultAdapter(appContext)
                                    } catch (_: Exception) {
                                        null
                                    }

                                    if (nfcAdapter == null) {
                                        return@launch
                                    }

                                    val enabled = nfcAdapter.isObserveModeEnabled

                                    val success = nfcAdapter.setObserveModeEnabled(!enabled)

                                    snackbarHostState.showSnackbar(
                                        "Observe mode " + (if (!enabled) "enabled" else "disabled") + " " + (if (success) "successfully" else "unsuccessfully")
                                    )
                                }
                            }) {
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

        errors = mutableListOf()

        val nfcAdapter: NfcAdapter? = try {
            NfcAdapter.getDefaultAdapter(this)
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

        val cardEmulation = try {
            CardEmulation.getInstance(nfcAdapter)
        } catch (_: Exception) {
            Log.e(TAG, "CardEmulation is unavailable")
            null
        }

        if (cardEmulation == null) {
            errors += "Unable to get CardEmulation"
            return
        }

        try {
            val observeModeEnabled = nfcAdapter.isObserveModeEnabled
            if (!observeModeEnabled && !nfcAdapter.setObserveModeEnabled(true)) {
                errors += "Unable to enable Observe Mode"
            }
            if (!cardEmulation.removeAidsForService(component, "payment")) {
                errors += "Unable to remove AID for service"
            }

            if (!cardEmulation.registerAidsForService(
                    component, "payment", listOf(
                        "A0C0FFEEC0FFEE",
                    )
                )
            ) {
                errors += "Unable to register AID for service"
            }

            if (!cardEmulation.setPreferredService(this, component)) {
                errors += "Unable to set preferred service"
            }

            try {
                nfcAdapter.setDiscoveryTechnology(this, FLAG_READER_DISABLE, FLAG_LISTEN_KEEP)
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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = mapTimestampToTimeText(loop.startDelta),
            //fontSize = 12.sp
        )

        ElevatedCard(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ), elevation = CardDefaults.cardElevation(
                defaultElevation = 1.dp
            ), shape = RoundedCornerShape(18.dp), modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                for (event in loop.events) {
                    // Decide if we want to display delta values between frame blocks or in them
                    /*Text(
                        text = mapTimestampToTimeText(event.delta),
                        fontSize = 12.sp
                    )*/
                    PollingEventItem(event = event, displayDelta = true)
                }
                Text(
                    text = mapTimestampToTimeText(loop.endDelta),
                    //fontSize = 12.sp
                )
            }

        }
    }
}

@Composable
fun PollingEventItem(event: PollingLoopEvent, displayDelta: Boolean = true) {
    val (typeName, color) = mapPollingFrameTypeToNameAndColor(event.type)
    val dataGainDisplayed = typeName !in Constants.POLLING_FRAME_TYPES_WITHOUT_GAIN_AND_DATA

    val (type, delta, gain) = Triple(
        typeName,
        mapTimestampToTimeText(event.delta),
        mapVendorSpecificGainToPowerPercentage(
            event.vendorSpecificGain
        )
    )

    ElevatedCard(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ), elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        ), modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .border(
                width = 1.dp, color = color.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium
            )
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .width(IntrinsicSize.Max)
        ) {
            Row(
                modifier = Modifier
                    .padding(
                        bottom = if (dataGainDisplayed) 4.dp else 10.dp,
                        top = 10.dp,
                        start = 10.dp,
                        end = 10.dp
                    )
                    .fillMaxWidth(),
            ) {
                Text(
                    text = type,
                    modifier = Modifier.width(32.dp)
                )

                Text(
                    text = event.name
                )
                if (displayDelta) {
                    Text(
                        textAlign = TextAlign.End,
                        text = delta, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (dataGainDisplayed) {
                Divider(
                    modifier = Modifier.fillMaxWidth(),
                    color = color.copy(alpha = 0.6f),
                )
                Row(
                    modifier = Modifier.padding(
                        top = 4.dp, bottom = 10.dp, start = 10.dp, end = 10.dp
                    ), horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = event.data.toHexString(),
                    )
                    Text(
                        text = gain,
                    )
                }
            }
        }
    }
}

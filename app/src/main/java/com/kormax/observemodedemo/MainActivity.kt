package com.kormax.observemodedemo


import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.PollingFrame
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.unit.dp
import com.kormax.observemodedemo.ui.theme.ObserveModeDemoTheme


class MainActivity : ComponentActivity() {
    private val TAG = this::class.java.simpleName

    private var errors: List<String> = listOf()
    private val component = ComponentName(
        "com.kormax.observemodedemo",
        "com.kormax.observemodedemo.ObserveModeHostApduService"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EnforceScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

            var pollingFrames: List<Pair<Int, PollingFrame>> by remember {
                mutableStateOf(
                    listOf()
                )
            }

            var displayOriginalData: Boolean by remember { mutableStateOf(false) }

            SystemBroadcastReceiver(Constants.POLLING_FRAME_DATA_ACTION) { intent ->
                Log.i(TAG, Constants.POLLING_FRAME_DATA_ACTION)
                val frame =
                    intent?.getParcelableExtra<PollingFrame>(Constants.POLLING_FRAME_DATA_KEY)
                        ?: return@SystemBroadcastReceiver;
                Log.i(TAG, "Adding new polling frame")
                pollingFrames += Pair(pollingFrames.size, frame)
            }

            ObserveModeDemoTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.primary,
                            ),
                            title = {
                                Text("NFC Observer")
                            },
                            actions = {
                                IconButton(onClick = {
                                    displayOriginalData = !displayOriginalData
                                }) {
                                    Icon(
                                        imageVector = if (displayOriginalData) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                                        contentDescription = "Toggle detailed info"
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        pollingFrames = listOf()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Clear polling frame history"
                                    )
                                }
                            },
                        )
                    }) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = true
                    ) {
                        if (errors.isEmpty()) {
                            items(
                                pollingFrames.size,
                                { pollingFrames.get(it).first }) { index ->
                                PollingFrameItem(
                                    frame = pollingFrames.get(index).second,
                                    displayOriginalData = displayOriginalData
                                )
                            }
                        } else {
                            items(
                                errors.size,
                                { errors.get(it) }) { index ->
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
                                        text = errors.get(index)
                                    )
                                }

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

        var success: Boolean

        try {
            success = nfcAdapter.setObserveModeEnabled(true)
            Log.i(TAG, "nfcAdapter.setObserveModeEnabled -> ${success}")

            val aids = listOf(
                "D2760000850101",
                "325041592E5359532E4444463031",
                "4f53452e5641532e3031",
                "A000000909ACCE5501",
                "A000000909ACCE5502",
            )

            success = cardEmulation.removeAidsForService(component, "payment")
            Log.i(TAG, "cardEmulation.removeAidsForService -> ${success}")
            success = cardEmulation.registerAidsForService(component, "payment", aids)
            Log.i(TAG, "cardEmulation.registerAidsForService -> ${success}")
            success = cardEmulation.setPreferredService(this, component)
            Log.i(TAG, "cardEmulation.setPreferredService -> ${success}")
            success = cardEmulation.setShouldDefaultToObserveModeForService(component, true)
            Log.i(TAG, "cardEmulation.setShouldDefaultToObserveModeForService -> ${success}")
        } catch (e: Exception) {
            errors += "${e.toString()}"
        }
    }
}


@Composable
fun PollingFrameItem(frame: PollingFrame, displayOriginalData: Boolean = false) {
    val (typeName, color) = mapPollingFrameTypeToNameAndColor(frame.type)
    val dataGainDisplayed = typeName !in Constants.POLLING_FRAME_TYPES_WITHOUT_GAIN_AND_DATA

    val (type, timestamp, gain) = if (displayOriginalData) {
        Triple(
            frame.type.toString(),
            frame.timestamp.toString(),
            frame.vendorSpecificGain.toString()
        )
    } else {
        Triple(
            typeName,
            mapTimestampToTimeText(frame.timestamp),
            mapVendorSpecificGainToPowerPercentage(
                frame.vendorSpecificGain
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .width(IntrinsicSize.Max)
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .padding(
                    bottom = if (dataGainDisplayed) 4.dp else 10.dp,
                    top = 10.dp,
                    start = 10.dp,
                    end = 10.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = type,
                modifier = Modifier
                    .weight(1f),
            )
            Text(
                text = timestamp,
                modifier = Modifier
            )
        }
        if (dataGainDisplayed) {
            Divider(
                modifier = Modifier
                    .fillMaxWidth(),
                color = color.copy(alpha = 0.4f),
            )
            Row(
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 10.dp, start = 10.dp, end = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = frame.data.toHexString(),
                )
                Text(
                    text = gain,
                )
            }
        }
    }
}

package com.kormax.observemodedemo


import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kormax.observemodedemo.ui.theme.ObserveModeDemoTheme


class MainActivity : ComponentActivity() {
    private val component = ComponentName(
        "com.kormax.observemodedemo",
        "com.kormax.observemodedemo.ObserveModeHostApduService"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var pollingFrames: List<Pair<Int, PollingFrame>> by remember {
                mutableStateOf(
                    listOf()
                )
            }

            SystemBroadcastReceiver(Constants.POLLING_FRAME_DATA_ACTION) { intent ->
                Log.i("MainActivity", Constants.POLLING_FRAME_DATA_ACTION)
                val frame = intent?.getParcelableExtra<PollingFrame>(Constants.POLLING_FRAME_DATA_KEY)
                    ?: return@SystemBroadcastReceiver;
                Log.i("MainActivity", "Adding new polling frame")
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
                                IconButton(
                                    onClick = {
                                        pollingFrames = listOf()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Localized description"
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
                        items(
                            pollingFrames.size,
                            { pollingFrames.get(it).first }) { index ->
                            PollingFrameItem(frame = pollingFrames.get(index).second)
                        }
                    }
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()

        val nfcAdapter: NfcAdapter? = try {
            NfcAdapter.getDefaultAdapter(this)
        } catch (_: Exception) {
            Log.e("MainActivity", "unable to get nfcAdapter")
            null
        }

        if (nfcAdapter == null) {
            return
        }

        var success: Boolean

        success = nfcAdapter.setObserveModeEnabled(true)
        Log.i("MainActivity", "nfcAdapter.setObserveModeEnabled -> ${success}")

        val cardEmulation = CardEmulation.getInstance(nfcAdapter)
        val aids = listOf(
            "D2760000850101",
            "325041592E5359532E4444463031",
            "4f53452e5641532e3031",
            "A000000909ACCE5501",
            "A000000909ACCE5502",
        )

        success = cardEmulation.removeAidsForService(component, "payment")
        Log.i("MainActivity", "cardEmulation.removeAidsForService -> ${success}")
        success = cardEmulation.registerAidsForService(component, "payment", aids)
        Log.i("MainActivity", "cardEmulation.registerAidsForService -> ${success}")
        success = cardEmulation.setPreferredService(this, component)
        Log.i("MainActivity", "cardEmulation.setPreferredService -> ${success}")
        success = cardEmulation.setShouldDefaultToObserveModeForService(component, true)
        Log.i("MainActivity", "cardEmulation.setShouldDefaultToObserveModeForService -> ${success}")
    }
}

@Composable
fun PollingFrameItem(frame: PollingFrame) {
    val (type, color) = when (frame.type) {
        PollingFrame.POLLING_LOOP_TYPE_A -> ("A" to Color.Cyan)
        PollingFrame.POLLING_LOOP_TYPE_B -> ("B" to Color.Red)
        PollingFrame.POLLING_LOOP_TYPE_F -> ("F" to Color.Green)
        PollingFrame.POLLING_LOOP_TYPE_OFF -> ("X" to Color.LightGray)
        PollingFrame.POLLING_LOOP_TYPE_ON -> ("O" to Color.White)
        else -> "U${frame.type}" to Color.Magenta
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
    ) {
        Row() {
            Text(
                text = type,
                modifier = Modifier
                    .width(24.dp)
            )
            Text(
                text = frame.data.toHexString(),
                modifier = Modifier
                    .fillMaxWidth()
            )
        }
        Row() {
            Text(
                text = frame.vendorSpecificGain.toString(),
                modifier = Modifier
                    .width(64.dp)
            )
            Text(
                text = frame.timestamp.toString(),
                modifier = Modifier
                    .fillMaxWidth()
            )
        }
    }
}


@Composable
fun SystemBroadcastReceiver(
    systemAction: String,
    onSystemEvent: (intent: Intent?) -> Unit
) {
    // Grab the current context in this part of the UI tree
    val context = LocalContext.current

    // Safely use the latest onSystemEvent lambda passed to the function
    val currentOnSystemEvent by rememberUpdatedState(onSystemEvent)

    // If either context or systemAction changes, unregister and register again
    DisposableEffect(context, systemAction) {
        val intentFilter = IntentFilter(systemAction)
        val broadcast = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                currentOnSystemEvent(intent)
            }
        }

        context.registerReceiver(broadcast, intentFilter, Context.RECEIVER_EXPORTED)
        // When the effect leaves the Composition, remove the callback
        onDispose {
            context.unregisterReceiver(broadcast)
        }
    }
}




package com.kormax.observemodedemo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.nfc.cardemulation.PollingFrame
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class Constants {

    companion object {
        const val POLLING_FRAME_DATA_ACTION = "com.kormax.observemodedemo.POLLING_FRAME_DATA"
        const val POLLING_FRAME_DATA_KEY = "frame"

        val POLLING_FRAME_TYPES_WITHOUT_GAIN_AND_DATA = setOf("X", "O")
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


@Composable
fun EnforceScreenOrientation(orientation: Int) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation = orientation
        onDispose {
            activity.requestedOrientation = originalOrientation
        }
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}


fun mapTimestampToTimeText(milliseconds: Long): String {
    return when {
        milliseconds < 1000 -> "$milliseconds ms"
        milliseconds < 60_000 -> String.format("%.2f sec", milliseconds / 1000.0)
        else -> String.format("%.2f min", milliseconds / 60000.0)
    }
}


fun mapVendorSpecificGainToPowerPercentage(vendorSpecificGain: Int): String {
    // Currently only Google Pixel with the ST chip have observe mode available, so this works fine
    // In the future, this would require maintaining a database of value ranges for each model
    // or an ability to get the adjusted value, based on OEM calibration like done for BLE.
    val squashedGain = vendorSpecificGain.coerceIn(0, 12)
    val percentage = squashedGain * 100 / 12
    return "$percentage%"
}


fun mapPollingFrameTypeToName(type: Int): String {
    val (typeName, _) = mapPollingFrameTypeToNameAndColor(type)
    return typeName
}


fun mapPollingFrameTypeToNameAndColor(type: Int) = when (type) {
    PollingFrame.POLLING_LOOP_TYPE_A -> ("A" to Color(0xFF0E79B2))
    PollingFrame.POLLING_LOOP_TYPE_B -> ("B" to Color(0xFFF53E54))
    PollingFrame.POLLING_LOOP_TYPE_F -> ("F" to Color(0xFF7AC74F))
    PollingFrame.POLLING_LOOP_TYPE_OFF -> ("X" to Color.DarkGray)
    PollingFrame.POLLING_LOOP_TYPE_ON -> ("O" to Color.White)
    else -> "U${type}" to Color.Magenta
}

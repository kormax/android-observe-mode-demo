package com.kormax.observemodedemo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.nfc.cardemulation.PollingFrame
import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.parcelize.Parcelize
import java.time.Instant
import java.time.Instant.now
import kotlin.math.ceil

class Constants {

    companion object {
        const val POLLING_LOOP_EVENT_ACTION = "com.kormax.observemodedemo.POLLING_FRAME_DATA"
        const val POLLING_LOOP_EVENT_DATA_KEY = "frame"

        val POLLING_FRAME_TYPES_WITHOUT_GAIN_AND_DATA = setOf("X", "O")
    }

}

@Parcelize
class PollingLoopEvent(
    val delta: Long,
    val type: Int,
    val data: ByteArray,
    val vendorSpecificGain: Int,
    val timestamp: Instant = now()
) : Parcelable {

    constructor(frame: PollingFrame) : this(
        frame.timestamp, frame.type, frame.data, frame.vendorSpecificGain, now()
    )

    companion object {
        val A = PollingFrame.POLLING_LOOP_TYPE_A
        val B = PollingFrame.POLLING_LOOP_TYPE_B
        val F = PollingFrame.POLLING_LOOP_TYPE_F
        val ON = PollingFrame.POLLING_LOOP_TYPE_ON
        val OFF = PollingFrame.POLLING_LOOP_TYPE_OFF
    }
}


@Composable
fun SystemBroadcastReceiver(
    systemAction: String, onSystemEvent: (intent: Intent?) -> Unit
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


fun mapPollingFrameTypeToNameAndColor(type: Int) = when (type) {
    PollingLoopEvent.A -> ("A" to Color(0xFF0E79B2))
    PollingLoopEvent.B -> ("B" to Color(0xFFF53E54))
    PollingLoopEvent.F -> ("F" to Color(0xFF7AC74F))
    PollingLoopEvent.OFF -> ("X" to Color.DarkGray)
    PollingLoopEvent.ON -> ("O" to Color.White)
    else -> "U${type}" to Color.Magenta
}

fun mapPollingFrameTypeToName(type: Int) = mapPollingFrameTypeToNameAndColor(type).first

fun mapPollingLoopToString(frames: Array<PollingFrame>) =
    frames.map { mapPollingFrameTypeToName(it.type) }.joinToString("") { it }


inline fun <reified T> smallestRepeatingSequence(
    arr: Array<T>, noinline comparator: (T, T) -> Boolean
): Array<T> {
    val n = arr.size
    for (length in 1..ceil((n / 2).toDouble()).toInt()) {
        if (n % length == 0) {
            val subArray = arr.copyOfRange(0, length)
            if (arr.equalTo(subArray.repeat(n / length), comparator)) {
                return subArray
            }
        }
    }
    return emptyArray()
}


val fieldTypeToIndex = hashMapOf(
    //PollingFrame.POLLING_LOOP_TYPE_ON to 1,
    PollingFrame.POLLING_LOOP_TYPE_A to 2,
    PollingFrame.POLLING_LOOP_TYPE_B to 3,
    PollingFrame.POLLING_LOOP_TYPE_F to 4,
    //PollingFrame.POLLING_LOOP_TYPE_V to 5
    //PollingFrame.POLLING_LOOP_TYPE_OFF to 6
)

fun alignPollingLoop(events: Array<PollingLoopEvent>): Array<PollingLoopEvent> {
    if (events.size <= 1) {
        return events
    }

    var biggest = Pair(0, 0)

    val rotations = events.indices.filter {
        events[it % events.size].type == PollingLoopEvent.ON && events[(it + events.size - 1) % events.size].type == PollingLoopEvent.OFF
    }.ifEmpty { events.indices.toList() }

    for (rotation in rotations) {
        var score = 0
        var previousTech = -1

        for (index in events.indices) {
            val currentTech =
                fieldTypeToIndex.getOrDefault(events[(index + rotation) % events.size].type, -1)
            if (previousTech < currentTech) {
                score += 1
                previousTech = currentTech
            }
        }

        if (biggest.first < score) {
            biggest = Pair(score, rotation)
        }
    }

    return events.rotate(biggest.second)
}

inline fun <reified T> largestRepeatingSequence(
    arr: Array<T>,
    noinline comparator: (T, T) -> Boolean,
): Array<T> {
    val possibilities = mutableListOf<Array<T>>()
    for (start in 0 until Math.ceil((arr.size / 2).toDouble()).toInt()) {
        for (end in start until arr.size) {
            val pattern = arr.copyOfRange(start, end)
            if (arr.containsSubArray(pattern.repeat(2), comparator)) {
                possibilities.add(pattern)
            }
        }
    }
    val sequence = possibilities.maxByOrNull { it.size } ?: emptyArray()
    return smallestRepeatingSequence(sequence, comparator).takeIf { it.isNotEmpty() } ?: sequence
}

inline fun <reified T> Array<T>.repeat(n: Int): Array<T> {
    return Array(size * n) { this[it % size] }
}

inline fun <reified T> Array<T>.rotate(n: Int): Array<T> {
    if (n == 0) {
        return this
    }
    return Array(size) { this[(it + n) % size] }
}

fun <T> Array<T>.containsSubArray(
    subArray: Array<T>, comparator: (T, T) -> Boolean
): Boolean {
    if (subArray.isEmpty()) return true

    var subIndex = 0
    for (index in indices) {
        if (comparator(this[index], subArray[subIndex])) {
            subIndex++
            if (subIndex == subArray.size) {
                return true
            }
        } else {
            subIndex = 0
        }
    }
    return false
}

fun <T> Array<T>.equalTo(other: Array<T>, comparator: (T, T) -> Boolean): Boolean {
    if (this.size != other.size) return false

    for (i in this.indices) {
        if (!comparator(this[i], other[i])) {
            return false
        }
    }
    return true
}

enum class DisplayMode {
    HISTORY, LOOP
}

data class Loop(
    val startDelta: Long,
    val endDelta: Long,
    val events: Array<PollingLoopEvent>,
    val timestamp: Instant = now()
)

fun mapPollingEventsToLoopActivity(frames: Array<PollingLoopEvent>): List<Loop> {
    val result = mutableListOf<Loop>()

    var startDelta = -1L
    var elements = emptyArray<PollingLoopEvent>()
    var currentIndex = 0
    for (frame in frames) {
        if (frame.type == PollingFrame.POLLING_LOOP_TYPE_OFF) {
            currentIndex = 0
            result += Loop(
                startDelta, frame.delta, elements.map { it }.toTypedArray(), now()
            )
            elements = emptyArray<PollingLoopEvent>()
            startDelta = -1
            continue
        }
        if (currentIndex == 0 && frame.type == PollingFrame.POLLING_LOOP_TYPE_ON) {
            startDelta = frame.delta
        } else {
            elements += frame
        }
        currentIndex += 1
    }

    if (elements.isNotEmpty() || startDelta != -1L) {
        result += Loop(startDelta, -1, elements.map { it }.toTypedArray(), now())
    }
    return result
}

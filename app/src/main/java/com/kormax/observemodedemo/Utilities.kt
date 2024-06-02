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
import kotlin.experimental.and
import kotlin.math.ceil


class Constants {

    companion object {
        const val POLLING_LOOP_EVENT_ACTION = "com.kormax.observemodedemo.POLLING_FRAME_DATA"
        const val POLLING_LOOP_EVENT_DATA_KEY = "frame"

        val POLLING_FRAME_TYPES_WITHOUT_GAIN_AND_DATA = setOf("X", "O")
    }

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


@Parcelize
class PollingLoopEvent(
    var delta: Long,
    val type: Int,
    val data: ByteArray,
    val vendorSpecificGain: Int,
    val timestamp: Instant = now()
) : Parcelable {

    constructor(frame: PollingFrame) : this(
        frame.timestamp, frame.type, frame.data, frame.vendorSpecificGain, now()
    )

    constructor(frame: PollingFrame, delta: Long) : this(
        frame.timestamp - delta, frame.type, frame.data, frame.vendorSpecificGain, now()
    )

    companion object {
        val A = PollingFrame.POLLING_LOOP_TYPE_A
        val B = PollingFrame.POLLING_LOOP_TYPE_B
        val F = PollingFrame.POLLING_LOOP_TYPE_F
        val ON = PollingFrame.POLLING_LOOP_TYPE_ON
        val OFF = PollingFrame.POLLING_LOOP_TYPE_OFF
        val UNKNOWN = PollingFrame.POLLING_LOOP_TYPE_UNKNOWN
        val PLACEHOLDER = PollingLoopEvent(-1, 0, ByteArray(0), 0, now())
    }

    val name: String by lazy {
        val hex = data.toHexString().lowercase()
        return@lazy when (type) {
            A -> parseTypeAFrame(hex)
            B -> parseTypeBFrame(hex)
            F -> parseTypeFFrame(hex)
            ON, OFF -> ""
            else -> parseOtherFrameTypes(hex)
        }
    }
}


fun parseOtherFrameTypes(data: String): String {
    if (data == "0600") {
        return "INITIATE"
    }

    // HLTA/HLTB commands should not be sent during discovery if nothing was detected
    // but some readers do nonetheless
    if (data == "5000") {
        // ISO14443-3-A HLTA
        return "HLTA"
    }
    if (data.startsWith("50") && data.length == 10) {
        // ISO14443-3-B HLTB
        return "HLTB"
    }

    if (data.length in 2..4) {
        if (data.endsWith("0a")) {
            // Picopass wakeup
            return "ACTALL"
        }
        // Weird case
        if (data.endsWith("0c")) {
            // Picopass anticollision command
            return "IDENTIFY"
        }
    }

    // ECP
    if (data.startsWith("6a") && data.length >= 8) {
        return "ECP" + when (data.substring(2, 4)) {
            "01" -> "1_" + parseECP1(data)
            "02" -> "2_" + parseECP2(data)
            else -> "_UNKNOWN"
        }
    }

    // Magsafe Wakeup
    if (data.startsWith("7") && data.length == 2) {
        return "MAGWUP_" + when (data) {
            "7a" -> "A"
            "7b" -> "B"
            "7c" -> "C"
            "7d" -> "D"
            else -> "U"
        }
    }
    return "PLF"
}

fun parseTypeAFrame(data: String): String {
    if (data.startsWith("52")) {
        return "WUPA"
    } else if (data.startsWith("26")) {
        return "REQA"
    }
    return parseOtherFrameTypes(data)
}

fun parseTypeBFrame(data: String): String {
    if (data.startsWith("05")) {
        if ((data.substring(data.length - 2, data.length).hexToByte() and 0x08.toByte()) > 0) {
            return "WUPB"
        } else {
            return "REQB"
        }
    }
    return parseOtherFrameTypes(data)
}

fun parseTypeFFrame(data: String): String {
    if (data.length >= 6) {
        return parseFeliCaSystemCode(data.substring(2, 6))
    }
    return parseOtherFrameTypes(data)
}

fun parseFeliCaSystemCode(systemCode: String) = when (systemCode.uppercase()) {
    "FFFF" -> "WILDCARD"
    "0003" -> "CJRC"
    "8008" -> "OCTOPUS"
    "FE00" -> "COMMON"
    "12FC" -> "NDEF"
    else -> "UNKNOWN"
}

fun parseECPTransitTCI(tci: String): String = when (tci.uppercase()) {
    "030000" -> "VENTRA"
    "030400" -> "HOPCARD"
    "030002" -> "TFL"
    "030001" -> "WMATA"
    "030005" -> "LATAP"
    "030007" -> "CLIPPER"
    "03095A" -> "NAVIGO"
    else -> "UNKNOWN"
}

fun parseECPAccessSubtype(value: String) = when (value.uppercase()) {
    "00" -> "UNIVERSITY"
    "01" -> "AUTOMOTIVE"
    "08" -> "AUTOMOTIVE"
    "09" -> "AUTOMOTIVE"
    "0A" -> "AUTOMOTIVE"
    "0B" -> "AUTOMOTIVE"
    "06" -> "HOME"
    else -> "UNKNOWN"
}

fun parseECP2(value: String): String {
    if (value.length < 8) {
        return "UNKNOWN"
    }
    return when (value.substring(6, 8)) {
        "01" -> "TRANSIT_" + parseECPTransitTCI(value.substring(10, 16))
        "02" -> "ACCESS_" + parseECPAccessSubtype(value.substring(8, 10))
        "03" -> "IDENTITY"
        "05" -> "HANDOVER"
        else -> "UNKNOWN"
    }
}

fun parseECP1(value: String): String {
    return when (value) {
        "6a01000000" -> "VAS_OR_PAYMENT"
        "6a01000001" -> "VAS_AND_PAYMENT"
        "6a01000002" -> "VAS_ONLY"
        "6a01000003" -> "PAY_ONLY"
        "6a01cf0000" -> "IGNORE"
        "6a01c30000" -> "GYMKIT"
        else -> {
            if (value.startsWith("6a0103")) {
                return "TRANSIT_" + parseECPTransitTCI(value.substring(4))
            }
            return "UNKNOWN"
        }
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


fun mapDeltaToTimeText(microseconds: Long) = when {
    microseconds == -1L -> "Continuous"
    microseconds >= 60_000_000 -> {
        val minutes = ceil(microseconds / 60_000_000.0).toInt()
        "$minutes min"
    }
    microseconds >= 1_000_000 -> {
        val seconds = ceil(microseconds / 1_000_000.0).toInt()
        "$seconds s"
    }
    microseconds >= 1_000 -> {
        val milliseconds = ceil(microseconds / 1_000.0).toInt()
        "$milliseconds ms"
    }
    else -> {
        "$microseconds us"
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
    PollingLoopEvent.UNKNOWN -> ("U" to Color.Magenta)
    else -> "U(${type})" to Color.Magenta
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
    // Attempt to align the polling loop by calculating best rotation using the following rules:
    //
    // 1. If there are rotations which start with an ON event and end with an OFF event, discard all other rotations
    // 2. Give 2 points to loops that follow the A -> B -> F order (Unknown frame type is ignored) for each type
    // 3. If there are empty ON and OFF event pairs, give 1 point if they are placed at the end
    //
    // As a potential improvement, delta values could also be taken into account to find proper alignment

    if (events.size <= 1) {
        return events
    }

    // Stores a pair of score and rotation values
    var biggest = Pair(0, 0)

    val rotations = events.indices.filter {
        events.get(0, it).type == PollingLoopEvent.ON
                && events.get(events.size - 1, it).type == PollingLoopEvent.OFF
    }.ifEmpty { events.indices.toList() }

    for (rotation in rotations) {
        var score = 0
        var previousTech = -1

        for (index in events.indices) {
            val currentTech =
                fieldTypeToIndex.getOrDefault(events.get(index, rotation).type, -1)
            if (previousTech < currentTech) {
                score += 2
                previousTech = currentTech
            }
        }

        // If rotated polling loop does not start with an empty pair of ON and OFF events
        // (instead, ends with one), increase score
        if (
            events.get(events.size - 2, rotation).type == PollingLoopEvent.ON
            && events.get(events.size - 1, rotation).type == PollingLoopEvent.OFF
        ) {
            score += 1
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

fun <T> Array<T>.get(index: Int, rotation: Int): T {
    return this[(index + rotation) % this.size]
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

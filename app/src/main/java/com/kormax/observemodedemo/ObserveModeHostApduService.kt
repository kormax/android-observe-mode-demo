package com.kormax.observemodedemo

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Bundle
import android.util.Log


class ObserveModeHostApduService : HostApduService() {
    private val TAG = this::class.java.simpleName
    private var DELTA = -1L

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.i(TAG, "processCommandApdu(${commandApdu.toHexString()}, ${extras})")
        return ByteArray(0)
    }

    override fun processPollingFrames(frames: List<PollingFrame>) {
        Log.i(TAG, "processPollingFrames(${
            frames.map {
                "${mapPollingFrameTypeToName(it.type)}(${it.data.toUByteArray().toHexString()})"
            }
        })")

        for (frame in frames) {
            if (frame.type == PollingFrame.POLLING_LOOP_TYPE_ON) {
                DELTA = frames[0].timestamp
            }
            sendBroadcast(Intent(Constants.POLLING_LOOP_EVENT_ACTION).apply {
                putExtra(Constants.POLLING_LOOP_EVENT_DATA_KEY, PollingLoopEvent(frame, DELTA))
            })
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "onDeactivated(${reason})")
    }
}

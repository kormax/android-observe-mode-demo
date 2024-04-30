package com.kormax.observemodedemo

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Bundle
import android.util.Log


class ObserveModeHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.i(
            "ObserveModeHostApduService",
            "processCommandApdu(${commandApdu.toHexString()}, ${extras})"
        )
        return ByteArray(0)
    }

    override fun processPollingFrames(frames: List<PollingFrame>) {
        Log.i(
            "ObserveModeHostApduService",
            "processPollingFrames(${
                frames.map {
                    when (it.type) {
                        PollingFrame.POLLING_LOOP_TYPE_A -> "A"
                        PollingFrame.POLLING_LOOP_TYPE_B -> "B"
                        PollingFrame.POLLING_LOOP_TYPE_F -> "F"
                        PollingFrame.POLLING_LOOP_TYPE_OFF -> "X"
                        PollingFrame.POLLING_LOOP_TYPE_ON -> "O"
                        else -> "U${it.type}"
                    } + "(${it.data.toUByteArray().toHexString()})"
                }
            })"
        )

        for (frame in frames) {
            sendBroadcast(
                Intent(Constants.POLLING_FRAME_DATA_ACTION).apply {
                    putExtra(Constants.POLLING_FRAME_DATA_KEY, frame)
                }
            )
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.i("ObserveModeHostApduService", "onDeactivated(${reason})")
    }
}
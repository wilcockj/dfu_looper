package com.example.nordicdfulooper

import android.bluetooth.BluetoothDevice
import no.nordicsemi.android.dfu.DfuDeviceSelector

class StableDfuDeviceSelector(
    private val originalAddress: String,
    private val originalName: String?
) : DfuDeviceSelector {

    override fun matches(
        device: BluetoothDevice,
        rssi: Int,
        scanRecord: ByteArray,
        originalAddress: String,
        incrementedAddress: String
    ): Boolean {
        val address = device.address
        if (address.equals(this.originalAddress, ignoreCase = true)) return true
        if (address.equals(incrementedAddress, ignoreCase = true)) return true

        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        if (!originalName.isNullOrBlank() && !name.isNullOrBlank()) {
            if (name.equals(originalName, ignoreCase = true)) return true
            if (name.contains(originalName, ignoreCase = true) && name.contains("dfu", ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}

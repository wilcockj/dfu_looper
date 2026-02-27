package com.example.nordicdfulooper

import android.app.Activity
import no.nordicsemi.android.dfu.DfuBaseService
import no.nordicsemi.android.dfu.DfuDeviceSelector

class DfuService : DfuBaseService() {
    companion object {
        @Volatile
        private var targetAddress: String? = null

        @Volatile
        private var targetName: String? = null

        fun setTarget(address: String, name: String?) {
            targetAddress = address
            targetName = name
        }
    }

    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java

    override fun getDeviceSelector(): DfuDeviceSelector {
        val address = targetAddress
        if (!address.isNullOrBlank()) {
            return StableDfuDeviceSelector(address, targetName)
        }
        return super.getDeviceSelector()
    }

    override fun isDebug(): Boolean = true
}

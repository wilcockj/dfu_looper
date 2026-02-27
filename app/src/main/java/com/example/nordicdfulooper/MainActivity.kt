package com.example.nordicdfulooper

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var txtZip: TextView
    private lateinit var txtDevice: TextView
    private lateinit var txtIterations: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var selectedZipUri: Uri? = null
    private var selectedDeviceAddress: String? = null
    private var selectedDeviceName: String? = null
    private var successfulIterations = 0
    private var isLoopRunning = false
    private var isDfuInProgress = false
    private var scanCallback: ScanCallback? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        appendLog("Permissions updated")
    }

    private val zipPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            selectedZipUri = uri
            txtZip.text = "ZIP: ${resolveName(uri)}"
            appendLog("Selected zip: ${resolveName(uri)}")
        }
    }

    private val devicePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        selectedDeviceAddress = data.getStringExtra(DeviceScanActivity.EXTRA_DEVICE_ADDRESS)
        selectedDeviceName = data.getStringExtra(DeviceScanActivity.EXTRA_DEVICE_NAME)
        txtDevice.text = "Device: ${selectedDeviceName ?: "Unknown"} (${selectedDeviceAddress ?: "n/a"})"
        appendLog("Selected device: ${selectedDeviceName ?: "Unknown"} (${selectedDeviceAddress ?: "n/a"})")
    }

    private val dfuProgressListener = object : DfuProgressListenerAdapter() {
        override fun onDfuProcessStarting(deviceAddress: String) {
            isDfuInProgress = true
            txtStatus.text = "Status: DFU starting"
            appendLog("DFU starting on $deviceAddress")
        }

        override fun onDeviceConnecting(deviceAddress: String) {
            txtStatus.text = "Status: connecting"
        }

        override fun onDfuCompleted(deviceAddress: String) {
            isDfuInProgress = false
            successfulIterations += 1
            txtIterations.text = "Successful iterations: $successfulIterations"
            txtStatus.text = "Status: DFU completed"
            appendLog("DFU completed on $deviceAddress (success #$successfulIterations)")
            scheduleScanRestart(2000)
        }

        override fun onDfuAborted(deviceAddress: String) {
            isDfuInProgress = false
            txtStatus.text = "Status: DFU aborted"
            appendLog("DFU aborted on $deviceAddress")
            scheduleScanRestart(2000)
        }

        override fun onError(
            deviceAddress: String,
            error: Int,
            errorType: Int,
            message: String
        ) {
            isDfuInProgress = false
            txtStatus.text = "Status: DFU error"
            appendLog("DFU error on $deviceAddress: $message")
            scheduleScanRestart(4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtZip = findViewById(R.id.txtZip)
        txtDevice = findViewById(R.id.txtDevice)
        txtIterations = findViewById(R.id.txtIterations)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)

        findViewById<Button>(R.id.btnPickZip).setOnClickListener {
            zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        findViewById<Button>(R.id.btnPickDevice).setOnClickListener {
            if (!hasRequiredPermissions()) {
                requestPermissions()
                appendLog("Missing BLE permissions")
                return@setOnClickListener
            }
            devicePickerLauncher.launch(Intent(this, DeviceScanActivity::class.java))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startLoop()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopLoop()
        }

        if (!hasRequiredPermissions()) {
            requestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        DfuServiceListenerHelper.registerProgressListener(this, dfuProgressListener)
    }

    override fun onStop() {
        DfuServiceListenerHelper.unregisterProgressListener(this, dfuProgressListener)
        stopTargetScan()
        super.onStop()
    }

    private fun startLoop() {
        if (selectedZipUri == null) {
            appendLog("Select a DFU zip file first")
            return
        }

        if (selectedDeviceAddress == null) {
            appendLog("Select a device first")
            return
        }

        if (!hasRequiredPermissions()) {
            requestPermissions()
            appendLog("Permissions are required before starting")
            return
        }

        if (isLoopRunning) {
            appendLog("Loop already running")
            return
        }

        successfulIterations = 0
        txtIterations.text = "Successful iterations: 0"
        isLoopRunning = true
        txtStatus.text = "Status: loop running"
        appendLog("Loop started")
        scheduleScanRestart(200)
    }

    private fun stopLoop() {
        isLoopRunning = false
        isDfuInProgress = false
        stopTargetScan()
        txtStatus.text = "Status: stopped"
        appendLog("Loop stopped")
    }

    private fun scheduleScanRestart(delayMs: Long) {
        mainHandler.removeCallbacks(scanAndDfuRunnable)
        if (isLoopRunning) {
            mainHandler.postDelayed(scanAndDfuRunnable, delayMs)
        }
    }

    private val scanAndDfuRunnable = Runnable {
        if (!isLoopRunning || isDfuInProgress) {
            return@Runnable
        }
        startTargetScan()
    }

    private fun startTargetScan() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: run {
            appendLog("Bluetooth adapter unavailable")
            return
        }

        if (!adapter.isEnabled) {
            appendLog("Enable Bluetooth to continue")
            scheduleScanRestart(5000)
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            appendLog("BLE scanner unavailable")
            scheduleScanRestart(5000)
            return
        }

        if (scanCallback != null) {
            return
        }

        txtStatus.text = "Status: scanning for target"
        appendLog("Scanning for target in DFU mode")

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address ?: return
                val name = result.device.name
                if (matchesSelectedDevice(address, name)) {
                    stopTargetScan()
                    appendLog("Target found: ${name ?: "Unknown"} ($address)")
                    startDfu(address, name ?: selectedDeviceName)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopTargetScan()
                appendLog("Scan failed with code $errorCode")
                scheduleScanRestart(4000)
            }
        }

        scanner.startScan(scanCallback)
        mainHandler.postDelayed({
            if (scanCallback != null) {
                stopTargetScan()
                appendLog("Target not found, retrying")
                scheduleScanRestart(3000)
            }
        }, 10000)
    }

    private fun stopTargetScan() {
        val callback = scanCallback ?: return
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        scanner?.stopScan(callback)
        scanCallback = null
    }

    private fun startDfu(currentAddress: String, currentName: String?) {
        val zipUri = selectedZipUri ?: return
        val selectedAddress = selectedDeviceAddress ?: return

        isDfuInProgress = true
        txtStatus.text = "Status: launching DFU"

        DfuServiceInitiator(currentAddress)
            .setDeviceName(currentName)
            .setKeepBond(true)
            .setForceDfu(true)
            .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
            .setPacketsReceiptNotificationsEnabled(false)
            .setMtu(247)
            .setZip(zipUri)
            .also {
                DfuService.setTarget(selectedAddress, selectedDeviceName)
                it.start(this, DfuService::class.java)
            }

        appendLog("Started DFU using ${resolveName(zipUri)}")
    }

    private fun matchesSelectedDevice(address: String, name: String?): Boolean {
        val selectedAddress = selectedDeviceAddress ?: return false
        val incremented = incrementMacAddress(selectedAddress)
        if (address.equals(selectedAddress, ignoreCase = true)) return true
        if (incremented != null && address.equals(incremented, ignoreCase = true)) return true

        val selectedName = selectedDeviceName
        if (!selectedName.isNullOrBlank() && !name.isNullOrBlank()) {
            if (name.equals(selectedName, ignoreCase = true)) return true
            if (name.contains(selectedName, ignoreCase = true) && name.contains("JRAK", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = requiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        return permissions.toTypedArray()
    }

    private fun resolveName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameColumn >= 0) {
                return cursor.getString(nameColumn)
            }
        }
        return uri.lastPathSegment ?: "selected.zip"
    }

    private fun incrementMacAddress(address: String): String? {
        return try {
            val normalized = address.replace(":", "")
            val incremented = (normalized.toLong(16) + 1).toString(16).padStart(12, '0').uppercase(Locale.US)
            incremented.chunked(2).joinToString(":")
        } catch (_: Exception) {
            null
        }
    }

    private fun appendLog(text: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        txtLog.append("[$timestamp] $text\n")
    }
}

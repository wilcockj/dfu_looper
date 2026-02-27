package com.example.nordicdfulooper

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DeviceScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
    }

    private val devices = mutableListOf<ScanResult>()
    private val labels = mutableListOf<String>()
    private val deviceIndexByAddress = mutableMapOf<String, Int>()
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var scanCallback: ScanCallback? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasScanPermission()) {
            startScan()
        } else {
            finish()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasScanPermission()) {
            startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_scan)

        listView = findViewById(R.id.listDevices)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = devices[position]
            val result = Intent().apply {
                putExtra(EXTRA_DEVICE_ADDRESS, selected.device.address)
                putExtra(EXTRA_DEVICE_NAME, selected.device.name)
            }
            setResult(RESULT_OK, result)
            finish()
        }

        if (hasScanPermission()) {
            startScan()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    override fun onStop() {
        stopScan()
        super.onStop()
    }

    private fun hasScanPermission(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startScan() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = bluetoothManager.adapter ?: run {
            Toast.makeText(this, "Bluetooth adapter unavailable", Toast.LENGTH_LONG).show()
            return
        }
        if (!btAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            Toast.makeText(this, "Enable Location for BLE scanning", Toast.LENGTH_LONG).show()
        }
        val scanner = btAdapter.bluetoothLeScanner ?: run {
            Toast.makeText(this, "BLE scanner unavailable", Toast.LENGTH_LONG).show()
            return
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                upsertResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { upsertResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Toast.makeText(
                    this@DeviceScanActivity,
                    "BLE scan failed: $errorCode",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf<ScanFilter>()
        try {
            scanner.startScan(filters, scanSettings, scanCallback)
        } catch (_: SecurityException) {
            Toast.makeText(this, "Missing Bluetooth permissions", Toast.LENGTH_LONG).show()
        }
    }

    private fun upsertResult(result: ScanResult) {
        val address = result.device.address ?: return
        val name = result.device.name ?: "Unknown"
        val label = "$name ($address), RSSI ${result.rssi}"
        val existingIndex = deviceIndexByAddress[address]
        if (existingIndex != null) {
            devices[existingIndex] = result
            labels[existingIndex] = label
        } else {
            devices += result
            labels += label
            deviceIndexByAddress[address] = devices.lastIndex
        }
        runOnUiThread { adapter.notifyDataSetChanged() }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun stopScan() {
        val callback = scanCallback ?: return
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(callback)
        scanCallback = null
    }
}

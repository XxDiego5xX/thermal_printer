package com.codingdevs.thermal_printer

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import com.codingdevs.thermal_printer.bluetooth.BluetoothConnection
import com.codingdevs.thermal_printer.bluetooth.BluetoothConstants
import com.codingdevs.thermal_printer.bluetooth.BluetoothService
import com.codingdevs.thermal_printer.bluetooth.BluetoothService.Companion.TAG
import com.codingdevs.thermal_printer.usb.USBPrinterService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*

class ThermalPrinterPlugin : FlutterPlugin,
    MethodChannel.MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    ActivityAware {

    private lateinit var channel: MethodChannel
    private var context: Context? = null
    private var currentActivity: Activity? = null

    private var requestPermissionBT = false
    private var isBle = false
    private var isScan = false

    private lateinit var adapter: USBPrinterService
    private lateinit var bluetoothService: BluetoothService

    private lateinit var btStateChannel: EventChannel
    private lateinit var usbStateChannel: EventChannel

    private var btSink: EventChannel.EventSink? = null
    private var usbSink: EventChannel.EventSink? = null

    // ==========================
    // ENGINE
    // ==========================

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        channel = MethodChannel(binding.binaryMessenger, methodChannel)
        channel.setMethodCallHandler(this)

        adapter = USBPrinterService.getInstance(usbHandler)
        context?.let { adapter.init(it) }

        bluetoothService = BluetoothService.getInstance(bluetoothHandler)
    }

    btStateChannel = EventChannel(
    binding.binaryMessenger,
    "com.codingdevs.thermal_printer/bt_state"
    )

    btStateChannel.setStreamHandler(object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            btSink = events
        }

        override fun onCancel(arguments: Any?) {
            btSink = null
        }
    })

    usbStateChannel = EventChannel(
    binding.binaryMessenger,
    "com.codingdevs.thermal_printer/usb_state"
    )

    usbStateChannel.setStreamHandler(object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            usbSink = events
        }

        override fun onCancel(arguments: Any?) {
            usbSink = null
        }
    })

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        bluetoothService.setHandler(null)
        adapter.setHandler(null)
        btStateChannel.setStreamHandler(null)
        usbStateChannel.setStreamHandler(null)
    }

    // ==========================
    // METHOD CHANNEL
    // ==========================

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {

        when (call.method) {

            "getBluetoothList" -> {
                isBle = false
                isScan = true

                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.scanBluDevice(channel)
                    result.success(null)
                } else {
                    result.success(false)
                }
            }

            else -> result.notImplemented()
        }
    }

    // ==========================
    // BLUETOOTH
    // ==========================

    private fun verifyIsBluetoothIsOn(): Boolean {

        if (!checkPermissions()) return false

        val adapterBt = bluetoothService.mBluetoothAdapter
        val activity = currentActivity

        if (adapterBt == null) {
            Log.e(TAG, "BluetoothAdapter is null")
            return false
        }

        if (!adapterBt.isEnabled) {

            if (requestPermissionBT) return false
            if (activity == null) return false

            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

            startActivityForResult(
                activity,
                enableBtIntent,
                PERMISSION_ENABLE_BLUETOOTH,
                null
            )

            requestPermissionBT = true
            return false
        }

        return true
    }

    // ==========================
    // PERMISSIONS (CORREGIDO)
    // ==========================

    private fun checkPermissions(): Boolean {

        val ctx = context ?: return false
        val activity = currentActivity ?: return false

        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                activity,
                notGranted.toTypedArray(),
                PERMISSION_ALL
            )
            return false
        }

        return true
    }

    // ==========================
    // ACTIVITY AWARE
    // ==========================

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
        bluetoothService.setActivity(currentActivity)
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
        bluetoothService.setActivity(null)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
        bluetoothService.setActivity(null)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        bluetoothService.setActivity(currentActivity)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {

        if (requestCode == PERMISSION_ENABLE_BLUETOOTH) {
            requestPermissionBT = false
        }

        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {

        if (requestCode == PERMISSION_ALL) {

            val granted = grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }

            if (!granted) {
                context?.let {
                    Toast.makeText(
                        it,
                        "Bluetooth permissions required",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            return true
        }

        return false
    }

    // ==========================
    // HANDLERS
    // ==========================

    private val usbHandler = object : Handler(Looper.getMainLooper()) {}
    private val bluetoothHandler = object : Handler(Looper.getMainLooper()) {}

    companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
        const val methodChannel = "com.codingdevs.thermal_printer"
    }
}
package com.codingdevs.thermal_printer

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
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
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

class ThermalPrinterPlugin : FlutterPlugin,
    MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    ActivityAware {

    private lateinit var channel: MethodChannel
    private var messageChannel: EventChannel? = null
    private var messageUSBChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var eventUSBSink: EventChannel.EventSink? = null

    private var context: Context? = null
    private var currentActivity: Activity? = null
    private var requestPermissionBT: Boolean = false
    private var isBle: Boolean = false
    private var isScan: Boolean = false

    lateinit var adapter: USBPrinterService
    private lateinit var bluetoothService: BluetoothService

    // ==========================
    // HANDLERS
    // ==========================

    private val usbHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                USBPrinterService.STATE_USB_CONNECTED -> eventUSBSink?.success(2)
                USBPrinterService.STATE_USB_CONNECTING -> eventUSBSink?.success(1)
                USBPrinterService.STATE_USB_NONE -> eventUSBSink?.success(0)
            }
        }
    }

    private val bluetoothHandler = object : Handler(Looper.getMainLooper()) {

        private val bluetoothStatus: Int
            get() = BluetoothService.bluetoothConnection?.state ?: 99

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)

            when (msg.what) {

                BluetoothConstants.MESSAGE_STATE_CHANGE -> {
                    when (bluetoothStatus) {

                        BluetoothConstants.STATE_CONNECTED -> {
                            eventSink?.success(2)
                            bluetoothService.removeReconnectHandlers()
                        }

                        BluetoothConstants.STATE_CONNECTING -> {
                            eventSink?.success(1)
                        }

                        BluetoothConstants.STATE_NONE -> {
                            eventSink?.success(0)
                            bluetoothService.autoConnectBt()
                        }

                        BluetoothConstants.STATE_FAILED -> {
                            eventSink?.success(0)
                        }
                    }
                }

                BluetoothConstants.MESSAGE_TOAST -> {
                    val bundle = msg.data
                    val toastId = bundle?.getInt(BluetoothConnection.TOAST)
                    val ctx = context
                    if (toastId != null && ctx != null) {
                        try {
                            Toast.makeText(ctx, ctx.getString(toastId), Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    // ==========================
    // ENGINE LIFECYCLE
    // ==========================

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {

        channel = MethodChannel(binding.binaryMessenger, methodChannel)
        channel.setMethodCallHandler(this)

        messageChannel = EventChannel(binding.binaryMessenger, eventChannelBT)
        messageChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                eventSink = sink
            }
            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })

        messageUSBChannel = EventChannel(binding.binaryMessenger, eventChannelUSB)
        messageUSBChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                eventUSBSink = sink
            }
            override fun onCancel(arguments: Any?) {
                eventUSBSink = null
            }
        })

        context = binding.applicationContext
        adapter = USBPrinterService.getInstance(usbHandler)
        context?.let { adapter.init(it) }

        bluetoothService = BluetoothService.getInstance(bluetoothHandler)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        messageChannel?.setStreamHandler(null)
        messageUSBChannel?.setStreamHandler(null)
        bluetoothService.setHandler(null)
        adapter.setHandler(null)
    }

    // ==========================
    // METHOD CHANNEL
    // ==========================

    override fun onMethodCall(call: MethodCall, result: Result) {

        when (call.method) {

            "getBluetoothList" -> {
                isBle = false
                isScan = true
                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.scanBluDevice(channel)
                    result.success(null)
                } else result.success(false)
            }

            "onStartConnection" -> {

                val ctx = context
                val address: String? = call.argument("address")
                val isBleArg: Boolean? = call.argument("isBle")
                val autoConnect: Boolean =
                    if (call.hasArgument("autoConnect"))
                        call.argument<Boolean>("autoConnect") ?: false
                    else false

                if (ctx == null || address == null || isBleArg == null) {
                    result.success(false)
                    return
                }

                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.setHandler(bluetoothHandler)
                    bluetoothService.onStartConnection(
                        ctx,
                        address,
                        result,
                        isBle = isBleArg,
                        autoConnect = autoConnect
                    )
                } else {
                    result.success(false)
                }
            }

            else -> result.notImplemented()
        }
    }

    // ==========================
    // PERMISSIONS & BLUETOOTH
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

    private fun checkPermissions(): Boolean {

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (!hasPermissions(context, *permissions.toTypedArray())) {

            val activity = currentActivity ?: return false

            ActivityCompat.requestPermissions(
                activity,
                permissions.toTypedArray(),
                PERMISSION_ALL
            )
            return false
        }

        return true
    }

    private fun hasPermissions(context: Context?, vararg permissions: String): Boolean {
        if (context == null) return false
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(context, permission)
                != PackageManager.PERMISSION_GRANTED
            ) return false
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
            if (resultCode == Activity.RESULT_OK && isScan) {
                if (isBle)
                    bluetoothService.scanBleDevice(channel)
                else
                    bluetoothService.scanBluDevice(channel)
            }
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
                        R.string.not_permissions,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                if (verifyIsBluetoothIsOn() && isScan) {
                    if (isBle)
                        bluetoothService.scanBleDevice(channel)
                    else
                        bluetoothService.scanBluDevice(channel)
                }
            }
            return true
        }

        return false
    }

    companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
        const val methodChannel = "com.codingdevs.thermal_printer"
        const val eventChannelBT = "com.codingdevs.thermal_printer/bt_state"
        const val eventChannelUSB = "com.codingdevs.thermal_printer/usb_state"
    }
}
package com.akshat.edithglasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, SENDING }

class BleManager(private val context: Context) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var textCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    private val _discoveredDeviceName = MutableStateFlow<String?>(null)
    val discoveredDeviceName: StateFlow<String?> = _discoveredDeviceName.asStateFlow()

    private val pendingChunks = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName
            if (name == BleProtocol.DEVICE_NAME) {
                _discoveredDeviceName.value = name
                stopScan()
                connectToDevice(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            _statusMessage.value = "Scan failed (code $errorCode)"
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled) {
            _statusMessage.value = "Bluetooth is off"
            return
        }
        if (scanner == null) {
            _statusMessage.value = "BLE scanner unavailable"
            return
        }
        _connectionState.value = ConnectionState.SCANNING
        _statusMessage.value = "Scanning for ${BleProtocol.DEVICE_NAME}..."
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(BleProtocol.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            _statusMessage.value = "Missing Bluetooth permission"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Connecting to ${device.name ?: device.address}..."
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _statusMessage.value = "Connected — discovering services..."
                    g.requestMtu(BleProtocol.REQUESTED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _statusMessage.value = "Disconnected"
                    textCharacteristic = null
                    pendingChunks.clear()
                    writeInFlight = false
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _statusMessage.value = "Service discovery failed"
                return
            }
            val service: BluetoothGattService? = g.getService(BleProtocol.SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleProtocol.TEXT_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                _statusMessage.value = "EDITH service not found on device"
                return
            }
            textCharacteristic = characteristic
            _connectionState.value = ConnectionState.CONNECTED
            _statusMessage.value = "Connected"
            drainQueue()
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _statusMessage.value = "BLE write error ($status), retrying..."
            }
            drainQueue()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        gatt?.disconnect()
    }

    fun sendText(text: String) {
        enqueueText(text, includeEnd = true)
    }

    /**
     * Always queues END, even when previous speech chunks have already
     * finished writing. This closes the exact race that could leave the
     * firmware buffer without a completion frame.
     */
    fun finishStreaming() {
        pendingChunks.addLast(byteArrayOf(BleProtocol.FRAME_END))
        _connectionState.value = ConnectionState.SENDING
        drainQueue()
    }

    fun sendStreamingChunk(text: String, isFirstChunk: Boolean) {
        if (text.isBlank()) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val chunkSize =
            (negotiatedMtu - BleProtocol.ATT_HEADER_OVERHEAD - BleProtocol.FRAME_HEADER_SIZE).coerceAtLeast(20)
        val chunks = splitUtf8Safe(bytes, chunkSize)

        chunks.forEachIndexed { index, chunk ->
            val type = when {
                isFirstChunk && index == 0 -> BleProtocol.FRAME_START
                index == chunks.size - 1 -> BleProtocol.FRAME_APPEND
                else -> BleProtocol.FRAME_CONTINUE
            }
            val packet = ByteArray(chunk.size + 1)
            packet[0] = type
            System.arraycopy(chunk, 0, packet, 1, chunk.size)
            pendingChunks.addLast(packet)
        }
        _connectionState.value = ConnectionState.SENDING
        drainQueue()
    }

    private fun enqueueText(text: String, includeEnd: Boolean) {
        val payloadBytes = text.toByteArray(StandardCharsets.UTF_8)
        val chunkSize =
            (negotiatedMtu - BleProtocol.ATT_HEADER_OVERHEAD - BleProtocol.FRAME_HEADER_SIZE).coerceAtLeast(20)
        val chunks = splitUtf8Safe(payloadBytes, chunkSize)

        if (chunks.isEmpty()) {
            pendingChunks.addLast(byteArrayOf(BleProtocol.FRAME_START))
        } else {
            chunks.forEachIndexed { index, chunk ->
                val frameType = when {
                    index == 0 -> BleProtocol.FRAME_START
                    index == chunks.size - 1 -> BleProtocol.FRAME_END
                    else -> BleProtocol.FRAME_CONTINUE
                }
                val packet = ByteArray(chunk.size + 1)
                packet[0] = frameType
                System.arraycopy(chunk, 0, packet, 1, chunk.size)
                pendingChunks.addLast(packet)
            }
        }

        if (includeEnd) pendingChunks.addLast(byteArrayOf(BleProtocol.FRAME_END))
        _connectionState.value = ConnectionState.SENDING
        drainQueue()
    }

    private fun splitUtf8Safe(bytes: ByteArray, maxSize: Int): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val result = mutableListOf<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            var end = (start + maxSize).coerceAtMost(bytes.size)
            while (end < bytes.size && end > start && (bytes[end].toInt() and 0xC0) == 0x80) end--
            if (end <= start) end = (start + maxSize).coerceAtMost(bytes.size)
            result.add(bytes.copyOfRange(start, end))
            start = end
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private fun drainQueue() {
        if (writeInFlight) return
        val characteristic = textCharacteristic
        val g = gatt
        if (characteristic == null || g == null) return

        val next = pendingChunks.pollFirst()
        if (next == null) {
            if (_connectionState.value == ConnectionState.SENDING) {
                _connectionState.value = ConnectionState.CONNECTED
                _statusMessage.value = "Sent"
            }
            return
        }

        writeInFlight = true
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = next

        if (!g.writeCharacteristic(characteristic)) {
            writeInFlight = false
            pendingChunks.addFirst(next)
            _statusMessage.value = "Write failed, retrying..."
        }
    }
}

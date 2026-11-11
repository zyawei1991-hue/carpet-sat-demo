package com.carpet.satdemo.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.carpet.satdemo.core.modem.ModemBytePipe
import com.carpet.satdemo.core.modem.RawLine
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE 那半截管道，判据全部来自自研 Lite APP 3.0.0 的反编译
 * （docs/cloud/gt1005-ble-from-lite-app.md §1/§2）：
 *
 * - 扫描按广播 service UUID `fff0` 过滤（ScanFilter.setServiceUuid），不按设备名；
 * - connectGatt(TRANSPORT_LE, PHY_LE_CODED)，连上后 requestConnectionPriority(BALANCED)
 *   + requestMtu(500)，onMtuChanged 记下分片大小 MTU-3，然后才 discoverServices；
 * - 服务 fff0 下：notify 收自 fff1（写 2902 CCCD），写出 fff2，单写在途；
 * - 无配对码，业务层协议与 UART 完全同一套（SDK 字节流入口）。
 */
class BleBytePipe(private val context: Context) : ModemBytePipe {

    companion object {
        private val SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val NOTIFY = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val WRITE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var byteHandler: ((ByteArray) -> Unit)? = null
    private var rawHandler: ((RawLine) -> Unit)? = null

    @Volatile
    override var isOpen: Boolean = false
        private set

    /** 状态流水，工程模式日志用 */
    val events = ArrayDeque<String>()
    var state: String = "idle"
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /** 单写在途：队列 + 标志，写回调里取下一段（反编译 ByteContainer.k(chunk) 同形） */
    private val outQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private var chunkSize = 20

    fun connect(): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = mgr.adapter ?: run { log("no-adapter"); return false }
        val scanner = adapter.bluetoothLeScanner ?: run { log("no-scanner"); return false }

        state = "scanning"
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()
        scanner.startScan(listOf(filter), settings, scanCb)
        log("scan started (fff0)")
        return true
    }

    fun disconnect() {
        close()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner
            scanner?.stopScan(this)
            val device: BluetoothDevice = result.device
            log("found ${device.address} rssi=${result.rssi}")
            state = "linking"
            if (Build.VERSION.SDK_INT >= 26) {
                device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_CODED)
            } else {
                device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("scan failed: $errorCode")
            state = "scan-failed"
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("gatt state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                if (Build.VERSION.SDK_INT >= 21) {
                    g.requestMtu(500)
                } else {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isOpen = false
                state = "disconnected"
                g.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("mtu=$mtu")
            if (mtu > 3) chunkSize = mtu - 3
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("discover failed: $status")
                return
            }
            val svc = g.getService(SERVICE) ?: run { log("no fff0 service"); return }
            val notifyChar = svc.getCharacteristic(NOTIFY) ?: run { log("no fff1"); return }
            writeChar = svc.getCharacteristic(WRITE) ?: run { log("no fff2"); return }
            if (!g.setCharacteristicNotification(notifyChar, true)) {
                log("enable notify failed")
                return
            }
            notifyChar.getDescriptor(CCCD)?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
                return // 等回调再宣布 LINKED，避免描述符还没写完就发数据
            }
            linked(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) linked(g) else log("cccd write failed: $status")
            } else {
                pumpNext()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing = false
            pumpNext()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid != NOTIFY) return
            val data = if (Build.VERSION.SDK_INT >= 33) {
                c.getValue() // 老 API 在 33 上仍可回退；新 API value 变更走另一重载
            } else {
                c.value
            } ?: return
            if (data.isEmpty()) return
            raw(RawLine(RawLine.Direction.FROM_MODULE, String(data, Charsets.US_ASCII).trim()))
            byteHandler?.invoke(data)
        }
    }

    @SuppressLint("MissingPermission")
    private fun linked(g: BluetoothGatt) {
        gatt = g
        isOpen = true
        state = "linked"
        log("LINKED")
        pumpNext()
    }

    override fun write(bytes: ByteArray) {
        raw(RawLine(RawLine.Direction.TO_MODULE, String(bytes, Charsets.US_ASCII).trim()))
        synchronized(outQueue) {
            var i = 0
            while (i < bytes.size) {
                val end = minOf(i + chunkSize, bytes.size)
                outQueue.add(bytes.copyOfRange(i, end))
                i = end
            }
        }
        pumpNext()
    }

    private fun pumpNext() {
        if (!isOpen || writing) return
        val chunk = synchronized(outQueue) { outQueue.pollFirst() } ?: return
        val g = gatt ?: return
        val c = writeChar ?: return
        writing = true
        if (!writeOnce(g, c, chunk)) {
            writing = false
            log("write init failed")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeOnce(g: BluetoothGatt, c: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        var sent = false
        if (Build.VERSION.SDK_INT >= 33) {
            sent = g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            c.value = payload
            sent = g.writeCharacteristic(c)
        }
        return sent
    }

    override fun onBytes(handler: (ByteArray) -> Unit) {
        byteHandler = handler
    }

    override fun onRawLine(handler: (RawLine) -> Unit) {
        rawHandler = handler
    }

    override fun close() {
        isOpen = false
        state = "closed"
        synchronized(outQueue) { outQueue.clear() }
        writing = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun log(msg: String) {
        synchronized(events) {
            events.addLast("ble: $msg")
            while (events.size > 50) events.removeFirst()
        }
    }

    private fun raw(line: RawLine) {
        rawHandler?.invoke(line)
    }
}

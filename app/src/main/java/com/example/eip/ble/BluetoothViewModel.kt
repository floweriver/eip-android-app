// v1.2.1 | 2026-08-31 | 修正 API 33 以下收不到任何 BLE 通知（缺兩參數回呼）
//
// changelog:
//   v1.2.1 | 2026-08-31 | 補上兩參數版 onCharacteristicChanged。三參數版是 API 33
//                         才新增的，只覆寫它會讓 API 24-32 的裝置（Fire 平板、
//                         舊 Android 平板）收到的每一則通知都被靜默丟棄。
//   v1.2.0 | 2026-08-31 | T-08 診斷用：記錄權限、藍牙開關、掃描被擋下的原因。
//                         原本這些失敗路徑全是靜默的，權限沒給和「真的找不到筆」
//                         在畫面上長得一模一樣。純新增 addLog，行為未變更。
//   v1.1.0 | 2026-08-31 | T-07 診斷用：記錄服務探索實際結果、送出方向、寫入回傳值、
//                         以及所有原本靜默的失敗路徑。純新增 addLog，行為未變更。
//   v1.0.0 | —          | 初版
package com.example.eip.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class LogMessage(val message: String, val type: LogType, val timestamp: Long = System.currentTimeMillis())
enum class LogType { SENT, RECEIVED, INFO, ERROR }

@SuppressLint("MissingPermission")
class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothManager by lazy { getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanJob: Job? = null
    // 連線逾時看門狗：連線卡在 CONNECTING 太久時強制釋放，避免 auto-connect 死鎖
    private var connectTimeoutJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _communicationLog = MutableStateFlow<List<LogMessage>>(emptyList())
    val communicationLog = _communicationLog.asStateFlow()

    private val _pencilSettings = MutableStateFlow<Map<Byte, Byte>>(emptyMap())
    val pencilSettings = _pencilSettings.asStateFlow()

    private val _usiSettings = MutableStateFlow<Map<Byte, Boolean>>(emptyMap()) 
    val usiSettings = _usiSettings.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel = _batteryLevel.asStateFlow()

    private val _firmwareVersion = MutableStateFlow<String>("Unknown")
    val firmwareVersion = _firmwareVersion.asStateFlow()

    private val _shutdownTime = MutableStateFlow<Int>(5)
    val shutdownTime = _shutdownTime.asStateFlow()

    private val _signalLevel = MutableStateFlow<Int>(0)
    val signalLevel = _signalLevel.asStateFlow()

    private var hasReceivedInitialStatus = false

    // 只記錄一次：確認舊版通知路徑真的有被呼叫到（驗證 v1.2.1 修正用）
    private var loggedLegacyNotifyPath = false

    // 手動切換時暫停 auto-connect，避免 loop 搶著把舊筆連回去
    @Volatile private var manualSwitching = false
    private var pendingSwitchTarget: BleDevice? = null
    // 使用者最後選定的裝置，auto-connect 會優先連它
    private var preferredDeviceId: String? = null

    init {
        startAutoConnectLoop()
        startScanning()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val addr = device.address
            // 掃描結果的名稱可能為 null（名稱未帶在廣播封包裡），改用多來源取名
            val name = (try { device.name } catch (e: SecurityException) { null }) ?: result.scanRecord?.deviceName

            _discoveredDevices.update { list ->
                when {
                    // 已在清單中（多半是已配對的 eip 筆）：掃到就代表在附近
                    list.any { it.id == addr } ->
                        list.map { if (it.id == addr) it.copy(isNearby = true) else it }
                    // 掃到名稱且是 eip 筆：新增（涵蓋尚未配對的筆，ChromeOS 常見）
                    name != null && name.contains("eip", ignoreCase = true) && !name.contains("magnetix", ignoreCase = true) -> {
                        addLog("Discovered $name ($addr)", LogType.INFO)
                        list + BleDevice(peripheral = device, name = name, rssi = result.rssi, isNearby = true)
                    }
                    else -> list
                }
            }
        }
    }

    fun startScanning() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            while (true) {
                try {
                    // 診斷 (T-08)：掃描迴圈是唯一無條件持續運轉的地方，
                    // 所以環境狀態記錄放這裡；藍牙關閉時 auto-connect 迴圈不會跑。
                    logEnvironmentIfChanged()

                    val adapter = bluetoothAdapter
                    val blockReason = when {
                        adapter == null -> "no Bluetooth adapter on this device"
                        !adapter.isEnabled -> "Bluetooth adapter is off"
                        !canScan() -> "${scanPermissionName()} not granted"
                        adapter.bluetoothLeScanner == null -> "bluetoothLeScanner unavailable"
                        else -> null
                    }
                    logScanBlockIfChanged(blockReason)

                    if (blockReason == null) {
                        val scanner = adapter?.bluetoothLeScanner
                        if (scanner != null) {
                            scanner.startScan(scanCallback)
                            delay(4000)
                            scanner.stopScan(scanCallback)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BleDebug", "Scan Error: ${e.message}")
                    addLog("X SCAN ERROR - ${e.message}", LogType.ERROR)
                }
                delay(3000)
            }
        }
    }

    private fun canScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startAutoConnectLoop() {
        viewModelScope.launch {
            delay(1000)
            while (true) {
                try {
                    if (!manualSwitching && bluetoothAdapter?.isEnabled == true) {
                        loadConnectedDevices()

                        val currentDevices = _discoveredDevices.value
                        val activeDevice = currentDevices.firstOrNull {
                            it.connectionState == DeviceConnectionState.READY ||
                            it.connectionState == DeviceConnectionState.CONNECTING ||
                            it.connectionState == DeviceConnectionState.CONNECTED ||
                            it.connectionState == DeviceConnectionState.DISCOVERING_SERVICES
                        }

                        if (activeDevice == null) {
                            val candidates = currentDevices.filter {
                                it.connectionState == DeviceConnectionState.DISCONNECTED && it.isNearby
                            }
                            val target = candidates.firstOrNull { it.id == preferredDeviceId } ?: candidates.firstOrNull()
                            if (target != null) {
                                connect(target)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BleDebug", "AutoConnect Loop Error: ${e.message}")
                }
                delay(5000)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) { try { gatt.close() } catch (_: Exception) {}; return }
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    addLog("Device connected: $deviceAddress", LogType.INFO)
                    updateDeviceConnectionState(deviceAddress, DeviceConnectionState.DISCOVERING_SERVICES)
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    addLog("Device disconnected: $deviceAddress", LogType.INFO)
                    updateDeviceConnectionState(deviceAddress, DeviceConnectionState.DISCONNECTED)
                    closeGatt()
                    resetState()
                }
            } else {
                addLog("GATT Error: status=$status", LogType.ERROR)
                updateDeviceConnectionState(deviceAddress, DeviceConnectionState.DISCONNECTED)
                closeGatt()
                resetState()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return

            // --- 診斷日誌 (T-07)：確認服務探索到底拿到了什麼 ---
            // 目前 READY 是在「確認 ffe0 存在」之前就設定的，所以服務沒解析出來時
            // 畫面照樣顯示連線正常，而每個指令都會被靜默丟棄。這幾行讓它現形。
            val services = gatt.services ?: emptyList()
            addLog("Services discovered: status=$status count=${services.size}", LogType.INFO)
            addLog("  UUIDs: " + (if (services.isEmpty()) "(none)" else services.joinToString(", ") { svc ->
                val u = svc.uuid.toString()
                if (u.endsWith("-0000-1000-8000-00805f9b34fb")) u.substring(4, 8) else u
            }), LogType.INFO)
            val pencilSvc = gatt.getService(BleProtocol.Pencil.SERVICE_UUID)
            if (pencilSvc == null) {
                addLog("X ffe0 NOT FOUND - every command will be silently dropped", LogType.ERROR)
            } else {
                val w = pencilSvc.getCharacteristic(BleProtocol.Pencil.WRITE_CHAR_UUID)
                val n = pencilSvc.getCharacteristic(BleProtocol.Pencil.NOTIFY_CHAR_UUID)
                addLog("ffe0 OK - write(ffe2)=${w != null} notify(ffe3)=${n != null}",
                    if (w != null && n != null) LogType.INFO else LogType.ERROR)
            }
            // --- 診斷日誌結束 ---

            if (status == BluetoothGatt.GATT_SUCCESS) {
                connectTimeoutJob?.cancel()   // 連線成功，取消逾時看門狗
                updateDeviceConnectionState(gatt.device.address, DeviceConnectionState.READY)
                toggleNotifications(BleProtocol.Pencil.SERVICE_UUID.toString(), BleProtocol.Pencil.NOTIFY_CHAR_UUID.toString(), true)
                queryInitialStatus()
            } else {
                addLog("X Service discovery FAILED: status=$status", LogType.ERROR)
            }
        }

        // API 33+ 走這個
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt !== bluetoothGatt) return
            processReceivedData(value)
        }

        // API 33 以下走這個。三參數版本是 API 33 才新增的，框架在舊系統上
        // 只認得這個兩參數版；不覆寫它，Fire 平板等 API 24-32 裝置收到的
        // 每一則通知都會被靜默丟棄（送得出去、收不回來）。
        // 框架依 API 等級只會呼叫其中一個，不會重複處理。
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== bluetoothGatt) return
            if (!loggedLegacyNotifyPath) {
                loggedLegacyNotifyPath = true
                addLog("Notify via legacy 2-arg callback (API ${Build.VERSION.SDK_INT})", LogType.INFO)
            }
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            processReceivedData(value)
        }
    }

    private fun processReceivedData(value: ByteArray) {
        if (value.size >= 3 && value[0] == BleProtocol.DEVICE_HEADER_0 && value[1] == BleProtocol.DEVICE_HEADER_1) {
            val hex = value.joinToString(" ") { "%02X".format(it) }
            addLog("← RECV: $hex", LogType.RECEIVED)
            when (val cmd = value[2]) {
                BleProtocol.Pencil.CMD_QUERY -> {
                    if (value.size == 7) {
                        val deviceKeyCode = value[3]
                        val funcCode = value[5]
                        _pencilSettings.update { existing ->
                            if (!hasReceivedInitialStatus || !existing.containsKey(deviceKeyCode)) {
                                existing + (deviceKeyCode to funcCode)
                            } else {
                                existing
                            }
                        }
                    }
                }
                BleProtocol.Pencil.CMD_FIRMWARE_QUERY -> {
                    if (value.size >= 5) {
                        val major = "%02X".format(value[3])
                        val minor = "%02X".format(value[4])
                        _firmwareVersion.value = "$major.$minor"
                    }
                }
                BleProtocol.Pencil.CMD_STATUS_QUERY -> {
                    if (value.size >= 6 && !hasReceivedInitialStatus) {
                        _signalLevel.value = value[4].toInt()
                        _shutdownTime.value = value[5].toInt()
                        hasReceivedInitialStatus = true
                    }
                }
            }
        }
    }

    private fun resetState() {
        _pencilSettings.value = emptyMap()
        _batteryLevel.value = null
        _firmwareVersion.value = "Unknown"
        _shutdownTime.value = 5
        _signalLevel.value = 0
        hasReceivedInitialStatus = false
    }

    private fun closeGatt() {
        connectTimeoutJob?.cancel()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e("BleDebug", "Error closing GATT: ${e.message}")
        }
        bluetoothGatt = null
    }

    fun queryInitialStatus() {
        viewModelScope.launch {
            delay(1500)
            sendPencilCommand(BleProtocol.Pencil.CMD_FIRMWARE_QUERY, null, byteArrayOf(0x00))
            delay(20)
            for (key in (1..6)) {
                queryPencilSettings(key.toByte())
                delay(20)
            }
            sendPencilCommand(BleProtocol.Pencil.CMD_STATUS_QUERY, null, byteArrayOf(0x00))
        }
    }

    fun setPencilFunctionWithRetry(keyCode: Byte, funcCode: Byte) {
        hasReceivedInitialStatus = true
        _pencilSettings.update { it + (keyCode to funcCode) }
        sendPencilCommand(BleProtocol.Pencil.CMD_SET, keyCode, byteArrayOf(funcCode))
    }

    fun queryPencilSettings(keyCode: Byte) {
        sendPencilCommand(BleProtocol.Pencil.CMD_QUERY, keyCode, byteArrayOf(0x00))
    }

    fun setSmartShutdownTime(minutes: Int) {
        hasReceivedInitialStatus = true
        sendPencilCommand(BleProtocol.Pencil.CMD_SMART_SHUTDOWN, null, byteArrayOf(minutes.toByte()))
        _shutdownTime.value = minutes
    }

    fun setSignalLevel(level: Int) {
        hasReceivedInitialStatus = true
        val levelByte = (level - 1).coerceIn(0, 4).toByte()
        sendPencilCommand(BleProtocol.Pencil.CMD_SIGNAL_ADJUST, null, byteArrayOf(levelByte))
        _signalLevel.value = levelByte.toInt()
    }

    fun setUsiSwitch(cmd: Byte, enable: Boolean) {
        val data = if (enable) byteArrayOf(0x01) else byteArrayOf(0x00)
        sendPencilCommand(cmd, null, data)
        _usiSettings.update { it + (BleProtocol.Pencil.CMD_TAIL_USI_QUERY to enable) }
    }

    fun factoryReset() {
        sendPencilCommand(BleProtocol.Pencil.CMD_FACTORY_RESET, null, byteArrayOf(0x00))
        resetState()
        queryInitialStatus()
    }

    fun sendPencilCommand(cmd: Byte, keyCode: Byte?, data: ByteArray, useCrc: Boolean = true) {
        val hasKeyCode = keyCode != null
        val len = 2 + 1 + (if (hasKeyCode) 1 else 0) + 1 + data.size + (if (useCrc) 1 else 0)
        val pkt = ByteArray(len)
        pkt[0] = 0x55; pkt[1] = 0xAA.toByte(); pkt[2] = cmd
        var offset = 3
        if (hasKeyCode) pkt[offset++] = keyCode!!
        pkt[offset++] = data.size.toByte()
        if (data.isNotEmpty()) System.arraycopy(data, 0, pkt, offset, data.size)
        if (useCrc) pkt[len - 1] = calculateCRC8Maxim(pkt.copyOf(len - 1))
        sendCommand(BleProtocol.Pencil.SERVICE_UUID.toString(), BleProtocol.Pencil.WRITE_CHAR_UUID.toString(), pkt)
    }

    private fun sendCommand(serviceUUID: String, charUUID: String, packet: ByteArray) {
        // 診斷日誌 (T-07)：原本三個失敗點都是靜默 return，送出方向完全沒有記錄。
        // 拆開來逐一記錄，才分得清「沒送出」「送了被拒」「送成功但沒回應」。
        val hex = packet.joinToString(" ") { "%02X".format(it) }
        val shortSvc = serviceUUID.substring(4, 8)
        val shortChar = charUUID.substring(4, 8)

        val gatt = bluetoothGatt
        if (gatt == null) {
            addLog("X SEND ABORT - no GATT: $hex", LogType.ERROR)
            return
        }
        val service = gatt.getService(UUID.fromString(serviceUUID))
        if (service == null) {
            addLog("X SEND ABORT - service $shortSvc not found: $hex", LogType.ERROR)
            return
        }
        val characteristic = service.getCharacteristic(UUID.fromString(charUUID))
        if (characteristic == null) {
            addLog("X SEND ABORT - char $shortChar not found: $hex", LogType.ERROR)
            return
        }
        val result: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // BluetoothStatusCodes.SUCCESS == 0；用字面值避免在舊版裝置觸發類別解析
            val rc = gatt.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (rc == 0) "ok" else "rc=$rc"
        } else {
            @Suppress("DEPRECATION") characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") characteristic.value = packet
            @Suppress("DEPRECATION") val ok = gatt.writeCharacteristic(characteristic)
            if (ok) "ok" else "rejected"
        }
        addLog("-> SENT: $hex [$result]", LogType.SENT)
    }

    fun toggleNotifications(serviceUUID: String, charUUID: String, enable: Boolean) {
        // 診斷日誌 (T-07)：CCCD 沒寫成功 = 通知永遠不會來 = 全部顯示 No Function。
        // 原本兩個失敗點都是靜默跳過。行為不變，只是讓失敗現形。
        val characteristic = bluetoothGatt?.getService(UUID.fromString(serviceUUID))?.getCharacteristic(UUID.fromString(charUUID))
        if (characteristic == null) {
            addLog("X NOTIFY ABORT - ${serviceUUID.substring(4, 8)}/${charUUID.substring(4, 8)} not found", LogType.ERROR)
            return
        }
        val setOk = bluetoothGatt?.setCharacteristicNotification(characteristic, enable)
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor == null) {
            addLog("X NOTIFY ABORT - CCCD(2902) not found, notifications will never arrive", LogType.ERROR)
            return
        }
        val valBytes = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeDescriptor(descriptor, valBytes)
        } else {
            @Suppress("DEPRECATION") descriptor.value = valBytes
            @Suppress("DEPRECATION") bluetoothGatt?.writeDescriptor(descriptor)
        }
        addLog("Notify=$enable - setCharNotification=$setOk, CCCD write issued", LogType.INFO)
    }

    fun calculateCRC8Maxim(data: ByteArray): Byte {
        var crc = 0x00
        for (b in data) {
            var v = (crc xor (b.toInt() and 0xFF)) and 0xFF
            for (i in 0 until 8) v = if ((v and 1) == 1) (v ushr 1) xor 0x8C else v ushr 1
            crc = v
        }
        return crc.toByte()
    }

    fun loadConnectedDevices() {
        // 診斷 (T-08)：這兩個 return 原本是靜默的，裝置清單為空時看不出原因。
        if (bluetoothAdapter == null) {
            logLoadBlockIfChanged("no Bluetooth adapter")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            logLoadBlockIfChanged("BLUETOOTH_CONNECT not granted")
            return
        }
        logLoadBlockIfChanged(null)

        try {
            val bonded = bluetoothAdapter!!.bondedDevices
            val connectedToSystem = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)

            fun isPencil(name: String?): Boolean {
                val n = name ?: return false
                return n.contains("eip", ignoreCase = true) && !n.contains("magnetix", ignoreCase = true)
            }

            val existingList = _discoveredDevices.value
            // 用 address 當 key 合併，保留插入順序
            val result = LinkedHashMap<String, BleDevice>()

            // 1. 已配對的 eip 筆 → 直接視為可連線。
            //    ChromeOS 上筆常已連線但「不再廣播」，掃描與 getConnectedDevices(GATT) 都偵測不到，
            //    因此不能依賴這兩個來源；已配對代表使用者綁定過，直接允許連線，連不上再回到 DISCONNECTED。
            bonded.filter { isPencil(try { it.name } catch (e: SecurityException) { null }) }.forEach { d ->
                val existing = existingList.find { it.id == d.address }
                result[d.address] = existing?.copy(isNearby = true)
                    ?: BleDevice(peripheral = d, name = d.name ?: "eiP Device", rssi = -1, isNearby = true)
            }

            // 2. 系統已連線但「未配對」的 eip 筆（ChromeOS 常見：連線但不 bond）
            connectedToSystem.filter { isPencil(try { it.name } catch (e: SecurityException) { null }) }.forEach { d ->
                if (!result.containsKey(d.address)) {
                    val existing = existingList.find { it.id == d.address }
                    result[d.address] = existing?.copy(isNearby = true)
                        ?: BleDevice(peripheral = d, name = d.name ?: "eiP Device", rssi = -1, isNearby = true)
                }
            }

            // 3. 保留掃描發現、既未配對也未被系統列為已連線的 eip 筆
            existingList.forEach { dev ->
                if (!result.containsKey(dev.id)) result[dev.id] = dev
            }

            _discoveredDevices.value = result.values.toList()
        } catch (e: Exception) {
            Log.e("BleDebug", "Load Bonded Fail: ${e.message}")
        }
    }

    fun connect(device: BleDevice) {
        // 診斷日誌 (T-02/T-07)：記錄實際連線目標與 bond 狀態，並讓「因 GATT 忙碌
        // 而被吞掉的點擊」現形（畫面標題寫死 USI 2.0 Ultra，看不出連到誰）。
        if (bluetoothGatt != null) {
            addLog("Connect ignored (GATT busy) -> ${device.name}", LogType.INFO)
            return
        }
        preferredDeviceId = device.id
        val bond = when (device.peripheral?.bondState) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_NONE -> "not bonded"
            else -> "unknown"
        }
        addLog("Connecting -> ${device.name} [${device.id}] ($bond)", LogType.INFO)
        updateDeviceConnectionState(device.id, DeviceConnectionState.CONNECTING, true)
        bluetoothGatt = device.peripheral?.connectGatt(getApplication(), false, gattCallback)
        startConnectTimeout(device.id)
    }

    // 連線 12 秒內若未進入 READY，視為失敗：釋放 GATT 並回到 DISCONNECTED，讓 auto-connect 下一輪重試。
    // 這可打破「連線卡在 CONNECTING → auto-connect 以為還在連 → 永不重試」的死鎖。
    private fun startConnectTimeout(address: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewModelScope.launch {
            delay(12000)
            val dev = _discoveredDevices.value.find { it.id == address }
            if (dev != null && dev.connectionState != DeviceConnectionState.READY) {
                addLog("Connect timeout, retrying: $address", LogType.ERROR)
                closeGatt()
                updateDeviceConnectionState(address, DeviceConnectionState.DISCONNECTED)
                resetState()
            }
        }
    }

    /**
     * 手動切換到另一隻筆：先斷開目前連線，等 BLE 協定堆疊沉澱後再連新的。
     * 這類筆一次只能維持一條 GATT，所以切換 = 斷 A → 連 B。
     */
    fun switchTo(target: BleDevice) {
        val current = _discoveredDevices.value.firstOrNull {
            it.connectionState != DeviceConnectionState.DISCONNECTED
        }
        if (current?.id == target.id) return                       // 已經是這隻
        if (target.peripheral == null || !target.isNearby) return  // 離線無法連
        preferredDeviceId = target.id

        if (bluetoothGatt == null && current == null) {
            connect(target)
            return
        }

        pendingSwitchTarget = target
        manualSwitching = true
        addLog("Switching to ${target.name}", LogType.INFO)
        viewModelScope.launch {
            closeGatt()
            current?.let { updateDeviceConnectionState(it.id, DeviceConnectionState.DISCONNECTED) }
            resetState()
            delay(600)
            val next = pendingSwitchTarget
            pendingSwitchTarget = null
            manualSwitching = false
            if (next != null) connect(next)
        }
    }

    fun disconnect() { closeGatt() }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 被銷毀（Activity finish）時務必關閉 GATT，
        // 否則連線洩漏會讓筆一直被舊連線佔住，下次開 App 連不上。
        closeGatt()
    }
    fun clearLogs() { _communicationLog.value = emptyList() }
    fun addLog(message: String, type: LogType) { viewModelScope.launch { _communicationLog.update { (it + LogMessage(message, type)).takeLast(100) } } }
    
    private fun updateDeviceConnectionState(address: String, state: DeviceConnectionState, isConnecting: Boolean = false) {
        viewModelScope.launch {
            _discoveredDevices.update { list ->
                list.map { d ->
                    if (d.id == address) d.copy(connectionState = state)
                    else if (isConnecting) d.copy(connectionState = DeviceConnectionState.DISCONNECTED)
                    else d
                }
            }
        }
    }
    // --- 診斷 (T-08)：權限與藍牙狀態原本完全沒有記錄。掃描迴圈的三個前置條件
    //     任一不成立就靜默空轉，使用者只看到「找不到你的筆」，分不出是權限沒給、
    //     藍牙沒開，還是真的掃不到。以下只在「狀態改變」時記錄，避免每幾秒洗版。---
    private var lastEnvSummary: String? = null
    private var lastScanBlockReason: String? = null
    private var lastLoadBlockReason: String? = null

    private fun permLabel(permission: String): String =
        if (hasPermission(permission)) "granted" else "DENIED"

    private fun scanPermissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "BLUETOOTH_SCAN" else "ACCESS_FINE_LOCATION"

    private fun logEnvironmentIfChanged() {
        val api = Build.VERSION.SDK_INT
        val perms = if (api >= Build.VERSION_CODES.S) {
            "SCAN=${permLabel(Manifest.permission.BLUETOOTH_SCAN)} " +
                "CONNECT=${permLabel(Manifest.permission.BLUETOOTH_CONNECT)}"
        } else {
            "BLUETOOTH=${permLabel(Manifest.permission.BLUETOOTH)} " +
                "ADMIN=${permLabel(Manifest.permission.BLUETOOTH_ADMIN)} " +
                "FINE_LOCATION=${permLabel(Manifest.permission.ACCESS_FINE_LOCATION)}"
        }
        val adapter = bluetoothAdapter
        val adapterState = when {
            adapter == null -> "ABSENT"
            adapter.isEnabled -> "enabled"
            else -> "OFF"
        }
        val summary = "Perm: $perms (API $api) | Adapter: $adapterState"
        if (summary != lastEnvSummary) {
            lastEnvSummary = summary
            val bad = perms.contains("DENIED") || adapterState != "enabled"
            addLog(summary, if (bad) LogType.ERROR else LogType.INFO)
        }
    }

    private fun logScanBlockIfChanged(reason: String?) {
        if (reason == lastScanBlockReason) return
        lastScanBlockReason = reason
        if (reason == null) addLog("Scanning active", LogType.INFO)
        else addLog("X SCAN BLOCKED - $reason", LogType.ERROR)
    }

    private fun logLoadBlockIfChanged(reason: String?) {
        if (reason == lastLoadBlockReason) return
        lastLoadBlockReason = reason
        if (reason != null) addLog("X DEVICE LIST BLOCKED - $reason", LogType.ERROR)
    }

    private fun hasPermission(permission: String): Boolean = ActivityCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED
}

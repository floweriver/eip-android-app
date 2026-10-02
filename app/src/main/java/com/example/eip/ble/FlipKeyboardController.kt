// v1.0.0 | 2026-10-01 | 初版：eiP Flip Keyboard 的設定狀態與收發流程
//
// changelog:
//   v1.0.0 | 2026-10-01 | 自 iOS 版 FlipKeyboardView 的狀態邏輯移植。獨立於
//                         BluetoothViewModel 之外，ViewModel 只負責把連線事件與
//                         收到的封包轉進來，筆的邏輯不受影響。
package com.example.eip.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 狀態訊息。圖示與顏色由 kind 決定，文字由畫面依 message 取對應語言的字串，
 * 這一層不持有任何顯示文字。
 */
data class FlipStatus(
    val kind: Kind,
    val message: Message,
    val mode: FlipMode? = null,
    val minutes: Int? = null
) {
    enum class Kind { INFO, SUCCESS, WARNING }

    enum class Message {
        NOT_LOADED, DISCONNECTED, LOADING, QUERY_FAILED,
        ALL_LOADED, LOADED_CURRENT, LOADED_NO_CURRENT,
        SENT, APPLIED, FIELD_ERROR, SEND_FAILED,
        SLEEP_SENT, SLEEP_SET, SLEEP_ERROR
    }
}

data class FlipUiState(
    /** 三台裝置的設定，index 0~2 對應裝置1~3。畫面上的修改先存在這裡，按送出才寫進鍵盤。 */
    val modes: List<FlipModeConfig> = List(3) { FlipModeConfig() },
    /**
     * 鍵盤已確認寫入的設定，來源是設定應答或查詢回覆。與 modes 比對即可判斷
     * 有無「改了但還沒送出」的變更。
     */
    val sentModes: List<FlipModeConfig?> = List(3) { null },
    val sleepMinutes: Int = 5,
    val sentSleepMinutes: Int? = null,
    /** 鍵盤回報的當前作用中裝置。 */
    val activeMode: FlipMode? = null,
    /** 查詢讀回當前裝置的次數。畫面據此跳到該裝置的分頁，重新讀取時即使裝置沒變也會跳。 */
    val activeReportCount: Int = 0,
    val status: FlipStatus = FlipStatus(FlipStatus.Kind.INFO, FlipStatus.Message.NOT_LOADED)
) {
    fun hasUnsentChanges(mode: FlipMode): Boolean {
        val sent = sentModes[mode.index] ?: return false
        return sent != modes[mode.index]
    }

    val hasUnsentSleepChange: Boolean
        get() = sentSleepMinutes != null && sentSleepMinutes != sleepMinutes
}

/**
 * eiP Flip Keyboard 的設定流程：查詢、送出、解析回覆。
 *
 * GATT 回呼在 binder 執行緒、使用者操作在主執行緒，兩邊都會進來，
 * 因此所有進入點皆以 @Synchronized 保護。
 *
 * @param send 實際寫入特徵值；回傳是否成功交給藍牙堆疊。
 */
class FlipKeyboardController(
    private val scope: CoroutineScope,
    private val send: (ByteArray) -> Boolean,
    private val queryTimeoutMs: Long = 3000L,
    private val currentModeTimeoutMs: Long = 1500L
) {
    private val _state = MutableStateFlow(FlipUiState())
    val state = _state.asStateFlow()

    private var queryInProgress = false

    // 查詢期間已收到設定回覆的裝置。
    // 第 4 則「當前裝置」的格式與前三則相同，無從由內容分辨；但前三則每個 Cmd
    // 各出現一次，第 4 則必定重複其中一個，故以「再次收到已出現過的 Cmd」判定。
    // 這比單純數第幾則更耐亂序或漏回。
    private val queriedModes = mutableSetOf<FlipMode>()

    // 鍵盤回覆不足 4 則時用來收尾。協議文件載明第 4 則格式「待定」，韌體未必會送。
    // 少了逾時，queryInProgress 會永遠為真，之後的主動上報就會被誤判成查詢回覆。
    private var queryTimeoutJob: Job? = null

    // 各裝置在各系統底下暫存的三顆鍵，外層 key 為裝置索引。
    // 換系統必須換一組功能碼，但使用者可能只是順手看看別的系統；
    // 沒有這份暫存的話切回來就是一片空白，等同誤刪設定。
    private val keyDrafts = mutableMapOf<Int, MutableMap<FlipSystem, List<Int>>>()

    // --- 連線事件 ---

    /** 通知已開啟、可以收發。讀回鍵盤現況，讓畫面反映實際設定與當前裝置。 */
    @Synchronized
    fun onReady() {
        query()
    }

    /** 斷線。清掉所有狀態，下次連上（可能是另一把鍵盤）重新讀取。 */
    @Synchronized
    fun onDisconnected() {
        queryTimeoutJob?.cancel()
        queryTimeoutJob = null
        queryInProgress = false
        queriedModes.clear()
        keyDrafts.clear()
        _state.value = FlipUiState(
            status = FlipStatus(FlipStatus.Kind.WARNING, FlipStatus.Message.DISCONNECTED)
        )
    }

    // --- 畫面上的修改（只存本地，不送出）---

    /**
     * 更換某台裝置的系統。
     *
     * 功能碼綁定系統，換系統後舊碼會顯示成別的系統的功能，因此不能直接留著；
     * 但也不能直接清空——那樣誤點一下系統，原本設好的三顆鍵就消失了。
     * 改為把每個系統的設定各自暫存，切回來時原樣還原。
     */
    @Synchronized
    fun setSystem(mode: FlipMode, system: FlipSystem) {
        val index = mode.index
        val current = _state.value.modes[index]
        if (system == current.system) return
        keyDrafts.getOrPut(index) { mutableMapOf() }[current.system] = current.keyCodes
        setMode(index, FlipModeConfig(system, restoredKeyCodes(system, index)))
    }

    /** 取回某系統先前的三顆鍵：優先用本次操作暫存的，其次用鍵盤已確認的，兩者皆無才視為未設定。 */
    private fun restoredKeyCodes(system: FlipSystem, index: Int): List<Int> {
        keyDrafts[index]?.get(system)?.let { return it }
        val sent = _state.value.sentModes[index]
        if (sent != null && sent.system == system) return sent.keyCodes
        return listOf(0x00, 0x00, 0x00)
    }

    @Synchronized
    fun setKeyCode(mode: FlipMode, keyIndex: Int, code: Int) {
        val current = _state.value.modes[mode.index]
        val keyCodes = current.keyCodes.toMutableList().also { it[keyIndex] = code }
        setMode(mode.index, current.copy(keyCodes = keyCodes))
    }

    @Synchronized
    fun setSleepMinutes(minutes: Int) {
        _state.update { it.copy(sleepMinutes = minutes.coerceIn(FlipProtocol.SLEEP_RANGE)) }
    }

    // --- 送出 ---

    @Synchronized
    fun sendMode(mode: FlipMode) {
        val config = _state.value.modes[mode.index]
        val ok = send(FlipProtocol.modeCommand(mode, config.system, config.keyCodes))
        setStatus(
            if (ok) FlipStatus(FlipStatus.Kind.SUCCESS, FlipStatus.Message.SENT, mode = mode)
            else FlipStatus(FlipStatus.Kind.WARNING, FlipStatus.Message.SEND_FAILED)
        )
    }

    @Synchronized
    fun sendSleep() {
        val minutes = _state.value.sleepMinutes
        val ok = send(FlipProtocol.sleepCommand(minutes))
        setStatus(
            if (ok) FlipStatus(FlipStatus.Kind.SUCCESS, FlipStatus.Message.SLEEP_SENT, minutes = minutes)
            else FlipStatus(FlipStatus.Kind.WARNING, FlipStatus.Message.SEND_FAILED)
        )
    }

    /** 送出查詢（Cmd 0xFF），鍵盤會依序回 4 則：裝置1~3 的設定，末則為當前裝置。 */
    @Synchronized
    fun query() {
        queryInProgress = true
        queriedModes.clear()
        if (send(FlipProtocol.queryCommand())) {
            setStatus(FlipStatus(FlipStatus.Kind.INFO, FlipStatus.Message.LOADING))
            scheduleQueryTimeout(queryTimeoutMs)
        } else {
            setStatus(FlipStatus(FlipStatus.Kind.WARNING, FlipStatus.Message.QUERY_FAILED))
            endQuery()
        }
    }

    private fun scheduleQueryTimeout(delayMs: Long) {
        queryTimeoutJob?.cancel()
        queryTimeoutJob = scope.launch {
            delay(delayMs)
            onQueryTimeout()
        }
    }

    @Synchronized
    private fun onQueryTimeout() {
        if (queryInProgress) endQuery()
    }

    private fun endQuery() {
        queryTimeoutJob?.cancel()
        queryTimeoutJob = null
        queryInProgress = false
        if (_state.value.activeMode == null && queriedModes.isNotEmpty()) {
            setStatus(FlipStatus(FlipStatus.Kind.INFO, FlipStatus.Message.LOADED_NO_CURRENT))
        }
    }

    // --- 接收 ---

    @Synchronized
    fun onDataReceived(bytes: ByteArray) {
        val reply = FlipProtocol.parseReply(bytes) ?: return

        // Cmd 0x05 的應答，Data5 帶已生效的休眠時間。
        if (reply.cmd == FlipProtocol.CMD_SLEEP) {
            if (reply.sleepMinutes !in FlipProtocol.SLEEP_RANGE) {
                setStatus(FlipStatus(FlipStatus.Kind.WARNING, FlipStatus.Message.SLEEP_ERROR))
                return
            }
            _state.update {
                it.copy(
                    sleepMinutes = reply.sleepMinutes,
                    sentSleepMinutes = reply.sleepMinutes,
                    status = FlipStatus(FlipStatus.Kind.SUCCESS, FlipStatus.Message.SLEEP_SET, minutes = reply.sleepMinutes)
                )
            }
            return
        }

        val mode = reply.mode ?: return

        if (queryInProgress) {
            if (mode in queriedModes) {
                // 重複出現的 Cmd ＝ 第 4 則「當前作用中的裝置」。
                _state.update {
                    it.copy(
                        activeMode = mode,
                        activeReportCount = it.activeReportCount + 1,
                        status = FlipStatus(FlipStatus.Kind.SUCCESS, FlipStatus.Message.LOADED_CURRENT, mode = mode)
                    )
                }
                endQuery()
            } else {
                queriedModes.add(mode)
                apply(reply, mode)
                if (reply.sleepMinutes in FlipProtocol.SLEEP_RANGE) {
                    _state.update { it.copy(sleepMinutes = reply.sleepMinutes, sentSleepMinutes = reply.sleepMinutes) }
                }
                if (queriedModes.size == FlipMode.entries.size) {
                    setStatus(FlipStatus(FlipStatus.Kind.SUCCESS, FlipStatus.Message.ALL_LOADED))
                    // 三則到齊後只差「當前裝置」，縮短等待。
                    scheduleQueryTimeout(currentModeTimeoutMs)
                }
            }
            return
        }

        if (reply.hasError) {
            setStatus(FlipStatus(FlipStatus.Kind.WARNING, FlipStatus.Message.FIELD_ERROR, mode = mode))
            return
        }

        // 非查詢期間收到 Cmd 0x01~0x03：設定應答（原樣回傳表示已寫入 flash），
        // 或鍵盤端切換系統後的主動上報——兩者格式相同，一併更新。
        apply(reply, mode)
        _state.update {
            it.copy(
                activeMode = mode,
                status = FlipStatus(FlipStatus.Kind.SUCCESS, FlipStatus.Message.APPLIED, mode = mode)
            )
        }
    }

    /** 把鍵盤回報的設定寫回本地狀態，並記為「鍵盤已確認的內容」。 */
    private fun apply(reply: FlipProtocol.Reply, mode: FlipMode) {
        if (reply.hasError) return
        val index = mode.index
        val system = reply.system ?: _state.value.modes[index].system
        val config = FlipModeConfig(system, reply.keyCodes.toList())
        _state.update {
            it.copy(
                modes = it.modes.toMutableList().also { list -> list[index] = config },
                // 回聲代表已寫入 flash，以此為基準判斷後續是否有未送出的變更。
                sentModes = it.sentModes.toMutableList().also { list -> list[index] = config }
            )
        }
        // 一併存入暫存，使用者切去別的系統再切回來時才還原得回來。
        keyDrafts.getOrPut(index) { mutableMapOf() }[system] = config.keyCodes
    }

    private fun setMode(index: Int, config: FlipModeConfig) {
        _state.update { it.copy(modes = it.modes.toMutableList().also { list -> list[index] = config }) }
    }

    private fun setStatus(status: FlipStatus) {
        _state.update { it.copy(status = status) }
    }
}

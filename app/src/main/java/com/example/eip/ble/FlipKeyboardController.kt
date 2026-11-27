// v1.2.0 | 2026-11-27 | 重設與無舊設定時套用各系統預設鍵；可一鍵改掉屬於別的系統的鍵
//
// changelog:
//   v1.2.0 | 2026-11-27 | resetKeys 與 restoredKeyCodes 改用 FlipFunctionCatalog.defaultKeyCodes
//                         （複製／貼上／全選），不再清成三顆全空。新增 replaceForeignKeys，
//                         只把功能碼屬於別的系統的鍵換成預設功能並寫入鍵盤。
//   v1.1.0 | 2026-11-11 | 配合新版畫面（無送出按鈕）：setSystem / setKeyCode / resetKeys 會自動
//                         寫入鍵盤，連續修改只送最後一次；休眠時間於 commitSleep 送出。
//                         新增 systemChosen，鍵盤回報的系統碼無效時視為未選。
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
    /** 三台裝置的設定，index 0~2 對應裝置1~3。 */
    val modes: List<FlipModeConfig> = List(3) { FlipModeConfig() },
    /**
     * 各裝置是否已有系統設定（鍵盤回報了有效系統，或使用者選過）。
     * 「系統模式」是必選項，未選之前快捷鍵區不可操作。
     */
    val systemChosen: List<Boolean> = List(3) { false },
    /** 鍵盤已確認寫入的設定，來源是設定應答或查詢回覆。切換系統時用來還原該系統的三顆鍵。 */
    val sentModes: List<FlipModeConfig?> = List(3) { null },
    val sleepMinutes: Int = 5,
    val sentSleepMinutes: Int? = null,
    /** 鍵盤回報的當前作用中裝置。 */
    val activeMode: FlipMode? = null,
    /** 查詢讀回當前裝置的次數。畫面據此跳到該裝置，重新讀取時即使裝置沒變也會跳。 */
    val activeReportCount: Int = 0,
    val status: FlipStatus = FlipStatus(FlipStatus.Kind.INFO, FlipStatus.Message.NOT_LOADED),
    /** 每次出現警告就加一。畫面據此彈出提示，連續兩次相同的警告也不會漏掉。 */
    val warningCount: Int = 0
)

/**
 * eiP Flip Keyboard 的設定流程：查詢、自動送出、解析回覆。
 *
 * GATT 回呼在 binder 執行緒、使用者操作在主執行緒，兩邊都會進來，
 * 因此所有進入點皆以 @Synchronized 保護。
 *
 * @param send 實際寫入特徵值；回傳是否成功交給藍牙堆疊。
 * @param autoSendDelayMs 修改後等多久才送出。連續修改（例如接連設三顆鍵）只送最後一次；
 *                        設為 0 則立即送出（單元測試用）。
 */
class FlipKeyboardController(
    private val scope: CoroutineScope,
    private val send: (ByteArray) -> Boolean,
    private val queryTimeoutMs: Long = 3000L,
    private val currentModeTimeoutMs: Long = 1500L,
    private val autoSendDelayMs: Long = 350L
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

    // 等待送出的延遲工作，key 為裝置索引。
    // 有工作在等的期間，較早那次送出的回聲不可寫回畫面，否則會蓋掉使用者剛改的內容。
    private val pendingSends = mutableMapOf<Int, Job>()

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
        pendingSends.values.forEach { it.cancel() }
        pendingSends.clear()
        _state.value = FlipUiState(
            status = FlipStatus(FlipStatus.Kind.WARNING, FlipStatus.Message.DISCONNECTED)
        )
    }

    // --- 畫面上的修改（改了就自動送出）---

    /**
     * 選擇或更換某台裝置的系統。
     *
     * 功能碼綁定系統，換系統後舊碼會顯示成別的系統的功能，因此不能直接留著；
     * 但也不能直接清空——那樣誤點一下系統，原本設好的三顆鍵就消失了。
     * 改為把每個系統的設定各自暫存，切回來時原樣還原。
     */
    @Synchronized
    fun setSystem(mode: FlipMode, system: FlipSystem) {
        val index = mode.index
        val current = _state.value.modes[index]
        val wasChosen = _state.value.systemChosen[index]
        if (wasChosen && system == current.system) return
        if (wasChosen) keyDrafts.getOrPut(index) { mutableMapOf() }[current.system] = current.keyCodes
        val config = FlipModeConfig(system, restoredKeyCodes(system, index))
        _state.update {
            it.copy(
                modes = it.modes.toMutableList().also { list -> list[index] = config },
                systemChosen = it.systemChosen.toMutableList().also { list -> list[index] = true }
            )
        }
        scheduleSend(mode)
    }

    /**
     * 取回某系統先前的三顆鍵：優先用本次操作暫存的，其次用鍵盤已確認的；
     * 兩者皆無（第一次選這個系統）就給該系統的預設鍵，鍵盤不會三顆全空。
     */
    private fun restoredKeyCodes(system: FlipSystem, index: Int): List<Int> {
        keyDrafts[index]?.get(system)?.let { return it }
        val sent = _state.value.sentModes[index]
        if (sent != null && sent.system == system) return sent.keyCodes
        return FlipFunctionCatalog.defaultKeyCodes(system)
    }

    @Synchronized
    fun setKeyCode(mode: FlipMode, keyIndex: Int, code: Int) {
        val current = _state.value.modes[mode.index]
        if (current.keyCodes[keyIndex] == code) return
        val keyCodes = current.keyCodes.toMutableList().also { it[keyIndex] = code }
        setMode(mode.index, current.copy(keyCodes = keyCodes))
        scheduleSend(mode)
    }

    /** 把某台裝置的三顆鍵回到該系統的預設（複製／貼上／全選）。尚未選系統時直接忽略。 */
    @Synchronized
    fun resetKeys(mode: FlipMode) {
        val index = mode.index
        if (!_state.value.systemChosen[index]) return
        val current = _state.value.modes[index]
        val reset = current.copy(keyCodes = FlipFunctionCatalog.defaultKeyCodes(current.system))
        keyDrafts.getOrPut(index) { mutableMapOf() }[reset.system] = reset.keyCodes
        setMode(index, reset)
        scheduleSend(mode)
    }

    /**
     * 只把功能碼屬於別的系統的鍵換成該系統的預設功能，其餘的鍵不動，然後寫入鍵盤。
     * 沒有不符的鍵時什麼都不做。
     */
    @Synchronized
    fun replaceForeignKeys(mode: FlipMode) {
        val index = mode.index
        if (!_state.value.systemChosen[index]) return
        val current = _state.value.modes[index]
        val foreign = current.foreignKeyIndices
        if (foreign.isEmpty()) return
        val defaults = FlipFunctionCatalog.defaultKeyCodes(current.system)
        val fixed = current.copy(
            keyCodes = current.keyCodes.mapIndexed { i, code -> if (i in foreign) defaults[i] else code }
        )
        keyDrafts.getOrPut(index) { mutableMapOf() }[fixed.system] = fixed.keyCodes
        setMode(index, fixed)
        scheduleSend(mode)
    }

    /** 拖曳滑桿時只改本地數值；放開後由 [commitSleep] 送出，不必每一格都寫進鍵盤。 */
    @Synchronized
    fun setSleepMinutes(minutes: Int) {
        _state.update { it.copy(sleepMinutes = minutes.coerceIn(FlipProtocol.SLEEP_RANGE)) }
    }

    // --- 送出 ---

    private fun scheduleSend(mode: FlipMode) {
        val index = mode.index
        pendingSends.remove(index)?.cancel()
        if (autoSendDelayMs <= 0L) {
            sendMode(mode)
            return
        }
        lateinit var job: Job
        job = scope.launch {
            delay(autoSendDelayMs)
            flushSend(mode, job)
        }
        pendingSends[index] = job
    }

    @Synchronized
    private fun flushSend(mode: FlipMode, job: Job) {
        // 這段期間若又有新的修改，會換成另一個工作；只有仍是最新那個才送。
        if (pendingSends[mode.index] !== job) return
        pendingSends.remove(mode.index)
        sendMode(mode)
    }

    /** 立即把某台裝置目前的設定寫進鍵盤。 */
    @Synchronized
    fun sendMode(mode: FlipMode) {
        val config = _state.value.modes[mode.index]
        val ok = send(FlipProtocol.modeCommand(mode, config.system, config.keyCodes))
        setStatus(
            if (ok) FlipStatus(FlipStatus.Kind.SUCCESS, FlipStatus.Message.SENT, mode = mode)
            else FlipStatus(FlipStatus.Kind.WARNING, FlipStatus.Message.SEND_FAILED)
        )
    }

    /** 把目前的休眠時間寫進鍵盤（放開滑桿時呼叫）。 */
    @Synchronized
    fun commitSleep() {
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
        // 或鍵盤端切換裝置後的主動上報——兩者格式相同，一併更新。
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
        val reported = reply.system
        val system = reported ?: _state.value.modes[index].system
        val config = FlipModeConfig(system, reply.keyCodes.toList())
        // 還有修改等著送出時，這則回聲對應的是較早的內容；
        // 此時寫回畫面會把使用者剛改的東西蓋掉，等下一則回聲再同步。
        val keepLocalEdits = pendingSends.containsKey(index)
        _state.update {
            it.copy(
                modes = if (keepLocalEdits) it.modes
                        else it.modes.toMutableList().also { list -> list[index] = config },
                // 系統碼無效（例如全新鍵盤回 0x00）時維持「未選擇」，讓使用者自己選。
                systemChosen = if (keepLocalEdits || reported == null) it.systemChosen
                               else it.systemChosen.toMutableList().also { list -> list[index] = true },
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
        _state.update {
            it.copy(
                status = status,
                warningCount = if (status.kind == FlipStatus.Kind.WARNING) it.warningCount + 1 else it.warningCount
            )
        }
    }
}

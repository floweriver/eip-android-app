package com.example.eip.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FlipKeyboardController 的查詢流程、自動送出、設定應答與重設。 */
class FlipKeyboardControllerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sent = mutableListOf<ByteArray>()
    private var sendSucceeds = true

    /** 預設不延遲，修改後立即送出，測試不必等待。 */
    private fun controller(
        queryTimeoutMs: Long = 60_000L,
        currentModeTimeoutMs: Long = 60_000L,
        autoSendDelayMs: Long = 0L
    ) = FlipKeyboardController(
        scope, { synchronized(sent) { sent.add(it) }; sendSucceeds },
        queryTimeoutMs, currentModeTimeoutMs, autoSendDelayMs
    )

    @After
    fun tearDown() = scope.cancel()

    /** 組出鍵盤方向（帧頭 AA 55）的回覆封包。 */
    private fun reply(cmd: Int, vararg data: Int): ByteArray {
        require(data.size == 5)
        val pkt = ByteArray(9)
        pkt[0] = 0xAA.toByte(); pkt[1] = 0x55
        pkt[2] = cmd.toByte()
        data.forEachIndexed { i, v -> pkt[3 + i] = v.toByte() }
        pkt[8] = FlipProtocol.crc8Maxim(pkt, 8).toByte()
        return pkt
    }

    /** 模擬查詢的四則回覆：裝置1 iOS、裝置2 Windows、裝置3 Android，休眠 7 分鐘，當前為裝置2。 */
    private fun FlipKeyboardController.answerQuery() {
        onDataReceived(reply(0x01, 0x01, 0x17, 0x18, 0x1A, 0x07))
        onDataReceived(reply(0x02, 0x02, 0x6A, 0x6B, 0x6C, 0x07))
        onDataReceived(reply(0x03, 0x03, 0x89, 0x8A, 0x8B, 0x07))
        onDataReceived(reply(0x02, 0x02, 0x6A, 0x6B, 0x6C, 0x07))
    }

    @Test
    fun onReady_sendsQueryAndLoadsAllDevices() {
        val c = controller()
        c.onReady()
        assertArrayEquals(FlipProtocol.queryCommand(), sent.single())
        assertEquals(FlipStatus.Message.LOADING, c.state.value.status.message)
        assertTrue(c.state.value.systemChosen.none { it })

        c.answerQuery()

        val s = c.state.value
        assertEquals(FlipModeConfig(FlipSystem.IOS, listOf(0x17, 0x18, 0x1A)), s.modes[0])
        assertEquals(FlipModeConfig(FlipSystem.WINDOWS, listOf(0x6A, 0x6B, 0x6C)), s.modes[1])
        assertEquals(FlipModeConfig(FlipSystem.ANDROID, listOf(0x89, 0x8A, 0x8B)), s.modes[2])
        assertEquals(s.modes, s.sentModes)
        assertTrue(s.systemChosen.all { it })
        assertEquals(7, s.sleepMinutes)
        assertEquals(7, s.sentSleepMinutes)
        // 重複出現的 Cmd 即第 4 則「當前裝置」
        assertEquals(FlipMode.DEVICE_2, s.activeMode)
        assertEquals(1, s.activeReportCount)
        assertEquals(FlipStatus.Message.LOADED_CURRENT, s.status.message)
    }

    @Test
    fun query_invalidSystemCodeLeavesSystemUnchosen() {
        val c = controller()
        c.onReady()
        // 全新鍵盤：裝置1 的系統碼為 0x00
        c.onDataReceived(reply(0x01, 0x00, 0x00, 0x00, 0x00, 0x05))
        c.onDataReceived(reply(0x02, 0x02, 0x6A, 0x6B, 0x6C, 0x05))
        val s = c.state.value
        assertFalse(s.systemChosen[0])
        assertTrue(s.systemChosen[1])
        assertFalse(s.systemChosen[2])
    }

    @Test
    fun query_fourthReplyIsRecognisedEvenWhenFirstThreeArriveOutOfOrder() {
        val c = controller()
        c.onReady()
        c.onDataReceived(reply(0x03, 0x03, 0x89, 0x8A, 0x8B, 0x05))
        c.onDataReceived(reply(0x01, 0x01, 0x17, 0x18, 0x1A, 0x05))
        c.onDataReceived(reply(0x02, 0x02, 0x6A, 0x6B, 0x6C, 0x05))
        assertEquals(FlipStatus.Message.ALL_LOADED, c.state.value.status.message)
        assertNull(c.state.value.activeMode)
        c.onDataReceived(reply(0x03, 0x03, 0x89, 0x8A, 0x8B, 0x05))
        assertEquals(FlipMode.DEVICE_3, c.state.value.activeMode)
    }

    @Test
    fun query_failsImmediatelyWhenSendIsRejected() {
        sendSucceeds = false
        val c = controller()
        c.query()
        assertEquals(FlipStatus.Message.QUERY_FAILED, c.state.value.status.message)
        assertEquals(1, c.state.value.warningCount)
        // 查詢已結束，之後收到的封包不應被當成查詢回覆
        c.onDataReceived(reply(0x01, 0x01, 0x17, 0x18, 0x1A, 0x00))
        assertEquals(FlipStatus.Message.APPLIED, c.state.value.status.message)
    }

    @Test
    fun query_timesOutWhenKeyboardNeverReportsCurrentDevice() {
        val c = controller(queryTimeoutMs = 60_000L, currentModeTimeoutMs = 30L)
        c.onReady()
        c.onDataReceived(reply(0x01, 0x01, 0x17, 0x18, 0x1A, 0x05))
        c.onDataReceived(reply(0x02, 0x02, 0x6A, 0x6B, 0x6C, 0x05))
        c.onDataReceived(reply(0x03, 0x03, 0x89, 0x8A, 0x8B, 0x05))
        Thread.sleep(400)
        assertEquals(FlipStatus.Message.LOADED_NO_CURRENT, c.state.value.status.message)
        // 逾時後查詢視窗已關閉：鍵盤主動上報應更新當前裝置，而非被當成第 4 則
        c.onDataReceived(reply(0x01, 0x04, 0xA6, 0xA7, 0xA8, 0x00))
        val s = c.state.value
        assertEquals(FlipMode.DEVICE_1, s.activeMode)
        assertEquals(FlipSystem.SAMSUNG, s.modes[0].system)
        assertEquals(FlipStatus.Message.APPLIED, s.status.message)
    }

    @Test
    fun edits_areSentAutomaticallyAndConfirmedByEcho() {
        val c = controller()
        c.onReady()
        c.answerQuery()
        sent.clear()

        c.setKeyCode(FlipMode.DEVICE_3, 1, 0x8E)
        assertArrayEquals(
            FlipProtocol.modeCommand(FlipMode.DEVICE_3, FlipSystem.ANDROID, listOf(0x89, 0x8E, 0x8B)),
            sent.single()
        )
        assertEquals(FlipStatus.Message.SENT, c.state.value.status.message)
        // 送出不等於生效：鍵盤回聲之前，已確認的內容仍是舊的
        assertEquals(listOf(0x89, 0x8A, 0x8B), c.state.value.sentModes[2]?.keyCodes)

        c.onDataReceived(reply(0x03, 0x03, 0x89, 0x8E, 0x8B, 0x00))
        assertEquals(c.state.value.modes[2], c.state.value.sentModes[2])
        assertEquals(FlipStatus.Message.APPLIED, c.state.value.status.message)
    }

    @Test
    fun edits_settingTheSameValueSendsNothing() {
        val c = controller()
        c.onReady()
        c.answerQuery()
        sent.clear()
        c.setKeyCode(FlipMode.DEVICE_1, 0, 0x17)
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.IOS)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun edits_rapidChangesAreSentOnceWithTheLatestValues() {
        val c = controller(autoSendDelayMs = 80L)
        c.onReady()
        c.answerQuery()
        sent.clear()

        c.setKeyCode(FlipMode.DEVICE_3, 0, 0x8C)
        c.setKeyCode(FlipMode.DEVICE_3, 1, 0x8D)
        // 較早那次修改的回聲在送出前抵達：不可把剛改的內容蓋回去
        c.onDataReceived(reply(0x03, 0x03, 0x8C, 0x8A, 0x8B, 0x00))
        c.setKeyCode(FlipMode.DEVICE_3, 2, 0x8E)
        assertTrue(synchronized(sent) { sent.isEmpty() })
        assertEquals(listOf(0x8C, 0x8D, 0x8E), c.state.value.modes[2].keyCodes)

        Thread.sleep(500)
        val frames = synchronized(sent) { sent.toList() }
        assertEquals(1, frames.size)
        assertArrayEquals(
            FlipProtocol.modeCommand(FlipMode.DEVICE_3, FlipSystem.ANDROID, listOf(0x8C, 0x8D, 0x8E)),
            frames[0]
        )
    }

    @Test
    fun edits_reportFailureWhenWriteIsRejected() {
        val c = controller()
        sendSucceeds = false
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.WINDOWS)
        assertEquals(FlipStatus.Message.SEND_FAILED, c.state.value.status.message)
        assertEquals(FlipStatus.Kind.WARNING, c.state.value.status.kind)
        assertEquals(1, c.state.value.warningCount)
    }

    @Test
    fun setSystem_onFreshDeviceMarksSystemChosenAndSendsDefaults() {
        val c = controller()
        // 尚未選系統時，即使選到與預設值相同的系統也要送出；沒有舊設定就給預設的複製／貼上／全選
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.ANDROID)
        assertTrue(c.state.value.systemChosen[0])
        assertArrayEquals(
            FlipProtocol.modeCommand(FlipMode.DEVICE_1, FlipSystem.ANDROID, listOf(0x89, 0x8A, 0x8B)),
            sent.single()
        )
    }

    @Test
    fun echoWithErrorField_isNotApplied() {
        val c = controller()
        c.onReady()
        c.answerQuery()
        c.onDataReceived(reply(0x01, 0x01, 0xFF, 0x18, 0x1A, 0x00))
        assertEquals(FlipStatus.Message.FIELD_ERROR, c.state.value.status.message)
        assertEquals(listOf(0x17, 0x18, 0x1A), c.state.value.modes[0].keyCodes)
    }

    @Test
    fun corruptedFrame_isIgnored() {
        val c = controller()
        val frame = reply(0x01, 0x01, 0x17, 0x18, 0x1A, 0x00)
        frame[8] = (frame[8] + 1).toByte()
        c.onDataReceived(frame)
        assertEquals(FlipUiState(), c.state.value)
    }

    @Test
    fun switchingSystem_keepsEachSystemsKeysAsDrafts() {
        val c = controller()
        c.onReady()
        c.answerQuery()

        // 裝置1 原為 iOS 複製/貼上/全選；第一次切到 Android 給 Android 的預設鍵
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.ANDROID)
        assertEquals(FlipModeConfig(FlipSystem.ANDROID, listOf(0x89, 0x8A, 0x8B)), c.state.value.modes[0])
        c.setKeyCode(FlipMode.DEVICE_1, 0, 0x99)

        // 切回 iOS：原本的設定原樣還原
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.IOS)
        assertEquals(listOf(0x17, 0x18, 0x1A), c.state.value.modes[0].keyCodes)

        // 再切到 Android：剛才的選擇也還在
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.ANDROID)
        assertEquals(listOf(0x99, 0x8A, 0x8B), c.state.value.modes[0].keyCodes)
        // 每次切換都把當下的整組設定寫進鍵盤
        assertArrayEquals(
            FlipProtocol.modeCommand(FlipMode.DEVICE_1, FlipSystem.ANDROID, listOf(0x99, 0x8A, 0x8B)),
            sent.last()
        )
    }

    @Test
    fun resetKeys_restoresDefaultsAndSends() {
        val c = controller()
        c.onReady()
        c.answerQuery()
        c.setKeyCode(FlipMode.DEVICE_2, 0, 0x74)
        c.setKeyCode(FlipMode.DEVICE_2, 2, 0x77)
        sent.clear()

        c.resetKeys(FlipMode.DEVICE_2)
        assertEquals(FlipModeConfig(FlipSystem.WINDOWS, listOf(0x6A, 0x6B, 0x6C)), c.state.value.modes[1])
        assertArrayEquals(
            FlipProtocol.modeCommand(FlipMode.DEVICE_2, FlipSystem.WINDOWS, listOf(0x6A, 0x6B, 0x6C)),
            sent.single()
        )
        // 重設後切走再切回來，不可把重設前的鍵還原回來
        c.setSystem(FlipMode.DEVICE_2, FlipSystem.IOS)
        c.setSystem(FlipMode.DEVICE_2, FlipSystem.WINDOWS)
        assertEquals(listOf(0x6A, 0x6B, 0x6C), c.state.value.modes[1].keyCodes)
    }

    @Test
    fun foreignKeys_areDetectedAndReplacedWithDefaultsOnly() {
        val c = controller()
        c.onReady()
        // 鍵盤端用 Fn 切成 iOS 但鍵碼沒清：第三顆仍是 Android 的 Gemini，第一顆是 Windows 的截圖
        c.onDataReceived(reply(0x01, 0x01, 0x74, 0x18, 0x99, 0x05))
        c.onDataReceived(reply(0x02, 0x02, 0x6A, 0x6B, 0x6C, 0x05))
        c.onDataReceived(reply(0x03, 0x03, 0x89, 0x8A, 0x8B, 0x05))
        c.onDataReceived(reply(0x01, 0x01, 0x74, 0x18, 0x99, 0x05))
        assertEquals(listOf(0, 2), c.state.value.modes[0].foreignKeyIndices)
        assertTrue(c.state.value.modes[1].foreignKeyIndices.isEmpty())
        sent.clear()

        c.replaceForeignKeys(FlipMode.DEVICE_1)
        // 只有不符的兩顆換成 iOS 預設，中間那顆保留
        assertEquals(listOf(0x17, 0x18, 0x1A), c.state.value.modes[0].keyCodes)
        assertArrayEquals(
            FlipProtocol.modeCommand(FlipMode.DEVICE_1, FlipSystem.IOS, listOf(0x17, 0x18, 0x1A)),
            sent.single()
        )
        // 沒有不符的鍵時不送
        c.replaceForeignKeys(FlipMode.DEVICE_2)
        assertEquals(1, sent.size)
    }

    @Test
    fun foreignKeys_ignoreDisabledAndUndefinedCodes() {
        assertTrue(FlipModeConfig(FlipSystem.IOS, listOf(0x00, 0x17, 0x50)).foreignKeyIndices.isEmpty())
        assertEquals(listOf(1), FlipModeConfig(FlipSystem.ANDROID, listOf(0x89, 0xA6, 0x00)).foreignKeyIndices)
    }

    @Test
    fun defaultKeyCodes_areCopyPasteSelectAllOfEachSystem() {
        for (system in FlipSystem.entries) {
            val names = FlipFunctionCatalog.defaultKeyCodes(system).map { FlipFunctionCatalog.function(it) }
            assertEquals(listOf("Copy", "Paste", "Select All"), names.map { it?.nameEn })
            assertTrue(names.all { it?.system == system })
        }
    }

    @Test
    fun resetKeys_isIgnoredBeforeSystemIsChosen() {
        val c = controller()
        c.resetKeys(FlipMode.DEVICE_1)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun sleep_isClampedSentOnCommitAndConfirmedByEcho() {
        val c = controller()
        c.onReady()
        c.answerQuery()
        sent.clear()

        // 拖曳途中只改本地數值，不送出
        c.setSleepMinutes(99)
        assertEquals(15, c.state.value.sleepMinutes)
        assertTrue(sent.isEmpty())

        c.commitSleep()
        assertArrayEquals(FlipProtocol.sleepCommand(15), sent.single())
        assertEquals(FlipStatus.Message.SLEEP_SENT, c.state.value.status.message)

        c.onDataReceived(reply(0x05, 0x00, 0x00, 0x00, 0x00, 0x0F))
        assertEquals(15, c.state.value.sentSleepMinutes)
        assertEquals(FlipStatus.Message.SLEEP_SET, c.state.value.status.message)

        // 超出範圍的回聲視為異常，不寫回
        c.onDataReceived(reply(0x05, 0x00, 0x00, 0x00, 0x00, 0xFF))
        assertEquals(FlipStatus.Message.SLEEP_ERROR, c.state.value.status.message)
        assertEquals(15, c.state.value.sleepMinutes)
    }

    @Test
    fun onDisconnected_clearsEverything() {
        val c = controller()
        c.onReady()
        c.answerQuery()
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.ANDROID)
        c.onDisconnected()

        val s = c.state.value
        assertEquals(FlipStatus.Message.DISCONNECTED, s.status.message)
        assertNull(s.activeMode)
        assertTrue(s.sentModes.all { it == null })
        assertTrue(s.systemChosen.none { it })
        // 暫存也清掉：下次連上的可能是另一把鍵盤，重選系統只拿得到預設鍵
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.WINDOWS)
        assertEquals(listOf(0x6A, 0x6B, 0x6C), c.state.value.modes[0].keyCodes)
    }

    @Test
    fun isFlipKeyboardName_onlyMatchesFlip() {
        assertTrue(FlipProtocol.isFlipKeyboardName("eiP Flip Keyboard"))
        // 現行韌體廣播的名稱，少了這一條鍵盤就連不上
        assertTrue(FlipProtocol.isFlipKeyboardName("eiP Flipstand Keyboard"))
        assertTrue(FlipProtocol.isFlipKeyboardName("EIP FLIPSTAND KEYBOARD"))
        assertFalse(FlipProtocol.isFlipKeyboardName("eiP HyperKeys"))
        assertFalse(FlipProtocol.isFlipKeyboardName("eiP USI 2.0 Ultra"))
        assertFalse(FlipProtocol.isFlipKeyboardName("Flip Keyboard"))
        assertFalse(FlipProtocol.isFlipKeyboardName(null))
    }
}

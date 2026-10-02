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

/** FlipKeyboardController 的查詢流程、設定應答與未送出變更的判斷。 */
class FlipKeyboardControllerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sent = mutableListOf<ByteArray>()
    private var sendSucceeds = true

    private fun controller(queryTimeoutMs: Long = 60_000L, currentModeTimeoutMs: Long = 60_000L) =
        FlipKeyboardController(scope, { sent.add(it); sendSucceeds }, queryTimeoutMs, currentModeTimeoutMs)

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

        c.answerQuery()

        val s = c.state.value
        assertEquals(FlipModeConfig(FlipSystem.IOS, listOf(0x17, 0x18, 0x1A)), s.modes[0])
        assertEquals(FlipModeConfig(FlipSystem.WINDOWS, listOf(0x6A, 0x6B, 0x6C)), s.modes[1])
        assertEquals(FlipModeConfig(FlipSystem.ANDROID, listOf(0x89, 0x8A, 0x8B)), s.modes[2])
        assertEquals(s.modes, s.sentModes)
        assertEquals(7, s.sleepMinutes)
        assertEquals(7, s.sentSleepMinutes)
        // 重複出現的 Cmd 即第 4 則「當前裝置」
        assertEquals(FlipMode.DEVICE_2, s.activeMode)
        assertEquals(1, s.activeReportCount)
        assertEquals(FlipStatus.Message.LOADED_CURRENT, s.status.message)
        assertFalse(FlipMode.entries.any { s.hasUnsentChanges(it) })
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
    fun edits_areLocalUntilSentAndConfirmedByEcho() {
        val c = controller()
        c.onReady()
        c.answerQuery()
        sent.clear()

        c.setKeyCode(FlipMode.DEVICE_3, 1, 0x8E)
        assertTrue(c.state.value.hasUnsentChanges(FlipMode.DEVICE_3))
        assertFalse(c.state.value.hasUnsentChanges(FlipMode.DEVICE_1))
        assertTrue(sent.isEmpty())

        c.sendMode(FlipMode.DEVICE_3)
        assertArrayEquals(
            FlipProtocol.modeCommand(FlipMode.DEVICE_3, FlipSystem.ANDROID, listOf(0x89, 0x8E, 0x8B)),
            sent.single()
        )
        assertEquals(FlipStatus.Message.SENT, c.state.value.status.message)
        // 送出不等於生效，要等鍵盤回聲
        assertTrue(c.state.value.hasUnsentChanges(FlipMode.DEVICE_3))

        c.onDataReceived(reply(0x03, 0x03, 0x89, 0x8E, 0x8B, 0x00))
        assertFalse(c.state.value.hasUnsentChanges(FlipMode.DEVICE_3))
        assertEquals(FlipStatus.Message.APPLIED, c.state.value.status.message)
    }

    @Test
    fun sendMode_reportsFailureWhenWriteIsRejected() {
        val c = controller()
        sendSucceeds = false
        c.sendMode(FlipMode.DEVICE_1)
        assertEquals(FlipStatus.Message.SEND_FAILED, c.state.value.status.message)
        assertEquals(FlipStatus.Kind.WARNING, c.state.value.status.kind)
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

        // 裝置1 原為 iOS 複製/貼上/全選；切到 Android 是空的
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.ANDROID)
        assertEquals(FlipModeConfig(FlipSystem.ANDROID, listOf(0, 0, 0)), c.state.value.modes[0])
        c.setKeyCode(FlipMode.DEVICE_1, 0, 0x89)

        // 切回 iOS：鍵盤已確認的設定原樣還原，不算有未送出的變更
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.IOS)
        assertEquals(listOf(0x17, 0x18, 0x1A), c.state.value.modes[0].keyCodes)
        assertFalse(c.state.value.hasUnsentChanges(FlipMode.DEVICE_1))

        // 再切到 Android：剛才尚未送出的選擇也還在
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.ANDROID)
        assertEquals(listOf(0x89, 0, 0), c.state.value.modes[0].keyCodes)
        assertTrue(c.state.value.hasUnsentChanges(FlipMode.DEVICE_1))
    }

    @Test
    fun sleep_isClampedSentAndConfirmedByEcho() {
        val c = controller()
        c.onReady()
        c.answerQuery()
        sent.clear()

        c.setSleepMinutes(99)
        assertEquals(15, c.state.value.sleepMinutes)
        assertTrue(c.state.value.hasUnsentSleepChange)

        c.sendSleep()
        assertArrayEquals(FlipProtocol.sleepCommand(15), sent.single())
        assertEquals(FlipStatus.Message.SLEEP_SENT, c.state.value.status.message)

        c.onDataReceived(reply(0x05, 0x00, 0x00, 0x00, 0x00, 0x0F))
        assertFalse(c.state.value.hasUnsentSleepChange)
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
        // 暫存也清掉：下次連上的可能是另一把鍵盤
        c.setSystem(FlipMode.DEVICE_1, FlipSystem.IOS)
        assertEquals(listOf(0, 0, 0), c.state.value.modes[0].keyCodes)
    }

    @Test
    fun isFlipKeyboardName_onlyMatchesFlip() {
        assertTrue(FlipProtocol.isFlipKeyboardName("eiP Flip Keyboard"))
        assertFalse(FlipProtocol.isFlipKeyboardName("eiP HyperKeys"))
        assertFalse(FlipProtocol.isFlipKeyboardName("eiP USI 2.0 Ultra"))
        assertFalse(FlipProtocol.isFlipKeyboardName("Flip Keyboard"))
        assertFalse(FlipProtocol.isFlipKeyboardName(null))
    }
}

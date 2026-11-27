package com.example.eip.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 以《KL122 通訊協議與功能碼》的測試向量與示例封包驗證 FlipProtocol。
 * 這些封包皆與實機收發紀錄一致，改動協議層後若此處不過，代表送出的封包已與韌體不符。
 */
class FlipProtocolTest {

    private fun bytes(hex: String): ByteArray =
        hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // --- CRC ---

    @Test
    fun crc_matchesDocumentTestVector() {
        assertEquals(0x75, FlipProtocol.crc8Maxim(bytes("55 AA 00 01 00 00 00 00")))
    }

    // --- 指令組裝 ---

    @Test
    fun modeCommand_matchesDocumentExamples() {
        assertArrayEquals(
            bytes("55 AA 01 01 17 18 1A 00 98"),
            FlipProtocol.modeCommand(FlipMode.DEVICE_1, FlipSystem.IOS, listOf(0x17, 0x18, 0x1A))
        )
        assertArrayEquals(
            bytes("55 AA 02 02 6A 6B 6C 00 AD"),
            FlipProtocol.modeCommand(FlipMode.DEVICE_2, FlipSystem.WINDOWS, listOf(0x6A, 0x6B, 0x6C))
        )
        assertArrayEquals(
            bytes("55 AA 03 03 89 8A 8B 00 E1"),
            FlipProtocol.modeCommand(FlipMode.DEVICE_3, FlipSystem.ANDROID, listOf(0x89, 0x8A, 0x8B))
        )
    }

    @Test
    fun sleepCommand_putsMinutesInData5() {
        assertArrayEquals(bytes("55 AA 05 00 00 00 00 07 D0"), FlipProtocol.sleepCommand(7))
    }

    @Test
    fun sleepCommand_clampsToAllowedRange() {
        assertEquals(1, FlipProtocol.sleepCommand(0)[7].toInt())
        assertEquals(15, FlipProtocol.sleepCommand(99)[7].toInt())
    }

    @Test
    fun queryCommand_matchesDocumentExample() {
        assertArrayEquals(bytes("55 AA FF 00 00 00 00 00 EE"), FlipProtocol.queryCommand())
    }

    // --- 回覆解析 ---

    @Test
    fun parseReply_readsKeyboardEcho() {
        val reply = FlipProtocol.parseReply(bytes("AA 55 01 01 17 18 1A 00 43"))
        assertNotNull(reply)
        reply!!
        assertEquals(FlipMode.DEVICE_1, reply.mode)
        assertEquals(FlipSystem.IOS, reply.system)
        assertEquals(listOf(0x17, 0x18, 0x1A), reply.keyCodes)
        assertEquals(0, reply.sleepMinutes)
        assertFalse(reply.hasError)

        val windows = FlipProtocol.parseReply(bytes("AA 55 02 02 6A 6B 6C 00 76"))
        assertEquals(FlipMode.DEVICE_2, windows?.mode)
        assertEquals(FlipSystem.WINDOWS, windows?.system)
    }

    @Test
    fun parseReply_rejectsCorruptedFrames() {
        // CRC 錯誤
        assertNull(FlipProtocol.parseReply(bytes("AA 55 01 01 17 18 1A 00 44")))
        // 帧頭是 App 方向
        assertNull(FlipProtocol.parseReply(bytes("55 AA 01 01 17 18 1A 00 98")))
        // 長度不足 / 過長
        assertNull(FlipProtocol.parseReply(bytes("AA 55 01 01 17 18 1A 00")))
        assertNull(FlipProtocol.parseReply(bytes("AA 55 01 01 17 18 1A 00 43 00")))
    }

    @Test
    fun parseReply_flagsErrorField() {
        val frame = bytes("AA 55 01 01 FF 18 1A 00 00")
        frame[8] = FlipProtocol.crc8Maxim(frame, 8).toByte()
        val reply = FlipProtocol.parseReply(frame)
        assertNotNull(reply)
        assertTrue(reply!!.hasError)
    }

    @Test
    fun parseReply_nonModeCommandHasNoMode() {
        val frame = bytes("AA 55 05 00 00 00 00 07 00")
        frame[8] = FlipProtocol.crc8Maxim(frame, 8).toByte()
        val reply = FlipProtocol.parseReply(frame)
        assertNotNull(reply)
        assertNull(reply!!.mode)
        assertEquals(7, reply.sleepMinutes)
    }

    // --- 功能碼表 ---

    @Test
    fun catalog_codesAreUniqueAndFitOneByte() {
        val codes = FlipFunctionCatalog.all.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it in 0x00..0xFE })
    }

    @Test
    fun catalog_hasExpectedCountPerSystem() {
        assertEquals(74, FlipFunctionCatalog.functions(FlipSystem.IOS).size)
        assertEquals(16, FlipFunctionCatalog.functions(FlipSystem.WINDOWS).size)
        assertEquals(19, FlipFunctionCatalog.functions(FlipSystem.ANDROID).size)
        assertEquals(16, FlipFunctionCatalog.functions(FlipSystem.SAMSUNG).size)
    }

    @Test
    fun catalog_codesStayWithinTheirSystemRange() {
        val ranges = mapOf(
            FlipSystem.IOS to 0x01..0x4E,
            FlipSystem.WINDOWS to 0x66..0x77,
            FlipSystem.ANDROID to 0x84..0x99,
            FlipSystem.SAMSUNG to 0xA1..0xB4
        )
        for ((system, range) in ranges) {
            assertTrue("$system", FlipFunctionCatalog.functions(system).all { it.code in range })
        }
    }

    @Test
    fun catalog_excludesCodesThatFailedOnDevice() {
        val removed = listOf(0x0C, 0x0F, 0x13, 0x46, 0x65, 0x75, 0x76, 0x8F, 0x97, 0x98, 0xAC, 0xAE)
        for (code in removed) assertNull("0x%02X".format(code), FlipFunctionCatalog.function(code))
    }

    @Test
    fun catalog_onlyIosIsGrouped() {
        assertEquals(
            listOf("General", "Goodnotes", "Notability", "Procreate"),
            FlipFunctionCatalog.groups(FlipSystem.IOS)
        )
        assertTrue(FlipFunctionCatalog.groups(FlipSystem.ANDROID).isEmpty())
        assertEquals(9, FlipFunctionCatalog.functions(FlipSystem.IOS, "Goodnotes").size)
    }

    @Test
    fun catalog_wiredKeyCodesBelongToTheirSystem() {
        for (system in FlipSystem.entries) {
            val codes = FlipFunctionCatalog.wiredKeyCodes(system)
            assertEquals(3, codes.size)
            for (code in codes) assertEquals(system, FlipFunctionCatalog.function(code)?.system)
        }
    }

    @Test
    fun modeConfig_isDisabledOnlyWhenAllKeysAreZero() {
        assertTrue(FlipModeConfig().isDisabled)
        assertFalse(FlipModeConfig(keyCodes = listOf(0x00, 0x89, 0x00)).isDisabled)
    }
}

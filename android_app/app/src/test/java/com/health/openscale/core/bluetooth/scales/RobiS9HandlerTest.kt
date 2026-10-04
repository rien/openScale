/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [RobiS9Handler] frame building and parsing.
 *
 * The A3 vectors and the BA/BB frames are verbatim from HCI snoop captures of the Fitdays
 * app and a Robi S9 (2026-10-04), and from ble-scale-sync #248.
 */
class RobiS9HandlerTest {

    private fun hex(s: String) = RobiS9Handler.hexToBytes(s)

    private val profile = RobiS9Handler.Companion.Profile(
        userId = 0x1250b566,
        heightCm = 183,
        lastWeightKg = 77.55f,
        male = true,
        age = 30,
    )

    @Test
    fun `decodes weight, heart rate and impedance from a captured A3 frame`() {
        val result = RobiS9Handler.decodeFinalResult(hex("e40800a30001333a380206000000000000000011"))

        assertThat(result).isNotNull()
        assertThat(result!!.grams).isEqualTo(78650)
        assertThat(result.heartRate).isEqualTo(56)
        assertThat(result.impedanceOhm).isEqualTo(518)
    }

    @Test
    fun `decodes a bad contact A3 frame as weight only`() {
        val result = RobiS9Handler.decodeFinalResult(hex("150800a30001317800000000000000000000000d"))

        assertThat(result).isNotNull()
        assertThat(result!!.grams).isEqualTo(78200)
        assertThat(result.heartRate).isEqualTo(0)
        assertThat(result.impedanceOhm).isEqualTo(0)
    }

    @Test
    fun `extracts 77_25 kg from the ble-scale-sync A3 frame`() {
        val result = RobiS9Handler.decodeFinalResult(hex("030800a300012dc2000000000000000000000013"))
        assertThat(result!!.grams).isEqualTo(77250)
    }

    @Test
    fun `rejects A2 frames, bad checksums and wrong lengths`() {
        assertThat(RobiS9Handler.decodeFinalResult(hex("540700a2040001333a0000000000000000000014"))).isNull()
        assertThat(RobiS9Handler.decodeFinalResult(hex("e40800a30001333a380206000000000000000012"))).isNull()
        assertThat(RobiS9Handler.decodeFinalResult(ByteArray(19))).isNull()
        assertThat(RobiS9Handler.decodeFinalResult(hex("e40801a30001333a380206000000000000000011"))).isNull()
    }

    @Test
    fun `builds the captured BA profile frame`() {
        val payload = RobiS9Handler.userProfilePayload(0x6ac23033, 120, profile)
        val frame = RobiS9Handler.buildFrame(0x02, 0xBA, payload)
        assertThat(frame).isEqualTo(hex("021000ba6ac2303300781250b566b71e4b9e2f0b"))
    }

    @Test
    fun `builds the captured BA frame without a last weight and heart rate off`() {
        val payload = RobiS9Handler.userProfilePayload(0x6ac23033, 120, profile.copy(lastWeightKg = 0f, heartRate = false))
        val frame = RobiS9Handler.buildFrame(0x01, 0xBA, payload)
        assertThat(frame).isEqualTo(hex("011000ba6ac2303300781250b566b700009e2b1e"))
    }

    @Test
    fun `builds the captured BA frame with heart rate off`() {
        val payload = RobiS9Handler.userProfilePayload(0x6ac24f00, 120, profile.copy(lastWeightKg = 78.19f, heartRate = false))
        val frame = RobiS9Handler.buildFrame(0x01, 0xBA, payload)
        assertThat(frame).isEqualTo(hex("011000ba6ac24f0000781250b566b71e8b9e2b13"))
    }

    @Test
    fun `builds the captured BB user list frame`() {
        val frame = RobiS9Handler.buildFrame(0x03, 0xBB, RobiS9Handler.userListPayload(profile))
        assertThat(frame).isEqualTo(hex("030a00bb011250b566b71e4b9e00000000000017"))
    }

    @Test
    fun `builds the captured B0 ack frame`() {
        val frame = RobiS9Handler.buildFrame(0x00, 0xB0, byteArrayOf(0xda.toByte(), 0x00))
        assertThat(frame).isEqualTo(hex("000300b0da00000000000000000000000000000a"))
    }

    @Test
    fun `marks a negative UTC offset with bit 15`() {
        val payload = RobiS9Handler.userProfilePayload(0, -300, profile)
        assertThat(payload[4].toInt() and 0xFF).isEqualTo(0x81)
        assertThat(payload[5].toInt() and 0xFF).isEqualTo(0x2C)
    }
}

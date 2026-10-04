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
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Originally ported from ble-scale-sync (GPL-3.0, © Kristián Partl):
 *   src/scales/robi-s9.ts — "Robi S9 (Lefu / Fitdays FFB0-new protocol)"
 *   upstream commits 4a8fda5 (#228, initial adapter) and 21b3afa (#248, weight = 3-byte BE grams).
 * The 0xBA/0xBB profile handshake, heart rate and impedance come from HCI snoop captures
 * of the current Fitdays app talking to a Robi S9.
 */
package com.health.openscale.core.bluetooth.scales

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.OkOkV2Lib
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

/**
 * Robi S9 smart scale (Fitdays app, icomon FFB0 protocol).
 *
 * Same 20-byte frame family as [RunstarR6Handler] and [RelaxmedicHandler]:
 * `[seq][len][00][type][payload…][chk]`, `len` = type + payload, `chk = sum(bytes[3..18]) & 0x1F`.
 *  - 0xFFB1 write     — app -> scale: 0xB0 ack, 0xBA user profile, 0xBB user list entry
 *  - 0xFFB2 notify    — 0xA2 live weight
 *  - 0xFFB3 indicate  — 0xA1 device info, 0xA0 ack, 0xA3 final result
 *
 * Session, as the Fitdays app runs it: the scale sends 0xA1 once the indications are on; the
 * app acks it and sends (0xBA, 0xBA, 0xBB) three times. The scale measures the impedance only
 * after it has a profile. 0xA3 carries weight, heart rate and impedance; the app acks it and
 * sends the profile again with the new weight. The scale computes no body composition itself.
 *
 * Because both scales advertise 0xFFB0, [RobiS9Handler] must be registered **before**
 * [MGBHandler] in [com.health.openscale.core.bluetooth.ScaleFactory] so a "Robi …" device
 * is claimed here by name (the MGB handler claims by service and would otherwise grab it).
 */
class RobiS9Handler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_WRITE: UUID = uuid16(0xFFB1)   // write (ack, profile)
    private val CHAR_LIVE: UUID = uuid16(0xFFB2)    // notify (live A2 frames)
    private val CHAR_RESULT: UUID = uuid16(0xFFB3)  // indicate (A1 info / A0 ack / A3 result)

    private var outgoingSeq = 0
    private var lastPreviewWeightKg = -1f
    private var lastResultSeq: Int? = null
    private var profileSent = false
    private var waitForStepOn = false
    private var lastWeightKg = 0f
    private var profileFallbackJob: Job? = null

    private val SETTINGS_KEY_HEART_RATE = "heart_rate"

    private fun heartRateEnabled(): Boolean = settingsGetInt(SETTINGS_KEY_HEART_RATE, 1) != 0

    @Composable
    override fun DeviceConfigurationUi() {
        var enabled by remember { mutableStateOf(heartRateEnabled()) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.robi_heart_rate_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.robi_heart_rate_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    settingsPutInt(SETTINGS_KEY_HEART_RATE, if (it) 1 else 0)
                },
            )
        }
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        // Swan/Icomon/YG are the MGB protocol; never claim them here.
        if (name.startsWith("swan") || name == "icomon" || name == "yg") return null
        // openScale only sees the advertised name (not the characteristic list) before
        // connecting, so — unlike the upstream adapter — we claim strictly by name.
        if (!name.contains("robi")) return null

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC
        )
        return DeviceSupport(
            displayName = "Robi S9",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        outgoingSeq = 0
        lastPreviewWeightKg = -1f
        lastResultSeq = null
        profileSent = false
        waitForStepOn = false
        lastWeightKg = storedWeightKg(user)

        setNotifyOn(SERVICE, CHAR_RESULT)
        setNotifyOn(SERVICE, CHAR_LIVE)

        // The profile normally follows the 0xA1 ack. Send it anyway if 0xA1 never comes.
        profileFallbackJob?.cancel()
        profileFallbackJob = scope.launch {
            delay(PROFILE_FALLBACK_MS)
            if (!profileSent) {
                logW("Robi S9 no 0xA1 device info received, sending profile anyway")
                sendProfile(currentAppUser(), HANDSHAKE)
            }
        }

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        profileFallbackJob?.cancel()
        profileFallbackJob = null
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.size != FRAME_SIZE) return
        if ((data[2].toInt() and 0xFF) != 0x00) return
        if (!isChecksumValid(data)) {
            logD("Robi S9 checksum mismatch ${data.toHexPreview(20)}")
            return
        }

        val seq = data[0].toInt() and 0xFF
        when (data[3].toInt() and 0xFF) {
            TYPE_LIVE_WEIGHT -> handleLiveWeight(data, user)
            TYPE_DEVICE_INFO -> {
                logI("Robi S9 device info ${data.toHexPreview(20)}")
                sendAck(seq)
                sendProfile(user, HANDSHAKE)
            }
            TYPE_ACK_IN -> logD("Robi S9 ack for seq ${data[4].toInt() and 0xFF}")
            TYPE_FINAL_RESULT -> handleFinalResult(data, seq, user)
            else -> logD(
                "Robi S9 unhandled frame type=0x${String.format("%02X", data[3])} " +
                    data.toHexPreview(20)
            )
        }
    }

    /**
     * 0xA2: byte4 = phase (0x01 live, 0x02 stable, 0x04 heart rate), bytes 5..8 = u32 BE grams.
     * The first non-zero weight after a result is a new weigh-in on the same connection.
     */
    private fun handleLiveWeight(data: ByteArray, user: ScaleUser) {
        val weightKg = u32be(data, 5) / 1000.0f
        if (waitForStepOn && weightKg > 0f) {
            waitForStepOn = false
            sendProfile(user, STEP_ON)
        }
        if (abs(weightKg - lastPreviewWeightKg) >= 0.05f) {
            userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
            lastPreviewWeightKg = weightKg
        }
    }

    private fun handleFinalResult(data: ByteArray, seq: Int, user: ScaleUser) {
        logI("Robi S9 final result ${data.toHexPreview(20)}")
        val result = decodeFinalResult(data) ?: return
        sendAck(seq)

        // Two weigh-ins on one connection can have the same weight, so match on the
        // scale's sequence number to detect a resent frame.
        if (lastResultSeq == seq) {
            logD("Robi S9 duplicate final result seq=$seq, skipping publish")
            return
        }
        lastResultSeq = seq

        val weightKg = result.grams / 1000.0f
        val measurement = ScaleMeasurement().apply {
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            if (result.heartRate != 0) this[MeasurementType.HEART_RATE] = Bpm(result.heartRate)
            if (result.impedanceOhm != 0) {
                this[MeasurementType.IMPEDANCE] = Ohm(result.impedanceOhm.toFloat())
            }
        }
        applyBodyComposition(measurement, weightKg, result.impedanceOhm, user)
        publish(measurement)

        lastWeightKg = weightKg
        waitForStepOn = true
        sendProfile(user, AFTER_RESULT)
    }

    /**
     * [OkOkV2Lib] has the fat mass regression of the Fitdays app: it gives the body fat the
     * app showed for three reference weigh-ins within 0.05 %. The other values come from
     * the same library and are not compared with the app.
     */
    private fun applyBodyComposition(
        measurement: ScaleMeasurement,
        weightKg: Float,
        impedanceOhm: Int,
        user: ScaleUser
    ) {
        if (impedanceOhm == 0) {
            logI("Robi S9 no impedance (bad foot contact), weight only")
            return
        }
        if (impedanceOhm !in IMPEDANCE_MIN..IMPEDANCE_MAX) {
            logW("Robi S9 impedance ${impedanceOhm}Ω outside $IMPEDANCE_MIN..${IMPEDANCE_MAX}Ω, weight only")
            return
        }
        if (user.bodyHeight <= 0f) {
            logW("Robi S9 no body height set for the current user, weight only")
            return
        }
        if (user.age < 18) {
            logW("Robi S9 body composition formulas are for adults only, weight only")
            return
        }

        val imp = impedanceOhm.toFloat()
        val lib = OkOkV2Lib(user.age, if (user.gender.isMale()) 1 else 0, user.bodyHeight)
        val fatPct = lib.getBodyFat(weightKg, imp)

        measurement[MeasurementType.BODY_FAT] = Percent(fatPct)
        measurement[MeasurementType.WATER] = Percent(lib.getWater(weightKg, imp))
        measurement[MeasurementType.MUSCLE] = Percent(lib.getMuscle(weightKg, imp))
        measurement[MeasurementType.LBM] = Kg(lib.getLBM(weightKg, imp))
        measurement[MeasurementType.BONE] = Kg(lib.getBoneMass(weightKg, imp))
        measurement[MeasurementType.PROTEIN] = Percent(lib.getProtein(weightKg, imp))
        measurement[MeasurementType.BMR] = Kcal(lib.getBMR(weightKg))
        measurement[MeasurementType.VISCERAL_FAT] = lib.getVisceralFat(weightKg, imp)

        logI("Robi S9 body composition (OkOkV2Lib, impedance=${impedanceOhm}Ω): fat=$fatPct%")
    }

    private fun storedWeightKg(user: ScaleUser): Float =
        lastMeasurementFor(user.id)?.get(MeasurementType.WEIGHT)?.value
            ?.takeIf { it.isFinite() && it > 0f } ?: 0f

    /** Send 0xBA and 0xBB frames in the order of [types]. */
    private fun sendProfile(user: ScaleUser, types: List<Int>) {
        profileSent = true
        val profile = Profile(
            userId = user.id.toLong() and 0xFFFFFFFFL,
            heightCm = user.bodyHeight.roundToInt().coerceIn(0, 255),
            lastWeightKg = lastWeightKg,
            male = user.gender.isMale(),
            age = user.age.coerceIn(0, 0x7F),
            heartRate = heartRateEnabled(),
        )
        val nowMillis = System.currentTimeMillis()
        val offsetMinutes = TimeZone.getDefault().getOffset(nowMillis) / 60000
        for (type in types) {
            val payload = if (type == TYPE_USER_PROFILE) {
                userProfilePayload(nowMillis / 1000L, offsetMinutes, profile)
            } else {
                userListPayload(profile)
            }
            write(type, payload)
        }
        logD("Robi S9 profile sent: user ${profile.userId}, last weight ${profile.lastWeightKg}kg")
    }

    private fun sendAck(seq: Int) = write(TYPE_ACK_OUT, byteArrayOf((seq and 0xFF).toByte(), 0x00))

    private fun write(type: Int, payload: ByteArray) {
        writeTo(SERVICE, CHAR_WRITE, buildFrame(outgoingSeq, type, payload), withResponse = true)
        outgoingSeq = (outgoingSeq + 1) and 0xFF
    }

    companion object {
        private const val FRAME_SIZE = 20

        private const val TYPE_ACK_IN = 0xA0
        private const val TYPE_DEVICE_INFO = 0xA1
        private const val TYPE_LIVE_WEIGHT = 0xA2
        private const val TYPE_FINAL_RESULT = 0xA3
        private const val TYPE_ACK_OUT = 0xB0
        private const val TYPE_USER_PROFILE = 0xBA
        private const val TYPE_USER_LIST = 0xBB

        /** Profile frame sequences of the Fitdays app. */
        private val HANDSHAKE = List(3) { listOf(TYPE_USER_PROFILE, TYPE_USER_PROFILE, TYPE_USER_LIST) }.flatten()
        private val AFTER_RESULT = List(3) { listOf(TYPE_USER_PROFILE, TYPE_USER_LIST) }.flatten()
        private val STEP_ON = listOf(TYPE_USER_PROFILE)

        private const val PROFILE_FALLBACK_MS = 3000L

        /** Byte 18 of 0xBA. Bit 2 enables the heart rate measurement. */
        private const val PROFILE_FLAGS = 0x2B
        private const val PROFILE_FLAG_HEART_RATE = 0x04

        internal const val IMPEDANCE_MIN = 200
        internal const val IMPEDANCE_MAX = 1200

        internal data class Profile(
            val userId: Long,
            val heightCm: Int,
            val lastWeightKg: Float,
            val male: Boolean,
            val age: Int,
            val heartRate: Boolean = true,
        )

        internal data class FinalResult(
            val grams: Int,
            val heartRate: Int,
            val impedanceOhm: Int,
        )

        /**
         * 0xA3: bytes 4..7 = weight (u32 BE grams), byte8 = heart rate (bpm),
         * bytes 9..10 = impedance (u16 BE Ohm). Heart rate and impedance are 0 when the
         * scale could not measure them.
         */
        internal fun decodeFinalResult(data: ByteArray): FinalResult? {
            if (data.size != FRAME_SIZE) return null
            if ((data[2].toInt() and 0xFF) != 0x00) return null
            if ((data[3].toInt() and 0xFF) != TYPE_FINAL_RESULT) return null
            if (!isChecksumValid(data)) return null
            val grams = u32be(data, 4)
            if (grams <= 0) return null

            return FinalResult(
                grams = grams,
                heartRate = data[8].toInt() and 0xFF,
                impedanceOhm = u16be(data, 9),
            )
        }

        /**
         * 0xBA payload: u32 BE unix time, u16 BE UTC offset in minutes (0x8000 = negative),
         * u32 BE user id, u8 height cm, u16 BE last weight * 100, u8 (sex shl 7) or age,
         * u8 flags (bit 2 = heart rate measurement on).
         */
        internal fun userProfilePayload(unixSeconds: Long, offsetMinutes: Int, profile: Profile): ByteArray {
            val encodedOffset = (abs(offsetMinutes) and 0x7FFF)
                .let { if (offsetMinutes < 0) it or 0x8000 else it }
            val weight = weightHundredths(profile.lastWeightKg)
            val payload = ByteArray(15)
            putU32be(payload, 0, unixSeconds)
            putU16be(payload, 4, encodedOffset)
            putU32be(payload, 6, profile.userId)
            payload[10] = profile.heightCm.toByte()
            putU16be(payload, 11, weight)
            payload[13] = sexAge(profile).toByte()
            payload[14] = (if (profile.heartRate) PROFILE_FLAGS or PROFILE_FLAG_HEART_RATE else PROFILE_FLAGS).toByte()
            return payload
        }

        /** 0xBB payload: u8 0x01, u32 BE user id, u8 height cm, u16 BE last weight * 100, u8 (sex shl 7) or age. */
        internal fun userListPayload(profile: Profile): ByteArray {
            val payload = ByteArray(9)
            payload[0] = 0x01
            putU32be(payload, 1, profile.userId)
            payload[5] = profile.heightCm.toByte()
            putU16be(payload, 6, weightHundredths(profile.lastWeightKg))
            payload[8] = sexAge(profile).toByte()
            return payload
        }

        /** `[seq][len][00][type][payload…][chk]`, `len` = type + payload. */
        internal fun buildFrame(seq: Int, type: Int, payload: ByteArray): ByteArray {
            require(payload.size <= 15) { "payload too large for a 20-byte frame" }
            val frame = ByteArray(FRAME_SIZE)
            frame[0] = (seq and 0xFF).toByte()
            frame[1] = (payload.size + 1).toByte()
            frame[2] = 0x00
            frame[3] = (type and 0xFF).toByte()
            payload.copyInto(frame, 4)
            frame[19] = computeChecksum(frame).toByte()
            return frame
        }

        internal fun computeChecksum(frame: ByteArray): Int {
            var sum = 0
            for (i in 3..18) sum += frame[i].toInt() and 0xFF
            return sum and 0x1F
        }

        internal fun isChecksumValid(frame: ByteArray): Boolean =
            (frame[19].toInt() and 0xFF) == computeChecksum(frame)

        /** Parse a hex string (even length) into bytes. */
        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }

        private fun weightHundredths(kg: Float): Int = (kg * 100f).roundToInt().coerceIn(0, 0xFFFF)

        private fun sexAge(profile: Profile): Int =
            ((if (profile.male) 1 else 0) shl 7) or (profile.age and 0x7F)

        private fun u32be(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

        private fun u16be(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        private fun putU16be(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value shr 8) and 0xFF).toByte()
            data[offset + 1] = (value and 0xFF).toByte()
        }

        private fun putU32be(data: ByteArray, offset: Int, value: Long) {
            data[offset] = ((value shr 24) and 0xFF).toByte()
            data[offset + 1] = ((value shr 16) and 0xFF).toByte()
            data[offset + 2] = ((value shr 8) and 0xFF).toByte()
            data[offset + 3] = (value and 0xFF).toByte()
        }
    }
}

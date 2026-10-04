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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reference values: body fat shown by the Fitdays app for a Robi S9 weigh-in
 * (male, 30 years, 183 cm).
 */
class OkOkV2LibTest {

    private val lib = OkOkV2Lib(age = 30, sex = 1, height = 183f)

    @Test
    fun `body fat matches the Fitdays app`() {
        assertThat(lib.getBodyFat(78.65f, 518f)).isWithin(0.05f).of(20.0f)
        assertThat(lib.getBodyFat(78.25f, 524f)).isWithin(0.06f).of(19.9f)
        assertThat(lib.getBodyFat(78.20f, 475f)).isWithin(0.05f).of(19.3f)
    }

    @Test
    fun `clamps body fat to 5 to 45 percent`() {
        assertThat(lib.getBodyFat(40f, 100f)).isEqualTo(5f)
        assertThat(lib.getBodyFat(250f, 1200f)).isEqualTo(45f)
    }

    @Test
    fun `water, bone and visceral fat are inside their ranges and not the lower bound`() {
        assertThat(lib.getWater(78.65f, 518f)).isWithin(0.05f).of(54.87f)
        assertThat(lib.getBoneMass(78.65f, 518f)).isWithin(0.05f).of(3.22f)
        assertThat(lib.getVisceralFat(78.65f, 518f)).isWithin(0.05f).of(8.8f)
    }
}

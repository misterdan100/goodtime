/**
 *     Goodtime Productivity
 *     Copyright (C) 2025 Adrian Cotfas
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.apps.adrcotfas.goodtime.fakes

import com.apps.adrcotfas.goodtime.bl.TimerType
import com.apps.adrcotfas.goodtime.bl.notifications.SoundPlayer
import com.apps.adrcotfas.goodtime.data.settings.SoundData

class FakeSoundPlayer : SoundPlayer {
    private val _playedSounds = mutableListOf<SoundData>()
    val playedSounds: List<SoundData> = _playedSounds

    override fun play(timerType: TimerType) {
        _playedSounds.add(SoundData())
    }

    override fun play(
        soundData: SoundData,
        loop: Boolean,
        forceSound: Boolean,
    ) {
        _playedSounds.add(soundData)
    }

    override fun stop() {}

    override fun close() {}
}

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
package com.apps.adrcotfas.goodtime.bl.notifications

import co.touchlab.kermit.Logger
import com.apps.adrcotfas.goodtime.bl.TimeProvider
import com.apps.adrcotfas.goodtime.bl.TimerManager
import com.apps.adrcotfas.goodtime.bl.TimerState
import com.apps.adrcotfas.goodtime.bl.TimerType
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.data.settings.SoundData
import com.apps.adrcotfas.goodtime.settings.notifications.toSoundData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Platform hook used to also deliver the session end warning while the app cannot execute code,
 * e.g. a scheduled local notification on iOS. Absent on platforms where the in-process
 * [SessionEndWarningHandler] is enough (Android keeps the process alive with a foreground service).
 */
interface SessionEndWarningNotifier {
    /**
     * Schedules (or replaces) the platform warning at the given moment.
     * @param warningTime the warning moment as elapsedRealtime (millis since boot)
     */
    suspend fun schedule(
        warningTime: Long,
        soundData: SoundData,
    )

    fun cancel()
}

/**
 * Warns the user a configurable number of minutes before the end of a countdown focus session
 * with a dedicated sound and a [warningFired] signal consumed by the UI (double screen flash).
 *
 * It observes the timer state directly, so any state change (pause/resume, skip, reset,
 * +1 minute, setting changes, restored sessions) cancels and recomputes the pending warning.
 * It never fires for break sessions or count-up (flow) sessions, and it is suppressed entirely
 * when the configured lead time is not smaller than the session duration.
 */
class SessionEndWarningHandler(
    timerManager: TimerManager,
    settingsRepo: SettingsRepository,
    private val soundPlayer: SoundPlayer,
    private val timeProvider: TimeProvider,
    private val notifier: SessionEndWarningNotifier?,
    coroutineScope: CoroutineScope,
    private val log: Logger,
) {
    private data class WarningConfig(
        val enabled: Boolean,
        val minutes: Int,
        val sound: SoundData,
    )

    private val _warningFired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once at the warning moment; consumed by the UI to trigger the double screen flash. */
    val warningFired: SharedFlow<Unit> = _warningFired

    init {
        coroutineScope.launch {
            combine(
                timerManager.timerData,
                settingsRepo.settings
                    .map {
                        WarningConfig(
                            enabled = it.sessionEndWarning,
                            minutes = it.sessionEndWarningMinutes,
                            sound = toSoundData(it.sessionEndWarningSound),
                        )
                    }.distinctUntilChanged(),
            ) { data, config -> data to config }
                .collectLatest { (data, config) ->
                    val eligible =
                        config.enabled &&
                            data.state == TimerState.RUNNING &&
                            data.type == TimerType.FOCUS &&
                            data.label.isCountdown &&
                            data.label.profile.workDuration > config.minutes

                    if (!eligible) {
                        notifier?.cancel()
                        return@collectLatest
                    }

                    val warningTime = data.endTime - config.minutes.minutes.inWholeMilliseconds
                    if (warningTime <= timeProvider.elapsedRealtime()) {
                        // the warning moment has already passed, e.g. resuming a session
                        // with less than the lead time remaining
                        notifier?.cancel()
                        return@collectLatest
                    }

                    log.v { "Scheduling session end warning at $warningTime" }
                    notifier?.schedule(warningTime, config.sound)

                    while (true) {
                        val wait = warningTime - timeProvider.elapsedRealtime()
                        if (wait <= 0) break
                        delay(wait)
                    }

                    // Fire only if we woke up close to the warning moment; when the app was
                    // suspended in between (iOS background), the platform notifier already
                    // handled it and firing late would be a stale warning.
                    if (timeProvider.elapsedRealtime() - warningTime < FIRE_TOLERANCE_MILLIS) {
                        log.i { "Session end warning fired" }
                        soundPlayer.play(config.sound, loop = false, forceSound = false)
                        _warningFired.tryEmit(Unit)
                    }
                }
        }
    }

    companion object {
        const val FIRE_TOLERANCE_MILLIS = 1000L
    }
}

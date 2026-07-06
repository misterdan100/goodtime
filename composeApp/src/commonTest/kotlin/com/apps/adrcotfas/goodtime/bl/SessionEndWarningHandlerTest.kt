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
package com.apps.adrcotfas.goodtime.bl

import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig
import com.apps.adrcotfas.goodtime.bl.notifications.SessionEndWarningHandler
import com.apps.adrcotfas.goodtime.data.local.LocalDataRepository
import com.apps.adrcotfas.goodtime.data.local.LocalDataRepositoryImpl
import com.apps.adrcotfas.goodtime.data.model.Label
import com.apps.adrcotfas.goodtime.data.model.TimerProfile
import com.apps.adrcotfas.goodtime.data.model.TimerProfile.Companion.DEFAULT_WORK_DURATION
import com.apps.adrcotfas.goodtime.data.settings.SettingsRepository
import com.apps.adrcotfas.goodtime.fakes.FakeEventListener
import com.apps.adrcotfas.goodtime.fakes.FakeLabelDao
import com.apps.adrcotfas.goodtime.fakes.FakeSessionDao
import com.apps.adrcotfas.goodtime.fakes.FakeSettingsRepository
import com.apps.adrcotfas.goodtime.fakes.FakeSoundPlayer
import com.apps.adrcotfas.goodtime.fakes.FakeTimeProvider
import com.apps.adrcotfas.goodtime.fakes.FakeTimerProfileDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEndWarningHandlerTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var localDataRepo: LocalDataRepository
    private lateinit var timerManager: TimerManager

    private val timeProvider = FakeTimeProvider()
    private val soundPlayer = FakeSoundPlayer()
    private val logger = Logger(StaticConfig())

    @BeforeTest
    fun setup() =
        runTest(testDispatcher) {
            timeProvider.elapsedRealtime = 0L
            settingsRepo = FakeSettingsRepository()
            localDataRepo =
                LocalDataRepositoryImpl(
                    sessionDao = FakeSessionDao(),
                    labelDao = FakeLabelDao(),
                    timerProfileDao = FakeTimerProfileDao(),
                    settingsRepo = settingsRepo,
                    coroutineScope = testScope,
                )

            localDataRepo.updateDefaultLabel(defaultLabel)
            localDataRepo.insertLabel(countUpLabel)
            localDataRepo.insertLabel(shortLabel)

            timerManager =
                TimerManager(
                    localDataRepo = localDataRepo,
                    settingsRepo = settingsRepo,
                    listeners = listOf(FakeEventListener()),
                    timeProvider,
                    FinishedSessionsHandler(
                        coroutineScope = testScope,
                        repo = localDataRepo,
                        settingsRepo = settingsRepo,
                        log = logger,
                    ),
                    logger,
                    coroutineScope = testScope,
                )
            timerManager.setup()

            SessionEndWarningHandler(
                timerManager = timerManager,
                settingsRepo = settingsRepo,
                soundPlayer = soundPlayer,
                timeProvider = timeProvider,
                notifier = null,
                coroutineScope = testScope,
                log = logger,
            )

            settingsRepo.setSessionEndWarning(true)
            settingsRepo.setSessionEndWarningMinutes(WARNING_MINUTES)
        }

    private fun TestScope.advanceBy(duration: Duration) {
        timeProvider.elapsedRealtime += duration.inWholeMilliseconds
        advanceTimeBy(duration.inWholeMilliseconds)
        runCurrent()
    }

    @Test
    fun `Fires at the warning moment for a countdown focus session`() =
        runTest(testDispatcher) {
            timerManager.start(TimerType.FOCUS)
            advanceBy(DEFAULT_DURATION - WARNING_MINUTES.minutes - 1.minutes)
            assertEquals(0, soundPlayer.playedSounds.size, "too early to fire")
            advanceBy(1.minutes)
            assertEquals(1, soundPlayer.playedSounds.size, "should have fired at the warning moment")
            advanceBy(1.minutes)
            assertEquals(1, soundPlayer.playedSounds.size, "should fire only once")
        }

    @Test
    fun `Does not fire when the setting is disabled`() =
        runTest(testDispatcher) {
            settingsRepo.setSessionEndWarning(false)
            timerManager.start(TimerType.FOCUS)
            advanceBy(DEFAULT_DURATION)
            assertEquals(0, soundPlayer.playedSounds.size)
        }

    @Test
    fun `Does not fire for break sessions`() =
        runTest(testDispatcher) {
            timerManager.start(TimerType.BREAK)
            advanceBy(defaultLabel.timerProfile.breakDuration.minutes)
            assertEquals(0, soundPlayer.playedSounds.size)
        }

    @Test
    fun `Does not fire for count-up sessions`() =
        runTest(testDispatcher) {
            settingsRepo.activateLabelWithName(countUpLabel.name)
            timerManager.start(TimerType.FOCUS)
            advanceBy(60.minutes)
            assertEquals(0, soundPlayer.playedSounds.size)
        }

    @Test
    fun `Does not fire when the lead time is not smaller than the session duration`() =
        runTest(testDispatcher) {
            settingsRepo.activateLabelWithName(shortLabel.name)
            timerManager.start(TimerType.FOCUS)
            advanceBy(WARNING_MINUTES.minutes)
            assertEquals(0, soundPlayer.playedSounds.size)
        }

    @Test
    fun `Pause cancels the warning and resuming past the warning moment does not re-fire`() =
        runTest(testDispatcher) {
            timerManager.start(TimerType.FOCUS)
            advanceBy(DEFAULT_DURATION - WARNING_MINUTES.minutes - 1.minutes)
            timerManager.toggle() // pause before the warning moment
            advanceBy(10.minutes)
            assertEquals(0, soundPlayer.playedSounds.size, "no warning while paused")
            timerManager.toggle() // resume; the warning moment is still ahead
            advanceBy(1.minutes)
            assertEquals(1, soundPlayer.playedSounds.size, "warning rescheduled after resume")

            advanceBy(1.minutes)
            timerManager.toggle() // pause past the warning moment
            advanceBy(10.minutes)
            timerManager.toggle() // resume with less than the lead time remaining
            advanceBy(1.minutes)
            assertEquals(1, soundPlayer.playedSounds.size, "no second warning when resuming past the warning moment")
        }

    @Test
    fun `Add one minute reschedules the warning`() =
        runTest(testDispatcher) {
            timerManager.start(TimerType.FOCUS)
            advanceBy(DEFAULT_DURATION - WARNING_MINUTES.minutes - 1.minutes)
            timerManager.addOneMinute()
            advanceBy(1.minutes)
            assertEquals(0, soundPlayer.playedSounds.size, "warning postponed by one minute")
            advanceBy(1.minutes)
            assertEquals(1, soundPlayer.playedSounds.size, "warning fired at the new moment")
        }

    @Test
    fun `Reset and skip cancel the warning`() =
        runTest(testDispatcher) {
            timerManager.start(TimerType.FOCUS)
            advanceBy(1.minutes)
            timerManager.reset()
            advanceBy(DEFAULT_DURATION)
            assertEquals(0, soundPlayer.playedSounds.size, "no warning after reset")

            timerManager.start(TimerType.FOCUS)
            advanceBy(1.minutes)
            timerManager.skip() // starts a break session
            advanceBy(DEFAULT_DURATION)
            assertEquals(0, soundPlayer.playedSounds.size, "no stale warning after skip")
        }

    @Test
    fun `Disabling the setting mid-session cancels the warning`() =
        runTest(testDispatcher) {
            timerManager.start(TimerType.FOCUS)
            advanceBy(1.minutes)
            settingsRepo.setSessionEndWarning(false)
            advanceBy(DEFAULT_DURATION)
            assertEquals(0, soundPlayer.playedSounds.size)
        }

    companion object {
        private const val WARNING_MINUTES = 3

        private val DEFAULT_DURATION = DEFAULT_WORK_DURATION.minutes

        private val defaultLabel =
            Label.defaultLabel().copy(timerProfile = TimerProfile(isLongBreakEnabled = true))

        private val countUpLabel =
            Label(
                name = "flow",
                useDefaultTimeProfile = false,
                timerProfile = TimerProfile(isCountdown = false, workBreakRatio = 3),
            )

        private val shortLabel =
            Label(
                name = "short",
                useDefaultTimeProfile = false,
                timerProfile = TimerProfile(workDuration = WARNING_MINUTES),
            )
    }
}

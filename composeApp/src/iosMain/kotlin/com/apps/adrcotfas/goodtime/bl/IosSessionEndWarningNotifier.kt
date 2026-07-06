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
import com.apps.adrcotfas.goodtime.bl.notifications.SessionEndWarningNotifier
import com.apps.adrcotfas.goodtime.data.settings.SoundData
import goodtime_productivity.composeapp.generated.resources.Res
import goodtime_productivity.composeapp.generated.resources.main_focus_ending_soon
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.compose.resources.getString
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

private const val WARNING_NOTIFICATION_ID = "goodtime_session_end_warning"

/**
 * Schedules a local notification for the session end warning so it is also delivered
 * while the app is suspended in the background. While the app is in the foreground,
 * the notification is not presented (no willPresentNotification implementation, matching
 * the end-of-session notification) and the in-app [com.apps.adrcotfas.goodtime.bl.notifications.SessionEndWarningHandler]
 * provides the sound and the screen flash instead.
 */
class IosSessionEndWarningNotifier(
    private val timeProvider: TimeProvider,
    private val log: Logger,
) : SessionEndWarningNotifier {
    private val notificationCenter = UNUserNotificationCenter.currentNotificationCenter()

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun schedule(
        warningTime: Long,
        soundData: SoundData,
    ) {
        val timeUntilWarning = (warningTime - timeProvider.elapsedRealtime()) / 1000.0
        if (timeUntilWarning <= 0) {
            log.w { "Attempted to schedule the warning with non-positive time: $timeUntilWarning seconds" }
            return
        }

        log.v { "Scheduling session end warning notification in $timeUntilWarning seconds" }

        val content =
            UNMutableNotificationContent().apply {
                setTitle(getString(Res.string.main_focus_ending_soon))
                setSound(resolveNotificationSound(soundData, log))
            }

        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(timeUntilWarning, false)
        val request = UNNotificationRequest.requestWithIdentifier(WARNING_NOTIFICATION_ID, content, trigger)

        notificationCenter.addNotificationRequest(request) { error ->
            if (error != null) {
                log.e { "Failed to schedule the warning notification: ${error.localizedDescription}" }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun cancel() {
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(listOf(WARNING_NOTIFICATION_ID))
    }
}

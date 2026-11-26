package com.littlebit.chat

import android.content.pm.ServiceInfo

/**
 * Pure decisions for [InferenceForegroundService], kept separate so the
 * Android 14-16 foreground-service rules can be unit tested off-device.
 */
internal object ForegroundServicePolicy {
    const val ACTION_STOP = "com.littlebit.chat.action.STOP_INFERENCE"

    /**
     * Android 13+ hides foreground notifications without POST_NOTIFICATIONS.
     * Starting the service anyway would leave an invisible ongoing notification,
     * so the notification simply stays optional.
     */
    fun shouldStartForegroundService(sdkInt: Int, notificationsGranted: Boolean): Boolean =
        sdkInt < 33 || notificationsGranted

    fun isStopAction(action: String?): Boolean = action == ACTION_STOP

    fun shouldCancelOperation(action: String?, versionMatches: Boolean): Boolean =
        isStopAction(action) && versionMatches

    fun shouldStopSelf(action: String?, operationPending: Boolean): Boolean =
        isStopAction(action) && !operationPending

    /** Must match android:foregroundServiceType="specialUse" in the manifest. */
    fun foregroundServiceType(sdkInt: Int): Int =
        if (sdkInt >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
}

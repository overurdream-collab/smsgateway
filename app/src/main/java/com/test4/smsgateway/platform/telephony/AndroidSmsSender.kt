package com.test4.smsgateway.platform.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * Thin Android SMS adapter. Message content is passed through memory only:
 * this class never logs, persists, or returns the destination/body.
 *
 * A successful return means Android accepted the dispatch request; it does
 * not mean the carrier delivered the SMS or the recipient received it.
 */
class AndroidSmsSender(
    private val context: Context
) {
    fun send(
        subscriptionId: Int,
        destination: String,
        messageBody: String
    ): SmsDispatchResult {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return SmsDispatchResult.PermissionRequired
        }

        if (!destination.matches(Regex("^\\+?[1-9][0-9]{7,14}$"))) {
            return SmsDispatchResult.InvalidDestination
        }

        if (messageBody.isBlank()) {
            return SmsDispatchResult.InvalidMessage
        }

        return try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            smsManager.sendTextMessage(destination, null, messageBody, null, null)
            SmsDispatchResult.SubmittedToAndroid
        } catch (_: SecurityException) {
            SmsDispatchResult.PermissionRequired
        } catch (_: IllegalArgumentException) {
            SmsDispatchResult.InvalidRequest
        } catch (_: UnsupportedOperationException) {
            SmsDispatchResult.TelephonyUnavailable
        } catch (_: RuntimeException) {
            SmsDispatchResult.DispatchFailed
        }
    }
}

sealed interface SmsDispatchResult {
    data object SubmittedToAndroid : SmsDispatchResult
    data object PermissionRequired : SmsDispatchResult
    data object InvalidDestination : SmsDispatchResult
    data object InvalidMessage : SmsDispatchResult
    data object InvalidRequest : SmsDispatchResult
    data object TelephonyUnavailable : SmsDispatchResult
    data object DispatchFailed : SmsDispatchResult
}

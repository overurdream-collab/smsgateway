package com.test4.smsgateway.platform.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Android 11 compatible subscription discovery.
 *
 * Phase one uses the single active SIM in the Note 10. The implementation
 * intentionally returns a list so a later dual-SIM policy can use the same
 * contract without changing callers.
 */
class AndroidSimManager(
    private val context: Context
) : SimManager {

    @Volatile
    private var selectedSubscriptionId: Int? = null

    override fun getAvailableSims(): SimAvailability {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return SimAvailability.PermissionRequired
        }

        val subscriptionManager = context.getSystemService(
            SubscriptionManager::class.java
        ) ?: return SimAvailability.TelephonyUnavailable

        val telephonyManager = context.getSystemService(
            TelephonyManager::class.java
        ) ?: return SimAvailability.TelephonyUnavailable

        return try {
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                ?: emptyList()

            val defaultSmsSubscriptionId =
                SubscriptionManager.getDefaultSmsSubscriptionId()

            val sims = activeSubscriptions.map { subscription ->
                val subscriptionTelephony = telephonyManager
                    .createForSubscriptionId(subscription.subscriptionId)

                SimInfo(
                    subscriptionId = subscription.subscriptionId,
                    slotIndex = subscription.simSlotIndex,
                    displayName = subscription.displayName?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: "SIM ${subscription.simSlotIndex + 1}",
                    isReady = subscriptionTelephony.simState ==
                        TelephonyManager.SIM_STATE_READY,
                    isDefaultForSms = subscription.subscriptionId ==
                        defaultSmsSubscriptionId
                )
            }

            val currentSelection = selectedSubscriptionId
            if (currentSelection == null || sims.none {
                    it.subscriptionId == currentSelection
                }
            ) {
                selectedSubscriptionId = sims
                    .firstOrNull { it.isDefaultForSms && it.isReady }
                    ?.subscriptionId
                    ?: sims.firstOrNull { it.isReady }?.subscriptionId
            }

            SimAvailability.Available(sims)
        } catch (_: SecurityException) {
            SimAvailability.PermissionRequired
        } catch (_: RuntimeException) {
            SimAvailability.TelephonyUnavailable
        }
    }

    override fun selectSim(subscriptionId: Int): Boolean {
        val availability = getAvailableSims()
        val sims = (availability as? SimAvailability.Available)?.subscriptions
            ?: return false

        val candidate = sims.firstOrNull {
            it.subscriptionId == subscriptionId && it.isReady
        } ?: return false

        selectedSubscriptionId = candidate.subscriptionId
        return true
    }

    override fun getSelectedSubscriptionId(): Int? = selectedSubscriptionId
}

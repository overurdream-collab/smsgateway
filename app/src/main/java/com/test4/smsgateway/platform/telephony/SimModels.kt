package com.test4.smsgateway.platform.telephony

/**
 * Stable application-level representation of a SIM subscription.
 * Do not persist the phone number or carrier identifying data.
 */
data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
    val isReady: Boolean,
    val isDefaultForSms: Boolean
)

sealed interface SimAvailability {
    data object PermissionRequired : SimAvailability
    data object TelephonyUnavailable : SimAvailability
    data class Available(val subscriptions: List<SimInfo>) : SimAvailability
}

/**
 * The gateway depends on this abstraction, not directly on Android's
 * SubscriptionManager. A future dual-SIM implementation can add policy
 * and selection without changing the queue or transport layers.
 */
interface SimManager {
    fun getAvailableSims(): SimAvailability
    fun selectSim(subscriptionId: Int): Boolean
    fun getSelectedSubscriptionId(): Int?
}

package com.test4.smsgateway.domain.model

/**
 * Metadata-only SMS job model.
 *
 * OTP, destination and message body are intentionally excluded from this
 * persisted/domain representation. They must remain transient in memory.
 */
data class SmsJob(
    val requestId: String,
    val subscriptionId: Int,
    val state: SmsJobState,
    val attempt: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val failureCode: String? = null
)

enum class SmsJobState {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    DELIVERY_UNKNOWN,
    DELIVERED
}

sealed interface SmsJobTransition {
    data object Invalid : SmsJobTransition
    data class Applied(val job: SmsJob) : SmsJobTransition
}

object SmsJobStateMachine {
    /**
     * Move to SENDING immediately before invoking Android's SMS API.
     * A job in DELIVERY_UNKNOWN must never be automatically resubmitted.
     */
    fun transition(
        job: SmsJob,
        target: SmsJobState,
        nowEpochMillis: Long,
        failureCode: String? = null
    ): SmsJobTransition {
        val allowed = when (job.state) {
            SmsJobState.PENDING ->
                target == SmsJobState.SENDING
            SmsJobState.SENDING ->
                target == SmsJobState.SENT ||
                    target == SmsJobState.FAILED ||
                    target == SmsJobState.DELIVERY_UNKNOWN
            SmsJobState.SENT ->
                target == SmsJobState.DELIVERED ||
                    target == SmsJobState.DELIVERY_UNKNOWN
            SmsJobState.FAILED,
            SmsJobState.DELIVERY_UNKNOWN,
            SmsJobState.DELIVERED -> false
        }

        if (!allowed || nowEpochMillis < job.updatedAtEpochMillis) {
            return SmsJobTransition.Invalid
        }

        return SmsJobTransition.Applied(
            job.copy(
                state = target,
                attempt = if (job.state == SmsJobState.PENDING &&
                    target == SmsJobState.SENDING
                ) job.attempt + 1 else job.attempt,
                updatedAtEpochMillis = nowEpochMillis,
                failureCode = failureCode
            )
        )
    }

    /** Only known pre-dispatch failures may be deliberately re-queued. */
    fun requeueKnownFailure(job: SmsJob, nowEpochMillis: Long): SmsJobTransition {
        if (job.state != SmsJobState.FAILED ||
            nowEpochMillis < job.updatedAtEpochMillis
        ) {
            return SmsJobTransition.Invalid
        }

        return SmsJobTransition.Applied(
            job.copy(
                state = SmsJobState.PENDING,
                updatedAtEpochMillis = nowEpochMillis,
                failureCode = null
            )
        )
    }
}

package com.test4.smsgateway.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsJobStateMachineTest {
    private fun job(state: SmsJobState = SmsJobState.PENDING) = SmsJob(
        requestId = "request-test-1",
        subscriptionId = 1,
        state = state,
        attempt = 0,
        createdAtEpochMillis = 100,
        updatedAtEpochMillis = 100
    )

    @Test
    fun pendingCanMoveToSendingAndIncrementsAttempt() {
        val result = SmsJobStateMachine.transition(
            job(), SmsJobState.SENDING, nowEpochMillis = 110
        )
        assertTrue(result is SmsJobTransition.Applied)
        assertEquals(1, (result as SmsJobTransition.Applied).job.attempt)
    }

    @Test
    fun sendingCanBecomeDeliveryUnknown() {
        val sending = (SmsJobStateMachine.transition(
            job(), SmsJobState.SENDING, 110
        ) as SmsJobTransition.Applied).job

        val result = SmsJobStateMachine.transition(
            sending, SmsJobState.DELIVERY_UNKNOWN, 120
        )
        assertTrue(result is SmsJobTransition.Applied)
        assertEquals(
            SmsJobState.DELIVERY_UNKNOWN,
            (result as SmsJobTransition.Applied).job.state
        )
    }

    @Test
    fun deliveryUnknownCannotBeRequeued() {
        val unknown = job(SmsJobState.DELIVERY_UNKNOWN)
        assertTrue(
            SmsJobStateMachine.requeueKnownFailure(unknown, 120) is
                SmsJobTransition.Invalid
        )
    }

    @Test
    fun failedKnownRequestCanBeRequeued() {
        val failed = job(SmsJobState.FAILED)
        val result = SmsJobStateMachine.requeueKnownFailure(failed, 120)
        assertTrue(result is SmsJobTransition.Applied)
        assertEquals(
            SmsJobState.PENDING,
            (result as SmsJobTransition.Applied).job.state
        )
    }

    @Test
    fun timeCannotMoveBackwards() {
        val result = SmsJobStateMachine.transition(
            job(), SmsJobState.SENDING, nowEpochMillis = 99
        )
        assertTrue(result is SmsJobTransition.Invalid)
    }
}

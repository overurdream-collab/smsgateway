package com.test4.smsgateway.domain.model

import org.junit.Assert.*
import org.junit.Test

class GatewayDispatchCycleTest {
    private class FakePort : GatewayDispatchPort {
        val sim = GatewaySim(10, 0)
        var ready: DispatchReadiness = DispatchReadiness.Ready(sim)
        var job: GatewayJob? = GatewayJob("synthetic-job", "synthetic-recipient", "synthetic-message")
        var afterPoll: (() -> Unit)? = null
        var outcome = SmsSubmission.SENT
        var pollError = false
        var submitError: Exception? = null
        var reportError = false
        var polls = 0
        var submissions = 0
        val reports = mutableListOf<String>()
        override fun readiness() = ready
        override fun poll(): GatewayJob? {
            polls++
            if (pollError) throw IllegalStateException("synthetic transport failure")
            afterPoll?.invoke()
            return job
        }
        override fun submit(job: GatewayJob, sim: GatewaySim): SmsSubmission {
            submissions++
            submitError?.let { throw it }
            return outcome
        }
        override fun report(job: GatewayJob, status: String, sim: GatewaySim) {
            reports.add(status)
            if (reportError) throw IllegalStateException("synthetic lost ACK")
        }
    }

    @Test fun `missing local prerequisites never lease a job`() {
        val cases = listOf(
            DispatchReadiness.LocalDisabled to DispatchResult.LOCAL_DISABLED,
            DispatchReadiness.SmsPermissionRequired to DispatchResult.SMS_PERMISSION_REQUIRED,
            DispatchReadiness.PhonePermissionRequired to DispatchResult.PHONE_PERMISSION_REQUIRED,
            DispatchReadiness.NoReadySim to DispatchResult.NO_READY_SIM,
        )
        for ((readiness, expected) in cases) {
            val port = FakePort().apply { ready = readiness }
            assertEquals(expected, GatewayDispatchCycle(port).runOne())
            assertEquals(0, port.polls)
            assertEquals(0, port.submissions)
            assertTrue(port.reports.isEmpty())
        }
    }

    @Test fun `empty queue is distinct from missing permission`() {
        val port = FakePort().apply { job = null }
        assertEquals(DispatchResult.IDLE, GatewayDispatchCycle(port).runOne())
        assertEquals(1, port.polls)
        assertEquals(0, port.submissions)
    }

    @Test fun `revocation or SIM replacement during polling prevents submission`() {
        for (changed in listOf(
            DispatchReadiness.LocalDisabled,
            DispatchReadiness.SmsPermissionRequired,
            DispatchReadiness.PhonePermissionRequired,
            DispatchReadiness.NoReadySim,
            DispatchReadiness.Ready(GatewaySim(11, 0)),
        )) {
            val port = FakePort().apply { afterPoll = { ready = changed } }
            assertEquals(DispatchResult.CANCELLED_AFTER_POLL, GatewayDispatchCycle(port).runOne())
            assertEquals(0, port.submissions)
            assertTrue(port.reports.isEmpty())
        }
    }

    @Test fun `confirmed send records sent not delivered`() {
        val port = FakePort()
        assertEquals(DispatchResult.SENT, GatewayDispatchCycle(port).runOne())
        assertEquals(1, port.submissions)
        assertEquals(listOf("sent"), port.reports)
    }

    @Test fun `known Android failure is reported once without resend`() {
        val port = FakePort().apply { outcome = SmsSubmission.FAILED }
        assertEquals(DispatchResult.FAILED, GatewayDispatchCycle(port).runOne())
        assertEquals(listOf("failed"), port.reports)
        assertEquals(1, port.submissions)
    }

    @Test fun `unknown callback never reports failure`() {
        val port = FakePort().apply { outcome = SmsSubmission.UNKNOWN }
        assertEquals(DispatchResult.UNKNOWN, GatewayDispatchCycle(port).runOne())
        assertTrue(port.reports.isEmpty())
        assertEquals(1, port.submissions)
    }

    @Test fun `submission exception cannot cause an automatic retry`() {
        val port = FakePort().apply { submitError = IllegalStateException("synthetic") }
        assertEquals(DispatchResult.UNKNOWN, GatewayDispatchCycle(port).runOne())
        assertTrue(port.reports.isEmpty())
        assertEquals(1, port.submissions)
    }

    @Test fun `interruption preserves interrupt status and uncertain outcome`() {
        val port = FakePort().apply { submitError = InterruptedException() }
        try {
            assertEquals(DispatchResult.UNKNOWN, GatewayDispatchCycle(port).runOne())
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(port.reports.isEmpty())
        } finally { Thread.interrupted() }
    }

    @Test fun `lost acknowledgement does not retry SMS or invent success`() {
        val port = FakePort().apply { reportError = true }
        assertEquals(DispatchResult.ACK_PENDING, GatewayDispatchCycle(port).runOne())
        assertEquals(1, port.submissions)
        assertEquals(listOf("sent"), port.reports)
    }

    @Test fun `multipart refusal is a known unsent failure`() {
        val port = FakePort().apply { outcome = SmsSubmission.UNSUPPORTED_MESSAGE }
        assertEquals(DispatchResult.UNSUPPORTED_MESSAGE, GatewayDispatchCycle(port).runOne())
        assertEquals(listOf("failed"), port.reports)
    }

    @Test fun `server error does not become empty queue`() {
        val port = FakePort().apply { pollError = true }
        assertThrows(IllegalStateException::class.java) { GatewayDispatchCycle(port).runOne() }
        assertEquals(0, port.submissions)
        assertTrue(port.reports.isEmpty())
    }

    @Test fun `diagnostics and payload identity never expose message contents`() {
        val port = FakePort()
        for (result in DispatchResult.entries) {
            assertFalse(result.diagnostic.contains(port.job!!.text))
            assertFalse(result.diagnostic.contains(port.job!!.recipient))
        }
        assertFalse(port.job.toString().contains(port.job!!.text))
    }
}

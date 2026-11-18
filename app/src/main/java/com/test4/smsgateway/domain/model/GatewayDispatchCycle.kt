package com.test4.smsgateway.domain.model

/** Transient payload: never persist, log, or include in diagnostics. */
class GatewayJob(val id: String, val recipient: String, val text: String)

data class GatewaySim(val subscriptionId: Int, val slotIndex: Int)

sealed interface DispatchReadiness {
    data class Ready(val sim: GatewaySim) : DispatchReadiness
    data object LocalDisabled : DispatchReadiness
    data object SmsPermissionRequired : DispatchReadiness
    data object PhonePermissionRequired : DispatchReadiness
    data object NoReadySim : DispatchReadiness
}

enum class SmsSubmission { SENT, FAILED, UNKNOWN, UNSUPPORTED_MESSAGE }

enum class DispatchResult(val diagnostic: String) {
    LOCAL_DISABLED("الإرسال المحلي معطّل؛ فعّل الاختبار يدوياً بعد الاعتماد"),
    SMS_PERMISSION_REQUIRED("إذن SMS غير ممنوح؛ لم تُسحب أي مهمة"),
    PHONE_PERMISSION_REQUIRED("إذن قراءة حالة الشريحة غير ممنوح؛ لم تُسحب أي مهمة"),
    NO_READY_SIM("لا توجد شريحة جاهزة؛ لم تُسحب أي مهمة"),
    IDLE("الجهاز معتمد ومتصل؛ طابور الرسائل فارغ"),
    CANCELLED_AFTER_POLL("تغيّر التصريح أو الشريحة؛ لم تُرسل المهمة المحجوزة وتحتاج مراجعة"),
    SENT("أكد Android الإرسال وسُجل بالخادم؛ استلام المستلم لم يُثبت بعد"),
    FAILED("أبلغ Android بفشل الإرسال؛ سُجل الفشل دون إعادة تلقائية"),
    UNKNOWN("نتيجة الإرسال غير مؤكدة؛ لا تعِد الإرسال قبل مراجعة المهمة"),
    ACK_PENDING("ظهرت نتيجة الإرسال لكن تعذر تسجيلها بالخادم؛ تحتاج تسوية دون إعادة إرسال"),
    UNSUPPORTED_MESSAGE("الرسالة متعددة الأجزاء؛ لم تُرسل وسُجل فشلها بالخادم"),
}

interface GatewayDispatchPort {
    fun readiness(): DispatchReadiness
    fun poll(): GatewayJob?
    fun submit(job: GatewayJob, sim: GatewaySim): SmsSubmission
    fun report(job: GatewayJob, status: String, sim: GatewaySim)
}

/** One lease per cycle. Never retry a submission or ACK by resending the SMS. */
class GatewayDispatchCycle(private val port: GatewayDispatchPort) {
    fun runOne(): DispatchResult {
        val before = port.readiness()
        if (before !is DispatchReadiness.Ready) return when (before) {
            DispatchReadiness.LocalDisabled -> DispatchResult.LOCAL_DISABLED
            DispatchReadiness.SmsPermissionRequired -> DispatchResult.SMS_PERMISSION_REQUIRED
            DispatchReadiness.PhonePermissionRequired -> DispatchResult.PHONE_PERMISSION_REQUIRED
            else -> DispatchResult.NO_READY_SIM
        }
        val job = port.poll() ?: return DispatchResult.IDLE
        // Polling can block: cancellation, permission revocation and SIM changes must win.
        val after = port.readiness()
        if (after !is DispatchReadiness.Ready || after.sim != before.sim) {
            // The server deliberately does not requeue expired leases. Leave it for review.
            return DispatchResult.CANCELLED_AFTER_POLL
        }
        val outcome = try { port.submit(job, after.sim) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return DispatchResult.UNKNOWN
        } catch (_: Exception) { return DispatchResult.UNKNOWN }
        if (outcome == SmsSubmission.UNKNOWN) return DispatchResult.UNKNOWN
        try {
            port.report(job, if (outcome == SmsSubmission.SENT) "sent" else "failed", after.sim)
        } catch (_: Exception) { return DispatchResult.ACK_PENDING }
        return when (outcome) {
            SmsSubmission.SENT -> DispatchResult.SENT
            SmsSubmission.FAILED -> DispatchResult.FAILED
            SmsSubmission.UNSUPPORTED_MESSAGE -> DispatchResult.UNSUPPORTED_MESSAGE
            SmsSubmission.UNKNOWN -> DispatchResult.UNKNOWN
        }
    }
}

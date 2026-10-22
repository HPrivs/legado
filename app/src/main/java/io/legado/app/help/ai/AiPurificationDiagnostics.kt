package io.legado.app.help.ai

import android.os.SystemClock
import io.legado.app.constant.AppLog
import java.util.concurrent.atomic.AtomicLong

/** Lightweight stage tracing that never records prompts, novel text, URLs, or credentials. */
object AiPurificationDiagnostics {

    private val traceSequence = AtomicLong()

    fun newTraceId(): String = "P${traceSequence.incrementAndGet()}"

    fun nowMillis(): Long = SystemClock.elapsedRealtime()

    fun log(
        traceId: String,
        stage: String,
        startedAtMillis: Long? = null,
        details: String = ""
    ) {
        val elapsed = startedAtMillis?.let {
            " elapsedMs=${(nowMillis() - it).coerceAtLeast(0L)}"
        }.orEmpty()
        val detailSuffix = details.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        AppLog.put("AI净化诊断 [$traceId] stage=$stage$elapsed$detailSuffix")
    }
}

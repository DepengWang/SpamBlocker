package spam.blocker.util

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job

/** Invalidates late work after hangup, timeout, preview, or a newer call. */
object CallerIdentitySession {
    private val generation = AtomicLong()
    private var work: Job? = null
    @Synchronized fun begin(): Long {
        work?.cancel()
        work = null
        return generation.incrementAndGet()
    }
    fun active(token: Long): Boolean = generation.get() == token
    fun invalidate() { begin() }
    @Synchronized fun track(token: Long, job: Job) {
        if (!active(token)) job.cancel() else work = job
    }
    @Synchronized fun finish(token: Long) { if (active(token)) work = null }
}

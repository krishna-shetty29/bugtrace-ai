package com.bugtrace.worker;

import java.time.*;
import java.util.concurrent.TimeUnit;

/** Clamp to both per-job time and the absolute loop deadline, including queue/transit delay. */
public final class ExecutionDeadline {
    public static long remainingNanos(int timeoutSeconds,Instant notAfter,Instant now) {
        long perJob=TimeUnit.SECONDS.toNanos(timeoutSeconds);
        if(notAfter==null)return perJob;
        var remaining=Duration.between(now,notAfter);
        if(remaining.isNegative()||remaining.isZero())return 0;
        return remaining.compareTo(Duration.ofSeconds(timeoutSeconds))>=0?perJob:remaining.toNanos();
    }
}

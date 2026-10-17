package com.bugtrace.worker;

import java.time.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionDeadlineTest {
    final Instant now=Instant.parse("2026-10-17T00:00:00Z");
    @Test void retainsLegacyPerJobTimeout(){assertEquals(TimeUnit.SECONDS.toNanos(120),ExecutionDeadline.remainingNanos(120,null,now));}
    @Test void clampsToAbsoluteDeadlineAfterQueueDelay(){assertEquals(TimeUnit.SECONDS.toNanos(15),ExecutionDeadline.remainingNanos(120,now.plusSeconds(15),now));}
    @Test void rejectsExpiredDeadlineBeforeContainerStart(){assertEquals(0,ExecutionDeadline.remainingNanos(120,now.minusSeconds(1),now));}
    @Test void absoluteDeadlineCannotIncreasePerJobBudget(){assertEquals(TimeUnit.SECONDS.toNanos(120),ExecutionDeadline.remainingNanos(120,now.plusSeconds(900),now));}
    @Test void extremeTimestampCannotOverflowDeadlineArithmetic(){assertEquals(TimeUnit.SECONDS.toNanos(120),ExecutionDeadline.remainingNanos(120,Instant.MAX,now));}
}

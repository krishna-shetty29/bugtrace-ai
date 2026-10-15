package com.bugtrace.backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableScheduling
public class QueueRecovery {
    private static final Logger log=LoggerFactory.getLogger(QueueRecovery.class);
    private final DurableQueueService queue;
    public QueueRecovery(DurableQueueService queue) { this.queue=queue; }
    @Scheduled(fixedDelay=5000)
    public void recover() {
        try { queue.recoverExpired(); }
        catch (Exception exception) { log.warn("Queue recovery failed; will retry",exception); }
    }
}

package com.example.kafka.consumer.scheduler;

import com.example.kafka.consumer.repository.ProcessedMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@Slf4j
public class DatabaseCleanupScheduler {
    
    private final ProcessedMessageRepository repository;
    private final int cleanupDays;
    
    public DatabaseCleanupScheduler(
            ProcessedMessageRepository repository,
            @Value("${app.idempotency.postgres-cleanup-days:7}") int cleanupDays) {
        
        this.repository = repository;
        this.cleanupDays = cleanupDays;
        
        log.info("DatabaseCleanupScheduler initialized - cleanup after {} days", cleanupDays);
    }
    
    // @Scheduled(cron = "0 0 2 * * *")
    // @Transactional
    // public void cleanupOldMessages() {
    //     log.info("Starting cleanup of old processed messages");
        
    //     try {
    //         LocalDateTime cutoffDate = LocalDateTime.now().minusDays(cleanupDays);
            
    //         int deletedCount = repository.deleteOldMessages(cutoffDate);
            
    //         log.info("Cleanup completed - {} old messages deleted (older than {})", 
    //                 deletedCount, cutoffDate);
            
    //     } catch (Exception e) {
    //         log.error("Error during database cleanup", e);
    //     }
    // }
    
    // @Scheduled(fixedRate = 3600000)
    // public void logStatistics() {
    //     try {
    //         LocalDateTime last24h = LocalDateTime.now().minusHours(24);
    //         long processedLast24h = repository.countProcessedSince(last24h);
            
    //         log.info("Statistics - Messages processed in last 24h: {}", processedLast24h);
            
    //     } catch (Exception e) {
    //         log.error("Error logging statistics", e);
    //     }
    // }
}

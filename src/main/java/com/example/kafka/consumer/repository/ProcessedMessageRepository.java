package com.example.kafka.consumer.repository;

import com.example.kafka.consumer.entity.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {
    
    Optional<ProcessedMessage> findByMessageId(String messageId);
    
    boolean existsByMessageId(String messageId);
    
    @Modifying
    @Query("DELETE FROM ProcessedMessage pm WHERE pm.processedAt < :cutoffDate")
    int deleteOldMessages(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    @Query("SELECT COUNT(pm) FROM ProcessedMessage pm WHERE pm.processedAt >= :since")
    long countProcessedSince(@Param("since") LocalDateTime since);
}

package com.example.kafka.consumer.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_messages", indexes = {
    @Index(name = "idx_message_id", columnList = "message_id", unique = true),
    @Index(name = "idx_event_type", columnList = "event_type"),
    @Index(name = "idx_processed_at", columnList = "processed_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedMessage {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "message_id", nullable = false, unique = true, length = 255)
    private String messageId;
    
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;
    
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;
    
    @Column(name = "source", length = 100)
    private String source;
    
    @Column(name = "correlation_id", length = 255)
    private String correlationId;
    
    @Column(name = "message_timestamp")
    private LocalDateTime messageTimestamp;
    
    @Column(name = "processed_at")
    @CreationTimestamp
    private LocalDateTime processedAt;
    
    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private ProcessingStatus status;
    
    @Column(name = "retry_count")
    private Integer retryCount;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    public enum ProcessingStatus {
        SUCCESS,
        FAILED,
        DUPLICATE
    }
}

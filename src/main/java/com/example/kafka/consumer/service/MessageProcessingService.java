package com.example.kafka.consumer.service;

import com.example.kafka.consumer.dto.MessageDto;
import com.example.kafka.consumer.entity.ProcessedMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class MessageProcessingService {
    
    private final IdempotencyService idempotencyService;
    private final Counter successCounter;
    private final Counter duplicateCounter;
    private final Counter failureCounter;
    private final Timer processingTimer;
    
    public MessageProcessingService(
            IdempotencyService idempotencyService,
            MeterRegistry meterRegistry) {
        
        this.idempotencyService = idempotencyService;
        
        this.successCounter = meterRegistry.counter("message.processing.success");
        this.duplicateCounter = meterRegistry.counter("message.processing.duplicate");
        this.failureCounter = meterRegistry.counter("message.processing.failure");
        this.processingTimer = meterRegistry.timer("message.processing.duration");
    }
    
    @Transactional
    public ProcessingResult processMessage(MessageDto messageDto) {
        return processingTimer.record(() -> {
            log.debug("Processing message: {}", messageDto.getMessageId());
            
            try {
                if (idempotencyService.isAlreadyProcessed(messageDto.getMessageId())) {
                    log.info("Duplicate message detected and skipped: {}", messageDto.getMessageId());
                    duplicateCounter.increment();
                    return ProcessingResult.duplicate(messageDto.getMessageId());
                }
                
                executeBusinessLogic(messageDto);
                
                ProcessedMessage processedMessage = buildProcessedMessage(messageDto, 
                        ProcessedMessage.ProcessingStatus.SUCCESS, null);
                
                if (idempotencyService.markAsProcessed(messageDto.getMessageId(), processedMessage)) {
                    log.info("Message processed successfully: {}", messageDto.getMessageId());
                    successCounter.increment();
                    return ProcessingResult.success(messageDto.getMessageId());
                } else {
                    log.warn("Failed to mark message as processed: {}", messageDto.getMessageId());
                    return ProcessingResult.duplicate(messageDto.getMessageId());
                }
                
            } catch (Exception e) {
                log.error("Error processing message: {}", messageDto.getMessageId(), e);
                failureCounter.increment();
                
                try {
                    ProcessedMessage errorMessage = buildProcessedMessage(messageDto, 
                            ProcessedMessage.ProcessingStatus.FAILED, e.getMessage());
                    idempotencyService.markAsProcessed(messageDto.getMessageId(), errorMessage);
                } catch (Exception ex) {
                    log.error("Failed to save error state for message: {}", messageDto.getMessageId(), ex);
                }
                
                return ProcessingResult.failure(messageDto.getMessageId(), e.getMessage());
            }
        });
    }
    
    private void executeBusinessLogic(MessageDto messageDto) {
        log.debug("Executing business logic for message: {}", messageDto.getMessageId());
        
        switch (messageDto.getEventType()) {
            case "ORDER_CREATED":
                processOrderCreated(messageDto);
                break;
            case "PAYMENT_RECEIVED":
                processPaymentReceived(messageDto);
                break;
            case "INVENTORY_UPDATE":
                processInventoryUpdate(messageDto);
                break;
            default:
                log.warn("Unknown event type: {}", messageDto.getEventType());
        }
    }
    
    private void processOrderCreated(MessageDto messageDto) {
        log.info("Processing ORDER_CREATED: {}", messageDto.getPayload());
    }
    
    private void processPaymentReceived(MessageDto messageDto) {
        log.info("Processing PAYMENT_RECEIVED: {}", messageDto.getPayload());
    }
    
    private void processInventoryUpdate(MessageDto messageDto) {
        log.info("Processing INVENTORY_UPDATE: {}", messageDto.getPayload());
    }
    
    private ProcessedMessage buildProcessedMessage(
            MessageDto messageDto, 
            ProcessedMessage.ProcessingStatus status,
            String errorMessage) {
        
        return ProcessedMessage.builder()
                .messageId(messageDto.getMessageId())
                .eventType(messageDto.getEventType())
                .payload(messageDto.getPayload())
                .source(messageDto.getSource())
                .correlationId(messageDto.getCorrelationId())
                .messageTimestamp(messageDto.getTimestamp())
                .status(status)
                .retryCount(0)
                .errorMessage(errorMessage)
                .build();
    }
    
    public record ProcessingResult(
            String messageId,
            Status status,
            String errorMessage
    ) {
        public enum Status {
            SUCCESS,
            DUPLICATE,
            FAILURE
        }
        
        public static ProcessingResult success(String messageId) {
            return new ProcessingResult(messageId, Status.SUCCESS, null);
        }
        
        public static ProcessingResult duplicate(String messageId) {
            return new ProcessingResult(messageId, Status.DUPLICATE, null);
        }
        
        public static ProcessingResult failure(String messageId, String errorMessage) {
            return new ProcessingResult(messageId, Status.FAILURE, errorMessage);
        }
        
        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }
        
        public boolean isDuplicate() {
            return status == Status.DUPLICATE;
        }
        
        public boolean isFailure() {
            return status == Status.FAILURE;
        }
    }
}

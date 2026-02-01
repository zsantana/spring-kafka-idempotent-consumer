package com.example.kafka.consumer.consumer;

import com.example.kafka.consumer.dto.MessageDto;
import com.example.kafka.consumer.service.MessageProcessingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class HighVolumeKafkaConsumer {
    
    private final MessageProcessingService messageProcessingService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String dlqTopic;
    private final ExecutorService executorService;
    
    private final Counter receivedCounter;
    private final Counter processedCounter;
    private final Counter failedCounter;
    private final Counter dlqCounter;
    private final Timer batchProcessingTimer;
    
    public HighVolumeKafkaConsumer(
            MessageProcessingService messageProcessingService,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${app.kafka.dlq-topic}") String dlqTopic,
            @Value("${spring.kafka.listener.concurrency:10}") int concurrency,
            MeterRegistry meterRegistry) {
        
        this.messageProcessingService = messageProcessingService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.dlqTopic = dlqTopic;
        
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        
        this.receivedCounter = meterRegistry.counter("kafka.messages.received");
        this.processedCounter = meterRegistry.counter("kafka.messages.processed");
        this.failedCounter = meterRegistry.counter("kafka.messages.failed");
        this.dlqCounter = meterRegistry.counter("kafka.messages.dlq");
        this.batchProcessingTimer = meterRegistry.timer("kafka.batch.processing.duration");
        
        log.info("HighVolumeKafkaConsumer initialized with {} concurrent consumers", concurrency);
    }
    
    @KafkaListener(
        topics = "${app.kafka.topic}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory",
        concurrency = "${spring.kafka.listener.concurrency:10}"
    )
    public void consumeBatch(
            List<ConsumerRecord<String, String>> records,
            Acknowledgment acknowledgment) {
        
        batchProcessingTimer.record(() -> {
            int batchSize = records.size();
            receivedCounter.increment(batchSize);
            
            log.info("Received batch of {} messages", batchSize);
            
            try {
                List<CompletableFuture<Void>> futures = records.stream()
                    .map(record -> CompletableFuture.runAsync(
                        () -> processRecord(record),
                        executorService
                    ))
                    .toList();
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                if (acknowledgment != null) {
                    acknowledgment.acknowledge();
                    log.debug("Batch acknowledged successfully");
                }
                
                log.info("Batch of {} messages processed successfully", batchSize);
                
            } catch (Exception e) {
                log.error("Error processing batch", e);
                failedCounter.increment(batchSize);
            }
        });
    }
    
    private void processRecord(ConsumerRecord<String, String> record) {
        try {
            log.debug("Processing record - Partition: {}, Offset: {}, Key: {}", 
                    record.partition(), record.offset(), record.key());
            
            MessageDto messageDto = deserializeMessage(record.value());
            
            if (messageDto == null) {
                log.error("Failed to deserialize message at offset {}", record.offset());
                sendToDLQ(record, "Deserialization failed");
                return;
            }
            
            MessageProcessingService.ProcessingResult result = 
                    messageProcessingService.processMessage(messageDto);
            
            handleProcessingResult(result, record);
            
        } catch (Exception e) {
            log.error("Unexpected error processing record at offset {}", record.offset(), e);
            failedCounter.increment();
            sendToDLQ(record, "Unexpected error: " + e.getMessage());
        }
    }
    
    private MessageDto deserializeMessage(String jsonMessage) {
        try {
            return objectMapper.readValue(jsonMessage, MessageDto.class);
        } catch (JsonProcessingException e) {
            log.error("Error deserializing message: {}", jsonMessage, e);
            return null;
        }
    }
    
    private void handleProcessingResult(
            MessageProcessingService.ProcessingResult result,
            ConsumerRecord<String, String> record) {
        
        if (result.isSuccess()) {
            processedCounter.increment();
            log.debug("Message {} processed successfully", result.messageId());
            
        } else if (result.isDuplicate()) {
            log.debug("Duplicate message {} skipped", result.messageId());
            
        } else if (result.isFailure()) {
            log.error("Failed to process message {}: {}", 
                    result.messageId(), result.errorMessage());
            failedCounter.increment();
            sendToDLQ(record, result.errorMessage());
        }
    }
    
    private void sendToDLQ(ConsumerRecord<String, String> record, String errorReason) {
        try {
            log.warn("Sending message to DLQ - Reason: {}, Offset: {}", 
                    errorReason, record.offset());
            
            kafkaTemplate.send(dlqTopic, record.key(), record.value())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send message to DLQ", ex);
                    } else {
                        dlqCounter.increment();
                        log.debug("Message sent to DLQ successfully");
                    }
                });
                
        } catch (Exception e) {
            log.error("Error sending message to DLQ", e);
        }
    }
}

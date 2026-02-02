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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class HighVolumeKafkaConsumer {
    
    private final MessageProcessingService messageProcessingService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String dlqTopic;
    private final ExecutorService workers;
    private final ConcurrentLinkedQueue<ConsumerRecord<String, String>> buffer;
    
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
        
        // ExecutorService com Virtual Threads para processamento assíncrono
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        
        // Buffer thread-safe para armazenar registros do Kafka
        this.buffer = new ConcurrentLinkedQueue<>();
        
        this.receivedCounter = meterRegistry.counter("kafka.messages.received");
        this.processedCounter = meterRegistry.counter("kafka.messages.processed");
        this.failedCounter = meterRegistry.counter("kafka.messages.failed");
        this.dlqCounter = meterRegistry.counter("kafka.messages.dlq");
        this.batchProcessingTimer = meterRegistry.timer("kafka.batch.processing.duration");
        
        // Registra gauge para monitorar tamanho do buffer
        meterRegistry.gauge("kafka.buffer.current.size", buffer, ConcurrentLinkedQueue::size);
        
        log.info("HighVolumeKafkaConsumer initialized with {} concurrent consumers and Virtual Thread executor", concurrency);
    }
    
    /**
     * Listener do Kafka que adiciona mensagens ao buffer e faz commit imediato.
     * O processamento real acontece de forma assíncrona no método drain().
     */
    @KafkaListener(
        topics = "${app.kafka.topic}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory",
        concurrency = "${spring.kafka.listener.concurrency:10}"
    )
    public void onMessage(
            List<ConsumerRecord<String, String>> records,
            Acknowledgment acknowledgment) {
        
        int batchSize = records.size();
        receivedCounter.increment(batchSize);
        
        log.info("Received batch of {} messages, adding to buffer", batchSize);
        
        // Adiciona todos os registros ao buffer - O(1) por operação
        for (ConsumerRecord<String, String> record : records) {
            buffer.offer(record);
        }
        
        // Commit imediato - não bloqueia o poll()
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
            log.info("Batch of {} messages acknowledged immediately", batchSize);
        }
        
        log.info("Buffer size after adding batch: {}", buffer.size());
    }
    
    /**
     * Drena o buffer e submete mensagens para processamento em threads virtuais.
     * Executa com delay mínimo para processar continuamente.
     */
    @Scheduled(fixedDelay = 10)
    public void drain() {
        ConsumerRecord<String, String> record;
        int processed = 0;
        
        
        // Drena o buffer enquanto houver mensagens
        while ((record = buffer.poll()) != null) {
            final ConsumerRecord<String, String> currentRecord = record;
            
            // Submete para processamento assíncrono em virtual thread
            workers.submit(() -> processRecord(currentRecord));
            processed++;
        }
        
        if (processed > 0) {
            log.info("Submitted {} messages for async processing", processed);
        }
    }
    
    private void processRecord(ConsumerRecord<String, String> record) {
        try {
            log.info("Processing record - Partition: {}, Offset: {}, Key: {}", 
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

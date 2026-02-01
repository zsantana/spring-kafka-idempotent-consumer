package com.example.kafka.consumer.producer;

import com.example.kafka.consumer.dto.MessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaMessageProducer {

    private final KafkaTemplate<String, MessageDto> kafkaTemplate;

    @Value("${app.kafka.topic}")
    private String topic;

    /**
     * Envia uma mensagem para o tópico Kafka configurado.
     *
     * @param message A mensagem a ser enviada
     * @return CompletableFuture com o resultado do envio
     */
    public CompletableFuture<SendResult<String, MessageDto>> send(MessageDto message) {
        // Garante que tem um ID se não foi fornecido
        if (message.getMessageId() == null || message.getMessageId().isBlank()) {
            message.setMessageId(UUID.randomUUID().toString());
        }
        
        // Define timestamp se não fornecido
        if (message.getTimestamp() == null) {
            message.setTimestamp(LocalDateTime.now());
        }

        log.info("Sending message to Kafka topic [{}]: messageId={}", topic, message.getMessageId());
        
        return kafkaTemplate.send(topic, message.getMessageId(), message)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Message sent successfully: messageId={}, partition={}, offset={}",
                                message.getMessageId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to send message: messageId={}, error={}",
                                message.getMessageId(), ex.getMessage());
                    }
                });
    }

    /**
     * Envia uma mensagem para um tópico específico.
     *
     * @param targetTopic O tópico de destino
     * @param message A mensagem a ser enviada
     * @return CompletableFuture com o resultado do envio
     */
    public CompletableFuture<SendResult<String, MessageDto>> sendToTopic(String targetTopic, MessageDto message) {
        if (message.getMessageId() == null || message.getMessageId().isBlank()) {
            message.setMessageId(UUID.randomUUID().toString());
        }
        
        if (message.getTimestamp() == null) {
            message.setTimestamp(LocalDateTime.now());
        }

        log.info("Sending message to Kafka topic [{}]: messageId={}", targetTopic, message.getMessageId());
        
        return kafkaTemplate.send(targetTopic, message.getMessageId(), message)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Message sent successfully to [{}]: messageId={}, partition={}, offset={}",
                                targetTopic,
                                message.getMessageId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to send message to [{}]: messageId={}, error={}",
                                targetTopic, message.getMessageId(), ex.getMessage());
                    }
                });
    }
}

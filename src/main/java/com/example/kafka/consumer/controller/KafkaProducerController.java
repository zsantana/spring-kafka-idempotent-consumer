package com.example.kafka.consumer.controller;

import com.example.kafka.consumer.dto.*;
import com.example.kafka.consumer.producer.KafkaMessageProducer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.SendResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequestMapping("/api/v1/kafka")
@RequiredArgsConstructor
@Validated
@Tag(name = "Kafka Producer", description = "API para publicação de mensagens no Kafka para testes")
public class KafkaProducerController {

    private final KafkaMessageProducer kafkaMessageProducer;

    @PostMapping(value = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Envia uma mensagem para o Kafka",
            description = "Publica uma única mensagem no tópico Kafka configurado. Se o messageId não for fornecido, será gerado automaticamente."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Mensagem enviada com sucesso",
                    content = @Content(schema = @Schema(implementation = MessageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Requisição inválida"),
            @ApiResponse(responseCode = "500", description = "Erro interno do servidor")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Mensagem a ser enviada",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = MessageDto.class),
                    examples = @ExampleObject(
                            name = "Exemplo de mensagem",
                            value = """
                                    {
                                        "message_id": "550e8400-e29b-41d4-a716-446655440000",
                                        "event_type": "ORDER_CREATED",
                                        "payload": "{\\"orderId\\": \\"12345\\", \\"amount\\": 99.99}",
                                        "source": "api-test",
                                        "correlation_id": "corr-001"
                                    }
                                    """
                    )
            )
    )
    public ResponseEntity<MessageResponse> sendMessage(@Valid @RequestBody MessageDto message) {
        try {
            CompletableFuture<SendResult<String, MessageDto>> future = kafkaMessageProducer.send(message);
            SendResult<String, MessageDto> result = future.get(10, TimeUnit.SECONDS);

            return ResponseEntity.ok(MessageResponse.builder()
                    .messageId(message.getMessageId())
                    .status("SENT")
                    .topic(result.getRecordMetadata().topic())
                    .partition(result.getRecordMetadata().partition())
                    .offset(result.getRecordMetadata().offset())
                    .timestamp(LocalDateTime.now())
                    .build());

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Error sending message: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(MessageResponse.builder()
                            .messageId(message.getMessageId())
                            .status("FAILED")
                            .timestamp(LocalDateTime.now())
                            .errorMessage(e.getMessage())
                            .build());
        }
    }

    @PostMapping(value = "/messages/batch", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Envia múltiplas mensagens para o Kafka",
            description = "Publica várias mensagens em lote no tópico Kafka. Útil para testes de carga e performance."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lote processado",
                    content = @Content(schema = @Schema(implementation = BatchMessageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Requisição inválida"),
            @ApiResponse(responseCode = "500", description = "Erro interno do servidor")
    })
    public ResponseEntity<BatchMessageResponse> sendBatchMessages(@Valid @RequestBody BatchMessageRequest request) {
        LocalDateTime startTime = LocalDateTime.now();
        long startMs = System.currentTimeMillis();

        List<String> messageIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int sent = 0;
        int failed = 0;

        int count = Math.min(request.getCount(), 10000); // Limite máximo de 10k mensagens

        List<CompletableFuture<SendResult<String, MessageDto>>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String messageId = UUID.randomUUID().toString();
            MessageDto message = MessageDto.builder()
                    .messageId(messageId)
                    .eventType(request.getEventType())
                    .payload(request.getPayloadPrefix() + " #" + (i + 1))
                    .timestamp(LocalDateTime.now())
                    .source(request.getSource() != null ? request.getSource() : "batch-api")
                    .correlationId("batch-" + startMs)
                    .build();

            messageIds.add(messageId);
            futures.add(kafkaMessageProducer.send(message));
        }

        // Aguarda todas as mensagens serem enviadas
        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get(30, TimeUnit.SECONDS);
                sent++;
            } catch (Exception e) {
                failed++;
                errors.add("Message " + messageIds.get(i) + ": " + e.getMessage());
            }
        }

        long executionTimeMs = System.currentTimeMillis() - startMs;

        return ResponseEntity.ok(BatchMessageResponse.builder()
                .totalRequested(count)
                .totalSent(sent)
                .totalFailed(failed)
                .executionTimeMs(executionTimeMs)
                .startTime(startTime)
                .endTime(LocalDateTime.now())
                .messageIds(messageIds)
                .errors(errors.isEmpty() ? null : errors)
                .build());
    }

    @PostMapping(value = "/messages/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Gera e envia mensagens de teste automaticamente",
            description = "Gera mensagens de teste com dados aleatórios e envia para o Kafka. Útil para testes rápidos."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Mensagens geradas e enviadas"),
            @ApiResponse(responseCode = "400", description = "Parâmetros inválidos")
    })
    public ResponseEntity<BatchMessageResponse> generateAndSendMessages(
            @Parameter(description = "Quantidade de mensagens a gerar", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(10000) int count,

            @Parameter(description = "Tipo do evento", example = "TEST_EVENT")
            @RequestParam(defaultValue = "TEST_EVENT") String eventType) {

        BatchMessageRequest request = BatchMessageRequest.builder()
                .count(count)
                .eventType(eventType)
                .payloadPrefix("Auto-generated test payload")
                .source("auto-generator")
                .build();

        return sendBatchMessages(request);
    }

    @PostMapping(value = "/messages/duplicate-test", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Testa detecção de duplicatas",
            description = "Envia a mesma mensagem múltiplas vezes para testar o mecanismo de idempotência."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Teste de duplicata executado")
    })
    public ResponseEntity<BatchMessageResponse> testDuplicateDetection(
            @Parameter(description = "ID da mensagem (será repetido)", example = "test-duplicate-001")
            @RequestParam(defaultValue = "test-duplicate-001") String messageId,

            @Parameter(description = "Quantidade de vezes para enviar", example = "5")
            @RequestParam(defaultValue = "5") @Min(2) @Max(100) int times) {

        LocalDateTime startTime = LocalDateTime.now();
        long startMs = System.currentTimeMillis();

        List<String> messageIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int sent = 0;
        int failed = 0;

        for (int i = 0; i < times; i++) {
            MessageDto message = MessageDto.builder()
                    .messageId(messageId) // Mesmo ID para todas
                    .eventType("DUPLICATE_TEST")
                    .payload("Duplicate test payload - attempt " + (i + 1))
                    .timestamp(LocalDateTime.now())
                    .source("duplicate-test")
                    .correlationId("dup-test-" + startMs)
                    .build();

            try {
                kafkaMessageProducer.send(message).get(10, TimeUnit.SECONDS);
                sent++;
                messageIds.add(messageId + " (attempt " + (i + 1) + ")");
            } catch (Exception e) {
                failed++;
                errors.add("Attempt " + (i + 1) + ": " + e.getMessage());
            }
        }

        long executionTimeMs = System.currentTimeMillis() - startMs;

        return ResponseEntity.ok(BatchMessageResponse.builder()
                .totalRequested(times)
                .totalSent(sent)
                .totalFailed(failed)
                .executionTimeMs(executionTimeMs)
                .startTime(startTime)
                .endTime(LocalDateTime.now())
                .messageIds(messageIds)
                .errors(errors.isEmpty() ? null : errors)
                .build());
    }
}

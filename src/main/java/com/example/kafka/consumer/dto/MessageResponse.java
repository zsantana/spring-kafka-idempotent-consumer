package com.example.kafka.consumer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Resposta do envio de mensagem")
public class MessageResponse {

    @Schema(description = "ID único da mensagem", example = "550e8400-e29b-41d4-a716-446655440000")
    private String messageId;

    @Schema(description = "Status do envio", example = "SENT")
    private String status;

    @Schema(description = "Tópico para onde a mensagem foi enviada", example = "high-volume-topic")
    private String topic;

    @Schema(description = "Partição onde a mensagem foi armazenada", example = "3")
    private Integer partition;

    @Schema(description = "Offset da mensagem na partição", example = "12345")
    private Long offset;

    @Schema(description = "Timestamp do envio")
    private LocalDateTime timestamp;

    @Schema(description = "Mensagem de erro (se houver)")
    private String errorMessage;
}

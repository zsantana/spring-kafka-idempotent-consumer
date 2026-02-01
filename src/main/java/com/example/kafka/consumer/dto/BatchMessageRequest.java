package com.example.kafka.consumer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Requisição para envio de mensagem em lote")
public class BatchMessageRequest {

    @Schema(description = "Tipo do evento", example = "ORDER_CREATED", requiredMode = Schema.RequiredMode.REQUIRED)
    private String eventType;

    @Schema(description = "Prefixo do payload", example = "Test payload", requiredMode = Schema.RequiredMode.REQUIRED)
    private String payloadPrefix;

    @Schema(description = "Quantidade de mensagens a serem enviadas", example = "100", minimum = "1", maximum = "10000")
    private int count;

    @Schema(description = "Fonte da mensagem", example = "batch-test")
    private String source;
}

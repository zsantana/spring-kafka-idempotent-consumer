package com.example.kafka.consumer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Resposta do envio de mensagens em lote")
public class BatchMessageResponse {

    @Schema(description = "Total de mensagens requisitadas", example = "100")
    private int totalRequested;

    @Schema(description = "Total de mensagens enviadas com sucesso", example = "98")
    private int totalSent;

    @Schema(description = "Total de mensagens com falha", example = "2")
    private int totalFailed;

    @Schema(description = "Tempo de execução em milissegundos", example = "1500")
    private long executionTimeMs;

    @Schema(description = "Timestamp do início do envio")
    private LocalDateTime startTime;

    @Schema(description = "Timestamp do fim do envio")
    private LocalDateTime endTime;

    @Schema(description = "Lista de IDs das mensagens enviadas")
    private List<String> messageIds;

    @Schema(description = "Lista de erros (se houver)")
    private List<String> errors;
}

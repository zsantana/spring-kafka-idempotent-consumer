package com.example.kafka.consumer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDto {
    
    @NotBlank(message = "Message ID cannot be blank")
    @JsonProperty("message_id")
    private String messageId;
    
    @NotBlank(message = "Event type cannot be blank")
    @JsonProperty("event_type")
    private String eventType;
    
    @NotNull(message = "Payload cannot be null")
    @JsonProperty("payload")
    private String payload;
    
    @JsonProperty("timestamp")
    private LocalDateTime timestamp;
    
    @JsonProperty("source")
    private String source;
    
    @JsonProperty("correlation_id")
    private String correlationId;
}

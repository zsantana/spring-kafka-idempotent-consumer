-- Script SQL para criação da tabela com otimizações para alto volume

CREATE TABLE IF NOT EXISTS processed_messages (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT,
    source VARCHAR(100),
    correlation_id VARCHAR(255),
    message_timestamp TIMESTAMP,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    retry_count INTEGER DEFAULT 0,
    error_message TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_message_id 
ON processed_messages(message_id);

CREATE INDEX IF NOT EXISTS idx_event_type 
ON processed_messages(event_type);

CREATE INDEX IF NOT EXISTS idx_processed_at 
ON processed_messages(processed_at DESC);

CREATE INDEX IF NOT EXISTS idx_status_processed_at 
ON processed_messages(status, processed_at DESC);

CREATE INDEX IF NOT EXISTS idx_correlation_id 
ON processed_messages(correlation_id) 
WHERE correlation_id IS NOT NULL;

ANALYZE processed_messages;

COMMENT ON TABLE processed_messages IS 'Armazena mensagens processadas do Kafka para controle de idempotência';
COMMENT ON COLUMN processed_messages.message_id IS 'ID único da mensagem (chave de idempotência)';
COMMENT ON COLUMN processed_messages.status IS 'Status do processamento: SUCCESS, FAILED, DUPLICATE';

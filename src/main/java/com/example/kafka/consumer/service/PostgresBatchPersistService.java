package com.example.kafka.consumer.service;

import com.example.kafka.consumer.entity.ProcessedMessage;
import com.example.kafka.consumer.repository.ProcessedMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Serviço de persistência em batch com back pressure para PostgreSQL.
 * 
 * Utiliza uma fila limitada (LinkedBlockingQueue) + Semáforo para controlar
 * a taxa de entrada, evitando saturação do banco de dados.
 * 
 * As mensagens são acumuladas em um buffer e persistidas periodicamente
 * em batch via saveAll(), reduzindo significativamente o número de 
 * round-trips ao banco.
 */
@Service
@Slf4j
public class PostgresBatchPersistService {

    private final ProcessedMessageRepository repository;
    private final BlockingQueue<ProcessedMessage> buffer;
    private final Semaphore backPressureSemaphore;
    private final int batchSize;

    private final Counter batchPersistCounter;
    private final Counter batchErrorCounter;
    private final Counter backPressureRejectedCounter;

    public PostgresBatchPersistService(
            ProcessedMessageRepository repository,
            @Value("${app.performance.buffer-capacity:10000}") int bufferCapacity,
            @Value("${app.performance.batch-size:50}") int batchSize,
            @Value("${app.performance.max-concurrent-permits:5000}") int maxPermits,
            MeterRegistry meterRegistry) {

        this.repository = repository;
        this.buffer = new LinkedBlockingQueue<>(bufferCapacity);
        this.backPressureSemaphore = new Semaphore(maxPermits);
        this.batchSize = batchSize;

        // Métricas de observabilidade
        Gauge.builder("persist.buffer.size", buffer, BlockingQueue::size)
                .description("Current buffer size for DB persistence")
                .register(meterRegistry);

        Gauge.builder("persist.semaphore.available", backPressureSemaphore, Semaphore::availablePermits)
                .description("Available permits for back pressure control")
                .register(meterRegistry);

        this.batchPersistCounter = meterRegistry.counter("persist.batch.success");
        this.batchErrorCounter = meterRegistry.counter("persist.batch.error");
        this.backPressureRejectedCounter = meterRegistry.counter("persist.backpressure.rejected");

        log.info("PostgresBatchPersistService initialized: bufferCapacity={}, batchSize={}, maxPermits={}",
                bufferCapacity, batchSize, maxPermits);
    }

    /**
     * Enfileira mensagem para persistência com back pressure.
     * 
     * Dois níveis de proteção:
     * 1. Semáforo limita a taxa de entrada global
     * 2. LinkedBlockingQueue com capacidade limitada impede OOM
     * 
     * @param message mensagem a ser persistida
     * @return true se enfileirada com sucesso, false se back pressure ativo
     */
    public boolean enqueue(ProcessedMessage message) {
        try {
            // Semáforo: primeiro nível de back pressure
            if (!backPressureSemaphore.tryAcquire(2, TimeUnit.SECONDS)) {
                log.warn("Back pressure ativo: semáforo esgotado para message {}",
                        message.getMessageId());
                backPressureRejectedCounter.increment();
                return false;
            }

            // Fila limitada: segundo nível de back pressure
            boolean offered = buffer.offer(message, 1, TimeUnit.SECONDS);
            if (!offered) {
                backPressureSemaphore.release();
                log.warn("Buffer cheio, back pressure ativo para message {}",
                        message.getMessageId());
                backPressureRejectedCounter.increment();
                return false;
            }

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            backPressureSemaphore.release();
            log.error("Interrupted while enqueuing message {}", message.getMessageId(), e);
            return false;
        }
    }

    /**
     * Flush periódico: drena o buffer e persiste em batch.
     * 
     * Usa saveAll() para reduzir round-trips ao PostgreSQL.
     * Combinado com hibernate.jdbc.batch_size e order_inserts,
     * o Hibernate fará INSERT em batch real.
     */
    @Scheduled(fixedDelayString = "${app.performance.flush-interval-ms:500}")
    @Transactional
    public void flushBatch() {
        if (buffer.isEmpty()) {
            return;
        }

        List<ProcessedMessage> batch = new ArrayList<>(batchSize);
        int drained = buffer.drainTo(batch, batchSize);

        if (drained == 0) {
            return;
        }

        try {
            repository.saveAll(batch);
            batchPersistCounter.increment(drained);
            log.debug("Batch persisted: {} messages in PostgreSQL", drained);
        } catch (Exception e) {
            batchErrorCounter.increment(drained);
            log.error("Error persisting batch of {} messages. Re-enqueuing...", drained, e);

            // Re-enfileira as mensagens que falharam (best-effort)
            for (ProcessedMessage msg : batch) {
                if (!buffer.offer(msg)) {
                    log.error("Could not re-enqueue message {} after batch failure", msg.getMessageId());
                }
            }
        } finally {
            // Libera os permits para permitir novas mensagens
            backPressureSemaphore.release(drained);
        }
    }

    /**
     * Graceful shutdown: persiste todas as mensagens restantes no buffer.
     */
    @PreDestroy
    public void shutdown() {
        int remaining = buffer.size();
        if (remaining > 0) {
            log.info("Flushing remaining {} messages before shutdown...", remaining);
            while (!buffer.isEmpty()) {
                flushBatch();
            }
            log.info("All buffered messages flushed successfully");
        }
    }

    /**
     * Retorna o tamanho atual do buffer (para diagnóstico).
     */
    public int getBufferSize() {
        return buffer.size();
    }

    /**
     * Retorna os permits disponíveis no semáforo (para diagnóstico).
     */
    public int getAvailablePermits() {
        return backPressureSemaphore.availablePermits();
    }
}

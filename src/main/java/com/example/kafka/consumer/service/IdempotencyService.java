package com.example.kafka.consumer.service;

import com.example.kafka.consumer.entity.ProcessedMessage;
import com.example.kafka.consumer.repository.ProcessedMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class IdempotencyService {
    
    private static final String REDIS_KEY_PREFIX = "idempotency:";
    private static final String LOCK_KEY_PREFIX = "lock:idempotency:";
    
    private final RedisTemplate<String, String> redisTemplate;
    private final RedissonClient redissonClient;
    private final ProcessedMessageRepository repository;
    private final boolean redisFallbackEnabled;
    private final long redisTtlSeconds;
    
    private final Counter redisHitCounter;
    private final Counter redisMissCounter;
    private final Counter redisErrorCounter;
    private final Counter postgresHitCounter;
    private final Counter duplicateCounter;
    
    public IdempotencyService(
            RedisTemplate<String, String> redisTemplate,
            RedissonClient redissonClient,
            ProcessedMessageRepository repository,
            @Value("${app.performance.redis-fallback-enabled:true}") boolean redisFallbackEnabled,
            @Value("${app.idempotency.redis-ttl-seconds:86400}") long redisTtlSeconds,
            MeterRegistry meterRegistry) {
        
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.repository = repository;
        this.redisFallbackEnabled = redisFallbackEnabled;
        this.redisTtlSeconds = redisTtlSeconds;
        
        this.redisHitCounter = meterRegistry.counter("idempotency.redis.hit");
        this.redisMissCounter = meterRegistry.counter("idempotency.redis.miss");
        this.redisErrorCounter = meterRegistry.counter("idempotency.redis.error");
        this.postgresHitCounter = meterRegistry.counter("idempotency.postgres.hit");
        this.duplicateCounter = meterRegistry.counter("idempotency.duplicate.detected");
    }
    
    public boolean isAlreadyProcessed(String messageId) {
        String redisKey = REDIS_KEY_PREFIX + messageId;
        
        try {
            Boolean exists = redisTemplate.hasKey(redisKey);
            
            if (Boolean.TRUE.equals(exists)) {
                log.debug("Message {} already processed (Redis hit)", messageId);
                redisHitCounter.increment();
                duplicateCounter.increment();
                return true;
            }
            
            redisMissCounter.increment();
            
        } catch (Exception e) {
            log.warn("Redis error checking message {}: {}. Falling back to PostgreSQL", 
                    messageId, e.getMessage());
            redisErrorCounter.increment();
            
            if (redisFallbackEnabled) {
                return checkInPostgres(messageId);
            }
        }
        
        if (redisFallbackEnabled) {
            return checkInPostgres(messageId);
        }
        
        return false;
    }
    
    public boolean markAsProcessed(String messageId, ProcessedMessage message) {
        String lockKey = LOCK_KEY_PREFIX + messageId;
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                try {
                    if (isAlreadyProcessed(messageId)) {
                        log.debug("Message {} was processed by another thread", messageId);
                        return false;
                    }
                    
                    markInRedis(messageId);
                    
                    if (redisFallbackEnabled) {
                        persistInPostgres(message);
                    }
                    
                    log.debug("Message {} marked as processed", messageId);
                    return true;
                    
                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("Could not acquire lock for message {} within timeout", messageId);
                return false;
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while trying to acquire lock for message {}", messageId, e);
            return false;
        } catch (Exception e) {
            log.error("Error marking message {} as processed", messageId, e);
            return false;
        }
    }
    
    private void markInRedis(String messageId) {
        try {
            String redisKey = REDIS_KEY_PREFIX + messageId;
            redisTemplate.opsForValue().set(
                redisKey, 
                String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(redisTtlSeconds)
            );
            log.debug("Message {} marked in Redis with TTL {}s", messageId, redisTtlSeconds);
        } catch (Exception e) {
            log.error("Error marking message {} in Redis", messageId, e);
            redisErrorCounter.increment();
            throw e;
        }
    }
    
    private boolean checkInPostgres(String messageId) {
        try {
            boolean exists = repository.existsByMessageId(messageId);
            if (exists) {
                log.debug("Message {} already processed (PostgreSQL hit)", messageId);
                postgresHitCounter.increment();
                duplicateCounter.increment();
            }
            return exists;
        } catch (Exception e) {
            log.error("Error checking message {} in PostgreSQL", messageId, e);
            return false;
        }
    }
    
    @Transactional
    private void persistInPostgres(ProcessedMessage message) {
        try {
            repository.save(message);
            log.debug("Message {} persisted in PostgreSQL", message.getMessageId());
        } catch (Exception e) {
            log.error("Error persisting message {} in PostgreSQL", message.getMessageId(), e);
        }
    }
    
    public void syncToRedis(String messageId) {
        try {
            if (repository.existsByMessageId(messageId)) {
                markInRedis(messageId);
                log.info("Message {} synced from PostgreSQL to Redis", messageId);
            }
        } catch (Exception e) {
            log.error("Error syncing message {} to Redis", messageId, e);
        }
    }
}

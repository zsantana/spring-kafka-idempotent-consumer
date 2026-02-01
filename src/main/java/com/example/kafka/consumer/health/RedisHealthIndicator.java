package com.example.kafka.consumer.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component("redisHealth")
@Slf4j
public class RedisHealthIndicator implements HealthIndicator {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    public RedisHealthIndicator(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @Override
    public Health health() {
        try {
            String pong = redisTemplate.getConnectionFactory()
                .getConnection()
                .ping();
            
            if ("PONG".equalsIgnoreCase(pong)) {
                return Health.up()
                    .withDetail("status", "Redis is available")
                    .withDetail("fallback_mode", "disabled")
                    .build();
            } else {
                return Health.down()
                    .withDetail("status", "Redis ping failed")
                    .withDetail("fallback_mode", "enabled - using PostgreSQL")
                    .build();
            }
            
        } catch (Exception e) {
            log.warn("Redis health check failed", e);
            return Health.down()
                .withDetail("status", "Redis is unavailable")
                .withDetail("fallback_mode", "enabled - using PostgreSQL")
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}

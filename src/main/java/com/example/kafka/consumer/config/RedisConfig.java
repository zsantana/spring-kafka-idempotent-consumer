package com.example.kafka.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class RedisConfig {
    
    @Value("${spring.data.redis.host:redis}")
    private String redisHost;
    
    @Value("${spring.data.redis.port:6379}")
    private int redisPort;
    
    @Value("${spring.data.redis.password:#{null}}")
    private String redisPassword;
    
    @Value("${spring.data.redis.redisson.connection-pool-size:100}")
    private int connectionPoolSize;
    
    @Value("${spring.data.redis.redisson.connection-minimum-idle-size:24}")
    private int connectionMinimumIdleSize;
    
    @Value("${spring.data.redis.redisson.idle-connection-timeout:10000}")
    private int idleConnectionTimeout;
    
    @Value("${spring.data.redis.redisson.timeout:3000}")
    private int timeout;
    
    @Value("${spring.data.redis.redisson.retry-attempts:3}")
    private int retryAttempts;
    
    @Value("${spring.data.redis.redisson.retry-interval:1500}")
    private int retryInterval;
    
    @Value("${spring.data.redis.redisson.threads:16}")
    private int threads;
    
    @Value("${spring.data.redis.redisson.netty-threads:32}")
    private int nettyThreads;
    
    @Bean
    public RedissonClient redissonClient(ObjectMapper objectMapper) {
        Config config = new Config();
        
        String address = String.format("redis://%s:%d", redisHost, redisPort);
        
        config.useSingleServer()
            .setAddress(address)
            .setPassword(redisPassword != null && !redisPassword.isEmpty() ? redisPassword : null)
            .setConnectionPoolSize(connectionPoolSize)
            .setConnectionMinimumIdleSize(connectionMinimumIdleSize)
            .setIdleConnectionTimeout(idleConnectionTimeout)
            .setTimeout(timeout)
            .setRetryAttempts(retryAttempts)
            .setRetryInterval(retryInterval)
            .setKeepAlive(true)
            .setTcpNoDelay(true);
        
        config.setCodec(new JsonJacksonCodec(objectMapper));
        config.setThreads(threads);
        config.setNettyThreads(nettyThreads);
        
        log.info("Redisson configured for Redis at {}:{}", redisHost, redisPort);
        
        return Redisson.create(config);
    }
    
    /**
     * Registra métricas do Redisson no Micrometer
     */
    @Bean
    public MeterBinder redissonMetrics(RedissonClient redissonClient) {
        return registry -> {
            // Connection pool metrics
            registry.gauge("redisson.pool.active.connections", redissonClient, 
                client -> client.getConfig().useSingleServer() != null ? 
                    client.getConfig().useSingleServer().getConnectionPoolSize() : 0);
            
            registry.gauge("redisson.pool.total.connections", redissonClient,
                client -> connectionPoolSize);
            
            log.info("Redisson metrics registered with Micrometer");
        };
    }
    
    @Bean
    public RedisTemplate<String, String> redisTemplate(
            RedisConnectionFactory connectionFactory) {
        
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        
        template.afterPropertiesSet();
        
        log.info("RedisTemplate configured");
        
        return template;
    }
    
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        
        log.info("LettuceConnectionFactory configured for {}:{}", redisHost, redisPort);
        
        return new LettuceConnectionFactory(config);
    }
}

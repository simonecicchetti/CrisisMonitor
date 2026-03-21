package com.crisismonitor.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Value("${REDIS_HOST:}")
    private String redisHost;

    /**
     * Graceful Redis failure handling: when Redis is down, methods execute normally
     * (without caching) instead of throwing exceptions.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.debug("Cache GET failed for cache={}, key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.debug("Cache PUT failed for cache={}, key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.debug("Cache EVICT failed for cache={}, key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.debug("Cache CLEAR failed for cache={}: {}", cache.getName(), e.getMessage());
            }
        };
    }

    /**
     * RedisTemplate for direct Redis operations (e.g., Claude situations cache).
     * Only created when Redis is available.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
            objectMapper.getPolymorphicTypeValidator(),
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();

        return template;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // If no Redis host is configured, use in-memory cache (ConcurrentHashMap)
        // This avoids hundreds of "Unable to connect to Redis" errors on startup
        // and makes the warmup 10x faster since @Cacheable actually caches in-process.
        if (redisHost == null || redisHost.isBlank()) {
            log.info("No REDIS_HOST configured — using in-memory cache (ConcurrentMapCacheManager)");
            return new ConcurrentMapCacheManager();
        }

        // Test Redis connection before creating RedisCacheManager
        try {
            connectionFactory.getConnection().ping();
            log.info("Redis connection successful — using RedisCacheManager");
        } catch (Exception e) {
            log.warn("Redis connection failed ({}), falling back to in-memory cache", e.getMessage());
            return new ConcurrentMapCacheManager();
        }

        // Configure ObjectMapper with Java 8 date/time support
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
            objectMapper.getPolymorphicTypeValidator(),
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        // Default cache configuration: 30 minutes TTL
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        // Custom TTLs for different cache types
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

        RedisCacheConfiguration longTtlConfig = defaultConfig.entryTtl(Duration.ofHours(2));
        cacheConfigs.put("allRiskScores", longTtlConfig);
        cacheConfigs.put("riskScore", longTtlConfig);
        cacheConfigs.put("allPrecipAnomalies", longTtlConfig);
        cacheConfigs.put("precipAnomaly", longTtlConfig);
        cacheConfigs.put("allCurrencyData", longTtlConfig);
        cacheConfigs.put("currencyData", longTtlConfig);
        cacheConfigs.put("exchangeRates", longTtlConfig);

        RedisCacheConfiguration gdeltConfig = defaultConfig.entryTtl(Duration.ofHours(4));
        cacheConfigs.put("gdeltConflictCount", gdeltConfig);
        cacheConfigs.put("gdeltSpikeIndex", gdeltConfig);
        cacheConfigs.put("gdeltTone", gdeltConfig);
        cacheConfigs.put("gdeltAllSpikes", gdeltConfig);
        cacheConfigs.put("gdeltGeoEvents", gdeltConfig);
        cacheConfigs.put("gdeltCrisisVolume", gdeltConfig);
        cacheConfigs.put("gdeltHeadlines", gdeltConfig);
        cacheConfigs.put("gdeltHeadlinesWithUrl", gdeltConfig);

        RedisCacheConfiguration storiesConfig = defaultConfig.entryTtl(Duration.ofMinutes(10));
        for (int i = 6; i <= 16; i++) cacheConfigs.put("storiesV" + i, storiesConfig);

        RedisCacheConfiguration fewsConfig = defaultConfig.entryTtl(Duration.ofHours(1));
        cacheConfigs.put("fewsIPC", fewsConfig);
        cacheConfigs.put("fewsAllIPC", fewsConfig);

        RedisCacheConfiguration aiConfig = defaultConfig.entryTtl(Duration.ofHours(4));
        cacheConfigs.put("aiAnalysisGlobal", aiConfig);
        cacheConfigs.put("aiAnalysisCountry", aiConfig);
        cacheConfigs.put("aiAnalysisRegion", aiConfig);
        cacheConfigs.put("aiAnalysisRegionV2", aiConfig);
        cacheConfigs.put("claudeSituations", aiConfig);

        RedisCacheConfiguration oneHourConfig = defaultConfig.entryTtl(Duration.ofHours(1));
        cacheConfigs.put("topicIntelligence", defaultConfig);
        cacheConfigs.put("intelligenceFeed", oneHourConfig);
        cacheConfigs.put("dailyBriefing", oneHourConfig);
        cacheConfigs.put("activeSituations", oneHourConfig);
        cacheConfigs.put("activeSituationsV2", oneHourConfig);
        cacheConfigs.put("gdeltRegionHeadlines", defaultConfig);
        cacheConfigs.put("countryProfileAggregated", oneHourConfig);
        cacheConfigs.put("countryDataPack", oneHourConfig);
        cacheConfigs.put("regionDetail", oneHourConfig);
        cacheConfigs.put("overviewContext", oneHourConfig);
        cacheConfigs.put("regionContext", oneHourConfig);
        cacheConfigs.put("regionalPulseV2", oneHourConfig);
        cacheConfigs.put("reliefwebDisasters", oneHourConfig);
        cacheConfigs.put("reliefwebHeadlines", oneHourConfig);
        cacheConfigs.put("reliefwebReports", oneHourConfig);
        cacheConfigs.put("rssGlobal", defaultConfig);
        cacheConfigs.put("rssHeadlines", defaultConfig);
        cacheConfigs.put("whoDiseaseOutbreaks", longTtlConfig);

        RedisCacheConfiguration newsFeedConfig = defaultConfig.entryTtl(Duration.ofMinutes(15));
        cacheConfigs.put("newsFeed", newsFeedConfig);

        // DTM (IOM displacement data) — changes monthly
        cacheConfigs.put("dtmCountryData", longTtlConfig);
        cacheConfigs.put("dtmByReason", longTtlConfig);
        cacheConfigs.put("dtmOperations", longTtlConfig);

        // HungerMap — refreshed every 30min by default, explicit for clarity
        cacheConfigs.put("countries", oneHourConfig);
        cacheConfigs.put("ipcData", oneHourConfig);
        cacheConfigs.put("severityData", oneHourConfig);
        cacheConfigs.put("alerts", oneHourConfig);
        cacheConfigs.put("foodSecurityMetrics", oneHourConfig);

        // WorldBank — static data, long TTL
        cacheConfigs.put("countryProfiles", longTtlConfig);
        cacheConfigs.put("inflationData", longTtlConfig);
        cacheConfigs.put("gdpData", longTtlConfig);
        cacheConfigs.put("populationData", longTtlConfig);
        cacheConfigs.put("povertyData", longTtlConfig);

        // UNHCR — updated infrequently
        cacheConfigs.put("unhcrPopulation", longTtlConfig);
        cacheConfigs.put("unhcrGlobalSummary", longTtlConfig);
        cacheConfigs.put("unhcrAsylum", longTtlConfig);
        cacheConfigs.put("unhcrDemographics", longTtlConfig);
        cacheConfigs.put("unhcrSolutions", longTtlConfig);

        // Nowcast — refreshed every hour, model predictions don't change rapidly
        cacheConfigs.put("nowcast", oneHourConfig);

        // FAO Food Price Index — monthly data, cache for 24 hours
        cacheConfigs.put("faoFoodPriceIndex", longTtlConfig);
        cacheConfigs.put("worldBankSFI", longTtlConfig);

        // Topic reports — expensive to generate (Claude API + multiple data sources)
        // Cache for 2 hours, first user generates, all others read from cache
        cacheConfigs.put("topicReport", longTtlConfig);

        // Other services
        cacheConfigs.put("hazards", oneHourConfig);
        cacheConfigs.put("migrationData", longTtlConfig);
        cacheConfigs.put("climateData", longTtlConfig);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}

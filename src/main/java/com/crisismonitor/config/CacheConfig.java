package com.crisismonitor.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
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

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * RedisTemplate for direct Redis operations (e.g., Claude situations cache).
     * Uses JSON serialization for values and String serialization for keys.
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

        // Expensive operations: 2 hour TTL (persists across restarts!)
        RedisCacheConfiguration longTtlConfig = defaultConfig.entryTtl(Duration.ofHours(2));
        cacheConfigs.put("allRiskScores", longTtlConfig);
        cacheConfigs.put("riskScore", longTtlConfig);
        cacheConfigs.put("allPrecipAnomalies", longTtlConfig);
        cacheConfigs.put("precipAnomaly", longTtlConfig);
        cacheConfigs.put("allCurrencyData", longTtlConfig);
        cacheConfigs.put("currencyData", longTtlConfig);
        cacheConfigs.put("exchangeRates", longTtlConfig);

        // GDELT: 4 hour TTL (rate limited, slow to fetch - keep cached longer)
        RedisCacheConfiguration gdeltConfig = defaultConfig.entryTtl(Duration.ofHours(4));
        cacheConfigs.put("gdeltConflictCount", gdeltConfig);
        cacheConfigs.put("gdeltSpikeIndex", gdeltConfig);
        cacheConfigs.put("gdeltTone", gdeltConfig);
        cacheConfigs.put("gdeltAllSpikes", gdeltConfig);
        cacheConfigs.put("gdeltGeoEvents", gdeltConfig);
        cacheConfigs.put("gdeltCrisisVolume", gdeltConfig);
        cacheConfigs.put("gdeltHeadlines", gdeltConfig);
        cacheConfigs.put("gdeltHeadlinesWithUrl", gdeltConfig);

        // Stories/News Feed: 10 min TTL (aggregates GDELT + ReliefWeb + RSS headlines)
        RedisCacheConfiguration storiesConfig = defaultConfig.entryTtl(Duration.ofMinutes(10));
        cacheConfigs.put("storiesV6", storiesConfig);
        cacheConfigs.put("storiesV7", storiesConfig);
        cacheConfigs.put("storiesV8", storiesConfig);
        cacheConfigs.put("storiesV9", storiesConfig);
        cacheConfigs.put("storiesV10", storiesConfig);
        cacheConfigs.put("storiesV11", storiesConfig);
        cacheConfigs.put("storiesV12", storiesConfig);
        cacheConfigs.put("storiesV13", storiesConfig);
        cacheConfigs.put("storiesV14", storiesConfig);
        cacheConfigs.put("storiesV15", storiesConfig);
        cacheConfigs.put("storiesV16", storiesConfig);

        // Food security: 1 hour TTL
        RedisCacheConfiguration fewsConfig = defaultConfig.entryTtl(Duration.ofHours(1));
        cacheConfigs.put("fewsIPC", fewsConfig);
        cacheConfigs.put("fewsAllIPC", fewsConfig);

        // AI Analysis: 4 hour TTL (expensive Claude API calls)
        RedisCacheConfiguration aiConfig = defaultConfig.entryTtl(Duration.ofHours(4));
        cacheConfigs.put("aiAnalysisGlobal", aiConfig);
        cacheConfigs.put("aiAnalysisCountry", aiConfig);
        cacheConfigs.put("aiAnalysisRegion", aiConfig);
        cacheConfigs.put("aiAnalysisCustom", aiConfig);

        // Topic Intelligence: 30 min TTL (aggregates GDELT + ReliefWeb)
        cacheConfigs.put("topicIntelligence", defaultConfig);

        // Intelligence Feed: 1 hour TTL (pre-aggregated feed data)
        RedisCacheConfiguration oneHourConfig = defaultConfig.entryTtl(Duration.ofHours(1));
        cacheConfigs.put("intelligenceFeed", oneHourConfig);

        // Daily Briefing: 1 hour TTL (news aggregation)
        cacheConfigs.put("dailyBriefing", oneHourConfig);

        // Active Situations: 1 hour TTL (situation detection)
        cacheConfigs.put("activeSituations", oneHourConfig);
        cacheConfigs.put("activeSituationsV2", oneHourConfig);

        // Claude Situation Detection: 4 hour TTL (expensive Claude API call, persists across restarts)
        RedisCacheConfiguration claudeSituationsConfig = defaultConfig.entryTtl(Duration.ofHours(4));
        cacheConfigs.put("claudeSituations", claudeSituationsConfig);

        // GDELT Regional Headlines: 30 min TTL (regional OR queries with URLs)
        cacheConfigs.put("gdeltRegionHeadlines", defaultConfig);

        // News Feed (two-column): 15 min TTL
        RedisCacheConfiguration newsFeedConfig = defaultConfig.entryTtl(Duration.ofMinutes(15));
        cacheConfigs.put("newsFeed", newsFeedConfig);

        // Country Profile (aggregated): 1 hour TTL (combines multiple data sources)
        cacheConfigs.put("countryProfileAggregated", oneHourConfig);

        // Region Detail drill-down: 1 hour TTL
        cacheConfigs.put("regionDetail", oneHourConfig);

        // AI Analysis v2: 4 hour TTL
        cacheConfigs.put("aiAnalysisRegionV2", aiConfig);

        // Overview/Region context: 1 hour TTL
        cacheConfigs.put("overviewContext", oneHourConfig);
        cacheConfigs.put("regionContext", oneHourConfig);

        // Regional Pulse: 1 hour TTL
        cacheConfigs.put("regionalPulseV2", oneHourConfig);

        // ReliefWeb: 1 hour TTL
        cacheConfigs.put("reliefwebDisasters", oneHourConfig);
        cacheConfigs.put("reliefwebHeadlines", oneHourConfig);
        cacheConfigs.put("reliefwebReports", oneHourConfig);

        // RSS: 30 min TTL
        cacheConfigs.put("rssGlobal", defaultConfig);
        cacheConfigs.put("rssHeadlines", defaultConfig);

        // WHO Disease Outbreak News: 2 hour TTL (updates infrequently)
        cacheConfigs.put("whoDiseaseOutbreaks", longTtlConfig);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}

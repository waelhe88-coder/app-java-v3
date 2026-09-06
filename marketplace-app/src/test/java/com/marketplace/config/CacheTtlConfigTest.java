package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the cache-entry TTL layer: the expiration the Redis cache
 * entries carry is declared under the property name Spring Boot 4.1.1
 * actually binds.
 *
 * <p>Root cause this guards: the application declared 13 named Redis caches
 * but no entry expiration at all. The official default is explicit —
 * {@code spring-configuration-metadata.json} of {@code spring-boot-cache}
 * 4.1.1: {@code spring.cache.redis.time-to-live — "Entry expiration. By
 * default the entries never expire."} The byte-verified binding path in that
 * jar is {@code CacheProperties$Redis.timeToLive -> RedisCacheConfiguration
 * -> defaultCacheConfig().entryTtl(...)}, so an absent key means every
 * cache entry lives forever: invalidation only happens via
 * {@code CacheInvalidationRelay} (AFTER_COMMIT), which fires solely when an
 * entity <i>changes</i> — dead rows never do. Growth is unbounded by
 * construction (query-text keys on catalog-search/search-results, entity-id
 * keys on bookings/conversations/paymentIntents) against a Redis with no
 * maxmemory/eviction. Spring does not reject unknown properties under this
 * prefix, so only a pinned test keeps the key honest — the same class of
 * latent "config that lies" defect as {@code EventStalenessProdConfigTest}.
 *
 * <p>The key lives in the base {@code application.yml} (not prod-only): dev
 * and prod inherit the same Redis behavior, while the test profile overrides
 * {@code spring.cache.type} to {@code simple} so the key is inert there.
 * End-to-end proof that the TTL actually reaches Redis
 * (SET .. EX, TTL &gt; 0 on a live key) lives in
 * {@code CacheRedisTtlIntegrationTest}.
 */
class CacheTtlConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void baseConfigBoundsCacheEntryLifetime() throws Exception {
        // Official name (spring-configuration-metadata.json, spring-boot-cache
        // 4.1.1): spring.cache.redis.time-to-live. Default = never expire.
        assertThat(property("application.yml", "spring.cache.redis.time-to-live"))
                .as("cache entries must carry a TTL — the framework default is 'never expire'")
                .isEqualTo("1h");
    }

    @Test
    void baseConfigDeclaresAllNamedCaches() throws Exception {
        // The 13 named caches this TTL governs. A missing name would silently
        // create caches on demand (default RedisCacheManager behavior) — the
        // list is the contract between yml and the @Cacheable annotations.
        String names = property("application.yml", "spring.cache.cache-names");
        assertThat(names).isNotNull();
        java.util.List<String> declared = java.util.Arrays.stream(names.split(","))
                .map(String::trim)
                .toList();
        assertThat(declared)
                .containsExactlyInAnyOrder(
                        "catalog-active-v2", "catalog-by-category-v2", "catalog-search-v2",
                        "pricing-calculations", "search-results-v2", "availability",
                        "bookings", "users", "userSubjects", "conversations",
                        "paymentIntents", "reviews", "providers");
    }

    @Test
    void noDeadTtlKeysAnywhere() throws Exception {
        // Pre-fix (and commonly mistyped) key spellings that bind to NOTHING
        // in Spring Boot 4.1.1 — their presence would be a silent no-op.
        // The complete spring.cache.* surface of spring-boot-cache 4.1.1 is:
        // cache-names, caffeine.spec, couchbase.expiration, infinispan.config,
        // jcache.config, jcache.provider, redis.cache-null-values,
        // redis.enable-statistics, redis.key-prefix, redis.time-to-live,
        // redis.use-key-prefix, type.
        for (String yml : List.of("application.yml", "application-dev.yml",
                "application-prod.yml", "application-test.yml")) {
            assertThat(property(yml, "spring.cache.redis-ttl"))
                    .as("spring.cache.redis-ttl is not a Boot 4.1.1 property (%s)", yml)
                    .isNull();
            assertThat(property(yml, "spring.cache.time-to-live"))
                    .as("spring.cache.time-to-live is not a Boot 4.1.1 property (%s)", yml)
                    .isNull();
            assertThat(property(yml, "spring.data.redis.cache.time-to-live"))
                    .as("spring.data.redis.cache.time-to-live is not a Boot 4.1.1 property (%s)", yml)
                    .isNull();
        }
    }

    private String property(String yml, String key) throws java.io.IOException {
        List<PropertySource<?>> sources = loader.load(yml, new ClassPathResource(yml));
        assertThat(sources).as("%s must load", yml).isNotEmpty();
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}

package com.marketplace.shared.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Health of the Spring Modulith event publication bus: reports {@code DOWN} when any
 * {@code event_publication} row has been incomplete for longer than the staleness
 * threshold (21600s = 6h, mirroring the prod configuration
 * {@code spring.modulith.events.staleness.published}).
 *
 * <p>The same query result is mirrored into the {@code marketplace.eventbus.stale}
 * gauge (Micrometer) so the condition is consumable through the metrics pipeline and
 * the alert rules committed under {@code monitoring/prometheus-rules/} (guarded by
 * {@code AlertRulesYamlTest} in marketplace-app). Gauge freshness is driven by a
 * self-scheduled probe (see {@link #refreshStalePublicationsGauge()}) because in
 * production no HTTP probe exercises this indicator: liveness is {@code ping}-only
 * and readiness is {@code db,redis,diskSpace} (both the Railway healthcheck and the
 * watchdog probe liveness/readiness only; full {@code /actuator/health} is
 * deliberately not probed). On query failure the gauge keeps its last observed
 * value while this indicator reports {@code DOWN} with the exception.
 *
 * <p>The {@link MeterRegistry} is injected via {@link ObjectProvider} and is strictly
 * optional: contexts that do not expose one (module test slices) keep the indicator
 * fully functional without the gauge.
 */
@Component
public class ModulithEventBusHealthIndicator extends AbstractHealthIndicator {

    private static final long STALE_THRESHOLD_SECONDS = 21600;

    /** Mirrored by alert rule {@code MarketplaceEventBusStale}. */
    static final String STALE_PUBLICATIONS_METRIC = "marketplace.eventbus.stale";

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong stalePublications = new AtomicLong(0);

    public ModulithEventBusHealthIndicator(JdbcTemplate jdbcTemplate,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        meterRegistry.stream().findFirst().ifPresent(registry -> Gauge
                .builder(STALE_PUBLICATIONS_METRIC, stalePublications, AtomicLong::doubleValue)
                .description("Event publications incomplete for longer than the staleness threshold (6h)")
                .register(registry));
    }

    /**
     * Keeps {@code marketplace.eventbus.stale} alive without any HTTP probe (see class
     * javadoc for why probes cannot be relied on). 60s keeps several gauge samples
     * inside the 5m {@code for:} window of {@code MarketplaceEventBusStale}; the
     * pattern matches {@code EventPublicationResubmission}'s scheduling style.
     * Scheduling is active via {@code CacheConfig}'s {@code @EnableScheduling}.
     */
    @Scheduled(fixedDelay = 60, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    void refreshStalePublicationsGauge() {
        health();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            Integer staleCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL AND EXTRACT(EPOCH FROM (NOW() - publication_date)) > ?",
                    Integer.class,
                    STALE_THRESHOLD_SECONDS);
            stalePublications.set(staleCount == null ? 0L : staleCount);
            if (staleCount != null && staleCount > 0) {
                builder.withDetail("staleEventCount", staleCount)
                       .withDetail("thresholdSeconds", STALE_THRESHOLD_SECONDS)
                       .down();
            } else {
                builder.up();
            }
        } catch (Exception e) {
            builder.down(e);
        }
    }
}

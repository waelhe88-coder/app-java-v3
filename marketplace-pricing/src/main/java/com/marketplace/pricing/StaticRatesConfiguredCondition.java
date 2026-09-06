package com.marketplace.pricing;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers the static-rates exchange bean only when at least one rate is
 * bound ({@code marketplace.pricing.currency.exchange.rates.<CODE>} — YAML map
 * or relaxed-binding env var). This is the framework's own conditional SPI —
 * the map-valued equivalent of {@code @ConditionalOnProperty}, the same shape
 * as the payments module's {@code StripeChannelConfiguredCondition}.
 *
 * <p>Detection goes through Spring Boot's {@link Binder} — the same binding
 * path {@code CurrencyExchangeProperties} uses — so every relaxed-binding
 * source shape counts: YAML maps, {@code MARKETPLACE_PRICING_CURRENCY_
 * EXCHANGE_RATES_<CODE>} env vars (the production shape on Railway), and
 * command-line properties. The previous property-name scan lowercased raw
 * property-source names and could never match the env-var spelling
 * (underscores, no dots) — the channel stayed dormant even with rates bound
 * (CodeRabbit #241).</p>
 *
 * <p>The no-rates state is the documented inert default: no exchange beans
 * exist, {@code PricingService.convert} keeps the 503 SU-001 dormant answer,
 * and nothing else changes.</p>
 */
class StaticRatesConfiguredCondition implements Condition {

    private static final String RATES_KEY_PREFIX = "marketplace.pricing.currency.exchange.rates";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (!(context.getEnvironment() instanceof ConfigurableEnvironment environment)) {
            return false;
        }
        var bound = Binder.get(environment)
                .bind(RATES_KEY_PREFIX, Bindable.mapOf(String.class, java.math.BigDecimal.class));
        return bound.isBound() && !bound.get().isEmpty();
    }
}

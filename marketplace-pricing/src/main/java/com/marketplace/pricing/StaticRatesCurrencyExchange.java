package com.marketplace.pricing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Configuration-driven ISO 4217 conversion: every rate is an explicit
 * deployment binding ({@code marketplace.pricing.currency.exchange.rates}),
 * so the channel is reproducible and auditable by config alone — no
 * external call, no hidden state. Conversion path is always
 * {@code from -> base -> to} through the single configured base currency.
 *
 * <p>Minor-unit semantics follow the ISO 4217 model: amounts arrive in the
 * source currency's minor units, are converted in major units, and are
 * scaled to the target currency's {@link Currency#getDefaultFractionDigits()}
 * (e.g. JPY has 0). Rounding is HALF_UP, the same convention the pricing
 * module already applies to tax and discount cents.</p>
 */
class StaticRatesCurrencyExchange implements CurrencyExchangePort {

    static final String RATE_SOURCE = "static-config";

    private static final MathContext RATE_MATH = new MathContext(12);

    private final Currency base;
    private final java.util.Map<String, BigDecimal> rates;

    StaticRatesCurrencyExchange(CurrencyExchangeProperties properties) {
        String baseCode = properties.baseCurrency() == null || properties.baseCurrency().isBlank()
                ? com.marketplace.shared.api.Currencies.DEFAULT_CODE
                : properties.baseCurrency().strip().toUpperCase(java.util.Locale.ROOT);
        this.base = Currency.getInstance(baseCode);
        this.rates = properties.rates();
    }

    @Override
    public ExchangeQuote convert(long amountMinorUnits, Currency from, Currency to) {
        requireRate(from);
        requireRate(to);

        if (from.equals(to)) {
            return new ExchangeQuote(amountMinorUnits, from.getCurrencyCode(),
                    amountMinorUnits, to.getCurrencyCode(), BigDecimal.ONE, RATE_SOURCE);
        }

        // source minor -> major -> base -> target major -> target minor
        BigDecimal sourceMajor = BigDecimal.valueOf(amountMinorUnits)
                .movePointLeft(fractionDigits(from));
        BigDecimal inBase = sourceMajor.multiply(rateOf(from), RATE_MATH);
        BigDecimal targetMajor = inBase.divide(rateOf(to), RATE_MATH);
        long targetMinor = targetMajor.movePointRight(fractionDigits(to))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        // The quoted rate must be the rate actually applied to the amount:
        // source -> base -> target means rateOf(from) / rateOf(to) — the same
        // ratio targetMajor applies above. Returning 1/rateOf(to) reported a
        // base-to-target rate for every non-base source (CodeRabbit #241).
        BigDecimal effectiveRate = rateOf(from).divide(rateOf(to), RATE_MATH);
        return new ExchangeQuote(amountMinorUnits, from.getCurrencyCode(),
                targetMinor, to.getCurrencyCode(), effectiveRate, RATE_SOURCE);
    }

    private void requireRate(Currency currency) {
        if (currency.equals(base)) {
            return;
        }
        if (!rates.containsKey(currency.getCurrencyCode())) {
            throw CurrencyExchangeUnavailableException.missingRate(
                    base.getCurrencyCode(), currency.getCurrencyCode());
        }
        if (rates.get(currency.getCurrencyCode()).signum() <= 0) {
            throw new CurrencyExchangeUnavailableException(
                    "Configured exchange rate for " + currency.getCurrencyCode()
                            + " must be positive (units of " + base.getCurrencyCode()
                            + " per 1 " + currency.getCurrencyCode() + ").");
        }
    }

    private BigDecimal rateOf(Currency currency) {
        return currency.equals(base) ? BigDecimal.ONE : rates.get(currency.getCurrencyCode());
    }

    private static int fractionDigits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        return digits < 0 ? 0 : digits; // -1 marks no minor unit (e.g. XAU)
    }
}

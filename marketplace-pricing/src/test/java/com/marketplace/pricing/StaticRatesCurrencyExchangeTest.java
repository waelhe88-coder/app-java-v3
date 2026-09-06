package com.marketplace.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticRatesCurrencyExchangeTest {

    // Base SAR; 1 USD = 3.75 SAR; 1 EUR = 4.05 SAR; 1 JPY = 0.025 SAR
    private final CurrencyExchangeProperties properties = new CurrencyExchangeProperties(
            "SAR", Map.of(
                    "USD", new BigDecimal("3.75"),
                    "EUR", new BigDecimal("4.05"),
                    "JPY", new BigDecimal("0.025")));

    private final StaticRatesCurrencyExchange exchange = new StaticRatesCurrencyExchange(properties);

    @Test
    void sameCurrency_isIdentityConversion() {
        var quote = exchange.convert(10_000L, Currency.getInstance("SAR"), Currency.getInstance("SAR"));

        assertThat(quote.targetMinorUnits()).isEqualTo(10_000L);
        assertThat(quote.rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(quote.rateSource()).isEqualTo("static-config");
    }

    @Test
    void baseToQuoted_convertsMinorUnits() {
        // 100.00 SAR -> USD: 100 / 3.75 = 26.666... -> 26.67 USD (HALF_UP)
        var quote = exchange.convert(10_000L, Currency.getInstance("SAR"), Currency.getInstance("USD"));

        assertThat(quote.sourceCurrency()).isEqualTo("SAR");
        assertThat(quote.targetCurrency()).isEqualTo("USD");
        assertThat(quote.targetMinorUnits()).isEqualTo(2_667L);
    }

    @Test
    void quotedToBase_convertsMinorUnits() {
        // 26.67 USD -> SAR: 26.67 * 3.75 = 100.0125 -> 10001 cents
        var quote = exchange.convert(2_667L, Currency.getInstance("USD"), Currency.getInstance("SAR"));

        assertThat(quote.targetMinorUnits()).isEqualTo(10_001L);
    }

    @Test
    void quotedToQuoted_routesThroughBase() {
        // 100.00 USD -> EUR: 100*3.75=375 SAR; 375/4.05=92.592... -> 92.59 EUR
        var quote = exchange.convert(10_000L, Currency.getInstance("USD"), Currency.getInstance("EUR"));

        assertThat(quote.targetMinorUnits()).isEqualTo(9_259L);
        // CodeRabbit #241: the quoted rate must be the rate APPLIED to the
        // amount — rateOf(from)/rateOf(to) = 3.75/4.05 ≈ 0.9259 USD->EUR —
        // not 1/rateOf(to), which only holds when the source IS the base.
        assertThat(quote.rate())
                .as("non-base-to-non-base conversion must quote the applied pair rate")
                .isEqualByComparingTo(new BigDecimal("3.75").divide(new BigDecimal("4.05"), new java.math.MathContext(12)));
    }

    @Test
    void quotedRate_matchesAmountConversionForBaseSource() {
        // Base -> quoted: amount uses 1/rateOf(to) exactly, and so must the
        // quoted rate (rateOf(base) == 1 keeps the general formula honest).
        var quote = exchange.convert(10_000L, Currency.getInstance("SAR"), Currency.getInstance("USD"));

        assertThat(quote.rate())
                .isEqualByComparingTo(new BigDecimal("1").divide(new BigDecimal("3.75"), new java.math.MathContext(12)));
    }

    @Test
    void zeroMinorDigitCurrency_scalesToMajorUnits() {
        // JPY has 0 fraction digits: 375 SAR -> 375/0.025 = 15000 JPY (no cents)
        var quote = exchange.convert(37_500L, Currency.getInstance("SAR"), Currency.getInstance("JPY"));

        assertThat(quote.targetMinorUnits()).isEqualTo(15_000L);
        // and back: 15000 JPY * 0.025 = 375 SAR
        assertThat(exchange.convert(15_000L, Currency.getInstance("JPY"), Currency.getInstance("SAR"))
                .targetMinorUnits()).isEqualTo(37_500L);
    }

    @Test
    void unknownRate_failsWithTheExactBindingRecipe() {
        var thrown = assertThatThrownBy(() ->
                exchange.convert(1_000L, Currency.getInstance("SAR"), Currency.getInstance("CHF")));

        thrown.isInstanceOf(CurrencyExchangeUnavailableException.class)
                .hasMessageContaining("CHF")
                .hasMessageContaining("marketplace.pricing.currency.exchange.rates.CHF");
    }

    @Test
    void nonPositiveRate_isRejected() {
        var bad = new StaticRatesCurrencyExchange(new CurrencyExchangeProperties(
                "SAR", Map.of("USD", new BigDecimal("0"))));

        assertThatThrownBy(() -> bad.convert(1_000L, Currency.getInstance("SAR"), Currency.getInstance("USD")))
                .isInstanceOf(CurrencyExchangeUnavailableException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void propertiesKeys_areNormalizedUppercase_forEnvRelaxedBinding() {
        var relaxed = new CurrencyExchangeProperties("sar", Map.of("usd", new BigDecimal("3.75")));

        assertThat(relaxed.baseCurrency()).isEqualTo("SAR");
        assertThat(relaxed.rates()).containsKey("USD");
    }

    @Test
    void propertiesDefault_whenNothingBound() {
        var none = new CurrencyExchangeProperties(null, null);

        assertThat(none.baseCurrency()).isEqualTo("SAR");
        assertThat(none.rates()).isEmpty();
    }
}

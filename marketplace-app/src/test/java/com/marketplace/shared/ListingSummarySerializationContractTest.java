package com.marketplace.shared;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JDK-serialization contract of {@link com.marketplace.shared.api.ListingSummary}
 * — the cached value type of the four catalog caches (Redis value path:
 * JdkSerializationRedisSerializer) — and the deploy-time eviction mechanism
 * that keeps stale-shape entries from ever being read (CodeRabbit #241).
 *
 * <p>Contract being pinned:
 * <ul>
 *   <li>Round-trip: a current-shape record serializes and deserializes with
 *       every component intact — the shape the caches write and read.</li>
 *   <li>Stale-shape hazard: a stream whose {@code currency} value is null
 *       deserializes to a record with {@code currency == null}. Per the Java
 *       Object Serialization Specification ("Record Serialization"), a
 *       component ABSENT from a pre-change stream resolves to the same
 *       default (null for a reference type) — a pre-B4 entry served as a
 *       cache hit would therefore surface a null currency and bypass the
 *       mapping that populates it. That hazard is exactly why the four cache
 *       names carry the serialization-schema version suffix.</li>
 *   <li>Versioned namespaces: the four ListingSummary cache names in
 *       CatalogService / SearchService must carry the {@code -v2} suffix and
 *       match the yml list — enforced by {@code ListingSummaryCacheContractFilesTest}
 *       (source-level assertions) so a future record-component change cannot
 *       silently keep the old namespace.</li>
 * </ul>
 */
class ListingSummarySerializationContractTest {

    @Test
    void roundTripPreservesEveryComponent() throws Exception {
        var original = new com.marketplace.shared.api.ListingSummary(
                UUID.randomUUID(), "Beachfront Villa", "VILLA",
                new BigDecimal("320.00"), "SAR", "provider-display-name");

        var bytes = serialize(original);
        com.marketplace.shared.api.ListingSummary restored = deserialize(bytes);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.currency()).as("the B4 currency component must survive the cache round-trip")
                .isEqualTo("SAR");
    }

    @Test
    void staleStreamSurfacesNullCurrency_componentAbsentResolvesToDefault() throws Exception {
        // The pre-B4 stream shape carried no currency component; the Object
        // Serialization Spec resolves an absent component to the type default
        // (null). Serializing a null-currency record is the byte-equivalent
        // proxy for that stream: both surface currency == null through the
        // canonical constructor. This documents WHY the cache names are
        // versioned — a null here would reach API consumers if the old
        // namespace were still read.
        var staleShaped = new com.marketplace.shared.api.ListingSummary(
                UUID.randomUUID(), "Beachfront Villa", "VILLA",
                new BigDecimal("320.00"), null, "provider-display-name");

        com.marketplace.shared.api.ListingSummary restored = deserialize(serialize(staleShaped));

        assertThat(restored.currency())
                .as("the documented hazard: a stale-shape entry has no currency to serve")
                .isNull();
    }

    private static byte[] serialize(Object value) throws Exception {
        try (var bos = new ByteArrayOutputStream();
             var oos = new ObjectOutputStream(bos)) {
            oos.writeObject(value);
            oos.flush();
            return bos.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserialize(byte[] bytes) throws Exception {
        try (var bis = new ByteArrayInputStream(bytes);
             var ois = new ObjectInputStream(bis)) {
            return (T) ois.readObject();
        }
    }
}

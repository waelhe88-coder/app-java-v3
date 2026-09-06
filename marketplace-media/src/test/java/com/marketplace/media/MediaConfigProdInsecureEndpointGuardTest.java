package com.marketplace.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CodeRabbit #242 round 2: the {@code allow-insecure-endpoint} escape hatch is
 * for local emulators only — {@link MediaConfig} must fail startup when it is
 * enabled under the prod profile (the house fail-fast-in-prod pattern), while
 * non-prod profiles keep the opt-in working.
 */
@ExtendWith(MockitoExtension.class)
class MediaConfigProdInsecureEndpointGuardTest {

    private final MediaConfig config = new MediaConfig();

    @Mock
    private Environment environment;

    private static MediaProperties properties(boolean allowInsecure) {
        return new MediaProperties(
                new MediaProperties.Storage("https://media.example.local", "auto", "b", "ak", "sk", allowInsecure),
                new MediaProperties.Limits(10_485_760L, java.util.Set.of("image/jpeg"),
                        Duration.ofMinutes(15)));
    }

    @Test
    void insecureEndpointOptIn_failsStartupInProdProfile() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> config.s3MediaStorage(properties(true), environment));

        assertTrue(thrown.getMessage().contains("allow-insecure-endpoint"));
        assertTrue(thrown.getMessage().contains("prod"));
    }

    @Test
    void insecureEndpointOptIn_buildsOutsideProd() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);

        assertDoesNotThrow(() -> {
            try (S3MediaStorage storage = config.s3MediaStorage(properties(true), environment)) {
                assertTrue(storage != null);
            }
        });
    }

    @Test
    void httpsEndpoint_buildsInProdProfile() {
        // allowInsecureEndpoint=false short-circuits the guard — the profile
        // is never even consulted (hence no stubbing here).

        assertDoesNotThrow(() -> {
            try (S3MediaStorage storage = config.s3MediaStorage(properties(false), environment)) {
                assertTrue(storage != null);
            }
        });
    }
}

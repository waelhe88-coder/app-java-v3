package com.marketplace.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Registers {@link MediaProperties} and — only when every storage credential is
 * bound — the single {@link S3MediaStorage} bean. Spring owns the bean lifecycle:
 * the {@code destroyMethod} close hook shuts the presigner and client down with
 * the context.
 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
class MediaConfig {

    @Bean(destroyMethod = "close")
    @Conditional(MediaStorageConfiguredCondition.class)
    S3MediaStorage s3MediaStorage(MediaProperties properties, Environment environment) {
        // House fail-fast-in-prod pattern (JwkSourceProdHardening,
        // OAuth2ClientSecretInitializer — CodeRabbit #242 round 2): the
        // cleartext-endpoint escape hatch exists for local emulators only and
        // must fail startup if enabled under the prod profile, instead of
        // silently binding an http endpoint in production.
        if (properties.storage().allowInsecureEndpoint()
                && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "marketplace.media.storage.allow-insecure-endpoint must never be enabled in the"
                            + " prod profile — cleartext storage endpoints leak SigV4 credentials"
                            + " (CWE-319)");
        }
        return new S3MediaStorage(
                properties.storage(),
                properties.limits().presignTtl()
        );
    }
}

package com.marketplace.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the constructor-binding contract of {@link MarketplaceProperties}: nested record
 * components primed with an empty {@link org.springframework.boot.context.properties.bind.DefaultValue}
 * are always bound to a non-null instance, even when the corresponding keys are absent.
 *
 * <p>This is exactly the regression that failed {@code MarketplaceApplicationTest.contextLoads}
 * in CI: {@code marketplace.security.oauth2} is not defined outside production, yet the
 * {@link com.marketplace.shared.security.OAuth2ClientSecretInitializer} dereferences it.
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding">
 *      Spring Boot — Type-safe Configuration Properties — Constructor binding — @DefaultValue</a>
 */
class MarketplacePropertiesBindingTest {

    @Test
    void bindsAbsentOauth2SectionToNonNullDefaults() {
        Map<String, Object> source = Map.of("marketplace.security.session.max-sessions", "2");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        assertThat(properties.security()).isNotNull();
        MarketplaceProperties.Security.OAuth2 oauth2 = properties.security().oauth2();
        assertThat(oauth2).isNotNull();
        assertThat(oauth2.client()).isNotNull();
        assertThat(oauth2.client().clientId()).isEmpty();
        assertThat(oauth2.client().secret()).isEmpty();
    }

    /**
     * CodeRabbit #241: SecurityConfig dereferences security().jwt().keystore()
     * and security().session().maxSessions() unconditionally, and it consumes
     * cors().allowedOrigins() (SecurityConfig:185) — every dereference-prone
     * section must bind non-null when its keys are absent, exactly like the
     * OAuth2 section above. One bound key (the same session key as the first
     * test) makes the {@code marketplace} prefix exist for the Binder — the
     * realistic production shape, where application.yml always carries some
     * marketplace.* keys while whole sections (jwt / keystore / cors) come
     * only from environment variables or not at all.
     */
    @Test
    void bindsAbsentSecurityJwtKeystoreAndCorsSectionsToNonNullDefaults() {
        Map<String, Object> source = Map.of("marketplace.security.session.max-sessions", "2");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        // cors: entirely absent section — primed, with the default origin.
        assertThat(properties.cors()).as("cors section (absent keys)").isNotNull();
        assertThat(properties.cors().allowedOrigins())
                .as("cors default origin applies when the section is absent")
                .containsExactly("https://marketplace.com");

        // security.jwt + keystore: entirely absent — primed all the way down.
        assertThat(properties.security()).as("security section").isNotNull();
        assertThat(properties.security().jwt()).as("jwt section (absent keys)").isNotNull();
        assertThat(properties.security().jwt().keystore())
                .as("keystore section (absent keys — SecurityConfig dereferences it unconditionally)")
                .isNotNull();
        assertThat(properties.security().jwt().keystore().path()).isEmpty();
        assertThat(properties.security().jwt().keystore().b64()).isEmpty();
        assertThat(properties.security().jwt().audience()).isEqualTo("marketplace-api");

        // session: the one bound key, with the documented default shape.
        assertThat(properties.security().session()).as("session section").isNotNull();
        assertThat(properties.security().session().maxSessions()).isEqualTo(2);
    }
}
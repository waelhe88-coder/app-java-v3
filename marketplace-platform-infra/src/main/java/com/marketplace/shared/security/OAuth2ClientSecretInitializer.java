package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bootstraps the {@code marketplace-web-client} registered client from external configuration
 * (environment variables) into the {@code oauth2_registered_client} table at startup.
 *
 * <p>This is the single official mutation/bootstrapping path for a registered client:
 * {@link RegisteredClientRepository#save(RegisteredClient)} (Spring Authorization Server —
 * core-model-components, "RegisteredClientRepository"). The client definition lives in
 * {@code V13__authorization_security.sql} schema and application configuration, not in a
 * hand-written seed whose internal JSON format is owned by the framework (Jackson), not by
 * this application.
 *
 * <p><b>Bootstrap (client absent):</b> when no client is configured and the {@code prod}
 * profile is not active, startup is a deliberate no-op (there is no consumer until D9).
 * When configured, a missing client is <em>created</em> through the official builder
 * ({@link ClientSettings} / {@link TokenSettings}) rather than mutated SQL — the map is
 * never hand-written, so it cannot drift from what the framework serializes.
 *
 * <p><b>Converge-on-boot (client present):</b> the full definition is re-derived from
 * configuration and code constants and rebuilt with
 * {@code RegisteredClient.withId(existing.getId())} — the identity-preserving equivalent
 * of the spec's {@code RegisteredClient.from(existing)} "identity only" prescription
 * (§4.1 تثبيت ب). It re-derives rather than copies because gate B made the redirect URIs
 * environment-driven ({@code OAUTH_CLIENT_REDIRECT_URIS}): {@code from()} seeds the
 * builder with the stored sets and offers no replace operation, so a partial copy would
 * silently keep stale redirect URIs when the environment changes; re-derivation converges
 * them at the next boot. The old settings map is <em>not</em> carried over either (a
 * partial/legacy map would preserve the id-token gap), so existing deployments converge
 * on the complete 8-key settings map at first startup.
 *
 * <p><b>Redirect URIs:</b> when {@code marketplace.security.oauth2.client.redirect-uris}
 * is blank the fixed development definition from the spec (§4.1) applies —
 * {@code http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client}, matched
 * textually by the authorize endpoint so the random test port works. In the {@code prod}
 * profile a blank value fails fast: production must configure the real BFF callback URLs
 * ({@code OAUTH_CLIENT_REDIRECT_URIS}, comma-separated) — closing the documented gate-B
 * debt (the production redirect was previously pinned to the development constant).
 *
 * <p><b>Idempotence guard:</b> {@code save()} runs only when the derived definition differs
 * from the stored row — client id, name, secret, redirect URIs, authentication methods,
 * grant types, scopes, or the settings maps. Matching secret + matching settings + matching
 * definition is a no-op, so concurrent instances converge without rewriting identical rows.
 *
 * <p><b>Fail-fast by profile:</b> {@code application-prod.yml} binds the client from mandatory
 * environment variables ({@code OAUTH_CLIENT_ID}/{@code OAUTH_CLIENT_SECRET}/
 * {@code OAUTH_CLIENT_REDIRECT_URIS}), so production must not silently run without a managed
 * client or with the development redirect constant. The nested
 * {@code marketplace.security.oauth2} section is bound non-null by an empty {@code @DefaultValue}
 * (official constructor-binding behavior), so it is safe to dereference in every profile.
 *
 * @see <a href="https://docs.spring.io/spring-authorization-server/reference/core-model-components.html#registered-client-repository">Spring Authorization Server — RegisteredClientRepository</a>
 */
@Component
public class OAuth2ClientSecretInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientSecretInitializer.class);

    private static final Profiles PROD_PROFILE = Profiles.of("prod");

    /**
     * Stable identity of the production web client. Referenced by
     * {@code oauth2_authorization.registered_client_id} and
     * {@code oauth2_authorization_consent.registered_client_id}, so it is invariant.
     */
    private static final String CLIENT_ID = "a7bd8b0d-7d42-4a64-9e34-1ad3ab22e37e";

    private static final String CLIENT_NAME = "Marketplace Web Client";
    private static final String REDIRECT_URI =
            "http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client";
    private static final String POST_LOGOUT_REDIRECT_URI = "http://127.0.0.1:8080/";

    private final MarketplaceProperties properties;
    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public OAuth2ClientSecretInitializer(MarketplaceProperties properties,
                                         RegisteredClientRepository registeredClientRepository,
                                         PasswordEncoder passwordEncoder,
                                         Environment environment) {
        this.properties = properties;
        this.registeredClientRepository = registeredClientRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        MarketplaceProperties.Security.OAuth2.Client client = properties.security().oauth2().client();
        String clientId = client.clientId();
        String rawSecret = client.secret();
        boolean prodProfile = environment.acceptsProfiles(PROD_PROFILE);

        if (!StringUtils.hasText(clientId) && !StringUtils.hasText(rawSecret)) {
            if (prodProfile) {
                throw new IllegalStateException(
                        "marketplace.security.oauth2.client.clientId and .secret must be configured in production"
                                + " (OAUTH_CLIENT_ID/OAUTH_CLIENT_SECRET)");
            }
            return;
        }
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(rawSecret)) {
            throw new IllegalStateException(
                    "marketplace.security.oauth2.client.clientId and .secret must both be configured");
        }
        if (prodProfile && !StringUtils.hasText(client.redirectUris())) {
            throw new IllegalStateException(
                    "marketplace.security.oauth2.client.redirectUris must be configured in production"
                            + " (OAUTH_CLIENT_REDIRECT_URIS) — the development redirect constant is not a"
                            + " valid production BFF callback");
        }

        Set<String> redirectUris = parseRedirectUris(client.redirectUris());

        RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
        boolean secretChanged = existing == null || !passwordEncoder.matches(rawSecret, existing.getClientSecret());

        RegisteredClient target = buildTarget(existing, clientId, rawSecret, secretChanged, redirectUris);

        if (existing == null) {
            try {
                registeredClientRepository.save(target);
                log.info("Bootstrapped registered client '{}' from environment configuration", clientId);
            } catch (DataIntegrityViolationException ex) {
                // Concurrent-replica bootstrap (CodeRabbit #241):
                // JdbcRegisteredClientRepository.save is check-then-insert, so
                // two replicas booting simultaneously can both see no row and
                // both insert the same stable CLIENT_ID — the loser's insert
                // violates the primary key and would fail startup. The row now
                // exists: converge onto it exactly like a normal restart.
                RegisteredClient winner = registeredClientRepository.findByClientId(clientId);
                if (winner == null) {
                    // Not a duplicate-key failure after all — a real integrity
                    // problem. Do not mask it.
                    throw ex;
                }
                boolean winnerSecretChanged = !passwordEncoder.matches(rawSecret, winner.getClientSecret());
                RegisteredClient converged = buildTarget(winner, clientId, rawSecret, winnerSecretChanged, redirectUris);
                if (needsSave(winner, converged)) {
                    registeredClientRepository.save(converged);
                }
                log.info("Converged registered client '{}' after concurrent bootstrap by another replica", clientId);
            }
        } else if (needsSave(existing, target)) {
            registeredClientRepository.save(target);
            if (secretChanged) {
                log.info("Rotated client_secret for registered client '{}' from environment configuration", clientId);
            } else {
                log.info("Converged settings for registered client '{}' from environment configuration", clientId);
            }
        }
    }

    /**
     * Full re-derivation (identity preserved via {@code withId}): the stored row is never
     * a source of truth — the definition always comes from configuration (clientId,
     * secret, redirect URIs) plus the spec §4.1 constants, so every boot converges the
     * row to exactly what the environment says it should be.
     */
    private RegisteredClient buildTarget(RegisteredClient existing,
                                         String clientId,
                                         String rawSecret,
                                         boolean secretChanged,
                                         Set<String> redirectUris) {
        String id = existing == null ? CLIENT_ID : existing.getId();
        RegisteredClient.Builder builder = RegisteredClient.withId(id)
                .clientId(clientId)
                .clientName(CLIENT_NAME)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
        if (redirectUris.isEmpty()) {
            builder.redirectUri(REDIRECT_URI);
        } else {
            redirectUris.forEach(builder::redirectUri);
        }
        builder.postLogoutRedirectUri(POST_LOGOUT_REDIRECT_URI)
                .scope("openid")
                .scope("profile")
                .clientSettings(buildClientSettings())
                .tokenSettings(buildTokenSettings());
        if (existing == null || secretChanged) {
            builder.clientSecret(passwordEncoder.encode(rawSecret));
        } else {
            builder.clientSecret(existing.getClientSecret());
        }
        return builder.build();
    }

    private static ClientSettings buildClientSettings() {
        return ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build();
    }

    private static TokenSettings buildTokenSettings() {
        return TokenSettings.builder()
                .reuseRefreshTokens(false)
                .accessTokenTimeToLive(Duration.ofSeconds(900))
                .refreshTokenTimeToLive(Duration.ofSeconds(604800))
                .authorizationCodeTimeToLive(Duration.ofSeconds(300))
                .build();
    }

    /**
     * Full-definition comparison (spec §4.1 idempotence guard, extended in gate B to the
     * fields the environment now drives): {@code save} happens if-and-only-if the stored
     * row differs from the derived definition in identity-relevant fields or the settings
     * maps. Matching secret + matching settings + matching definition is a no-op, so
     * concurrent instances converge without rewriting identical rows.
     */
    private static boolean needsSave(RegisteredClient existing, RegisteredClient target) {
        if (existing == null) {
            return true;
        }
        if (!Objects.equals(existing.getClientId(), target.getClientId())
                || !Objects.equals(existing.getClientName(), target.getClientName())
                || !Objects.equals(existing.getClientSecret(), target.getClientSecret())
                || !Objects.equals(existing.getRedirectUris(), target.getRedirectUris())
                || !Objects.equals(existing.getPostLogoutRedirectUris(), target.getPostLogoutRedirectUris())
                || !Objects.equals(existing.getClientAuthenticationMethods(), target.getClientAuthenticationMethods())
                || !Objects.equals(existing.getAuthorizationGrantTypes(), target.getAuthorizationGrantTypes())
                || !Objects.equals(existing.getScopes(), target.getScopes())) {
            return true;
        }
        return !existing.getClientSettings().getSettings().equals(target.getClientSettings().getSettings())
                || !existing.getTokenSettings().getSettings().equals(target.getTokenSettings().getSettings());
    }

    /**
     * Splits the comma-separated {@code OAUTH_CLIENT_REDIRECT_URIS} value; drops blank
     * entries, preserves order. An empty/blank raw value means "not configured" and the
     * caller falls back to the fixed development definition.
     */
    private static Set<String> parseRedirectUris(String raw) {
        Set<String> uris = new LinkedHashSet<>();
        if (!StringUtils.hasText(raw)) {
            return uris;
        }
        for (String candidate : raw.split(",")) {
            if (StringUtils.hasText(candidate)) {
                uris.add(candidate.trim());
            }
        }
        return uris;
    }
}

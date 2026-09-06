package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2ClientSecretInitializerTest {

    private final RegisteredClientRepository repository = mock(RegisteredClientRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private static final String APP_REDIRECT_URI = "http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client";

    @Test
    void doesNothingWhenClientNotConfigured() {
        OAuth2ClientSecretInitializer initializer =
                new OAuth2ClientSecretInitializer(properties("", ""), repository, passwordEncoder, environment(false));

        initializer.run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void failsWhenRedirectUrisBlankInProductionProfile() {
        OAuth2ClientSecretInitializer prodInitializer = new OAuth2ClientSecretInitializer(
                properties("web", "raw", ""), repository, passwordEncoder, environment(true));

        assertThatThrownBy(() -> prodInitializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redirectUris must be configured in production")
                .hasMessageContaining("OAUTH_CLIENT_REDIRECT_URIS");
    }

    @Test
    void failsWhenClientNotConfiguredInProductionProfile() {
        OAuth2ClientSecretInitializer prodInitializer = new OAuth2ClientSecretInitializer(
                properties("", ""), repository, passwordEncoder, environment(true));

        assertThatThrownBy(() -> prodInitializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured in production");
    }

    @Test
    void failsWhenOnlyOneOfClientIdSecretConfigured() {
        OAuth2ClientSecretInitializer partial = new OAuth2ClientSecretInitializer(
                properties("web", ""), repository, passwordEncoder, environment(false));

        assertThatThrownBy(() -> partial.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must both be configured");
    }

    @Test
    void bootstrapsClientWithStableIdAndFullOfficialSettingsWhenAbsent() {
        when(repository.findByClientId("web")).thenReturn(null);
        when(passwordEncoder.encode("raw")).thenReturn("enc");
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        new OAuth2ClientSecretInitializer(properties("web", "raw"), repository, passwordEncoder, environment(false))
                .run(null);

        RegisteredClient saved = savedClientArgument();
        assertThat(saved.getId()).isEqualTo("a7bd8b0d-7d42-4a64-9e34-1ad3ab22e37e");
        assertThat(saved.getClientSecret()).isEqualTo("enc");
        assertThat(saved.getTokenSettings().getIdTokenSignatureAlgorithm()).isNotNull();
        assertThat(saved.getTokenSettings().getAccessTokenFormat()).isNotNull();
        assertThat(saved.getTokenSettings().getSettings().get("settings.token.access-token-time-to-live"))
                .isEqualTo(Duration.ofSeconds(900));
        assertThat(saved.getTokenSettings().getSettings().get("settings.token.reuse-refresh-tokens"))
                .isEqualTo(false);
        assertThat(saved.getClientAuthenticationMethods())
                .containsExactly(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(saved.getAuthorizationGrantTypes())
                .containsExactlyInAnyOrder(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE,
                        org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN,
                        org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(saved.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(saved.getRedirectUris())
                .as("blank env redirect falls back to the fixed development definition (spec 4.1)")
                .containsExactly(APP_REDIRECT_URI);
    }

    @Test
    void doesNothingWhenSecretAndSettingsAlreadyMatchCompleteClient() {
        RegisteredClient existing = completeClient("old");
        when(repository.findByClientId("web")).thenReturn(existing);
        when(passwordEncoder.matches("raw", "old")).thenReturn(true);

        new OAuth2ClientSecretInitializer(properties("web", "raw"), repository, passwordEncoder, environment(false))
                .run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void convergesLegacyPartialMapIntoCompleteOfficialSettingsWhenSecretMatches() {
        RegisteredClient legacy = legacyPartialClient("enc");
        when(repository.findByClientId("web")).thenReturn(legacy);
        when(passwordEncoder.matches("raw", "enc")).thenReturn(true);

        new OAuth2ClientSecretInitializer(properties("web", "raw"), repository, passwordEncoder, environment(false))
                .run(null);

        RegisteredClient saved = savedClientArgument();
        assertThat(saved.getTokenSettings().getIdTokenSignatureAlgorithm()).isNotNull();
        assertThat(saved.getTokenSettings().getAccessTokenFormat()).isNotNull();
        assertThat(saved.getClientSecret()).isEqualTo("enc");
    }

    @Test
    void convergesAfterConcurrentReplicaWonTheBootstrapInsert() {
        // CodeRabbit #241 (multi-replica bootstrap): JdbcRegisteredClientRepository.save
        // is check-then-insert, so two replicas booting simultaneously can both
        // see no row and both insert the same stable CLIENT_ID — the loser's
        // insert violates the primary key. The loser must reload the winner's
        // row and converge onto it (a real re-derivation: the winner row here
        // is legacy-partial), NOT fail startup.
        when(repository.findByClientId("web")).thenReturn(null, legacyPartialClient("enc"));
        when(passwordEncoder.encode("raw")).thenReturn("enc");
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        doThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"oauth2_registered_client_pkey\""))
                .doAnswer(inv -> null)
                .when(repository).save(any(RegisteredClient.class));

        new OAuth2ClientSecretInitializer(properties("web", "raw"), repository, passwordEncoder, environment(false))
                .run(null);

        // save #1 = the lost bootstrap insert; save #2 = the convergence update
        // onto the winner's row.
        org.mockito.ArgumentCaptor<RegisteredClient> captor =
                org.mockito.ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        RegisteredClient converged = captor.getAllValues().get(1);
        assertThat(converged.getTokenSettings().getIdTokenSignatureAlgorithm()).isNotNull();
        assertThat(converged.getClientSecret()).isEqualTo("enc");
        assertThat(converged.getAuthorizationGrantTypes())
                .as("the winner's partial row was re-derived into the complete official definition")
                .contains(org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS);
    }

    @Test
    void convergesEnvDrivenRedirectUrisWhilePreservingRowIdentity() {
        String existingRowId = UUID.randomUUID().toString();
        RegisteredClient existing = completeClientWithRedirect(existingRowId, "https://old-bff.example.com/callback").build();
        when(repository.findByClientId("web")).thenReturn(existing);
        when(passwordEncoder.matches("raw", existing.getClientSecret())).thenReturn(true);

        new OAuth2ClientSecretInitializer(
                properties("web", "raw", "https://bff.example.com/callback , com.example.bff:/oauth2/callback"),
                repository, passwordEncoder, environment(false))
                .run(null);

        RegisteredClient saved = savedClientArgument();
        assertThat(saved.getId())
                .as("converge re-derives the definition but preserves the row identity")
                .isEqualTo(existingRowId);
        assertThat(saved.getRedirectUris())
                .as("env-driven redirect URIs replace the stored set (comma-split, trimmed, blanks dropped;"
                        + " RegisteredClient stores them in a set, so order is not guaranteed)")
                .containsExactlyInAnyOrder("https://bff.example.com/callback", "com.example.bff:/oauth2/callback");
        assertThat(saved.getClientSecret())
                .as("secret unchanged means the stored encoded secret is reused")
                .isEqualTo(existing.getClientSecret());
    }

    @Test
    void rotatesSecretWhenStoredValueDiffers() {
        RegisteredClient existing = completeClient("old");
        when(repository.findByClientId("web")).thenReturn(existing);
        when(passwordEncoder.matches("raw", "old")).thenReturn(false);
        when(passwordEncoder.encode("raw")).thenReturn("new");

        new OAuth2ClientSecretInitializer(properties("web", "raw"), repository, passwordEncoder, environment(false))
                .run(null);

        assertThat(savedClientArgument().getClientSecret()).isEqualTo("new");
    }

    private static Environment environment(boolean prodProfileActive) {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(prodProfileActive);
        return environment;
    }

    private RegisteredClient savedClientArgument() {
        org.mockito.ArgumentCaptor<RegisteredClient> captor =
                org.mockito.ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> legacyPartialTokenMap() {
        Map<String, Object> partial = new java.util.HashMap<>();
        partial.put("settings.token.reuse-refresh-tokens", false);
        partial.put("settings.token.access-token-time-to-live", Duration.ofSeconds(900));
        partial.put("settings.token.refresh-token-time-to-live", Duration.ofSeconds(604800));
        partial.put("settings.token.authorization-code-time-to-live", Duration.ofSeconds(300));
        return partial;
    }

    private static RegisteredClient legacyPartialClient(String encodedSecret) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("web")
                .clientSecret(encodedSecret)
                .clientName("Marketplace Web Client")
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client")
                .postLogoutRedirectUri("http://127.0.0.1:8080/")
                .scope("openid")
                .scope("profile")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.withSettings(legacyPartialTokenMap()).build())
                .build();
    }

    private static RegisteredClient completeClient(String encodedSecret) {
        return completeClientWithRedirect(UUID.randomUUID().toString(), APP_REDIRECT_URI)
                .clientSecret(encodedSecret)
                .build();
    }

    private static RegisteredClient.Builder completeClientWithRedirect(String id, String redirectUri) {
        return RegisteredClient.withId(id)
                .clientId("web")
                .clientSecret("irrelevant")
                .clientName("Marketplace Web Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .redirectUri(redirectUri)
                .postLogoutRedirectUri("http://127.0.0.1:8080/")
                .scope("openid")
                .scope("profile")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .reuseRefreshTokens(false)
                        .accessTokenTimeToLive(Duration.ofSeconds(900))
                        .refreshTokenTimeToLive(Duration.ofSeconds(604800))
                        .authorizationCodeTimeToLive(Duration.ofSeconds(300))
                        .build());
    }

    private static MarketplaceProperties properties(String clientId, String secret) {
        return properties(clientId, secret, "");
    }

    private static MarketplaceProperties properties(String clientId, String secret, String redirectUris) {
        return new MarketplaceProperties(
                null,
                new MarketplaceProperties.Security(
                        null,
                        null,
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client(clientId, secret, redirectUris),
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", ""))));
    }
}

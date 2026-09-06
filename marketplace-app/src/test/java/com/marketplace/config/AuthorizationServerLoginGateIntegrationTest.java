package com.marketplace.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end login gate for the framework-managed authorization server:
 * authorization request (code + PKCE) &rarr; form login &rarr; authorization code
 * &rarr; token endpoint &rarr; Bearer access token &rarr; protected API.
 *
 * <p>Closes the E5 gap: {@code jwt()} test post-processors bypass the real
 * {@link org.springframework.security.oauth2.jwt.JwtDecoder} and
 * {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter};
 * this gate exercises the full mint&ndash;validate loop over real HTTP, proving
 * that tokens issued by the authorization server (customized with
 * {@code roles} + {@code aud} by the {@code OAuth2TokenCustomizer}) pass the
 * resource server decoder (issuer + audience validators) and the authorities
 * converter on a protected endpoint.
 *
 * <p>Fixtures follow the official wiring: the schema is the application's own
 * {@code V13__authorization_security.sql} migration (the PostgreSQL adaptation of
 * the Spring Authorization Server schema), the client is registered through the
 * {@link RegisteredClientRepository} bean, and the user through the
 * {@link UserDetailsManager} bean.
 *
 * <p>The schema is applied through {@code spring.sql.init} (not in a
 * {@code @BeforeAll}) on purpose: {@code JdbcOAuth2AuthorizationService} resolves
 * the LOB-ish column types from live database metadata while the bean is being
 * constructed (JdbcOAuth2AuthorizationService.java:400-460). If the tables do not
 * exist yet, every {@code *_value}/{@code *_metadata}/{@code attributes} column
 * falls back to the BLOB default and token values are bound as {@code bytea}
 * ({@code operator does not exist: text = bytea} on PostgreSQL). Boot orders the
 * SQL initializer before the {@code JdbcTemplate} bean (and therefore before this
 * test context's {@code JdbcOAuth2AuthorizationService}), which mirrors production,
 * where Flyway migrates before the beans are constructed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V13__authorization_security.sql",
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        // Gate B pattern (1): prove the env-driven redirect URIs path live — the value
        // equals the fixed development definition, so the flow is unchanged while the
        // wiring (OAUTH_CLIENT_REDIRECT_URIS parsing) is exercised end to end.
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationServerLoginGateIntegrationTest {

    private static final String ADMIN_USERNAME = "it-login-gate-admin";
    private static final String USER_USERNAME = "it-login-gate-user";
    private static final String PASSWORD = "it-login-gate-password";

    private static final String CLIENT_ID = "it-login-gate-client";
    private static final String CLIENT_SECRET = "it-login-gate-secret";
    private static final String REDIRECT_URI = "https://login-gate.test.example/callback";

    private static final String APP_CLIENT_ID = "marketplace-web-client";
    private static final String APP_CLIENT_SECRET = "it-app-secret";
    private static final String APP_REDIRECT_URI = "http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client";

    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String PROTECTED_ADMIN_PATH = "/api/v1/admin/system";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationConsentService authorizationConsentService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private GateResult adminGate;

    @BeforeAll
    void setUpFixtures() {
        registerLoginGateClient();
        registerUser(ADMIN_USERNAME, "ADMIN");
        registerUser(USER_USERNAME, "USER");
    }

    @BeforeEach
    void resetConsentFixtures() {
        RegisteredClient appClient = registeredClientRepository.findByClientId(APP_CLIENT_ID);
        if (appClient != null) {
            removeConsentIfPresent(appClient.getId(), ADMIN_USERNAME);
            removeConsentIfPresent(appClient.getId(), USER_USERNAME);
        }
    }

    private void removeConsentIfPresent(String clientId, String principalName) {
        OAuth2AuthorizationConsent consent = authorizationConsentService.findById(clientId, principalName);
        if (consent != null) {
            authorizationConsentService.remove(consent);
        }
    }

    @Test
    void authorizationCodeWithPkceMintsTokenAcceptedByRealDecoderAndRoleGate() throws Exception {
        GateResult gate = adminGate();

        assertThat(gate.accessToken()).isNotBlank();
        assertThat(gate.tokenType()).isEqualToIgnoringCase("Bearer");
        assertThat(gate.refreshToken()).isNotBlank();

        // Mint side: the OAuth2TokenCustomizer issues roles + aud, the issuer comes from
        // spring.security.oauth2.authorizationserver.issuer (AuthorizationServerSettings bean).
        // aud is asserted through the parsed JSON: RFC 7519 4.1.3 allows the single-value
        // (string) and the multi-value (array) serialization, so the raw-contains form
        // would depend on the serializer's shape choice.
        JsonNode claims = objectMapper.readTree(jwtClaimsAsJson(gate.accessToken()));
        assertThat(claims.path("iss").asString()).isEqualTo("http://localhost:8080");
        assertThat(claims.path("aud").toString()).contains("marketplace-api");
        assertThat(claims.path("roles").toString()).contains("ADMIN");

        // Validate side: the real decoder (issuer + audience + signature) and the
        // JwtAuthenticationConverter (roles claim -> ROLE_ authorities) gate the API.
        // 401 would mean the token failed validation; 403 would mean the ROLE_ADMIN
        // authority was not mapped; 404 means authentication and authorization passed
        // and no handler is mapped at this path (there is none - only the security rule).
        HttpResponse<String> apiResponse = getWithBearer(PROTECTED_ADMIN_PATH, gate.accessToken());
        assertThat(apiResponse.statusCode()).isNotEqualTo(401);
        assertThat(apiResponse.statusCode()).isNotEqualTo(403);
    }

    @Test
    void mintedTokenForNonAdminPrincipalIsRejectedWithProblemDetail403() throws Exception {
        GateResult gate = loginGate(USER_USERNAME, PASSWORD);

        HttpResponse<String> apiResponse = getWithBearer(PROTECTED_ADMIN_PATH, gate.accessToken());

        assertThat(apiResponse.statusCode()).isEqualTo(403);
        assertThat(apiResponse.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(apiResponse.body()).contains("Access denied");
    }

    @Test
    void refreshTokenGrantRotatesAccessTokenStillAcceptedByTheGate() throws Exception {
        GateResult gate = adminGate();

        HttpResponse<String> refreshResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=refresh_token&refresh_token=" + gate.refreshToken());
        assertThat(refreshResponse.statusCode()).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(refreshResponse.body());
        String rotatedAccessToken = tokens.path("access_token").asString();
        assertThat(rotatedAccessToken).isNotBlank().isNotEqualTo(gate.accessToken());

        HttpResponse<String> apiResponse = getWithBearer(PROTECTED_ADMIN_PATH, rotatedAccessToken);
        assertThat(apiResponse.statusCode()).isNotEqualTo(401);
        assertThat(apiResponse.statusCode()).isNotEqualTo(403);
    }

    /**
     * Exercises the authoritative bootstrapped client (marketplace-web-client) end to end:
     * the OAuth2ClientSecretInitializer ApplicationRunner creates it from the official
     * builders during context startup, so this closes the live id-token gap (the old seed
     * wrote a partial token map lacking id-token-signature-algorithm/access-token-format,
     * which JwtGenerator then threw on for any openid flow). A full openid authorize -&gt;
     * consent (required) -&gt; code -&gt; token exchange that mint an id-token proves the
     * complete 8-key settings map serializes/deserializes through the real mapper.
     */
    @Test
    void bootstrappedWebClientMintsIdTokenThroughConsentFlow() throws Exception {
        GateResult gate = consentGate(ADMIN_USERNAME, PASSWORD);

        assertThat(gate.accessToken()).isNotBlank();
        assertThat(gate.refreshToken()).isNotBlank();
        assertThat(gate.idToken())
                .as("the openid scope must yield an id_token from the bootstrapped client")
                .isNotBlank();
        assertThat(gate.idToken().split("\\."))
                .as("the id_token must be a structurally valid JWT")
                .hasSize(3);

        JsonNode claims = objectMapper.readTree(jwtClaimsAsJson(gate.accessToken()));
        assertThat(claims.path("iss").asString()).isEqualTo("http://localhost:8080");
        assertThat(claims.path("aud").toString()).contains("marketplace-api");
        assertThat(claims.path("roles").toString()).contains("ADMIN");

        JsonNode idTokenClaims = objectMapper.readTree(jwtClaimsAsJson(gate.idToken()));
        assertThat(idTokenClaims.path("iss").asString()).isEqualTo("http://localhost:8080");
        assertThat(idTokenClaims.path("aud").toString()).contains("marketplace-web-client");
        assertThat(idTokenClaims.path("sub").asString()).isEqualTo(ADMIN_USERNAME);
    }

    /**
     * Consent is required for the bootstrapped client (requireAuthorizationConsent=true).
     * A first approval persists oauth2_authorization_consent for (client, principal); a
     * second authorization for the same principal skips consent and goes straight to code.
     * Using distinct principals keeps the positive and negative cases isolated.
     */
    @Test
    void bootstrappedWebClientRequiresAndRecordsConsentPerPrincipal() throws Exception {
        GateResult first = consentGate(ADMIN_USERNAME, PASSWORD);
        assertThat(first.accessToken()).isNotBlank();
        assertThat(consentPageRendered)
                .as("a fresh principal must actually pass the consent page")
                .isTrue();

        // CodeRabbit #241: assert the persistence itself — the (client,
        // principal) row is what makes the next same-principal authorization
        // skip consent. Without it the test could pass while consent
        // persistence is broken (the direct-code branch and the consent-page
        // branch both end in a token).
        RegisteredClient appClient = registeredClientRepository.findByClientId(APP_CLIENT_ID);
        org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent consent =
                authorizationConsentService.findById(appClient.getId(), ADMIN_USERNAME);
        assertThat(consent).as("the first approval must persist consent for (client, principal)")
                .isNotNull();
        assertThat(consent.getScopes()).contains("openid", "profile");

        // Second authorization for the SAME principal: the recorded consent
        // must skip the page and return the code directly.
        GateResult repeated = consentGate(ADMIN_USERNAME, PASSWORD);
        assertThat(repeated.accessToken())
                .as("same-principal re-authorization skips consent and still issues tokens")
                .isNotBlank();
        assertThat(consentPageRendered)
                .as("recorded consent must skip the consent page on the next authorization")
                .isFalse();

        GateResult second = consentGate(USER_USERNAME, PASSWORD);
        assertThat(second.accessToken()).isNotBlank();
        assertThat(consentPageRendered)
                .as("a different principal must pass the consent page (cross-principal isolation)")
                .isTrue();
    }

    /**
     * The bootstrapped client's operational settings are preserved verbatim (no drift from
     * the official TokenSettings values): reuse=false, access 900s, refresh 604800s,
     * authorization-code 300s, plus requireProofKey/requireAuthorizationConsent=true.
     */
    @Test
    void bootstrappedWebClientKeepsOperationalSettings() {
        RegisteredClient appClient = registeredClientRepository.findByClientId(APP_CLIENT_ID);
        assertThat(appClient).isNotNull();

        TokenSettings tokenSettings = appClient.getTokenSettings();
        assertThat(tokenSettings.getSettings().get("settings.token.reuse-refresh-tokens")).isEqualTo(false);
        assertThat(tokenSettings.getSettings().get("settings.token.access-token-time-to-live"))
                .isEqualTo(Duration.ofSeconds(900));
        assertThat(tokenSettings.getSettings().get("settings.token.refresh-token-time-to-live"))
                .isEqualTo(Duration.ofSeconds(604800));
        assertThat(tokenSettings.getSettings().get("settings.token.authorization-code-time-to-live"))
                .isEqualTo(Duration.ofSeconds(300));
        assertThat(tokenSettings.getIdTokenSignatureAlgorithm()).isNotNull();
        assertThat(tokenSettings.getAccessTokenFormat()).isNotNull();

        ClientSettings clientSettings = appClient.getClientSettings();
        assertThat(clientSettings.isRequireProofKey()).isTrue();
        assertThat(clientSettings.isRequireAuthorizationConsent()).isTrue();
    }

    /**
     * Runs the same browser-less login gate but against the bootstrapped
     * marketplace-web-client, which requires consent. Step 4 (authenticated authorize) is
     * followed by the rendered consent page POST before the code is returned.
     */
    // Set by consentGate: which branch the LAST flow took — the consent
    // page (fresh principal) or the direct-code 302 (recorded consent).
    // The #241 strengthening asserts both directions explicitly.
    private boolean consentPageRendered;

    private GateResult consentGate(String username, String password) throws Exception {
        RegisteredClient appClient = registeredClientRepository.findByClientId(APP_CLIENT_ID);
        assertThat(appClient)
                .as("the OAuth2ClientSecretInitializer must have bootstrapped the authoritative client")
                .isNotNull();
        assertThat(appClient.getClientSettings().isRequireAuthorizationConsent())
                .as("authoritative client must require consent (requireAuthorizationConsent=true)")
                .isTrue();

        String state = UUID.randomUUID().toString();
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));

        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + APP_CLIENT_ID
                + "&scope=openid%20profile"
                + "&state=" + state
                + "&redirect_uri=" + encode(APP_REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize -> login: %s", body(authorizeFirst)).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("login CSRF").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        // The official isAuthorizationConsentRequired (SAS 7.1.1) returns "no consent
        // needed" when the request scopes are exactly {openid}; requesting openid+profile
        // forces the consent screen for a fresh principal. If the (client, principal)
        // consent is already recorded, the authenticated authorize returns the code
        // directly (302); otherwise it renders the consent page as a 200 (the
        // OAuth2AuthorizationEndpointFilter forwards to DefaultConsentPage, no redirect).
        // Both paths are legitimate; we branch on the response.
        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        String authorizationCode;
        if (authorizeSecond.statusCode() == 302 && authorizeSecond.headers().firstValue("Location").orElse("")
                .contains("code=")) {
            authorizationCode = queryParam(authorizeSecond.headers().firstValue("Location").orElse(""), "code");
            consentPageRendered = false;
        } else {
            consentPageRendered = true;
            assertThat(authorizeSecond.statusCode())
                    .as("authorize authenticated must render the consent page, got: %s", body(authorizeSecond))
                    .isEqualTo(200);
            assertThat(authorizeSecond.body())
                    .as("consent page body: %s", body(authorizeSecond))
                    .contains("Consent required");

            // Submit approval. The official DefaultConsentPage form is bound to the consent
            // state generated by OAuth2AuthorizationConsentAuthenticationProvider ("Generated
            // authorization consent state"), which differs from the authorization request
            // state, so we reuse the hidden state/client_id rendered in the consent page.
            // One scope checkbox is posted per approved scope; the page has NO CSRF field
            // (SAS 7.1.1) and the POST is exempted from CSRF on the authorization matcher.
            String consentState = attributesFromConsentPage(authorizeSecond.body()).get("state");
            String consentClientId = attributesFromConsentPage(authorizeSecond.body()).get("client_id");
            assertThat(consentState).as("consent page hidden state").isNotBlank();
            assertThat(consentClientId).as("consent page hidden client_id").isEqualTo(APP_CLIENT_ID);

            HttpResponse<String> consentPost = postForm(AUTHORIZE_PATH,
                    "client_id=" + encode(consentClientId)
                            + "&state=" + encode(consentState)
                            + "&scope=openid"
                            + "&scope=profile",
                    sessionCookie);
            assertThat(consentPost.statusCode()).as("consent submit: %s", body(consentPost)).isEqualTo(302);
            String redirect = consentPost.headers().firstValue("Location").orElse("");
            assertThat(redirect).startsWith(APP_REDIRECT_URI).contains("code=");
            authorizationCode = queryParam(redirect, "code");
        }

        HttpResponse<String> tokenResponse = postFormWithBasicAuth(
                TOKEN_PATH,
                APP_CLIENT_ID,
                APP_CLIENT_SECRET,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(APP_REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(
                tokens.path("access_token").asString(),
                tokens.path("refresh_token").asString(),
                tokens.path("token_type").asString(),
                tokens.path("id_token").asString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String clientId, String clientSecret,
                                                       String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + credentials)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Executes the real browser-less login gate:
     * authorize (code + PKCE) -&gt; 302 /login -&gt; credentials POST -&gt; saved request
     * -&gt; authorization code -&gt; token exchange (client_secret_basic + code_verifier).
     */
    private GateResult loginGate(String username, String password) throws Exception {
        String state = UUID.randomUUID().toString();
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));

        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + state
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        // 1) Unauthenticated authorization request -> redirect to the login page
        //    (LoginUrlAuthenticationEntryPoint negotiated via Accept: text/html).
        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login: %s", body(authorizeFirst))
                .isEqualTo(302);
        assertThat(authorizeFirst.headers().firstValue("Location").orElse("")).contains(LOGIN_PATH);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).as("spring-session cookie expected").isNotBlank();

        // 2) Fetch the login form; the CSRF token is bound to the session.
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        // 3) Submit credentials -> redirect back to the saved authorization request.
        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        // 4) Re-issue the authorization request as an authenticated principal -> code.
        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond))
                .isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        assertThat(redirect).contains("code=");
        assertThat(redirect).contains("state=" + state);
        String authorizationCode = queryParam(redirect, "code");

        // 5) Exchange the code for tokens (client_secret_basic + PKCE verifier).
        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(
                tokens.path("access_token").asString(),
                tokens.path("refresh_token").asString(),
                tokens.path("token_type").asString(),
                tokens.path("id_token").asString());
    }

    private synchronized GateResult adminGate() throws Exception {
        if (adminGate == null) {
            adminGate = loginGate(ADMIN_USERNAME, PASSWORD);
        }
        return adminGate;
    }

    private void registerLoginGateClient() {
        if (registeredClientRepository.findByClientId(CLIENT_ID) != null) {
            return;
        }
        RegisteredClient loginGateClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientSecret("{noop}" + CLIENT_SECRET)
                .clientName("Login Gate Integration Test Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();
        registeredClientRepository.save(loginGateClient);
    }

    private void registerUser(String username, String role) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        UserDetails user = User.withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles(role)
                .build();
        userDetailsManager.createUser(user);
    }

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> attributesFromConsentPage(String pageBody) {
        Map<String, String> values = new java.util.HashMap<>();
        Matcher matcher = Pattern.compile("<input[^>]*type=\"hidden\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]+)\"")
                .matcher(pageBody);
        while (matcher.find()) {
            values.put(matcher.group(1), matcher.group(2));
        }
        return values;
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + credentials)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    private String sessionCookie(HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            Matcher matcher = SESSION_COOKIE.matcher(header);
            if (matcher.find()) {
                return matcher.group(1) + "=" + matcher.group(2);
            }
        }
        return null;
    }

    private String latestSessionCookie(HttpResponse<?> response, String fallback) {
        String cookie = sessionCookie(response);
        return cookie != null ? cookie : fallback;
    }

    private String csrfTokenFrom(String loginHtml) {
        Matcher matcher = CSRF_INPUT.matcher(loginHtml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = CSRF_INPUT_REVERSED.matcher(loginHtml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String jwtClaimsAsJson(String accessToken) {
        String[] parts = accessToken.split("\\.");
        assertThat(parts).hasSize(3);
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    private String queryParam(String location, String name) {
        String query = location.substring(location.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private static String randomCodeVerifier() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0, Math.min(500, response.body().length()));
    }

    private record GateResult(String accessToken, String refreshToken, String tokenType, String idToken) {
    }
}

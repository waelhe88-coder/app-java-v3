package com.marketplace.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.marketplace.MarketplaceApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard for <b>multi-replica readiness</b> (Phase-C of the gap
 * analysis — the replica decision is gated on load per
 * {@code load-test/README.md}, the correctness at N&gt;1 is this test's
 * contract): <b>two full application contexts against the SAME PostgreSQL and
 * Redis</b>, with every step of the flow below landing on a <i>different</i>
 * replica than the one that stored the state it reads.
 *
 * <p><b>Why this gap is real even though every channel is shared "by
 * design":</b> the design distributes state across five channels (HTTP session
 * &rarr; spring-session-data-redis with the indexed repository, SAS
 * authorizations/consent/clients &rarr; JDBC via V13, users &rarr;
 * {@code JdbcUserDetailsManager}, cache &rarr; Redis since the L11
 * serialization fix, signing keys &rarr; the shared keystore channel of
 * {@code keys/README.md}), but <b>no test ever exercised two instances over
 * the same data stores</b> — every integration test boots exactly one context.
 * A regression in any of those channels (a serializer change, a session
 * namespace drift, an in-memory fallback) would break horizontal scaling
 * while all single-instance tests stay green. This test pins the actual
 * cross-instance behavior, measured live first on two real JVMs
 * (2026-09-05, 16/16 probes):</p>
 *
 * <p><b>What is proven (each against the measured live behavior):</b></p>
 * <ol>
 *   <li><b>Rolling deploy</b> — the second context boots against the
 *       already-migrated database (Flyway validate-only; a validate failure
 *       fails the boot).</li>
 *   <li><b>Session sharing</b> — the authorization request <i>saved on
 *       replica A</i> (saved request + CSRF + fixation-rotated session id) is
 *       read and completed <i>on replica B</i>; the session is visible in
 *       Redis under {@code marketplace:session:sessions:<uuid>} (the SESSION
 *       cookie carries the base64 of that uuid — a DefaultCookieSerializer
 *       behavior measured live, not assumed).</li>
 *   <li><b>SAS state sharing</b> — the authorization code <i>issued by
 *       replica B</i> is exchanged for tokens <i>on replica A</i> (the JDBC
 *       {@code oauth2_authorization} row is the only possible source).</li>
 *   <li><b>JWT key sharing</b> — the token <i>minted and signed by replica
 *       A</i> passes replica B's decoder (issuer + audience + signature from
 *       the <i>shared JKS keystore</i> — the production channel for signing
 *       keys, exercised here instead of the dev-only ephemeral key that would
 *       make each replica sign differently).</li>
 *   <li><b>Cache sharing</b> — the entry cached <i>by replica A</i> is served
 *       <i>by replica B</i> after the database rows are deleted out from
 *       under the cache (only the shared Redis entry can answer) — the
 *       {@code ColdCacheRedisSerializationIntegrationTest} pattern applied
 *       across instances.</li>
 * </ol>
 *
 * <p><b>Setup.</b> Context A is the {@code @SpringBootTest} context on
 * {@code RANDOM_PORT} with real PostgreSQL (Flyway enabled,
 * {@code ddl-auto=none}) + Redis and {@code cache.type=redis} — the
 * {@code ColdCacheRedisSerializationIntegrationTest} shape. Context B is
 * launched manually in {@code @BeforeAll} through
 * {@link SpringApplicationBuilder} against the <i>same</i> containers (explicit
 * datasource/redis coordinates, passed as command-line args so they beat the
 * test profile), on its own random port, with the <i>same</i> keystore file:
 * a JKS generated once per test run with the JDK's own {@code keytool} (the
 * app requires a certificate entry — {@code SecurityConfig.jwkSource} reads
 * {@code KeyStore.getCertificate(alias)}). The confidential client is
 * registered through the official {@link RegisteredClientRepository} (shared
 * JDBC), the user through the official {@link UserDetailsManager} (shared
 * {@code auth_users}) — both fixtures land in the shared database and are
 * therefore visible to both replicas, exactly like a real deployment.</p>
 *
 * <p><b>Documented non-goals.</b> Scheduled jobs (two cleanups + the
 * resubmission sweep) run per-replica and are idempotent by design (SYSTEM.md
 * §5); Resilience4j limiter budgets are per-instance (N&times; aggregate —
 * the documented user-tuning gate); in-JVM Spring events never cross
 * replicas. None of those can regress this test's invariants.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The test profile overrides the cache type to 'simple' — force the
        // production type so both replicas exercise the real shared Redis path.
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=1h",
        // CI connection budget: the Integration workflow's shared PostgreSQL
        // service defaults to max_connections=100, and every cached module-test
        // context holds its own Hikari pool against it. Two more full contexts
        // at the default 20/5 pool pushed the run over that limit (measured on
        // main: FATAL "too many clients already" in a NEIGHBORING test class —
        // marginal, 2-of-3 runs green). The pool size is not part of any
        // invariant this guard proves — 5/1 for BOTH replicas keeps the layer's
        // marginal pressure (10 connections) below a single default context.
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MultiReplicaReadinessIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; connection details via RedisContainerConnectionDetailsFactory
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    private static final String USERNAME = "it-replica-user";
    private static final String PASSWORD = "it-replica-password";
    private static final String CLIENT_ID = "it-replica-client";
    private static final String CLIENT_SECRET = "it-replica-secret";
    private static final String REDIRECT_URI = "https://replica-it.example/callback";
    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";

    /** Replica B: a second full application context on its own random port. */
    private static ConfigurableApplicationContext replicaB;
    private static int portB;

    /** The shared JKS both replicas sign with (the production key channel). */
    private static Path keystoreFile;

    @DynamicPropertySource
    static void sharedKeystoreProperties(DynamicPropertyRegistry registry) {
        // Both replicas must sign with the SAME key — the production channel
        // (keys/README.md). Context A reads these lazily when its context
        // boots; context B receives the same values as command-line args.
        if (keystoreFile != null) {
            registry.add("marketplace.security.jwt.keystore.path", () -> keystoreFile.toString());
            registry.add("marketplace.security.jwt.keystore.password", () -> "itstore");
            registry.add("marketplace.security.jwt.keystore.alias", () -> "itjwt");
            registry.add("marketplace.security.jwt.keystore.key-password", () -> "itjwtkey");
        }
    }

    @BeforeAll
    static void startReplicaB() throws Exception {
        // The Testcontainers extension (BeforeAllCallback) has already started
        // the shared PostgreSQL + Redis. Generate the shared keystore first.
        keystoreFile = Files.createTempFile("multi-replica-it", ".jks");
        Files.delete(keystoreFile); // keytool refuses to overwrite an existing (empty) file
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString(),
                "-genkeypair", "-keyalg", "RSA", "-keysize", "2048", "-alias", "itjwt",
                "-keystore", keystoreFile.toString(), "-storetype", "JKS",
                "-storepass", "itstore", "-keypass", "itjwtkey",
                "-dname", "CN=multi-replica-it", "-validity", "30")
                .redirectErrorStream(true)
                .start();
        String keytoolOutput = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor()).as("keytool must generate the shared JKS: %s", keytoolOutput).isZero();

        // Command-line args are the highest-precedence property source: they
        // override the test profile's cache.type=simple / flyway disabled /
        // ddl create-drop and its localhost datasource, so replica B runs in
        // exactly the production-shaped configuration against the SAME stores.
        replicaB = new SpringApplicationBuilder(MarketplaceApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--spring.cache.type=redis",
                        // CI connection budget — same rationale as context A's
                        // properties: the shared service PG caps at 100
                        // connections and both replicas must stay light.
                        "--spring.datasource.hikari.maximum-pool-size=5",
                        "--spring.datasource.hikari.minimum-idle=1",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.data.redis.host=" + redis.getHost(),
                        "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--marketplace.security.jwt.keystore.path=" + keystoreFile,
                        "--marketplace.security.jwt.keystore.password=itstore",
                        "--marketplace.security.jwt.keystore.alias=itjwt",
                        "--marketplace.security.jwt.keystore.key-password=itjwtkey");
        portB = ((WebServerApplicationContext) replicaB).getWebServer().getPort();
        assertThat(portB).as("replica B must be listening on its own port").isPositive();
    }

    @AfterAll
    static void stopReplicaB() {
        if (replicaB != null) {
            replicaB.close();
        }
    }

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${local.server.port}")
    private int portA;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** FK parents (V2: provider_listings.provider_id → users). */
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();

    private void ensureSharedFixtures() {
        if (!userDetailsManager.userExists(USERNAME)) {
            UserDetails user = User.withUsername(USERNAME)
                    .password("{noop}" + PASSWORD)
                    .roles("ADMIN")
                    .build();
            userDetailsManager.createUser(user);
        }
        if (registeredClientRepository.findByClientId(CLIENT_ID) == null) {
            RegisteredClient replicaClient = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(CLIENT_ID)
                    .clientSecret("{noop}" + CLIENT_SECRET)
                    .clientName("Multi-Replica Readiness Test Client")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(REDIRECT_URI)
                    .scope("openid")
                    // Deterministic single branch: PKCE required (Gate B
                    // contract), consent not required — the consent page
                    // itself is exercised by the login-gate test.
                    .clientSettings(ClientSettings.builder()
                            .requireProofKey(true)
                            .requireAuthorizationConsent(false)
                            .build())
                    .build();
            registeredClientRepository.save(replicaClient);
        }
    }

    @Test
    void bothReplicasAreHealthyAgainstTheSameDataStores() throws Exception {
        // Rolling-deploy invariant: replica B booted in @BeforeAll against the
        // same already-migrated database (whichever context ran Flyway first,
        // the other validated). If validation failed, B's boot would have
        // failed the whole class. Both liveness endpoints must answer.
        assertThat(getJson(portA, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(getJson(portB, "/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    @Test
    void loginFlowSurvivesReplicaFailover() throws Exception {
        ensureSharedFixtures();

        // --- replica A starts the authorization (session created on A) ---
        String verifier = randomCodeVerifier();
        String authorizeUrl = "http://127.0.0.1:" + portA + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + UUID.randomUUID()
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + base64Url(sha256(verifier))
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(portA, authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize -> login: %s", body(authorizeFirst)).isEqualTo(302);
        String cookie = sessionCookie(authorizeFirst.headers().allValues("Set-Cookie"));

        // The session must live in the SHARED Redis (the channel the HTTP
        // session filter of BOTH replicas reads). The SESSION cookie value is
        // the base64 of the session id (DefaultCookieSerializer — measured
        // live, see class doc); the RedisIndexedSessionRepository key is
        // <namespace>:sessions:<uuid>.
        String sessionId = new String(Base64.getDecoder().decode(cookieValue(cookie)), StandardCharsets.UTF_8);
        assertThat(stringRedisTemplate.hasKey("marketplace:session:sessions:" + sessionId))
                .as("replica A's session is visible in shared Redis under marketplace:session:sessions:*")
                .isTrue();

        // --- replica B continues the login with replica A's cookie ---
        HttpResponse<String> loginPage = get(portB, "http://127.0.0.1:" + portB + LOGIN_PATH, cookie);
        assertThat(loginPage.statusCode()).as("login page on replica B: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("login CSRF rendered by replica B").isNotBlank();

        HttpResponse<String> loginPost = postForm(portB, LOGIN_PATH,
                "username=" + USERNAME + "&password=" + PASSWORD + "&_csrf=" + encode(csrfToken), cookie);
        assertThat(loginPost.statusCode()).as("login on replica B: %s", body(loginPost)).isEqualTo(302);
        // THE session-sharing proof: the redirect target is the request SAVED
        // BY REPLICA A (Spring Security's RequestCache). Without a shared
        // session, replica B would fall back to "/". The absolute URL points
        // at replica A — the load-balancer-sent-me-elsewhere case continues
        // on B by rewriting the host.
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).contains("/oauth2/authorize");
        cookie = latestSessionCookie(loginPost.headers().allValues("Set-Cookie"), cookie);
        // Session-fixation protection rotated the id ON REPLICA B — the new
        // cookie is issued by B and must be usable on B.
        assertThat(latestSessionCookie(loginPost.headers().allValues("Set-Cookie"), null)).isNotBlank();

        // --- replica B completes the authorization ---
        String authorizeOnB = savedRequest.replace("127.0.0.1:" + portA, "127.0.0.1:" + portB);
        HttpResponse<String> authorizeSecond = get(portB, authorizeOnB, cookie);
        assertThat(authorizeSecond.statusCode())
                .as("authenticated authorize on replica B: %s", body(authorizeSecond))
                .isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI).contains("code=");
        String authorizationCode = queryParam(redirect, "code");

        // --- replica A exchanges replica B's code (shared JDBC SAS state) ---
        HttpResponse<String> tokenResponse = postFormWithBasicAuth(portA, TOKEN_PATH, CLIENT_ID, CLIENT_SECRET,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + encode(verifier));
        assertThat(tokenResponse.statusCode()).as("token on replica A with replica B's code: %s", body(tokenResponse))
                .isEqualTo(200);
        assertThat(tokenResponse.body()).contains("\"access_token\"");
        String accessToken = jsonValue(tokenResponse.body(), "access_token");

        // --- replica B validates replica A's token (shared JKS keystore) ---
        // A real ROLE_ADMIN-gated route: 200 proves the token passed replica
        // B's decoder (issuer + audience + signature from the shared JKS) and
        // the roles converter. 401 = signature/issuer rejected; 403 = roles
        // not mapped.
        HttpResponse<String> apiResponse = getWithBearer(portB, "/api/v1/admin/users", accessToken);
        assertThat(apiResponse.statusCode()).as("replica B accepts replica A's token: %s", body(apiResponse))
                .isEqualTo(200);
    }

    @Test
    void cacheEntryPutByReplicaAIsServedByReplicaB() throws Exception {
        // Cold start (CodeRabbit #241 / the ColdCache pattern): a warm entry
        // from an earlier test method would answer the first request from the
        // cache — the PUT would never happen and the cross-replica proof
        // would collapse silently (both assertions still pass).
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });

        seedListingRow();

        // Replica A: cold miss -> the @Cacheable PUT into shared Redis.
        HttpResponse<String> first = getJson(portA, "/api/v1/listings?page=0&size=10");
        assertThat(first.statusCode()).as("cold browse on replica A: %s", body(first)).isEqualTo(200);

        // Remove the database rows behind the cache's back: raw JDBC fires no
        // AFTER_COMMIT relay, so the shared cache entry survives. Replica B
        // can now only answer from replica A's Redis entry.
        jdbcTemplate.update("DELETE FROM provider_listings");

        HttpResponse<String> second = getJson(portB, "/api/v1/listings?page=0&size=10");
        assertThat(second.statusCode()).as("replica B serves replica A's cache entry: %s", body(second))
                .isEqualTo(200);
        assertThat(second.body())
                .as("the page replica B served is replica A's cached content")
                .contains("Replica Readiness Garden Villa");
    }

    private void seedListingRow() {
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                PROVIDER_ID, "replica-provider@example.com",
                "replica-provider@example.com", "Replica Readiness Provider");
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                LISTING_ID, PROVIDER_ID, "Replica Readiness Garden Villa",
                "Cross-instance cache proof listing", "home", 100_00L);
    }

    // -- HTTP helpers (the AuthorizationServerLoginGateIntegrationTest shape) --

    /** GET with {@code Accept: application/json} — REST/actuator endpoints (an
     *  HTML Accept on these yields 406, the CI round-2 finding). */
    private HttpResponse<String> getJson(int port, String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + (path.startsWith("/") ? path : "/" + path)))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** GET with the browser Accept — login/authorize pages (HTML endpoints). */
    private HttpResponse<String> get(int port, String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.startsWith("http") ? url : "http://127.0.0.1:" + port + url))
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(int port, String path, String accessToken) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(int port, String path, String form, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(int port, String path, String clientId, String clientSecret,
                                                       String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json")
                        .header("Authorization", "Basic " + credentials)
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sessionCookie(List<String> setCookies) {
        for (String value : setCookies) {
            if (value.startsWith("SESSION=") || value.startsWith("JSESSIONID=")) {
                return value.split(";", 2)[0];
            }
        }
        throw new AssertionError("no session cookie in " + setCookies);
    }

    private static String latestSessionCookie(List<String> setCookies, String fallback) {
        for (String value : setCookies) {
            if (value.startsWith("SESSION=") || value.startsWith("JSESSIONID=")) {
                return value.split(";", 2)[0];
            }
        }
        return fallback;
    }

    private static String cookieValue(String cookie) {
        return cookie.split("=", 2)[1];
    }

    private static String csrfTokenFrom(String html) {
        java.util.regex.Matcher forward = java.util.regex.Pattern
                .compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        if (forward.find()) {
            return forward.group(1);
        }
        java.util.regex.Matcher reversed = java.util.regex.Pattern
                .compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"").matcher(html);
        return reversed.find() ? reversed.group(1) : "";
    }

    private static String queryParam(String url, String name) {
        String query = url.split("\\?", 2)[1];
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no " + name + " in " + url);
    }

    private static String jsonValue(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            throw new AssertionError("no " + key + " in " + json);
        }
        return m.group(1);
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
        return response.body() == null ? "" : response.body().substring(0, Math.min(400, response.body().length()));
    }
}

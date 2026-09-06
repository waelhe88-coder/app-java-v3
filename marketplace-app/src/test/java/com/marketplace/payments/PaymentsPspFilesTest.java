package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the real payment channel (roadmap B3 / gap G-PROD-3): pins
 * the wiring that makes the capability real and honestly inert. Same class
 * of latent "config that lies" defect as {@code MediaFilesTest}: nothing at
 * runtime rejects a renamed env placeholder, a dropped SDK entry, or a
 * second class importing the Stripe SDK — only a pinned test keeps them
 * honest.
 *
 * <p>Pinned contract:
 * <ul>
 *   <li>application.yml — the two PAYMENTS_STRIPE_* provider-gate
 *       placeholders exist (empty defaults = channel inert, processIntent
 *       keeps the legacy behavior, Stripe webhook answers 503 SU-001)</li>
 *   <li>parent pom — stripe-java managed in dependencyManagement with the
 *       documented Exception #11 and the version property</li>
 *   <li>marketplace-payments pom — the ONLY module consuming the SDK</li>
 *   <li>SDK isolation — StripePspChannel is the single production class
 *       importing com.stripe (same rule as S3MediaStorage for the AWS SDK)</li>
 *   <li>V33 — psp_intent_id on payment_intents + the Envers audit-table debt
 *       closed (refunded_amount_cents / starts_at / ends_at added to the
 *       _aud tables) + the webhook-resolution index</li>
 *   <li>controller — the raw-body Stripe endpoint reads the
 *       Stripe-Signature header (official recipe) and decodes the payload
 *       UTF-8 from raw bytes</li>
 * </ul>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback.
 */
class PaymentsPspFilesTest {

    private Path repoRoot() {
        Path fromModule = Paths.get("../");
        if (Files.isDirectory(fromModule.resolve(".github"))) {
            return fromModule;
        }
        return Paths.get(".");
    }

    private String read(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative));
    }

    @Test
    void applicationYmlCarriesTheProviderGatePlaceholders() throws IOException {
        String yml = read("marketplace-app/src/main/resources/application.yml");
        Map<String, Object> root = new Yaml().load(yml);

        @SuppressWarnings("unchecked")
        Map<String, Object> marketplace = (Map<String, Object>) root.get("marketplace");
        assertThat(marketplace).as("marketplace section must exist").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> payments = (Map<String, Object>) marketplace.get("payments");
        assertThat(payments).as("marketplace.payments section must exist").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> psp = (Map<String, Object>) payments.get("psp");
        assertThat(psp).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> stripe = (Map<String, Object>) psp.get("stripe");
        assertThat(stripe).isNotNull();
        assertThat(stripe.get("api-key"))
                .as("PAYMENTS_STRIPE_API_KEY placeholder (empty default = channel inert)")
                .isEqualTo("${PAYMENTS_STRIPE_API_KEY:}");
        assertThat(stripe.get("webhook-secret"))
                .isEqualTo("${PAYMENTS_STRIPE_WEBHOOK_SECRET:}");
    }

    @Test
    void parentPomManagesTheStripeSdkAsDocumentedException() throws IOException {
        String pom = read("pom.xml");
        assertThat(pom)
                .as("Exception #11 must stay documented next to the managed entry")
                .contains("Exception #11: Stripe Java SDK")
                .contains("<artifactId>stripe-java</artifactId>")
                .contains("<stripe.version>33.4.1</stripe.version>");
    }

    @Test
    void paymentsPomIsTheOnlySdkConsumer() throws IOException {
        assertThat(read("marketplace-payments/pom.xml"))
                .contains("<groupId>com.stripe</groupId>")
                .contains("<artifactId>stripe-java</artifactId>");

        try (Stream<Path> modulePoms = Files.list(repoRoot()).filter(p -> {
            String name = p.getFileName().toString();
            return name.startsWith("marketplace-") && Files.isDirectory(p)
                    && !name.equals("marketplace-payments");
        })) {
            for (Path module : modulePoms.toList()) {
                Path pom = module.resolve("pom.xml");
                if (!Files.exists(pom)) {
                    continue;
                }
                // read directly — the listed path is already repo-rooted
                assertThat(Files.readString(pom))
                        .as("only marketplace-payments may consume the Stripe SDK: " + module.getFileName())
                        .doesNotContain("<artifactId>stripe-java</artifactId>");
            }
        }
    }

    @Test
    void stripeSdkImportsStayConfinedToTheChannelAdapter() throws IOException {
        Path paymentsMain = repoRoot().resolve("marketplace-payments/src/main/java/com/marketplace/payments");
        try (Stream<Path> sources = Files.walk(paymentsMain)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                // read directly — walked paths are already repo-rooted
                String content = Files.readString(source);
                if (source.getFileName().toString().equals("StripePspChannel.java")) {
                    assertThat(content)
                            .as("the channel adapter owns the Stripe SDK boundary")
                            .contains("import com.stripe.");
                } else {
                    assertThat(content)
                            .as("com.stripe must stay confined to StripePspChannel — found import in "
                                    + source.getFileName())
                            .doesNotContain("import com.stripe.");
                }
            }
        }
    }

    @Test
    void migrationV33OwnsTheLinkAndClosesTheAudTableDebt() throws IOException {
        String sql = read("marketplace-app/src/main/resources/db/migration/V33__payments_psp_channel.sql");
        assertThat(sql)
                .as("the psp_intent_id link column (webhook resolution path)")
                .contains("alter table payment_intents add column if not exists psp_intent_id");
        assertThat(sql)
                .as("webhook resolution index over linked rows only")
                .contains("idx_payment_intents_psp")
                .contains("where psp_intent_id is not null");
        assertThat(sql)
                .as("Envers audit debt from V26/V27 must stay closed")
                .contains("alter table payment_intents_aud add column if not exists psp_intent_id")
                .contains("alter table payment_intents_aud add column if not exists refunded_amount_cents")
                .contains("alter table payments_aud add column if not exists refunded_amount_cents")
                .contains("alter table bookings_aud add column if not exists starts_at")
                .contains("alter table bookings_aud add column if not exists ends_at");
    }

    @Test
    void migrationV36MakesThePspLinkOneToOneAtTheDatabaseLevel() throws IOException {
        // CodeRabbit #241 (finding on V33): the entity-side guard alone lets
        // two rows hold the same non-null psp_intent_id, and the webhook
        // resolver's findByPspIntentId contract requires a single row.
        String sql = read("marketplace-app/src/main/resources/db/migration/V36__unique_psp_intent_link.sql");
        assertThat(sql)
                .as("the non-unique V33 index is retired in favor of the unique one")
                .contains("drop index if exists idx_payment_intents_psp");
        assertThat(sql)
                .as("the link is unique among linked rows only (NULL stays free)")
                .contains("create unique index if not exists uq_payment_intents_psp_intent_id")
                .contains("on payment_intents (psp_intent_id)")
                .contains("where psp_intent_id is not null");
    }

    @Test
    void controllerCarriesTheRawBodyStripeEndpoint() throws IOException {
        String controller = read("marketplace-payments/src/main/java/com/marketplace/payments/PaymentsController.java");
        assertThat(controller)
                .as("the official recipe: raw request body + Stripe-Signature header")
                .contains("@PostMapping(path = \"/webhooks/stripe\"")
                .contains("@RequestBody byte[] rawPayload")
                .contains("@RequestHeader(\"Stripe-Signature\")")
                .as("payload decoded UTF-8 from raw bytes so the signed bytes reach the verifier intact")
                .contains("StandardCharsets.UTF_8");
    }

    @Test
    void legacyWebhookEndpointStaysByteCompatible() throws IOException {
        String controller = read("marketplace-payments/src/main/java/com/marketplace/payments/PaymentsController.java");
        assertThat(controller)
                .as("the legacy HMAC channel keeps its exact contract")
                .contains("@PostMapping(\"/webhooks/{provider}\")")
                .contains("X-Webhook-Signature");
        String service = read("marketplace-payments/src/main/java/com/marketplace/payments/PaymentsService.java");
        assertThat(service)
                .as("the legacy HMAC validation stays on the processWebhookEvent path")
                .contains("paymentWebhookSecurity.validateSignature(eventId + eventType, signature)");
    }
}

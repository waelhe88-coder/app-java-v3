package com.marketplace.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Set;

/**
 * Type-safe configuration for the media module (constructor binding, primed with
 * empty {@link DefaultValue} sections per the house binding rule — AGENTS.md).
 *
 * <p>The storage section mirrors the Cloudflare R2 credential set exactly as the
 * official R2 presigned-URL documentation prescribes (cached at
 * {@code scripts/media-doc-verify/r2-presigned-urls.md}):
 * <pre>
 *   endpoint:  https://&lt;ACCOUNT_ID&gt;.r2.cloudflarestorage.com
 *   region:    auto        ("Required by SDK but not used by R2")
 *   bucket:    the R2 bucket name
 *   accessKey / secretKey: R2 API token credentials
 * </pre>
 * Any S3-compatible endpoint works (AWS S3 included) — the provider choice is a
 * deployment-time environment decision, not a code decision.
 *
 * <p>When any storage credential is blank the module is inert by design: no
 * storage beans are created (see {@code MediaStorageConfiguredCondition}) and
 * every media endpoint answers 503 SU-001 — the same graceful-provider-gate
 * pattern as {@code spring.mail.host} / the MAIL placeholders (SYSTEM.md §15
 * debt item 3). No fail-fast: media is an optional product capability.
 */
@ConfigurationProperties(prefix = "marketplace.media")
public record MediaProperties(
        @DefaultValue Storage storage,
        @DefaultValue Limits limits
) {

    public record Storage(
            @DefaultValue("") String endpoint,
            @DefaultValue("auto") String region,
            @DefaultValue("") String bucket,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            /**
             * Explicit opt-in for cleartext (http://) storage endpoints — local
             * emulator deployments only. The production constructor of
             * {@link S3MediaStorage} rejects non-HTTPS endpoints unless this
             * flag is set (CWE-319, CodeRabbit #241).
             */
            @DefaultValue("false") boolean allowInsecureEndpoint
    ) {}

    public record Limits(
            /** Hard per-object upload cap in bytes. */
            @DefaultValue("10485760") long maxUploadBytes,
            /** Server-side allowlist — anything else is rejected before any URL is signed. */
            @DefaultValue({"image/jpeg", "image/png", "image/webp", "image/gif"}) Set<String> allowedContentTypes,
            /**
             * Presigned URL lifetime for both the upload (PUT) and download (GET)
             * URLs. Official bounds: 1 second to 7 days (R2 doc + S3Presigner
             * javadoc, "cannot be longer than 7 days").
             */
            @DefaultValue("15m") Duration presignTtl
    ) {}
}

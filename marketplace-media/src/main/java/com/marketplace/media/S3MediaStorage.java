package com.marketplace.media;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.net.URI;
import java.time.Duration;

/**
 * The S3-compatible storage channel — the ONLY class in this module that talks
 * AWS SDK. Official evidence (cached under {@code scripts/media-doc-verify/}):
 * <ul>
 *   <li>R2 presigned-URL doc: "Presigned URLs are generated server-side with no
 *       communication with R2, requiring only your R2 API credentials and an
 *       implementation of the AWS Signature Version 4 signing algorithm" —
 *       presigning is local computation, never a network call.</li>
 *   <li>S3Presigner javadoc (s3-2.54.13-sources.jar): presigned requests are
 *       valid for the configured "signature duration", at most 7 days; browser-
 *       compatible presigned requests need nothing but a host header.</li>
 *   <li>R2 doc region note: {@code region: "auto" // Required by SDK but not
 *       used by R2}.</li>
 * </ul>
 *
 * <p>Content-type pinning: the declared {@code contentType} is part of the
 * signed PutObjectRequest, so a client cannot upload bytes of a different type
 * under the issued URL without breaking the signature (the storage rejects it).
 */
final class S3MediaStorage implements AutoCloseable {

    private final S3Presigner presigner;
    private final S3Client client;
    private final String bucket;
    private final Duration presignTtl;
    private final SdkHttpClient httpClient;

    /**
     * Production constructor — builds the presigner, the client and its JDK-based
     * HTTP implementation from bound properties. Used by {@code MediaConfig}.
     */
    S3MediaStorage(MediaProperties.Storage storage, Duration presignTtl) {
        this.httpClient = UrlConnectionHttpClient.builder().build();
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
        var region = Region.of(storage.region());
        var endpoint = URI.create(storage.endpoint());
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !storage.allowInsecureEndpoint()) {
            // Cleartext S3 endpoints transmit SigV4 credentials and object bytes
            // unencrypted (CWE-319, CodeRabbit #241). HTTP is an explicit
            // per-deployment opt-in reserved for local emulators.
            throw new IllegalStateException(
                    "marketplace.media.storage.endpoint must use https (got: "
                            + storage.endpoint() + ") — set marketplace.media.storage.allow-insecure-endpoint=true"
                            + " only for a local non-production emulator");
        }
        // Path-style addressing ({endpoint}/{bucket}/{key}) is forced explicitly:
        // the SDK default is virtual-host style (S3Configuration sources,
        // DEFAULT_PATH_STYLE_ACCESS_ENABLED = false), which requires wildcard
        // DNS for the bucket subdomain. Path-style URLs are deterministic and
        // work on every S3-compatible endpoint including R2.
        var addressing = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        this.presigner = S3Presigner.builder()
                .region(region)
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .serviceConfiguration(addressing)
                .build();
        this.client = S3Client.builder()
                .region(region)
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .httpClient(this.httpClient)
                .serviceConfiguration(addressing)
                .build();
        this.bucket = storage.bucket();
        this.presignTtl = presignTtl;
    }

    /**
     * Test constructor — collaborators injected (real presigner against a fake
     * endpoint still works offline; a mocked client for HeadObject tests).
     */
    S3MediaStorage(S3Presigner presigner, S3Client client, String bucket, Duration presignTtl) {
        this.presigner = presigner;
        this.client = client;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
        this.httpClient = null;
    }

    /**
     * Presigns a single-object PUT for the given key. Pure signing — no network.
     */
    String presignUpload(String objectKey, String contentType) {
        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presign = PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(putObject)
                .build();
        return presigner.presignPutObject(presign).url().toString();
    }

    /**
     * Presigns a single-object GET for the given key. Pure signing — no network.
     */
    String presignDownload(String objectKey) {
        GetObjectRequest getObject = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .getObjectRequest(getObject)
                .build();
        return presigner.presignGetObject(presign).url().toString();
    }

    /**
     * Verifies via HeadObject that the uploaded object exists with exactly the
     * declared content type and size. This is the single network call of the
     * whole upload flow — the anti-forgery gate that stops a client from
     * "confirming" an upload it never performed.
     */
    boolean verifyUploaded(String objectKey, String contentType, long sizeBytes) {
        HeadObjectResponse head;
        try {
            head = client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (RuntimeException ex) {
            // NoSuchKey, 403 on missing object, connectivity — all mean "not verifiable"
            return false;
        }
        return sizeEquals(head, sizeBytes) && contentTypeEquals(head, contentType);
    }

    private static boolean sizeEquals(HeadObjectResponse head, long sizeBytes) {
        return head.contentLength() != null && head.contentLength() == sizeBytes;
    }

    private static boolean contentTypeEquals(HeadObjectResponse head, String contentType) {
        return head.contentType() != null && head.contentType().equalsIgnoreCase(contentType);
    }

    /**
     * Best-effort object removal (owner delete). Storage-side failure is logged
     * by the caller and never fails the API call — bucket lifecycle rules own
     * orphan cleanup.
     */
    void deleteObject(String objectKey) {
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build());
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
        if (httpClient != null) {
            httpClient.close();
        }
    }
}

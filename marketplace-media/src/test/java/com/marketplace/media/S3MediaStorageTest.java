package com.marketplace.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the S3-compatible channel with a REAL presigner against a fake
 * endpoint — official R2 documentation: "Presigned URLs are generated
 * server-side with no communication with R2", so signing is pure local
 * computation and needs no network. The HeadObject verification path uses a
 * mocked client.
 */
@ExtendWith(MockitoExtension.class)
class S3MediaStorageTest {

    private static final String ENDPOINT = "https://storage.example.local";
    private static final String BUCKET = "media-bucket";

    private S3Presigner realPresigner() {
        return S3Presigner.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(ENDPOINT))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access", "test-secret")))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Mock
    private S3Client client;

    @Test
    void presignUpload_signsKeyContentTypeAndExpiry() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15));
            String url = storage.presignUpload("listings/abc/photo.jpg", "image/jpeg");

            assertTrue(url.startsWith(ENDPOINT + "/" + BUCKET + "/listings/abc/photo.jpg"),
                    "URL must address the exact object: " + url);
            assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), "SigV4 must be used");
            assertTrue(url.contains("X-Amz-SignedHeaders="), "signed headers must be present");
            assertTrue(url.contains("X-Amz-Expires=900"), "15 minutes = 900 seconds");
            assertTrue(url.contains("X-Amz-Signature="), "URL carries the signature");
        }
    }

    @Test
    void presignDownload_addressesSameObjectForGet() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15));
            String url = storage.presignDownload("listings/abc/photo.jpg");

            assertTrue(url.startsWith(ENDPOINT + "/" + BUCKET + "/listings/abc/photo.jpg"));
            assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        }
    }

    @Test
    void verifyUploaded_trueWhenTypeAndSizeMatch() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15));
            when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder()
                            .contentLength(2048L)
                            .contentType("image/jpeg")
                            .build());

            assertTrue(storage.verifyUploaded("k", "image/jpeg", 2048L));
        }
    }

    @Test
    void verifyUploaded_falseWhenSizeMismatches() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15));
            when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder()
                            .contentLength(999L)
                            .contentType("image/jpeg")
                            .build());

            assertFalse(storage.verifyUploaded("k", "image/jpeg", 2048L));
        }
    }

    @Test
    void verifyUploaded_falseWhenTypeMismatches() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15));
            when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder()
                            .contentLength(2048L)
                            .contentType("application/octet-stream")
                            .build());

            assertFalse(storage.verifyUploaded("k", "image/jpeg", 2048L));
        }
    }

    @Test
    void verifyUploaded_falseWhenObjectMissingOrUnreachable() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15));
            when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                    .thenThrow(new RuntimeException("NoSuchKey"));

            assertFalse(storage.verifyUploaded("k", "image/jpeg", 2048L));
        }
    }

    @Test
    void deleteObject_delegatesToTheClient() {
        try (S3Presigner presigner = realPresigner()) {
            S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15));

            storage.deleteObject("listings/abc/photo.jpg");

            verify(client).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
        }
    }

    @Test
    void close_shutsDownPresignerAndClient() {
        S3Presigner presigner = org.mockito.Mockito.mock(S3Presigner.class);
        S3MediaStorage storage = new S3MediaStorage(presigner, client, BUCKET, Duration.ofMinutes(15));

        storage.close();

        verify(presigner).close();
        verify(client).close();
    }

    private static MediaProperties.Storage storage(String endpoint, boolean allowInsecure) {
        return new MediaProperties.Storage(endpoint, "auto", BUCKET, "test-access", "test-secret", allowInsecure);
    }

    @Test
    void productionConstructor_rejectsCleartextEndpoint() {
        // CWE-319 / CodeRabbit #241: an http:// endpoint carries SigV4
        // credentials and object bytes in the clear — the production
        // constructor must refuse it before any client or presigner is built.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new S3MediaStorage(storage("http://localhost:4566", false), Duration.ofMinutes(15)));

        assertTrue(thrown.getMessage().contains("must use https"));
        assertTrue(thrown.getMessage().contains("http://localhost:4566"));
    }

    @Test
    void productionConstructor_acceptsHttpsEndpoint() {
        // The honest default: https endpoints build normally.
        try (S3MediaStorage storage = new S3MediaStorage(storage("https://media.example.local", false),
                Duration.ofMinutes(15))) {
            assertNotNull(storage);
        }
    }

    @Test
    void productionConstructor_allowsCleartextOnlyWithExplicitOptIn() {
        // The explicit local-emulator escape hatch (allow-insecure-endpoint)
        // is the only way an http endpoint builds.
        try (S3MediaStorage storage = new S3MediaStorage(storage("http://localhost:4566", true),
                Duration.ofMinutes(15))) {
            assertNotNull(storage);
        }
    }
}

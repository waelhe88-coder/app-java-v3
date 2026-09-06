package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private MediaAssetRepository repository;
    @Mock
    private ObjectProvider<S3MediaStorage> storageProvider;
    @Mock
    private S3MediaStorage storage;
    @Mock
    private ListingPriceProvider listingPriceProvider;
    @Mock
    private ProviderLookupPort providerLookupPort;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private Authentication authentication;

    private MediaService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MediaService(repository, storageProvider, mediaProperties(),
                listingPriceProvider, providerLookupPort, currentUserProvider);
    }

    private MediaProperties mediaProperties() {
        return new MediaProperties(
                new MediaProperties.Storage("", "auto", "", "", "", false),
                new MediaProperties.Limits(10_485_760L,
                        Set.of("image/jpeg", "image/png", "image/webp", "image/gif"),
                        Duration.ofMinutes(15)));
    }

    private void mockOwner() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", userId)));
    }

    private MediaAsset pendingAsset() {
        return MediaAsset.create(listingId, providerId, "listings/" + listingId + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 2048L, 1);
    }

    @Test
    void requestUpload_withoutStorageConfigured_answers503() {
        when(storageProvider.getIfAvailable()).thenReturn(null);

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> service.requestUpload(listingId, "image/jpeg", 1024L, authentication));
        assertEquals(503, ex.getStatusCode().value());
    }

    @Test
    void requestUpload_withUnsupportedContentType_rejectsBeforeSigning() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);

        assertThrows(BadRequestException.class,
                () -> service.requestUpload(listingId, "video/mp4", 1024L, authentication));
        verify(storage, never()).presignUpload(any(), any());
    }

    @Test
    void requestUpload_withOversize_rejectsBeforeSigning() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);

        assertThrows(BadRequestException.class,
                () -> service.requestUpload(listingId, "image/jpeg", 10_485_761L, authentication));
        verify(storage, never()).presignUpload(any(), any());
    }

    @Test
    void requestUpload_byNonOwner_isDenied() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 1000L));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", UUID.randomUUID())));

        assertThrows(AccessDeniedException.class,
                () -> service.requestUpload(listingId, "image/jpeg", 1024L, authentication));
        verify(repository, never()).save(any());
    }

    @Test
    void requestUpload_byOwner_returnsPresignedView() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 1000L));
        mockOwner();
        when(repository.countByListingId(listingId)).thenReturn(0L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.presignUpload(any(), eq("image/jpeg"))).thenReturn("https://storage.example/signed-put");

        var view = service.requestUpload(listingId, "IMAGE/JPEG", 2048L, authentication);

        assertEquals("https://storage.example/signed-put", view.uploadUrl());
        assertEquals(Duration.ofMinutes(15), view.urlLifetime());
        // server-generated key: listings/{listingId}/{uuid}.jpg — extension from the normalized type
        assertEquals("jpg", view.objectKey().split("/")[2].split("\\.")[1]);
        assertTrue(view.objectKey().startsWith("listings/" + listingId + "/"));
        verify(storage).presignUpload(view.objectKey(), "image/jpeg");
    }

    @Test
    void requestUpload_forMissingListing_throwsNotFound() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(listingPriceProvider.getListingInfo(listingId))
                .thenThrow(new ResourceNotFoundException("Listing", listingId));

        assertThrows(ResourceNotFoundException.class,
                () -> service.requestUpload(listingId, "image/jpeg", 1024L, authentication));
    }

    @Test
    void confirmUpload_whenStorageVerifyFails_staysPending() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        mockOwner();
        when(storage.verifyUploaded(asset.getObjectKey(), "image/jpeg", 2048L)).thenReturn(false);

        assertThrows(BadRequestException.class,
                () -> service.confirmUpload(asset.getId(), authentication));
        assertEquals(MediaAssetStatus.PENDING_UPLOAD, asset.getStatus());
    }

    @Test
    void confirmUpload_whenVerified_marksUploaded() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        mockOwner();
        when(storage.verifyUploaded(asset.getObjectKey(), "image/jpeg", 2048L)).thenReturn(true);
        when(storage.presignDownload(asset.getObjectKey())).thenReturn("https://storage.example/signed-get");

        var view = service.confirmUpload(asset.getId(), authentication);

        assertEquals("UPLOADED", view.status());
        assertEquals("https://storage.example/signed-get", view.downloadUrl());
    }

    @Test
    void confirmUpload_byNonOwner_isDenied() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", UUID.randomUUID())));

        assertThrows(AccessDeniedException.class,
                () -> service.confirmUpload(asset.getId(), authentication));
        assertEquals(MediaAssetStatus.PENDING_UPLOAD, asset.getStatus());
    }

    @Test
    void confirmUpload_byAdmin_bypassesOwnership() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        lenient().when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(storage.verifyUploaded(asset.getObjectKey(), "image/jpeg", 2048L)).thenReturn(true);
        when(storage.presignDownload(asset.getObjectKey())).thenReturn("https://storage.example/signed-get");

        var view = service.confirmUpload(asset.getId(), authentication);
        assertEquals("UPLOADED", view.status());
    }

    @Test
    void listByListing_onlyReturnsUploadedPresigned() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset uploaded = pendingAsset();
        uploaded.markUploaded();
        when(repository.findByListingIdAndStatusOrderByPositionAsc(listingId, MediaAssetStatus.UPLOADED))
                .thenReturn(java.util.List.of(uploaded));
        when(storage.presignDownload(uploaded.getObjectKey())).thenReturn("https://storage.example/signed-get");

        var views = service.listByListing(listingId);

        assertEquals(1, views.size());
        assertEquals("https://storage.example/signed-get", views.get(0).downloadUrl());
    }

    @Test
    void delete_removesRecordAndBestEffortObject() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        mockOwner();

        service.delete(asset.getId(), authentication);

        verify(repository).delete(asset);
        verify(storage).deleteObject(asset.getObjectKey());
    }

    @Test
    void delete_whenStorageRemovalFails_isStillAcknowledged() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        mockOwner();
        doThrow(new RuntimeException("storage down")).when(storage).deleteObject(asset.getObjectKey());

        service.delete(asset.getId(), authentication);

        verify(repository).delete(asset);
    }
}

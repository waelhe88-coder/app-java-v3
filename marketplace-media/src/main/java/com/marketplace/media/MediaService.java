package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Listing media business logic — the provider-owned half of roadmap item B1
 * (G-PROD-1): issue a presigned upload URL bound to a server-generated object
 * key, verify the object after upload, expose presigned read URLs.
 *
 * <p>Ownership follows the exact house pattern of {@code CatalogService.verifyOwnership}:
 * the listing is resolved through the existing {@link ListingPriceProvider} port
 * (implemented by catalog), the provider record through {@link ProviderLookupPort},
 * and the current user through {@link CurrentUserProvider}. The only shared
 * addition is {@link ServiceUnavailableException} — the house pattern for
 * problem-detail exceptions (ResourceNotFound/BadRequest/Conflict).
 *
 * <p>Observation policy (layer 6): commands only — {@code media.upload.request},
 * {@code media.upload.confirm}, {@code media.asset.delete}. Reads are measured by
 * the framework's own {@code http.server.requests}.
 */
@Service
@Transactional
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** Server-controlled extension mapping — the client never touches the key. */
    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private final MediaAssetRepository mediaAssetRepository;
    private final ObjectProvider<S3MediaStorage> storage;
    private final MediaProperties properties;
    private final ListingPriceProvider listingPriceProvider;
    private final ProviderLookupPort providerLookupPort;
    private final CurrentUserProvider currentUserProvider;

    public MediaService(MediaAssetRepository mediaAssetRepository,
                        ObjectProvider<S3MediaStorage> storage,
                        MediaProperties properties,
                        ListingPriceProvider listingPriceProvider,
                        ProviderLookupPort providerLookupPort,
                        CurrentUserProvider currentUserProvider) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.storage = storage;
        this.properties = properties;
        this.listingPriceProvider = listingPriceProvider;
        this.providerLookupPort = providerLookupPort;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Issues a presigned PUT URL for a new asset of the given listing. Validates
     * the type allowlist and size cap BEFORE anything is signed, resolves the
     * listing, and verifies the caller owns it.
     */
    @Observed(name = "media.upload.request")
    @PreAuthorize("hasRole('PROVIDER')")
    public MediaUploadView requestUpload(UUID listingId, String contentType,
                                         long sizeBytes, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        String normalizedType = normalizeContentType(contentType);
        validateContentType(normalizedType);
        validateSize(sizeBytes);

        ListingPriceProvider.ListingInfo listing = listingPriceProvider.getListingInfo(listingId);
        verifyListingOwnership(listing.providerId(), authentication);

        // Display-position allocation must be atomic per listing (CodeRabbit
        // #241): countByListingId()+1 inside a transaction does NOT serialize
        // concurrent transactions — two uploads can read the same count and
        // persist the same position. The advisory transaction lock below is
        // held until the surrounding transaction commits, so per-listing
        // allocations are serialized in the database (hashtextextended maps
        // the listing UUID text to a single bigint lock key, PG13+).
        mediaAssetRepository.lockListingPositionAllocation(listingId.toString());

        String objectKey = buildObjectKey(listingId, normalizedType);
        MediaAsset asset = mediaAssetRepository.save(MediaAsset.create(
                listingId, listing.providerId(), objectKey, normalizedType,
                sizeBytes, (int) (mediaAssetRepository.countByListingId(listingId) + 1)));

        String uploadUrl = s3.presignUpload(objectKey, normalizedType);
        return new MediaUploadView(asset.getId(), objectKey, uploadUrl, properties.limits().presignTtl());
    }

    /**
     * Confirms an upload: verifies via HeadObject that the object exists with
     * exactly the declared type and size, then moves the asset to UPLOADED.
     * A failed verification leaves the asset PENDING — confirmable again.
     */
    @Observed(name = "media.upload.confirm")
    @PreAuthorize("hasRole('PROVIDER')")
    public MediaAssetView confirmUpload(UUID mediaId, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        MediaAsset asset = getById(mediaId);
        verifyAssetOwnership(asset, authentication);

        boolean verified = s3.verifyUploaded(asset.getObjectKey(), asset.getContentType(), asset.getSizeBytes());
        if (!verified) {
            throw new BadRequestException(
                    "Object not found in storage (or type/size mismatch) for media: " + mediaId);
        }
        asset.markUploaded();
        return toView(asset, s3.presignDownload(asset.getObjectKey()));
    }

    /**
     * Read path: presigned GET URLs for every UPLOADED asset of the listing,
     * in display order. Presigning is local computation — no cache, no network.
     */
    @Transactional(readOnly = true)
    public List<MediaAssetView> listByListing(UUID listingId) {
        S3MediaStorage s3 = requireStorage();
        return mediaAssetRepository
                .findByListingIdAndStatusOrderByPositionAsc(listingId, MediaAssetStatus.UPLOADED)
                .stream()
                .map(asset -> toView(asset, s3.presignDownload(asset.getObjectKey())))
                .toList();
    }

    /**
     * Soft-deletes the asset record and best-effort removes the storage object
     * — only AFTER the database delete commits (CodeRabbit #241): the
     * repository delete is just scheduled until commit, so removing the object
     * first would leave the row pointing at a vanished object whenever the
     * transaction rolls back. A storage-side removal failure is logged, never
     * fatal — bucket lifecycle rules own orphans.
     */
    @Observed(name = "media.asset.delete")
    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public void delete(UUID mediaId, Authentication authentication) {
        S3MediaStorage s3 = requireStorage();
        MediaAsset asset = getById(mediaId);
        verifyAssetOwnership(asset, authentication);

        mediaAssetRepository.delete(asset);
        String objectKey = asset.getObjectKey();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    removeStorageObject(s3, objectKey);
                }
            });
        } else {
            // No active transaction (unit tests) — remove immediately.
            removeStorageObject(s3, objectKey);
        }
    }

    private void removeStorageObject(S3MediaStorage s3, String objectKey) {
        try {
            s3.deleteObject(objectKey);
        } catch (RuntimeException ex) {
            log.warn("Storage object removal failed for key {} (bucket lifecycle will own it): {}",
                    objectKey, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public MediaAsset getById(UUID id) {
        return mediaAssetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Media asset not found: " + id));
    }

    private S3MediaStorage requireStorage() {
        S3MediaStorage s3 = storage.getIfAvailable();
        if (s3 == null) {
            throw new ServiceUnavailableException(
                    "Media storage is not configured. Set MEDIA_S3_ENDPOINT, MEDIA_S3_BUCKET, "
                            + "MEDIA_S3_ACCESS_KEY and MEDIA_S3_SECRET_KEY to enable listing media.");
        }
        return s3;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("Content type is required");
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private void validateContentType(String normalizedType) {
        if (!properties.limits().allowedContentTypes().contains(normalizedType)) {
            throw new BadRequestException(
                    "Unsupported media content type: " + normalizedType
                            + " (allowed: " + properties.limits().allowedContentTypes() + ")");
        }
    }

    private void validateSize(long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > properties.limits().maxUploadBytes()) {
            throw new BadRequestException(
                    "Media size " + sizeBytes + " bytes is outside the allowed range (max "
                            + properties.limits().maxUploadBytes() + ")");
        }
    }

    /**
     * Same ownership rule as {@code CatalogService.verifyOwnership}: admin passes,
     * otherwise the provider record behind the listing/asset must be linked to
     * the current user.
     */
    private void verifyOwnership(UUID providerId, Authentication authentication, String denialMessage) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (currentUserProvider.isAdmin(authentication)) {
            return;
        }
        providerLookupPort.findById(providerId)
                .filter(provider -> provider.userId() != null && provider.userId().equals(currentUserId))
                .orElseThrow(() -> new AccessDeniedException(denialMessage));
    }

    private void verifyListingOwnership(UUID providerId, Authentication authentication) {
        verifyOwnership(providerId, authentication, "You do not own this listing");
    }

    private void verifyAssetOwnership(MediaAsset asset, Authentication authentication) {
        verifyOwnership(asset.getProviderId(), authentication, "You do not own this media asset");
    }

    private String buildObjectKey(UUID listingId, String contentType) {
        String extension = EXTENSION_BY_TYPE.getOrDefault(contentType, "bin");
        return "listings/" + listingId + "/" + UUID.randomUUID() + "." + extension;
    }

    private MediaAssetView toView(MediaAsset asset, String downloadUrl) {
        return new MediaAssetView(
                asset.getId(),
                asset.getListingId(),
                asset.getContentType(),
                asset.getSizeBytes(),
                asset.getStatus().name(),
                asset.getPosition(),
                downloadUrl,
                asset.getCreatedAt()
        );
    }

    /**
     * Response of {@link #requestUpload} — everything a client needs to upload
     * directly to storage.
     */
    public record MediaUploadView(UUID mediaId, String objectKey, String uploadUrl,
                                  java.time.Duration urlLifetime) {}

    /**
     * Read/confirm response — the presigned GET URL is freshly signed per call.
     */
    public record MediaAssetView(UUID id, UUID listingId, String contentType, long sizeBytes,
                                 String status, int position, String downloadUrl,
                                 java.time.Instant createdAt) {}
}

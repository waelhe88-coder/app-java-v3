package com.marketplace.media;

import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Role enforcement of the media service commands — the house pattern
 * ({@code ReviewsServiceSecurityTest}): the REAL service under
 * {@code @EnableMethodSecurity}, so the @PreAuthorize rules fire exactly as in
 * production. The storage bean is a mock whose getIfAvailable() returns null —
 * the documented inert state — which lets each positive case prove that the
 * role gate passed (the call reaches business logic and answers the honest
 * 503) instead of silently short-circuiting.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { MediaService.class, MediaServiceSecurityTest.TestConfig.class })
@EnableMethodSecurity(proxyTargetClass = true)
class MediaServiceSecurityTest {

    @Autowired
    private MediaService mediaService;

    @MockitoBean
    private MediaAssetRepository mediaAssetRepository;

    @MockitoBean
    private ObjectProvider<S3MediaStorage> storageProvider;

    @MockitoBean
    private ListingPriceProvider listingPriceProvider;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Configuration
    static class TestConfig {
        @Bean
        MediaProperties mediaProperties() {
            return new MediaProperties(
                    new MediaProperties.Storage("", "auto", "", "", "", false),
                    new MediaProperties.Limits(10_485_760L,
                            Set.of("image/jpeg", "image/png"), Duration.ofMinutes(15)));
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void requestUpload_whenNotProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> mediaService.requestUpload(UUID.randomUUID(), "image/jpeg", 1024L, null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void confirmUpload_whenNotProvider_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> mediaService.confirmUpload(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void delete_whenNotProviderOrAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> mediaService.delete(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void requestUpload_whenProvider_thenReachesBusinessLogic() {
        // role gate passed: the call proceeds into the method body and hits the
        // honest inert gate (no storage beans bound), never AccessDenied
        assertThatExceptionOfType(ServiceUnavailableException.class).isThrownBy(
                () -> mediaService.requestUpload(UUID.randomUUID(), "image/jpeg", 1024L, null));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_whenAdmin_thenReachesBusinessLogic() {
        assertThatExceptionOfType(ServiceUnavailableException.class).isThrownBy(
                () -> mediaService.delete(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void listByListing_isOpenToAuthenticatedRoles() {
        // read path carries no @PreAuthorize — any authenticated role reaches it
        assertThatExceptionOfType(ServiceUnavailableException.class).isThrownBy(
                () -> mediaService.listByListing(UUID.randomUUID()));
    }
}

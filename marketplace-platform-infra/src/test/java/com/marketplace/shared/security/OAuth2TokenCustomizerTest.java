package com.marketplace.shared.security;

import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2TokenCustomizerTest {

    private static final String AUDIENCE = "marketplace-api";

    private OAuth2TokenCustomizer<JwtEncodingContext> customizer;

    @BeforeEach
    void setUp() {
        customizer = new SecurityConfig(properties(), null).jwtTokenCustomizer();
    }

    @Test
    void customizerAddsRolesToAccessToken() {
        JwtEncodingContext context = buildContext(
                AuthorityUtils.createAuthorityList("ROLE_ADMIN", "ROLE_USER"),
                OAuth2TokenType.ACCESS_TOKEN);

        customizer.customize(context);

        Object rolesClaim = context.getClaims().build().getClaims().get("roles");
        assertThat(rolesClaim).isInstanceOf(Set.class);
        assertThat(rolesClaim.toString()).contains("ADMIN", "USER");
    }

    @Test
    void customizerAddsAudienceToAccessToken() {
        JwtEncodingContext context = buildContext(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                OAuth2TokenType.ACCESS_TOKEN);

        customizer.customize(context);

        assertThat(context.getClaims().build().getAudience())
                .containsExactly(AUDIENCE);
    }

    @Test
    void customizerIgnoresNonAccessTokenTypes() {
        JwtEncodingContext context = buildContext(
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"),
                OAuth2TokenType.REFRESH_TOKEN);

        customizer.customize(context);

        assertThat(context.getClaims().build().getClaims())
                .doesNotContainKey("roles")
                .doesNotContainKey("aud");
    }

    private static JwtEncodingContext buildContext(
            List<? extends org.springframework.security.core.GrantedAuthority> authorities,
            OAuth2TokenType tokenType) {
        RegisteredClient registeredClient = RegisteredClient.withId("test-client")
                .clientId("test-client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("read")
                .build();

        var principal = new UsernamePasswordAuthenticationToken("user", null, authorities);

        JwsHeader.Builder jwsHeaderBuilder = JwsHeader.with(SignatureAlgorithm.RS256);
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .subject("user-123");

        return JwtEncodingContext.with(jwsHeaderBuilder, claimsBuilder)
                .registeredClient(registeredClient)
                .principal(principal)
                .tokenType(tokenType)
                .build();
    }

    private static MarketplaceProperties properties() {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", "", ""),
                                AUDIENCE
                        ),
                        new MarketplaceProperties.Security.Session(2),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", ""))
                )
        );
    }
}

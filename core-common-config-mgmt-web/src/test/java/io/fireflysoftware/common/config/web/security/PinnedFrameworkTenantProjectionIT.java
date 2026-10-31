package io.fireflysoftware.common.config.web.security;

import org.fireflyframework.security.core.context.PrincipalFactory;
import org.fireflyframework.security.core.authority.*;
import org.fireflyframework.security.rs.JwtToFireflyPrincipalConverter;
import org.fireflyframework.security.webflux.authentication.FireflyAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PinnedFrameworkTenantProjectionIT {
    @Test void frameworkConverterProjectsExistingTenantClaim() {
        var tenant="00000000-0000-0000-0000-000000000001";var factory=new PrincipalFactory(new ConfigurableAuthorityMapper(new AuthorityMappingProperties(List.of("roles"),List.of("scope"),"ROLE_")));
        var jwt=Jwt.withTokenValue("preverified-unit-value").header("alg","RS256").issuer("https://issuer.example.test").subject("actual-machine").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("tenant_id",tenant).claim("principal_kind","MACHINE").claim("client_id","actual-client").build();
        var result=(FireflyAuthenticationToken)new JwtToFireflyPrincipalConverter(factory).convert(jwt).block();assertNotNull(result);assertEquals(tenant,result.securityPrincipal().tenantId());assertEquals("MACHINE",result.securityPrincipal().claims().get("principal_kind"));assertFalse(result.securityPrincipal().claims().containsKey("staff_actor_id"));
    }
}

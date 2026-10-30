package io.fireflysoftware.common.config.web.tenantbinding;

import io.fireflysoftware.common.config.web.config.TenantResourceBindingConfiguration;
import io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController;
import io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter;
import java.time.Clock;
import java.util.List;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class TenantResourceBindingCurrentReadinessTest {
    @Test void defaultOffDoesNotDiscoverController(){try(var context=new AnnotationConfigApplicationContext()){context.register(TenantResourceBindingCurrentController.class);context.refresh();assertTrue(context.getBeansOfType(TenantResourceBindingCurrentController.class).isEmpty());}}
    @Test void optedInWithoutRealIngressFailsClosed(){try(var context=new AnnotationConfigApplicationContext()){context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("enabled",java.util.Map.of("firefly.configuration.tenant-resource-binding.enabled","true")));context.register(TenantResourceBindingCurrentController.class);assertThrows(org.springframework.beans.BeansException.class,context::refresh);}}
    @Test void strictVerifiedClaimOnlyProjectionIsAccepted(){assertNotNull(filter(verified(),new MockEnvironment()));}
    @Test void optionalTenantProjectionIsRefused(){var properties=verified();properties.setTenantRequired(false);assertThrows(IllegalArgumentException.class,()->filter(properties,new MockEnvironment()));}
    @Test void missingTenantClaimPathIsRefused(){var properties=verified();properties.setTenantClaimPaths(List.of());assertThrows(IllegalArgumentException.class,()->filter(properties,new MockEnvironment()));}
    @Test void alternateOrFallbackClaimPathsAreRefused(){var properties=verified();properties.setTenantClaimPaths(List.of("tenant_id","defaultTenant"));assertThrows(IllegalArgumentException.class,()->filter(properties,new MockEnvironment()));}
    @Test void disabledResourceServerIsRefused(){assertThrows(IllegalArgumentException.class,()->filter(verified(),new MockEnvironment().withProperty("firefly.security.resource-server.enabled","false")));}
    @Test void nonStrictPolicyIsRefused(){var properties=verified();properties.setStrictPolicy(false);assertThrows(IllegalArgumentException.class,()->filter(properties,new MockEnvironment()));}
    @Test void missingIssuerJwksOrAudienceIsRefused(){var properties=verified();properties.setIssuer(null);var noIssuer=properties;assertThrows(IllegalArgumentException.class,()->filter(noIssuer,new MockEnvironment()));properties=verified();properties.setJwkSetUri(null);var noJwks=properties;assertThrows(IllegalArgumentException.class,()->filter(noJwks,new MockEnvironment()));var noAudience=verified();noAudience.setAudiences(List.of());assertThrows(IllegalArgumentException.class,()->filter(noAudience,new MockEnvironment()));}
    @Test void publicMatcherForThisRouteIsRefused(){for(String matcher:List.of("/**","/api/v1/tenant-resource-bindings/**",TenantResourceBindingCurrentBudgetFilter.PATH)){var properties=verified();properties.setPermitMatchers(List.of(matcher));assertThrows(IllegalArgumentException.class,()->filter(properties,new MockEnvironment()));}}
    @Test void unrelatedLegacyPublicMatcherIsPreserved(){var properties=verified();properties.setPermitMatchers(List.of("/actuator/health"));assertNotNull(filter(properties,new MockEnvironment()));assertEquals(List.of("/actuator/health"),properties.getPermitMatchers());}
    @Test void unrelatedDefaultTenantSettingCannotReplaceRequiredClaim(){var properties=verified();properties.setTenantRequired(false);assertThrows(IllegalArgumentException.class,()->filter(properties,new MockEnvironment().withProperty("firefly.security.default-tenant","fallback")));}
    static ResourceServerProperties verified(){var properties=new ResourceServerProperties();properties.setStrictPolicy(true);properties.setIssuer("https://issuer.example");properties.setJwkSetUri("https://issuer.example/jwks");properties.setAudiences(List.of("configuration"));properties.setTenantRequired(true);properties.setTenantClaimPaths(List.of("tenant_id"));return properties;}
    private static TenantResourceBindingCurrentBudgetFilter filter(ResourceServerProperties properties,MockEnvironment environment){return new TenantResourceBindingConfiguration().tenantResourceBindingCurrentBudgetFilter(Clock.systemUTC(),properties,environment);}
}

package io.fireflysoftware.common.config.web.config;
import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.core.authority.impl.TenantResourceBindingServiceImpl;
import io.fireflysoftware.common.config.core.authority.impl.TenantResourceBindingNativeAuthorityImpl;
import io.fireflysoftware.common.config.core.authority.impl.TenantResourceBindingGovernanceServiceImpl;
import io.fireflysoftware.common.config.models.repositories.tenantbinding.TenantResourceBindingNativeRepository;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import io.fireflysoftware.common.config.models.repositories.tenantbinding.TenantResourceBindingStore;
import io.fireflysoftware.common.config.models.repositories.tenantbinding.impl.TenantResourceBindingStoreImpl;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.spi.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import java.time.Clock;
import java.util.Set;

@Configuration(proxyBeanMethods=false)
@Profile("!openapi-gen")
@EnableR2dbcRepositories(basePackageClasses=TenantResourceBindingNativeRepository.class,entityOperationsRef="institutionAuthorityEntityOperations")
@ConditionalOnProperty(name="firefly.configuration.tenant-resource-binding.enabled",havingValue="true")
public class TenantResourceBindingConfiguration {
 @Bean public TenantResourceBindingLookupAuthorization tenantResourceBindingLookupAuthorization(SecurityContextPort contexts,PolicyDecisionPort policies,@Qualifier("institutionAuthorityClock") Clock clock,ResourceServerProperties verified){
  if(!verified.isStrictPolicy()||verified.getIssuer()==null||verified.getIssuer().isBlank()||verified.getJwkSetUri()==null||verified.getJwkSetUri().isBlank()||verified.getAudiences().isEmpty())throw new IllegalArgumentException("Verified issuer, audience, JWKS and strict current policy required for opaque lookup");
  return new TenantResourceBindingLookupAuthorization(contexts,policies,clock,Set.of(verified.getIssuer()));
 }
 @Bean public TenantResourceBindingStore tenantResourceBindingStore(@Qualifier("institutionAuthorityEntityOperations") R2dbcEntityTemplate template){return new TenantResourceBindingStoreImpl(template);}
 @Bean public TenantResourceBindingService tenantResourceBindingService(TenantResourceBindingLookupAuthorization lookup,InstitutionAuthorityAuthorization governance,R2dbcTransactionManager manager,TenantResourceBindingStore store,TenantResourceBindingNativeAuthority nativeAuthority){return new TenantResourceBindingServiceImpl(lookup,governance,manager,store,nativeAuthority);}
 @Bean @ConditionalOnMissingBean({TenantResourceBindingNativeAuthority.class,TenantResourceBindingNativeGovernance.class})
 public TenantResourceBindingNativeAuthorityImpl tenantResourceBindingNativeAuthority(@Qualifier("institutionAuthorityEntityOperations") R2dbcEntityTemplate template,TenantResourceBindingNativeRepository repository){return new TenantResourceBindingNativeAuthorityImpl(template,repository);}
 @Bean public TenantResourceBindingGovernanceService tenantResourceBindingGovernanceService(InstitutionAuthorityAuthorization governance,R2dbcTransactionManager manager,TenantResourceBindingNativeGovernance nativeAuthority){return new TenantResourceBindingGovernanceServiceImpl(governance,manager,nativeAuthority);}

 @Bean public io.fireflysoftware.common.config.web.tenantbinding.TenantResourceBindingCurrentIngress tenantResourceBindingCurrentIngress(TenantResourceBindingService service){return new io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentIngressImpl(service);}
 @Bean public io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter tenantResourceBindingCurrentBudgetFilter(@Qualifier("institutionAuthorityClock") Clock clock,ResourceServerProperties verified,org.springframework.core.env.Environment environment){
  if(!environment.getProperty("firefly.security.resource-server.enabled",Boolean.class,true)||!verified.isStrictPolicy()||verified.getIssuer()==null||verified.getIssuer().isBlank()||verified.getJwkSetUri()==null||verified.getJwkSetUri().isBlank()||verified.getAudiences().isEmpty()||!verified.isTenantRequired()||!java.util.List.of("tenant_id").equals(verified.getTenantClaimPaths()))throw new IllegalArgumentException("Verified claim-only required tenant projection and strict resource server required for CURRENT HTTP");
  var route=org.springframework.http.server.PathContainer.parsePath(io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter.PATH);
  var parser=new org.springframework.web.util.pattern.PathPatternParser();
  if(verified.getPermitMatchers()==null||verified.getPermitMatchers().stream().anyMatch(pattern->parser.parse(pattern).matches(route)))throw new IllegalArgumentException("CURRENT HTTP cannot be a public matcher");
  return new io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter(clock,System::nanoTime);
 }

}

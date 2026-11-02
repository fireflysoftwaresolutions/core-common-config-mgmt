package io.fireflysoftware.common.config.web.config;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.core.authority.impl.*;
import io.fireflysoftware.common.config.models.repositories.authority.*;
import io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingGovernanceBudgetFilterImpl;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@Profile("!openapi-gen")
@ConditionalOnProperty(prefix="firefly.configuration.tenant-resource-binding",name={"enabled","governance-http-enabled"},havingValue="true")
public class TenantResourceBindingGovernanceHttpConfiguration {
    @Bean public TenantResourceBindingRecoveryReader tenantResourceBindingRecoveryReader(InstitutionAuthorityAuthorization authority,InstitutionAuthorityTransaction transactions,NativeActionRepository actions,NativeReceiptRepository receipts){return new TenantResourceBindingRecoveryReaderImpl(authority,transactions,actions,receipts);}
    @Bean public TenantResourceBindingGovernedActions tenantResourceBindingGovernedActions(InstitutionAuthorityAuthorization authority,TenantResourceBindingGovernanceService governance,TenantResourceBindingService controls,TenantResourceBindingRecoveryReader recovery){return new TenantResourceBindingGovernedActionsImpl(authority,governance,controls,recovery);}
    @Bean public TenantResourceBindingGovernanceBudgetFilterImpl tenantResourceBindingGovernanceBudgetFilter(@Qualifier("institutionAuthorityClock")Clock clock,ResourceServerProperties verified){
        if(!verified.isStrictPolicy()||verified.getIssuer()==null||verified.getIssuer().isBlank()||verified.getJwkSetUri()==null||verified.getJwkSetUri().isBlank()||verified.getAudiences().isEmpty()||!verified.isTenantRequired()||!java.util.List.of("tenant_id").equals(verified.getTenantClaimPaths())||verified.getPermitMatchers()==null)throw new IllegalArgumentException("Strict verified claim-only authority is required for binding governance HTTP");
        var parser=new PathPatternParser();for(String suffix:java.util.List.of("/intents","/actions","/actions/observations")){var route=PathContainer.parsePath(TenantResourceBindingGovernanceBudgetFilterImpl.BASE+suffix);if(verified.getPermitMatchers().stream().anyMatch(pattern->parser.parse(pattern).matches(route)))throw new IllegalArgumentException("Binding governance HTTP cannot be a public matcher");}
        return new TenantResourceBindingGovernanceBudgetFilterImpl(clock,System::nanoTime);
    }
}

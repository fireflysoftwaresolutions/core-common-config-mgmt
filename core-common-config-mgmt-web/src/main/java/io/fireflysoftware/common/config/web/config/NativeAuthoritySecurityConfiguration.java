package io.fireflysoftware.common.config.web.config;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.models.repositories.authority.NativeAuthorityOperationsRepository;
import org.fireflyframework.security.adapter.opa.OpaPolicyDecisionAdapter;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.URI;
import java.time.Clock;
import java.util.Set;

@Configuration(proxyBeanMethods=false)
@org.springframework.context.annotation.Profile("!openapi-gen")
public class NativeAuthoritySecurityConfiguration {
    @Bean public Clock institutionAuthorityClock(){return Clock.systemUTC();}

    @Bean public PolicyDecisionPort institutionAuthorityPolicyDecisionPort(@Value("${firefly.configuration.authority.policy-url}") URI policyUri,@Value("${firefly.configuration.authority.policy-path}") String decisionPath) {
        if(!Set.of("http","https").contains(policyUri.getScheme()) || policyUri.getHost()==null || policyUri.getUserInfo()!=null || policyUri.getFragment()!=null || decisionPath.isBlank()) throw new IllegalArgumentException("Explicit institution authority policy endpoint required");
        return new OpaPolicyDecisionAdapter(WebClient.builder().baseUrl(policyUri.toString()).build(),decisionPath);
    }
    @Bean public InstitutionAuthorityAuthorization institutionAuthorityAuthorization(SecurityContextPort principals,PolicyDecisionPort policy,@Qualifier("institutionAuthorityClock") Clock clock,ResourceServerProperties resourceServer,@Value("${firefly.configuration.authority.platform-tenant-id}") String platformTenant) {
        if(!resourceServer.isStrictPolicy() || resourceServer.getIssuer()==null || resourceServer.getIssuer().isBlank() || resourceServer.getJwkSetUri()==null || resourceServer.getJwkSetUri().isBlank() || resourceServer.getAudiences().isEmpty()) throw new IllegalArgumentException("Verified external issuer, audience, JWKS and strict current policy are required");
        return new InstitutionAuthorityAuthorization(principals,policy,clock,Set.of(resourceServer.getIssuer()),platformTenant,true);
    }
    @Bean public InstitutionAuthorityDatabaseContext institutionAuthorityDatabaseContext(@Qualifier("institutionAuthorityEntityOperations") R2dbcEntityOperations operations,NativeAuthorityOperationsRepository repository) {
        return new InstitutionAuthorityDatabaseContext(operations,repository);
    }
    @Bean public InstitutionAuthorityTransaction institutionAuthorityTransaction(R2dbcTransactionManager manager,@Qualifier("institutionAuthorityEntityOperations") R2dbcEntityOperations operations,InstitutionAuthorityDatabaseContext context) {
        return new InstitutionAuthorityTransaction(manager,operations,context);
    }
}

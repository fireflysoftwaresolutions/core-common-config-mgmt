package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.models.repositories.authority.*;
import io.fireflysoftware.common.config.interfaces.authority.*;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityActionSpringDataNativeIT {
    @BeforeAll static void lease() throws Exception {TenantEligibilityNativeIT.lease();try(var ignored=TenantEligibilityNativeIT.runtime()) {}}
    static final Duration WAIT=Duration.ofSeconds(15);
    static org.fireflyframework.security.api.domain.SecurityPrincipal principal(String tenant,String employee) {
        var claims=new HashMap<String,Object>();claims.put("tenant_id",tenant);claims.put("exp",Instant.now().plusSeconds(300));claims.put("principal_kind",employee==null?"MACHINE":"STAFF");if(employee==null)claims.put("client_id","current-native-client");else claims.put("staff_actor_id",employee);
        return new org.fireflyframework.security.api.domain.SecurityPrincipal(employee==null?"current-machine":"staff/"+employee,"https://native-qualification.example.test",tenant,Set.of(),Set.of(),Map.copyOf(claims),null,null,Set.of(),Map.of());
    }
    @Test void currentAuthorizedDerivedReadReturnsFullReceiptButAbsenceStaysUnknown() throws Exception {
        var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var prepared=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,prepared);var result=InstitutionAuthorityGrantNativeIT.issue(platform,tenant,prepared);var action=prepared.get("target").get("grant").get("actionId").textValue();
        var factory=ConnectionFactories.get(ConnectionFactoryOptions.parse(System.getenv("CFG_NATIVE_JDBC_URL").replace("jdbc:postgresql:","r2dbc:postgresql:")).mutate().option(ConnectionFactoryOptions.USER,"cfg_native_runtime").option(ConnectionFactoryOptions.PASSWORD,System.getenv("CFG_NATIVE_RUNTIME_PASSWORD")).build());
        try(var app=new AnnotationConfigApplicationContext()) {
            app.registerBean(ConnectionFactory.class,()->factory);app.register(io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration.class);app.refresh();var ops=app.getBean(NativeAuthorityOperationsRepository.class);var entities=app.getBean("institutionAuthorityEntityOperations",R2dbcEntityTemplate.class);var permit=new AtomicBoolean(true);
            var auth=new InstitutionAuthorityAuthorization(()->Mono.just(principal(platform,null)),(p,a,r,c)->Mono.just(permit.get()?org.fireflyframework.security.api.domain.Decision.permit():org.fireflyframework.security.api.domain.Decision.deny("revoked")),Clock.systemUTC(),Set.of("https://native-qualification.example.test"),platform,true);var boundary=new InstitutionAuthorityTransaction(new R2dbcTransactionManager(factory),entities,new InstitutionAuthorityDatabaseContext(entities,ops));
            var service=new io.fireflysoftware.common.config.core.services.authority.impl.NativeAuthorityActionServiceImpl(auth,boundary,app.getBean(NativeActionRepository.class),app.getBean(NativeReceiptRepository.class),ops,Clock.systemUTC());
            var snapshot=service.readAsPlatform(tenant,action).block(WAIT);assertNotNull(snapshot);assertEquals(NativeAuthorityActionState.RECEIPT_FOUND,snapshot.state());assertTrue(snapshot.historicalOnly());assertEquals(result.get("receipt"),AuthorityWire.parse(snapshot.canonicalReceiptJson()));assertEquals(AuthorityWire.sha(snapshot.canonicalReceiptJson()),snapshot.receiptDigest());assertEquals(NativeAuthorityActionState.UNKNOWN,service.readAsPlatform(platform,action).block(WAIT).state());
            permit.set(false);assertEquals(403,assertThrows(ResponseStatusException.class,()->service.readAsPlatform(tenant,action).block(WAIT)).getStatusCode().value());
        }
    }
    @Test void typedServicesIssueControlReadHistoryAndReplayWithCurrentPermission() throws Exception {
        var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var prepared=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0,5000);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,prepared);
        var factory=ConnectionFactories.get(ConnectionFactoryOptions.parse(System.getenv("CFG_NATIVE_JDBC_URL").replace("jdbc:postgresql:","r2dbc:postgresql:")).mutate().option(ConnectionFactoryOptions.USER,"cfg_native_runtime").option(ConnectionFactoryOptions.PASSWORD,System.getenv("CFG_NATIVE_RUNTIME_PASSWORD")).build());
        try(var app=new AnnotationConfigApplicationContext()) {
            app.registerBean(ConnectionFactory.class,()->factory);app.register(io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration.class);app.refresh();var ops=app.getBean(NativeAuthorityOperationsRepository.class);var entities=app.getBean("institutionAuthorityEntityOperations",R2dbcEntityTemplate.class);var auth=new InstitutionAuthorityAuthorization(()->Mono.just(principal(platform,null)),(p,a,r,c)->Mono.just(org.fireflyframework.security.api.domain.Decision.permit()),Clock.systemUTC(),Set.of("https://native-qualification.example.test"),platform,true);var boundary=new InstitutionAuthorityTransaction(new R2dbcTransactionManager(factory),entities,new InstitutionAuthorityDatabaseContext(entities,ops));var service=new io.fireflysoftware.common.config.core.services.authority.impl.InstitutionAuthorityServiceImpl(auth,boundary,ops,app.getBean(NativeProposalRepository.class),app.getBean(NativeProposalRevisionRepository.class),Clock.systemUTC());
            var target=prepared.get("target");var grant=target.get("grant");var institution=grant.get("institutionId").textValue();var original=target.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);var issued=service.issueBootstrapGrant(tenant,institution,original).block(WAIT);assertNotNull(issued);Thread.sleep(5100);assertEquals(issued.receipt(),service.issueBootstrapGrant(tenant,institution,original).block(WAIT).receipt());
            var control=InstitutionAuthorityGrantControlNativeIT.prepare(platform,tenant,target,0,"ACTIVE","SUSPENDED");InstitutionAuthorityGrantControlNativeIT.approve(platform,tenant,control);var controlled=service.controlAsPlatform(tenant,institution,control.get("target").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).block(WAIT);assertEquals("SUSPENDED",controlled.receipt().result().state());
            var historical=service.historyAsPlatform(tenant,institution,grant.get("issuerAuthorityId").textValue(),grant.get("grantId").textValue(),1).block(WAIT);assertTrue(historical.historicalOnly());assertEquals(issued.receipt().result(),historical.definition());
        }
    }
    @Test void typedReconciliationReadsDurableNegativeFenceAndExecuteReportsConflict() throws Exception {
        var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var prepared=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0,2000);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,prepared);Thread.sleep(2100);
        var factory=ConnectionFactories.get(ConnectionFactoryOptions.parse(System.getenv("CFG_NATIVE_JDBC_URL").replace("jdbc:postgresql:","r2dbc:postgresql:")).mutate().option(ConnectionFactoryOptions.USER,"cfg_native_runtime").option(ConnectionFactoryOptions.PASSWORD,System.getenv("CFG_NATIVE_RUNTIME_PASSWORD")).build());
        try(var app=new AnnotationConfigApplicationContext()) {
            app.registerBean(ConnectionFactory.class,()->factory);app.register(io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration.class);app.refresh();var ops=app.getBean(NativeAuthorityOperationsRepository.class);var entities=app.getBean("institutionAuthorityEntityOperations",R2dbcEntityTemplate.class);var auth=new InstitutionAuthorityAuthorization(()->Mono.just(principal(platform,null)),(p,a,r,c)->Mono.just(org.fireflyframework.security.api.domain.Decision.permit()),Clock.systemUTC(),Set.of("https://native-qualification.example.test"),platform,true);var boundary=new InstitutionAuthorityTransaction(new R2dbcTransactionManager(factory),entities,new InstitutionAuthorityDatabaseContext(entities,ops));
            var actions=new io.fireflysoftware.common.config.core.services.authority.impl.NativeAuthorityActionServiceImpl(auth,boundary,app.getBean(NativeActionRepository.class),app.getBean(NativeReceiptRepository.class),ops,Clock.systemUTC());var grants=new io.fireflysoftware.common.config.core.services.authority.impl.InstitutionAuthorityServiceImpl(auth,boundary,ops,app.getBean(NativeProposalRepository.class),app.getBean(NativeProposalRevisionRepository.class),Clock.systemUTC());
            var target=prepared.get("target");var grant=target.get("grant");var action=grant.get("actionId").textValue();var original=target.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);var body="{\"operation\":\"GRANT_ISSUE\",\"originalBase64\":\""+Base64.getEncoder().encodeToString(original)+"\"}";
            var negative=actions.reconcileGrantAsPlatform(tenant,action,body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).block(WAIT);assertEquals(NativeAuthorityActionState.NO_EFFECT_FINAL,negative.state());assertEquals(negative,actions.readAsPlatform(tenant,action).block(WAIT));assertEquals(negative,actions.reconcileGrantAsPlatform(tenant,action,body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).block(WAIT));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->grants.issueBootstrapGrant(tenant,grant.get("institutionId").textValue(),original).block(WAIT)).getStatusCode().value());
        }
    }

}

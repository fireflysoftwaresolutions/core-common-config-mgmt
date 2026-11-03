package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.models.repositories.authority.*;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthoritySpringDataNativeIT {
    @BeforeAll static void lease() throws Exception {TenantEligibilityNativeIT.lease();try(var ignored=TenantEligibilityNativeIT.runtime()) {}}
    @Test void genuineRepositoryProxyCompositeMappingAndContextUseOneNativeTransaction() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_establish_context(uuid,uuid,text,text,uuid,uuid,text,text,text,text,uuid,uuid,text,boolean,bigint,bigint)");
        var tenant=TenantEligibilityNativeIT.tenant();var action=UUID.randomUUID();var original=TenantEligibilityNativeIT.suspendJson(action.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var intent=TenantEligibilityIntentCodec.decodeSuspend(tenant,original);
        var ownerUrl=System.getenv("CFG_NATIVE_JDBC_URL");assertNotNull(ownerUrl);
        var options=ConnectionFactoryOptions.parse(ownerUrl.replace("jdbc:postgresql:","r2dbc:postgresql:"))
            .mutate().option(ConnectionFactoryOptions.USER,"cfg_native_runtime").option(ConnectionFactoryOptions.PASSWORD,System.getenv("CFG_NATIVE_RUNTIME_PASSWORD")).build();
        var factory=ConnectionFactories.get(options);
        try(var app=new AnnotationConfigApplicationContext()) {
            app.registerBean(ConnectionFactory.class,()->factory);app.register(io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration.class);app.refresh();
            var nativeOps=app.getBean(NativeAuthorityOperationsRepository.class);var actions=app.getBean(NativeActionRepository.class);var receipts=app.getBean(NativeReceiptRepository.class);
            var transactions=TransactionalOperator.create(new R2dbcTransactionManager(factory));var deadline=System.currentTimeMillis()+30000;
            var result=transactions.transactional(nativeOps.establishContext(UUID.fromString(tenant),null,"TENANT_SUSPEND","TENANT_ELIGIBILITY",UUID.fromString(tenant),action,intent.intentDigest(),"MACHINE","https://issuer.example.test","native-machine",UUID.fromString(tenant),null,"native-client",false,deadline,deadline)
                .flatMap(ready->{assertTrue(ready);return nativeOps.suspendTenant(UUID.fromString(tenant),original);})
                .flatMap(receipt->actions.findByIdTenantIdAndIdActionId(UUID.fromString(tenant),action)
                    .flatMap(row->{assertEquals(action,row.id().actionId());assertEquals(UUID.fromString(tenant),row.id().tenantId());assertEquals("TENANT_SUSPEND",row.operation());return receipts.findByIdTenantIdAndIdActionId(UUID.fromString(tenant),action);})
                    .switchIfEmpty(Mono.error(new AssertionError("Actual Spring Data composite row mapping failed")))))
                .block(Duration.ofSeconds(15));
            assertNotNull(result);assertEquals(action,result.id().actionId());
        }
    }
    @Test void typedServiceUsesCurrentPrincipalBeforeRealSpringDataTransaction() throws Exception {
        var tenant=TenantEligibilityNativeIT.tenant();var action=UUID.randomUUID();var original=TenantEligibilityNativeIT.suspendJson(action.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var options=ConnectionFactoryOptions.parse(System.getenv("CFG_NATIVE_JDBC_URL").replace("jdbc:postgresql:","r2dbc:postgresql:"))
            .mutate().option(ConnectionFactoryOptions.USER,"cfg_native_runtime").option(ConnectionFactoryOptions.PASSWORD,System.getenv("CFG_NATIVE_RUNTIME_PASSWORD")).build();
        var factory=ConnectionFactories.get(options);
        try(var app=new AnnotationConfigApplicationContext()) {
            app.registerBean(ConnectionFactory.class,()->factory);app.register(io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration.class);app.refresh();
            var operations=app.getBean(NativeAuthorityOperationsRepository.class);var entities=app.getBean("institutionAuthorityEntityOperations",R2dbcEntityTemplate.class);
            var principal=new org.fireflyframework.security.api.domain.SecurityPrincipal("typed-native-machine","https://issuer.example.test",tenant,java.util.Set.of(),java.util.Set.of(),java.util.Map.of("exp",java.time.Instant.now().plusSeconds(300),"tenant_id",tenant,"principal_kind","MACHINE","client_id","native-client"),null,null,java.util.Set.of(),java.util.Map.of());
            var authorization=new InstitutionAuthorityAuthorization(()->Mono.just(principal),(p,a,r,c)->Mono.just(org.fireflyframework.security.api.domain.Decision.permit()),java.time.Clock.systemUTC(),java.util.Set.of("https://issuer.example.test"),tenant,true);
            var boundary=new InstitutionAuthorityTransaction(new R2dbcTransactionManager(factory),entities,new InstitutionAuthorityDatabaseContext(entities,operations));
            var service=new io.fireflysoftware.common.config.core.services.authority.impl.TenantEligibilityServiceImpl(authorization,boundary,operations,app.getBean(NativeProposalRepository.class),app.getBean(NativeProposalRevisionRepository.class),java.time.Clock.systemUTC());
            var result=service.suspendTenant(tenant,original).block(Duration.ofSeconds(15));assertNotNull(result);assertFalse(result.receipt().result().tenantActive());assertEquals(action.toString(),result.currentTenantEligibility().lastActionId());
        }
    }

    @Test void typedDecisionUsesProtectedRetainedTargetReadAndThenExactIntentPolicy() throws Exception {
        var tenant=TenantEligibilityNativeIT.tenant();var prepared=TenantEligibilityNativeIT.recovery(tenant);var employee="00000000-0000-0000-0000-000000000101";
        var options=ConnectionFactoryOptions.parse(System.getenv("CFG_NATIVE_JDBC_URL").replace("jdbc:postgresql:","r2dbc:postgresql:")).mutate().option(ConnectionFactoryOptions.USER,"cfg_native_runtime").option(ConnectionFactoryOptions.PASSWORD,System.getenv("CFG_NATIVE_RUNTIME_PASSWORD")).build();var factory=ConnectionFactories.get(options);
        try(var app=new AnnotationConfigApplicationContext()) {
            app.registerBean(ConnectionFactory.class,()->factory);app.register(io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration.class);app.refresh();
            var operations=app.getBean(NativeAuthorityOperationsRepository.class);var entities=app.getBean("institutionAuthorityEntityOperations",R2dbcEntityTemplate.class);var contexts=new java.util.ArrayList<java.util.Map<String,Object>>();
            var principal=new org.fireflyframework.security.api.domain.SecurityPrincipal("qualified-staff/"+employee,"https://native-qualification.example.test",tenant,java.util.Set.of(),java.util.Set.of(),java.util.Map.of("exp",java.time.Instant.now().plusSeconds(300),"tenant_id",tenant,"principal_kind","STAFF","staff_actor_id",employee),null,null,java.util.Set.of(),java.util.Map.of());
            var authorization=new InstitutionAuthorityAuthorization(()->Mono.just(principal),(p,a,r,c)->{contexts.add(java.util.Map.copyOf(c));return Mono.just(org.fireflyframework.security.api.domain.Decision.permit());},java.time.Clock.systemUTC(),java.util.Set.of("https://native-qualification.example.test"),tenant,true);
            var boundary=new InstitutionAuthorityTransaction(new R2dbcTransactionManager(factory),entities,new InstitutionAuthorityDatabaseContext(entities,operations));var service=new io.fireflysoftware.common.config.core.services.authority.impl.TenantEligibilityServiceImpl(authorization,boundary,operations,app.getBean(NativeProposalRepository.class),app.getBean(NativeProposalRevisionRepository.class),java.time.Clock.systemUTC());
            var raw="{\"actionId\":\""+UUID.randomUUID()+"\",\"expectedProposalControlVersion\":0,\"proposalRevision\":1,\"targetIntentDigest\":\""+prepared.get("targetDigest").textValue()+"\",\"reason\":\"Typed retained target review\"}";
            var result=service.decideLifecycleProposal(tenant,prepared.get("proposalId").textValue(),"submit",raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)).block(Duration.ofSeconds(15));assertEquals("SUBMITTED",result.receipt().result().state());assertEquals(2,contexts.size());assertTrue(contexts.get(0).get("canonicalIntent").toString().contains("FF_NATIVE_AUTHORITY_PROPOSAL_READ_V1"));assertTrue(contexts.get(1).get("canonicalIntent").toString().contains(prepared.get("targetCanonical").textValue()));
        }
    }

    @Test void actualPlatformMachineCanSuspendAnotherTenantWithoutImpersonation() throws Exception {
        var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var original=TenantEligibilityNativeIT.suspendJson(UUID.randomUUID().toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var options=ConnectionFactoryOptions.parse(System.getenv("CFG_NATIVE_JDBC_URL").replace("jdbc:postgresql:","r2dbc:postgresql:")).mutate().option(ConnectionFactoryOptions.USER,"cfg_native_runtime").option(ConnectionFactoryOptions.PASSWORD,System.getenv("CFG_NATIVE_RUNTIME_PASSWORD")).build();var factory=ConnectionFactories.get(options);
        try(var app=new AnnotationConfigApplicationContext()) {
            app.registerBean(ConnectionFactory.class,()->factory);app.register(io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration.class);app.refresh();
            var operations=app.getBean(NativeAuthorityOperationsRepository.class);var entities=app.getBean("institutionAuthorityEntityOperations",R2dbcEntityTemplate.class);
            var principal=new org.fireflyframework.security.api.domain.SecurityPrincipal("platform-machine","https://issuer.example.test",platform,java.util.Set.of(),java.util.Set.of(),java.util.Map.of("exp",java.time.Instant.now().plusSeconds(300),"tenant_id",platform,"principal_kind","MACHINE","client_id","platform-client"),null,null,java.util.Set.of(),java.util.Map.of());
            var authorization=new InstitutionAuthorityAuthorization(()->Mono.just(principal),(p,a,r,c)->Mono.just(org.fireflyframework.security.api.domain.Decision.permit()),java.time.Clock.systemUTC(),java.util.Set.of("https://issuer.example.test"),platform,true);
            var boundary=new InstitutionAuthorityTransaction(new R2dbcTransactionManager(factory),entities,new InstitutionAuthorityDatabaseContext(entities,operations));var service=new io.fireflysoftware.common.config.core.services.authority.impl.TenantEligibilityServiceImpl(authorization,boundary,operations,app.getBean(NativeProposalRepository.class),app.getBean(NativeProposalRevisionRepository.class),java.time.Clock.systemUTC());
            assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.suspendTenant(tenant,original).block(Duration.ofSeconds(15))).getStatusCode().value());
            var result=service.suspendTenantAsPlatform(tenant,original).block(Duration.ofSeconds(15));assertFalse(result.receipt().result().tenantActive());assertEquals("MACHINE",result.receipt().actor().kind());assertEquals(platform,result.receipt().actor().tenantId());assertNull(result.receipt().actor().employeeId());assertEquals(result.receipt(),service.suspendTenantAsPlatform(tenant,original).block(Duration.ofSeconds(15)).receipt());
        }
    }

}

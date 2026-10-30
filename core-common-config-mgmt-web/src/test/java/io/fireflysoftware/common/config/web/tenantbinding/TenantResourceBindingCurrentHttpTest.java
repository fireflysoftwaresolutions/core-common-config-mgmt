package io.fireflysoftware.common.config.web.tenantbinding;

import io.fireflysoftware.common.config.core.authority.TenantResourceBindingLookupAuthorization;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController;
import io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.core.context.PrincipalFactory;
import org.fireflyframework.security.rs.ResourceServerAutoConfiguration;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.webflux.context.ReactorSecurityContextAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class TenantResourceBindingCurrentHttpTest {
    private static final String PATH=TenantResourceBindingCurrentBudgetFilter.PATH;
    @Test void typedNoStoreObservationUsesSignedOpaqueNamespace(){
        var calls=new AtomicInteger();var actual=new AtomicInteger();var clock=Clock.systemUTC();
        var lookup=new TenantResourceBindingLookupAuthorization(new ReactorSecurityContextAdapter(),(p,a,r,c)->Mono.just(Decision.permit()),clock,Set.of("https://issuer.example"));
        var client=client(" tenant, Ω ",b->{calls.incrementAndGet();return lookup.authorize(b).map(admission->{assertSame(b,admission.originalBudget());assertEquals(" tenant, Ω ",admission.namespace().platformTenant());actual.incrementAndGet();return TenantResourceBindingCurrentIngressTest.current(clock);});},(p,a,r,c)->Mono.just(Decision.permit()));
        client.get().uri(PATH).headers(h->{h.setBearerAuth("controlled-token");h.set("X-Tenant-Id","44444444-4444-4444-4444-444444444444");}).exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody(Current.class).value(value->{assertEquals(" tenant, Ω ",value.definition().namespace().platformTenant());assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"),value.definition().resourceTenant().value());});
        assertEquals(1,calls.get());assertEquals(1,actual.get());
    }
    @Test void repeatedCurrentKeyUsesFreshIngressAndNeverTouchesMutationCache(){
        var reads=new AtomicInteger();var cacheCalls=new AtomicInteger();var budgets=Collections.newSetFromMap(new IdentityHashMap<R2dbcOperationBudget,Boolean>());
        var cache=new org.fireflyframework.web.idempotency.cache.IdempotencyCache(){
            public Mono<org.fireflyframework.web.idempotency.model.CachedResponse> get(String key){cacheCalls.incrementAndGet();return Mono.error(new AssertionError("GET cannot read mutation cache"));}
            public Mono<Void> put(String key,org.fireflyframework.web.idempotency.model.CachedResponse value){cacheCalls.incrementAndGet();return Mono.error(new AssertionError("GET cannot write mutation cache"));}
        };
        var properties=new org.fireflyframework.web.idempotency.config.IdempotencyProperties();properties.setNamespace("controlled-current-owner");
        var filter=new org.fireflyframework.web.idempotency.filter.IdempotencyWebFilter(properties,cache);
        var client=client("tenant",budget->{budgets.add(budget);var current=TenantResourceBindingCurrentIngressTest.current(Clock.systemUTC());return Mono.just(new Current(current.reference(),current.definition(),reads.incrementAndGet(),current.observedAtEpochMillis(),current.validUntilEpochMillis()));},(p,a,r,c)->Mono.just(Decision.permit()),filter);
        for(long expected=1;expected<=2;expected++){
            long version=expected;
            client.get().uri(PATH).headers(headers->{headers.setBearerAuth("controlled-token");headers.set("X-Idempotency-Key","same-current-key");}).exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody(Current.class).value(value->assertEquals(version,value.controlVersion()));
        }
        assertEquals(2,reads.get());assertEquals(2,budgets.size());assertEquals(0,cacheCalls.get());
    }
    @Test void missingBearerNeverCallsCore(){var calls=new AtomicInteger();client("tenant",read(calls),(p,a,r,c)->Mono.just(Decision.permit())).get().uri(PATH).exchange().expectStatus().isUnauthorized().expectHeader().valueEquals("Cache-Control","no-store").expectHeader().doesNotExist("Content-Type").expectHeader().exists("WWW-Authenticate").expectBody().isEmpty();assertEquals(0,calls.get());}
    @Test void missingSignedTenantCannotUseForgedHeader(){var calls=new AtomicInteger();client(null,read(calls),(p,a,r,c)->Mono.just(Decision.permit())).get().uri(PATH).headers(h->{h.setBearerAuth("controlled-token");h.set("X-Tenant-Id","tenant");h.set("X-Platform-Tenant","tenant");}).exchange().expectStatus().isUnauthorized().expectHeader().valueEquals("Cache-Control","no-store").expectHeader().doesNotExist("Content-Type").expectHeader().exists("WWW-Authenticate").expectBody().isEmpty();assertEquals(0,calls.get());}
    @Test void ambiguousSignedTenantsNeverCallCore(){var calls=new AtomicInteger();client(List.of("tenant-a","tenant-b"),read(calls),(p,a,r,c)->Mono.just(Decision.permit())).get().uri(PATH).headers(h->h.setBearerAuth("controlled-token")).exchange().expectStatus().isUnauthorized();assertEquals(0,calls.get());}
    @Test void deniedUrlPolicyNeverCallsCore(){var calls=new AtomicInteger();client("tenant",read(calls),(p,a,r,c)->Mono.just(Decision.deny("private reason"))).get().uri(PATH).headers(h->h.setBearerAuth("controlled-token")).exchange().expectStatus().isForbidden().expectHeader().valueEquals("Cache-Control","no-store").expectHeader().doesNotExist("Content-Type").expectHeader().exists("WWW-Authenticate").expectBody().isEmpty();assertEquals(0,calls.get());}
    @Test void obligatedUrlPermitNeverCallsCore(){var calls=new AtomicInteger();client("tenant",read(calls),(p,a,r,c)->Mono.just(Decision.permit(List.of(Obligation.of("MFA",Map.of()))))).get().uri(PATH).headers(h->h.setBearerAuth("controlled-token")).exchange().expectStatus().isForbidden();assertEquals(0,calls.get());}
    @Test void deniedCoreLookupIsSanitized403(){client("tenant",b->Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,"private actor")),(p,a,r,c)->Mono.just(Decision.permit())).get().uri(PATH).headers(h->h.setBearerAuth("controlled-token")).exchange().expectStatus().isForbidden().expectHeader().valueEquals("Cache-Control","no-store").expectHeader().contentType("application/json").expectHeader().valueEquals("Cache-Control","no-store").expectBody(TenantResourceBindingCurrentController.Failure.class).isEqualTo(new TenantResourceBindingCurrentController.Failure("TENANT_RESOURCE_BINDING_PERMISSION_UNAVAILABLE"));}
    @Test void postAuthenticationLookup401HasCodeOnlyFailure(){client("tenant",b->Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"private lookup reason")),(p,a,r,c)->Mono.just(Decision.permit())).get().uri(PATH).headers(h->h.setBearerAuth("controlled-token")).exchange().expectStatus().isUnauthorized().expectHeader().contentType("application/json").expectHeader().valueEquals("Cache-Control","no-store").expectBody(TenantResourceBindingCurrentController.Failure.class).isEqualTo(new TenantResourceBindingCurrentController.Failure("TENANT_RESOURCE_BINDING_AUTHENTICATION_REQUIRED"));}
    @Test void missingNativeCapabilityIsSanitized503(){client("tenant",b->Mono.error(new IllegalStateException("private native SQL and credentials")),(p,a,r,c)->Mono.just(Decision.permit())).get().uri(PATH).headers(h->h.setBearerAuth("controlled-token")).exchange().expectStatus().isEqualTo(503).expectHeader().contentType("application/json").expectHeader().valueEquals("Cache-Control","no-store").expectBody(TenantResourceBindingCurrentController.Failure.class).isEqualTo(new TenantResourceBindingCurrentController.Failure("TENANT_RESOURCE_BINDING_UNAVAILABLE"));}
    @Test void unexpectedNamespaceQueryCannotSelectAuthority(){var calls=new AtomicInteger();client("tenant",read(calls),(p,a,r,c)->Mono.just(Decision.permit())).get().uri(PATH+"?resourceTenant=44444444-4444-4444-4444-444444444444").headers(h->h.setBearerAuth("controlled-token")).exchange().expectStatus().isBadRequest().expectHeader().contentType("application/json").expectHeader().valueEquals("Cache-Control","no-store").expectBody(TenantResourceBindingCurrentController.Failure.class).isEqualTo(new TenantResourceBindingCurrentController.Failure("INVALID_TENANT_RESOURCE_BINDING_REQUEST"));assertEquals(0,calls.get());}
    @Test void actualBodyWithoutAuthorityParametersIsRejected(){var calls=new AtomicInteger();client("tenant",read(calls),(p,a,r,c)->Mono.just(Decision.permit())).method(org.springframework.http.HttpMethod.GET).uri(PATH).headers(h->h.setBearerAuth("controlled-token")).bodyValue("{\"namespace\":\"other\"}").exchange().expectStatus().isBadRequest();assertEquals(0,calls.get());}
    @Test void controllerWithoutOriginalFilterRefuses(){var ingress=TenantResourceBindingCurrentIngressTest.ingress(b->Mono.just(TenantResourceBindingCurrentIngressTest.current(Clock.systemUTC())));WebTestClient.bindToController(new TenantResourceBindingCurrentController(ingress)).build().get().uri(PATH).exchange().expectStatus().isEqualTo(503);}
    @Test void bodyWithoutContentLengthOrTransferEncodingIsRejected(){
        var calls=new AtomicInteger();var request=org.springframework.mock.http.server.reactive.MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET,PATH).body(reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap("unexpected".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var exchange=org.springframework.mock.web.server.MockServerWebExchange.from(request);new TenantResourceBindingCurrentBudgetFilter(Clock.systemUTC(),System::nanoTime).filter(exchange,e->Mono.empty());
        var controller=new TenantResourceBindingCurrentController(TenantResourceBindingCurrentIngressTest.ingress(read(calls)));
        var error=assertThrows(ResponseStatusException.class,()->controller.current(exchange).block());assertEquals(400,error.getStatusCode().value());assertEquals(0,calls.get());
    }
    private static Function<R2dbcOperationBudget,Mono<Current>> read(AtomicInteger calls){return b->{calls.incrementAndGet();return Mono.just(TenantResourceBindingCurrentIngressTest.current(Clock.systemUTC()));};}
    private static WebTestClient client(Object tenant,Function<R2dbcOperationBudget,Mono<Current>> read,PolicyDecisionPort policy,org.springframework.web.server.WebFilter... additional){
        var properties=TenantResourceBindingCurrentReadinessTest.verified();var auto=new ResourceServerAutoConfiguration();
        var converter=auto.fireflyJwtAuthenticationConverter(new PrincipalFactory(auto.fireflyAuthorityMappingPort(properties)),properties);
        ReactiveJwtDecoder decoder=token->{var builder=Jwt.withTokenValue(token).header("alg","RS256").issuer("https://issuer.example").subject("opaque-subject").issuedAt(Instant.now().minusSeconds(1)).expiresAt(Instant.now().plusSeconds(60));if(tenant!=null)builder.claim("tenant_id",tenant);return Mono.just(builder.build());};
        var chain=auto.fireflySecurityWebFilterChain(ServerHttpSecurity.http(),decoder,converter,policy,properties);
        return WebTestClient.bindToController(new TenantResourceBindingCurrentController(TenantResourceBindingCurrentIngressTest.ingress(read)))
            .webFilter(new TenantResourceBindingCurrentBudgetFilter(Clock.systemUTC(),System::nanoTime),new WebFilterChainProxy(chain)).webFilter(additional).configureClient().responseTimeout(Duration.ofSeconds(7)).build();
    }
}

package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Namespace;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;

/** Authenticated bootstrap read; no canonical-UUID governance principal is constructed. */
public final class TenantResourceBindingLookupAuthorization {
    public static final class Admission {
        private final TenantResourceBindingLookupAuthorization owner;
        private final SecurityPrincipal principal;
        private final Namespace namespace;
        private final R2dbcOperationBudget originalBudget;
        private final long validUntil;
        private Admission(TenantResourceBindingLookupAuthorization owner,SecurityPrincipal principal,Namespace namespace,R2dbcOperationBudget originalBudget,long validUntil){this.owner=owner;this.principal=principal;this.namespace=namespace;this.originalBudget=originalBudget;this.validUntil=validUntil;}
        public Namespace namespace(){return namespace;}
        public String subject(){return principal.subject();}
        public R2dbcOperationBudget originalBudget(){return originalBudget;}
        public long validUntilEpochMillis(){return validUntil;}
        public void requireCurrent(){originalBudget.remaining();if(owner.clock.millis()>=validUntil)throw denied();}
        public Duration remaining(){requireCurrent();return minimum(originalBudget.remaining(),Duration.ofMillis(validUntil-owner.clock.millis()));}
        public Admission narrowNative(long nativeUntil){requireCurrent();if(nativeUntil<=owner.clock.millis()||nativeUntil>validUntil)throw denied();return new Admission(owner,principal,namespace,originalBudget,nativeUntil);}
    }
    private final SecurityContextPort contexts;
    private final PolicyDecisionPort policies;
    private final Clock clock;
    private final Set<String> issuers;
    public TenantResourceBindingLookupAuthorization(SecurityContextPort contexts,PolicyDecisionPort policies,Clock clock,Set<String> issuers){
        this.contexts=Objects.requireNonNull(contexts);this.policies=Objects.requireNonNull(policies);this.clock=Objects.requireNonNull(clock);this.issuers=Set.copyOf(issuers);
        if(this.issuers.isEmpty())throw new IllegalArgumentException("Verified issuer trust required");for(var issuer:issuers)new Namespace(issuer,"validation");
    }
    public Mono<Admission> authorize(R2dbcOperationBudget original){
        Objects.requireNonNull(original);
        return Mono.defer(()->{
            var remaining=original.remaining();long cap=Math.addExact(clock.millis(),minimum(remaining,Duration.ofSeconds(5)).toMillis());
            return TransactionSynchronizationManager.forCurrentTransaction().map(TransactionSynchronizationManager::isActualTransactionActive)
                .onErrorResume(NoTransactionException.class,error->Mono.just(false)).flatMap(active->{
                    if(active)throw denied();
                    return contexts.currentPrincipal().switchIfEmpty(Mono.error(denied())).flatMap(principal->{
                        var namespace=validate(principal);long until=Math.min(cap,((Instant)principal.claims().get("exp")).toEpochMilli());
                        var admission=new Admission(this,principal,namespace,original,until);admission.requireCurrent();
                        return policy(admission,"ADMISSION").thenReturn(admission);
                    });
                }).timeout(minimum(original.remaining(),Duration.ofMillis(cap-clock.millis())));
        }).onErrorMap(error->error instanceof ResponseStatusException?error:denied());
    }
    public Mono<Admission> reauthorize(Admission admission,String phase){
        return Mono.defer(()->{
            if(admission==null||admission.owner!=this||!Set.of("ACQUIRE","COMMIT","RESPONSE").contains(phase))throw denied();admission.requireCurrent();
            return contexts.currentPrincipal().switchIfEmpty(Mono.error(denied())).flatMap(current->{
                validate(current);if(!admission.principal.equals(current))throw denied();return policy(admission,phase).thenReturn(admission);
            }).timeout(admission.remaining());
        }).onErrorMap(error->error instanceof ResponseStatusException?error:denied());
    }
    private Mono<Void> policy(Admission admission,String phase){
        return Mono.defer(()->{
            admission.requireCurrent();var ns=admission.namespace;var digest=TenantResourceBindingCodec.namespaceDigest(ns);
            var context=Map.<String,Object>of("contextKind","TENANT_RESOURCE_BINDING_LOOKUP","actorIssuer",admission.principal.issuer(),"actorSubject",admission.principal.subject(),
                "platformTenant",ns.platformTenant(),"namespaceDigest",digest,"phase",phase,"validUntilEpochMillis",admission.validUntil);
            return policies.authorize(admission.principal,"configuration.tenant-resource-binding.current","platform-tenant/"+digest,context)
                .filter(decision->decision.granted()&&decision.obligations().isEmpty()).switchIfEmpty(Mono.error(denied()))
                .doOnNext(decision->admission.requireCurrent()).then().timeout(admission.remaining());
        });
    }
    private Namespace validate(SecurityPrincipal principal){
        if(principal==null||!issuers.contains(principal.issuer())||!Objects.equals(principal.claims().get("tenant_id"),principal.tenantId()))throw denied();
        AuthorityWire.text(principal.subject(),255);var namespace=new Namespace(principal.issuer(),principal.tenantId());
        if(!(principal.claims().get("exp") instanceof Instant expiry)||!expiry.isAfter(clock.instant())||expiry.isAfter(Instant.parse("9999-12-31T23:59:59Z")))throw denied();
        var nbf=principal.claims().get("nbf");if(nbf!=null&&(!(nbf instanceof Instant instant)||instant.isAfter(clock.instant())))throw denied();return namespace;
    }
    private static Duration minimum(Duration a,Duration b){return a.compareTo(b)<0?a:b;}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Tenant resource binding lookup denied");}
}

package io.fireflysoftware.common.config.core.authority;

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.*;
import java.util.*;

public final class InstitutionAuthorityAuthorization {
    public enum Operation {
        PREPARE("proposal.prepare",true), SUBMIT("proposal.submit",true),
        APPROVE("proposal.approve",true), REJECT("proposal.reject",true), WITHDRAW("proposal.withdraw",true),
        GRANT_ISSUE("grant.issue",false), GRANT_CONTROL("grant.control",false),
        TENANT_RESOURCE_BINDING_PREPARE("tenant-resource-binding.prepare",true), TENANT_RESOURCE_BINDING_SUBMIT("tenant-resource-binding.submit",true),
        TENANT_RESOURCE_BINDING_APPROVE("tenant-resource-binding.approve",true), TENANT_RESOURCE_BINDING_REJECT("tenant-resource-binding.reject",true), TENANT_RESOURCE_BINDING_WITHDRAW("tenant-resource-binding.withdraw",true),
        TENANT_RESOURCE_BINDING_ESTABLISH("tenant-resource-binding.establish",false), TENANT_RESOURCE_BINDING_REVOKE("tenant-resource-binding.revoke",false),
        TENANT_SUSPEND("tenant.suspend",false), TENANT_TRANSITION("tenant.transition",false),
        STATUS_SUSPEND("status.suspend",false), STATUS_TRANSITION("status.transition",false),
        OBSERVE("grant.observe",false), HISTORY("history",false), READ_ACTION("action.read",false), RECONCILE("action.reconcile",false);
        private final String action;
        private final boolean staffOnly;
        Operation(String action,boolean staffOnly) { this.action=action;this.staffOnly=staffOnly; }
    }
    public enum ResourceType { INSTITUTION_AUTHORITY, PROPOSAL, GRANT, TENANT_ELIGIBILITY, STATUS_ELIGIBILITY, ACTION, TENANT_RESOURCE_BINDING, TENANT_RESOURCE_BINDING_PROPOSAL }
    /** Internal owner scope; HTTP bodies never supply principal or recovery admission. */
    public record Scope(String tenantId,String institutionId,ResourceType resourceType,String resourceId,Operation operation,String canonicalIntent,String intentDigest,boolean platformRecovery) {}
    public record Actor(String kind,String issuer,String subject,String tenantId,String employeeId,String clientId,long expiresAtEpochMillis) {}

    public static final class Admission {
        private final InstitutionAuthorityAuthorization owner;
        private final Actor actor;
        private final Scope scope;
        private final long validUntilEpochMillis;
        private final Clock clock;
        private final R2dbcOperationBudget originalBudget;
        private Admission(InstitutionAuthorityAuthorization owner,Actor actor,Scope scope,long validUntilEpochMillis,Clock clock,R2dbcOperationBudget originalBudget) { this.owner=owner;this.actor=actor;this.scope=scope;this.validUntilEpochMillis=validUntilEpochMillis;this.clock=clock;this.originalBudget=originalBudget; }
        public Actor actor() { return actor; }
        public Scope scope() { return scope; }
        public long validUntilEpochMillis() { return validUntilEpochMillis; }
        public R2dbcOperationBudget originalBudget() { return originalBudget; }
        public Admission narrowNative(long nativeUntil) { requireCurrent();if(nativeUntil<=clock.millis()||nativeUntil>validUntilEpochMillis)throw denied();return new Admission(owner,actor,scope,nativeUntil,clock,originalBudget); }
        public Duration remaining() { requireCurrent();var cap=Duration.ofMillis(validUntilEpochMillis-clock.millis());if(originalBudget==null)return cap;var original=originalBudget.remaining();return original.compareTo(cap)<0?original:cap; }
        public void requireCurrent() { if(originalBudget!=null)originalBudget.remaining();if(clock.millis()>=actor.expiresAtEpochMillis() || clock.millis()>=validUntilEpochMillis) throw denied(); }
    }
    private final SecurityContextPort contexts;
    private final PolicyDecisionPort policies;
    private final Clock clock;
    private final Set<String> trustedIssuers;
    private final String platformTenant;
    private final boolean strictPolicy;

    public InstitutionAuthorityAuthorization(SecurityContextPort contexts,PolicyDecisionPort policies,Clock clock,Set<String> trustedIssuers,String platformTenant,boolean strictPolicy) {
        this.contexts=Objects.requireNonNull(contexts);this.policies=Objects.requireNonNull(policies);this.clock=Objects.requireNonNull(clock);
        this.trustedIssuers=Set.copyOf(trustedIssuers);this.platformTenant=platformTenant;this.strictPolicy=strictPolicy;
        AuthorityWire.uuid(platformTenant);
        if(this.trustedIssuers.isEmpty()) throw new IllegalArgumentException("Issuer trust is required");
        for(var issuer:this.trustedIssuers) { AuthorityWire.text(issuer,500);var uri=URI.create(issuer);if(!uri.isAbsolute() || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("Invalid issuer trust"); }
    }
    public String platformTenantId(){return platformTenant;}
    public Mono<Admission> authorize(Scope scope) {
        return Mono.defer(()->{
            if(scope!=null&&(scope.resourceType()==ResourceType.TENANT_RESOURCE_BINDING||scope.resourceType()==ResourceType.TENANT_RESOURCE_BINDING_PROPOSAL))throw denied();
            return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(manager->manager.isActualTransactionActive()?Mono.<Admission>error(denied()):admit(scope,null,Long.MAX_VALUE))
                .onErrorResume(NoTransactionException.class,error->admit(scope,null,Long.MAX_VALUE));})
            .onErrorMap(error->error instanceof ResponseStatusException?error:denied());
    }
    public Mono<Admission> authorize(Scope scope,R2dbcOperationBudget originalBudget) {
        Objects.requireNonNull(originalBudget);
        return Mono.defer(()->{
            var remaining=originalBudget.remaining();
            long originalCap=Math.addExact(clock.millis(),minimum(remaining,Duration.ofSeconds(30)).toMillis());
            return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(manager->manager.isActualTransactionActive()?Mono.<Admission>error(denied()):admit(scope,originalBudget,originalCap))
                .onErrorResume(NoTransactionException.class,error->admit(scope,originalBudget,originalCap))
                .timeout(minimum(originalBudget.remaining(),Duration.ofMillis(originalCap-clock.millis())));
        }).onErrorMap(error->error instanceof ResponseStatusException?error:denied());
    }
    public Mono<Admission> reauthorize(Admission original) {
        return Mono.defer(()->{
            if(original==null||original.owner!=this)throw denied();original.requireCurrent();
            return admit(original.scope,original.originalBudget,original.validUntilEpochMillis)
                .filter(current->current.actor.equals(original.actor)).switchIfEmpty(Mono.error(denied()))
                .doOnNext(current->original.requireCurrent()).thenReturn(original).timeout(original.remaining());
        }).onErrorMap(error->error instanceof ResponseStatusException?error:denied());
    }
    private Mono<Admission> admit(Scope scope,R2dbcOperationBudget originalBudget,long originalCap) {
        return Mono.defer(()-> {
            if(originalBudget!=null)originalBudget.remaining();
            if(!strictPolicy) return Mono.error(denied());
            validate(scope);
            return contexts.currentPrincipal().switchIfEmpty(Mono.error(denied())).flatMap(principal-> {
                var actor=actor(principal);
                if(scope.operation().staffOnly&&!actor.kind().equals("STAFF")) return Mono.error(denied());
                if(scope.platformRecovery()) { if(!actor.tenantId().equals(platformTenant)) return Mono.error(denied()); }
                else if(!actor.tenantId().equals(scope.tenantId())) return Mono.error(denied());
                if(scope.resourceType()==ResourceType.STATUS_ELIGIBILITY&&!scope.platformRecovery()) return Mono.error(denied());
                long until=Math.min(originalCap,Math.min(actor.expiresAtEpochMillis(),Math.addExact(clock.millis(),30000L)));
                var context=new HashMap<String,Object>();
                context.put("actorKind",actor.kind());context.put("actorIssuer",actor.issuer());context.put("actorSubject",actor.subject());context.put("actorTenantId",actor.tenantId());context.put("tenantId",scope.tenantId());
                if(actor.employeeId()!=null) context.put("staffActorId",actor.employeeId());
                if(actor.clientId()!=null) context.put("clientId",actor.clientId());
                if(scope.institutionId()!=null) context.put("institutionId",scope.institutionId());
                context.put("resourceType",scope.resourceType().name());context.put("resourceId",scope.resourceId());context.put("canonicalIntent",scope.canonicalIntent());context.put("intentDigest",scope.intentDigest());context.put("platformRecovery",scope.platformRecovery());
                var admission=new Admission(this,actor,scope,until,clock,originalBudget);admission.requireCurrent();
                return Mono.defer(()->policies.authorize(principal,"configuration.institution.authority."+scope.operation().action,"tenant/"+scope.tenantId()+"/"+scope.resourceType().name()+"/"+scope.resourceId(),Map.copyOf(context)))
                    .timeout(originalBudget==null?Duration.ofSeconds(5):minimum(Duration.ofSeconds(5),originalBudget.remaining())).filter(decision->decision.granted()&&decision.obligations().isEmpty())
                    .switchIfEmpty(Mono.error(denied())).map(decision->{admission.requireCurrent();return admission;});
            });
        });
    }
    private static Duration minimum(Duration a,Duration b){return a.compareTo(b)<0?a:b;}
    private Actor actor(SecurityPrincipal principal) {
        AuthorityWire.text(principal.subject(),255);AuthorityWire.text(principal.issuer(),500);AuthorityWire.uuid(principal.tenantId());
        if(!trustedIssuers.contains(principal.issuer()) || !Objects.equals(principal.claims().get("tenant_id"),principal.tenantId())) throw denied();
        if(!(principal.claims().get("exp") instanceof Instant expiry) || !expiry.isAfter(clock.instant()) || expiry.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw denied();
        var notBefore=principal.claims().get("nbf");if(notBefore!=null&&(!(notBefore instanceof Instant nbf)||nbf.isAfter(clock.instant()))) throw denied();
        var kind=principal.claims().get("principal_kind");String employee=null;String client=null;
        if("STAFF".equals(kind)) {
            if(!(principal.claims().get("staff_actor_id") instanceof String id)) throw denied();AuthorityWire.uuid(id);employee=id;
            if(principal.claims().get("client_id") instanceof String value) { AuthorityWire.text(value,255);client=value; }
        } else if("MACHINE".equals(kind)) {
            if(principal.claims().containsKey("staff_actor_id") || !(principal.claims().get("client_id") instanceof String id)) throw denied();AuthorityWire.text(id,255);client=id;
        } else throw denied();
        return new Actor((String)kind,principal.issuer(),principal.subject(),principal.tenantId(),employee,client,expiry.toEpochMilli());
    }
    private static void validate(Scope scope) {
        if(scope==null || scope.operation()==null || scope.resourceType()==null) throw denied();
        boolean bindingOperation=scope.operation()==Operation.TENANT_RESOURCE_BINDING_ESTABLISH||scope.operation()==Operation.TENANT_RESOURCE_BINDING_REVOKE;
        if(bindingOperation!=(scope.resourceType()==ResourceType.TENANT_RESOURCE_BINDING)||bindingOperation&&!scope.platformRecovery())throw denied();
        boolean bindingProposal=scope.operation()==Operation.TENANT_RESOURCE_BINDING_PREPARE||scope.operation()==Operation.TENANT_RESOURCE_BINDING_SUBMIT||scope.operation()==Operation.TENANT_RESOURCE_BINDING_APPROVE||scope.operation()==Operation.TENANT_RESOURCE_BINDING_REJECT||scope.operation()==Operation.TENANT_RESOURCE_BINDING_WITHDRAW;
        if(bindingProposal!=(scope.resourceType()==ResourceType.TENANT_RESOURCE_BINDING_PROPOSAL)||bindingProposal&&!scope.platformRecovery())throw denied();
        AuthorityWire.uuid(scope.tenantId());AuthorityWire.uuid(scope.resourceId());if(scope.institutionId()!=null) AuthorityWire.uuid(scope.institutionId());
        AuthorityWire.digest(scope.intentDigest());AuthorityWire.text(scope.canonicalIntent(),262144);if(scope.canonicalIntent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144) throw denied();if(!AuthorityWire.sha(scope.canonicalIntent()).equals(scope.intentDigest())) throw denied();
    }
    public static void requireIndependentStaff(Actor maker,Actor checker) {
        if(maker==null || checker==null || !maker.kind().equals("STAFF") || !checker.kind().equals("STAFF") || maker.employeeId()==null || checker.employeeId()==null || maker.employeeId().equals(checker.employeeId()) || maker.issuer().equals(checker.issuer())&&maker.subject().equals(checker.subject())) throw denied();
    }
    private static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Institution authority denied"); }
}

/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import java.time.*;
import java.net.URI;
import java.util.*;
import java.util.function.LongSupplier;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import reactor.core.publisher.Mono;

public final class DocumentIssuerAuthorization {
    private final SecurityContextPort security;
    private final PolicyDecisionPort policies;
    private final String issuer,audience,tenantClaim,clientClaim;
    private final Set<String> clientIds;
    private final Clock clock;
    private final LongSupplier nanos;
    public record Scope(String tenantId,String issuerId,String publicationId,String actionId,
            Operation operation,String intentDigest,Long fixedUntilEpochMillis,Use documentUse,String brandingId) {
        public Scope(String tenantId,String issuerId,String publicationId,String actionId,Operation operation,String intentDigest,Long fixedUntilEpochMillis,Use documentUse){this(tenantId,issuerId,publicationId,actionId,operation,intentDigest,fixedUntilEpochMillis,documentUse,null);}
        public Scope(String tenantId,String issuerId,String publicationId,String actionId,Operation operation,String intentDigest,Long fixedUntilEpochMillis){this(tenantId,issuerId,publicationId,actionId,operation,intentDigest,fixedUntilEpochMillis,null);}
    }
    public final class Permit {
        private final DocumentIssuerAuthorization owner=DocumentIssuerAuthorization.this;
        private final SecurityPrincipal principal;
        private final Scope scope;
        private final R2dbcOperationBudget budget;
        private final Instant observedAt,originalUntil,effectiveUntil;
        private final long beforeNanos;
        private final boolean recoveryOnly;
        private Permit(SecurityPrincipal principal,Scope scope,R2dbcOperationBudget budget,Instant observedAt,Instant originalUntil,Instant effectiveUntil,long beforeNanos,boolean recoveryOnly){this.recoveryOnly=recoveryOnly;this.principal=principal;this.scope=scope;this.budget=budget;this.observedAt=observedAt;this.originalUntil=originalUntil;this.effectiveUntil=effectiveUntil;this.beforeNanos=beforeNanos;}
        public Actor actor(){return new Actor(principal.issuer(),principal.subject());}
        public Scope scope(){return scope;}
        public boolean recoveryOnly(){return recoveryOnly;}
        public R2dbcOperationBudget budget(){return budget;}
        public Instant observedAt(){return observedAt;}
        public Instant originalUntil(){return originalUntil;}
        public Instant effectiveUntil(){return effectiveUntil;}
        public long beforeNanos(){return beforeNanos;}
        Permit narrow(R2dbcOperationBudget cap){return new Permit(principal,scope,budget.intersect(cap),observedAt,originalUntil,effectiveUntil,beforeNanos,recoveryOnly);}
        public Permit narrowNative(DocumentIssuerNativeWindow window,Clock clock,LongSupplier nanos){
            requireTimeSources(clock,nanos);
            return new Permit(principal,scope,window.cap(this,clock,nanos),observedAt,originalUntil,minimum(effectiveUntil,window.deadline(this)),beforeNanos,recoveryOnly);
        }
        public Permit resolveIssuer(String actualIssuerId){
            AuthorityWire.uuid(actualIssuerId);
            if(scope.operation()!=Operation.CURRENT||scope.issuerId()!=null&&!scope.issuerId().equals(actualIssuerId))throw unavailable();
            var resolved=new Scope(scope.tenantId(),actualIssuerId,scope.publicationId(),scope.actionId(),scope.operation(),scope.intentDigest(),scope.fixedUntilEpochMillis(),scope.documentUse());
            return new Permit(principal,resolved,budget,observedAt,originalUntil,effectiveUntil,beforeNanos,recoveryOnly);
        }
        @Override public String toString(){return "DocumentIssuerPermit";}
    }
    public DocumentIssuerAuthorization(SecurityContextPort security,PolicyDecisionPort policies,
            String issuer,String audience,String tenantClaim,String clientClaim,String clientId,Clock clock,LongSupplier nanos) {
        this(security,policies,issuer,audience,tenantClaim,clientClaim,Set.of(clientId),clock,nanos);
    }
    public DocumentIssuerAuthorization(SecurityContextPort security,PolicyDecisionPort policies,
            String issuer,String audience,String tenantClaim,String clientClaim,Set<String> clientIds,Clock clock,LongSupplier nanos) {
        this.security=Objects.requireNonNull(security);this.policies=Objects.requireNonNull(policies);
        DocumentIssuerCodec.text(issuer,1,500);DocumentIssuerCodec.text(audience,1,255);
        if(clientIds==null||clientIds.isEmpty())throw unavailable();
        this.clientIds=Set.copyOf(clientIds);for(var clientId:this.clientIds)DocumentIssuerCodec.text(clientId,1,255);
        var uri=URI.create(issuer);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw unavailable();
        for(var key:new String[]{tenantClaim,clientClaim})if(key==null||!key.matches("[A-Za-z][A-Za-z0-9_:.]{0,63}")
                ||Set.of("iss","sub","exp","nbf","aud").contains(key))throw unavailable();
        if(tenantClaim.equals(clientClaim))throw unavailable();
        this.issuer=issuer;this.audience=audience;this.tenantClaim=tenantClaim;this.clientClaim=clientClaim;
        this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }
    public Mono<Permit> authorize(Scope scope){return authorize(scope,false);}
    public Mono<Permit> authorizeRecovery(Scope scope){return authorize(scope,true);}
    private Mono<Permit> authorize(Scope scope,boolean recovery){
        return Mono.defer(()->{
            if(recovery&&(scope==null||!Set.of(Operation.PREPARE,Operation.PUBLISH,Operation.REVOKE).contains(scope.operation())))throw unavailable();
            validateScope(scope);var observed=clock.instant();long before=nanos.getAsLong();
            var initialUntil=minimum(observed.plusSeconds(2),!recovery&&positive(scope)?Instant.ofEpochMilli(scope.fixedUntilEpochMillis()):observed.plusSeconds(2));
            var initial=R2dbcOperationBudget.observed(observed,initialUntil,before,Duration.ofSeconds(2),clock,nanos);
            return security.currentPrincipal().switchIfEmpty(Mono.error(unavailable())).flatMap(principal->{
                var expiry=validatePrincipal(principal,scope,recovery);var until=minimum(expiry,observed.plusSeconds(5));
                if(!recovery&&positive(scope))until=minimum(until,Instant.ofEpochMilli(scope.fixedUntilEpochMillis()));
                var budget=R2dbcOperationBudget.observed(observed,until,before,Duration.ofSeconds(5),clock,nanos);
                var callerInitial=initial.intersect(budget);var permit=new Permit(principal,scope,budget,observed,until,until,before,recovery);
                return decide(principal,scope,"ADMISSION",recovery).then(Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(unavailable())))
                    .map(current->{callerInitial.remaining();if(!principal.equals(current))throw unavailable();validatePrincipal(current,scope,recovery);budget.remaining();return permit;})
                    .timeout(callerInitial.remaining());
            }).timeout(initial.remaining());
        }).onErrorMap(error->unavailable());
    }
    public Mono<Void> reauthorize(Permit permit){
        return reauthorize(permit,"RESPONSE");
    }
    public Mono<Void> beforeCommit(Permit permit){return reauthorize(permit,"COMMIT");}
    public void requireCurrent(Permit permit){if(permit==null||permit.owner!=this)throw unavailable();permit.budget.remaining();}
    public void requireTimeSources(Clock clock,LongSupplier nanos){if(this.clock!=clock||this.nanos!=nanos)throw unavailable();}
    private Mono<Void> reauthorize(Permit permit,String phase){
        return Mono.defer(()->{
            if(permit==null||permit.owner!=this)throw unavailable();permit.budget.remaining();
            return security.currentPrincipal().switchIfEmpty(Mono.error(unavailable())).flatMap(principal->{
                if(!permit.principal.equals(principal))throw unavailable();validatePrincipal(principal,permit.scope,permit.recoveryOnly);
                return decide(principal,permit.scope,phase,permit.recoveryOnly).then(Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(unavailable())))
                    .flatMap(current->{if(!permit.principal.equals(current))throw unavailable();validatePrincipal(current,permit.scope,permit.recoveryOnly);permit.budget.remaining();return Mono.<Void>empty();});
            }).timeout(permit.budget.remaining());
        }).onErrorMap(error->unavailable());
    }
    private void validateScope(Scope scope){
        if(scope==null||scope.operation()==null)throw unavailable();
        for(var id:List.of(scope.tenantId(),scope.publicationId(),scope.actionId()))AuthorityWire.uuid(id);
        if(scope.issuerId()!=null)AuthorityWire.uuid(scope.issuerId());else if(scope.operation()!=Operation.CURRENT)throw unavailable();
        AuthorityWire.digest(scope.intentDigest());
        if(scope.operation()==Operation.CURRENT||scope.operation()==Operation.SOURCE)DocumentIssuerCodec.useKey(scope.documentUse());
        if(scope.operation()==Operation.SOURCE)AuthorityWire.uuid(scope.brandingId());else if(scope.brandingId()!=null)throw unavailable();
        if(scope.operation()==Operation.PREPARE&&scope.fixedUntilEpochMillis()==null)throw unavailable();
        if(positive(scope)&&(scope.fixedUntilEpochMillis()<1||scope.fixedUntilEpochMillis()>253402300799999L))throw unavailable();
    }
    private Instant validatePrincipal(SecurityPrincipal principal,Scope scope,boolean recovery){
        DocumentIssuerCodec.text(principal.issuer(),1,500);DocumentIssuerCodec.text(principal.subject(),1,255);
        var now=clock.instant();String operation=(recovery?Operation.RECOVER:scope.operation()).name().toLowerCase(Locale.ROOT);
        if(!issuer.equals(principal.issuer())||!scope.tenantId().equals(principal.tenantId())||!scope.tenantId().equals(principal.claim(tenantClaim,String.class))
                ||!principal.hasScope("configuration:document-issuers:"+operation))throw unavailable();
        var expiry=principal.claim("exp",Instant.class);var nbf=principal.claim("nbf",Instant.class);var aud=principal.claim("aud",List.class);
        if(expiry==null||nbf==null||!now.isBefore(expiry)||now.isBefore(nbf)||!nbf.isBefore(expiry)||expiry.isAfter(Instant.parse("9999-12-31T23:59:59Z"))
                ||aud==null||aud.size()!=1||!audience.equals(aud.getFirst())||!clientIds.contains(principal.claim(clientClaim,String.class)))throw unavailable();
        if(!recovery&&positive(scope)&&!now.isBefore(Instant.ofEpochMilli(scope.fixedUntilEpochMillis())))throw unavailable();
        return expiry;
    }
    private Mono<Void> decide(SecurityPrincipal principal,Scope scope,String phase,boolean recovery){
        String resource="tenant/"+scope.tenantId()+"/document-issuer"+(scope.issuerId()==null?"":"/"+scope.issuerId())+"/publication/"+scope.publicationId()+"/action/"+scope.actionId();
        var context=new HashMap<String,Object>(Map.of("tenantId",scope.tenantId(),"publicationId",scope.publicationId(),
            "actionId",scope.actionId(),"operation",(recovery?Operation.RECOVER:scope.operation()).name(),"intentDigest",scope.intentDigest(),"phase",phase,
            "purpose",Purpose.DEPOSIT_ACCOUNT_ENROLLMENT.name()));
        if(recovery)context.put("recoveredOperation",scope.operation().name());
        if(scope.issuerId()!=null)context.put("issuerId",scope.issuerId());
        if(scope.brandingId()!=null)context.put("brandingId",scope.brandingId());
        if(scope.documentUse()!=null){context.put("documentType",scope.documentUse().documentType());context.put("locale",scope.documentUse().locale());}
        return Mono.defer(()->policies.authorize(principal,"configuration.document-issuer."+(recovery?Operation.RECOVER:scope.operation()).name().toLowerCase(Locale.ROOT),resource,Map.copyOf(context)))
            .filter(decision->decision.granted()&&decision.obligations().isEmpty()).switchIfEmpty(Mono.error(unavailable())).then();
    }
    private static boolean positive(Scope scope){return scope.fixedUntilEpochMillis()!=null&&(scope.operation()==Operation.PREPARE||scope.operation()==Operation.PUBLISH);}
    private static Instant minimum(Instant first,Instant second){return first.isBefore(second)?first:second;}
    private static IllegalStateException unavailable(){return new IllegalStateException("DOCUMENT_ISSUER_AUTHORITY_UNAVAILABLE");}
}

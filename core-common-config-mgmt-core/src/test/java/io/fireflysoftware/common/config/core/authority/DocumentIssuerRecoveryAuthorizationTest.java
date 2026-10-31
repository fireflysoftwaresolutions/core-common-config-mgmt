/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.Operation;
import java.lang.reflect.InvocationTargetException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIssuerRecoveryAuthorizationTest {
    static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z");
    final AtomicLong ticks=new AtomicLong();final LongSupplier nanos=ticks::get;final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    final DocumentIssuerAuthorization.Scope scope=new DocumentIssuerAuthorization.Scope(DocumentIssuerApiContractTest.TENANT,"10000000-0000-4000-8000-000000000002","10000000-0000-4000-8000-000000000003","10000000-0000-4000-8000-000000000004",Operation.PREPARE,"a".repeat(64),NOW.minusSeconds(1).toEpochMilli());
    SecurityPrincipal principal(String client,Set<String> scopes){return new SecurityPrincipal("maker","https://identity.example",scope.tenantId(),Set.of(),scopes,Map.of("tenant_id",scope.tenantId(),"exp",NOW.plusSeconds(60),"nbf",NOW.minusSeconds(1),"aud",List.of("docs"),"azp",client),NOW,null,Set.of(),Map.of());}
    DocumentIssuerAuthorization authority(SecurityPrincipal principal,PolicyDecisionPort pdp)throws Exception{
        var ctor=DocumentIssuerAuthorization.class.getConstructor(SecurityContextPort.class,PolicyDecisionPort.class,String.class,String.class,String.class,String.class,Set.class,Clock.class,LongSupplier.class);
        return ctor.newInstance((SecurityContextPort)()->Mono.just(principal),pdp,"https://identity.example","docs","tenant_id","azp",Set.of("staff","product"),clock,nanos);
    }
    @SuppressWarnings("unchecked") DocumentIssuerAuthorization.Permit recovery(DocumentIssuerAuthorization a,DocumentIssuerAuthorization.Scope request)throws Exception{
        var method=a.getClass().getMethod("authorizeRecovery",DocumentIssuerAuthorization.Scope.class);
        try{return ((Mono<DocumentIssuerAuthorization.Permit>)method.invoke(a,request)).block();}catch(InvocationTargetException error){throw (RuntimeException)error.getCause();}
    }
    @Test void recoveryPreservesOriginalIntentButUsesExplicitCurrentRecoveryPermission()throws Exception{
        var phases=new ArrayList<String>();var a=authority(principal("staff",Set.of("configuration:document-issuers:recover")),(p,action,resource,context)->{assertEquals("configuration.document-issuer.recover",action);assertEquals("RECOVER",context.get("operation"));assertEquals("PREPARE",context.get("recoveredOperation"));assertEquals(scope.intentDigest(),context.get("intentDigest"));phases.add((String)context.get("phase"));return Mono.just(Decision.permit());});
        var permit=recovery(a,scope);assertEquals(scope,permit.scope());assertEquals(Duration.ofSeconds(5),permit.budget().remaining());ticks.set(1_000_000_000L);a.reauthorize(permit).block();assertEquals(Duration.ofSeconds(4),permit.budget().remaining());assertEquals(List.of("ADMISSION","RESPONSE"),phases);
    }
    @Test void writePermissionDoesNotImplyRecoveryPermission()throws Exception{var calls=new AtomicInteger();var a=authority(principal("staff",Set.of("configuration:document-issuers:prepare")),(p,action,r,c)->{calls.incrementAndGet();return Mono.just(Decision.permit());});assertThrows(IllegalStateException.class,()->recovery(a,scope));assertEquals(0,calls.get());}
    @Test void recoveryPermissionDoesNotRenewOrAuthorizeExpiredMutation()throws Exception{var a=authority(principal("staff",Set.of("configuration:document-issuers:recover")),(p,action,r,c)->Mono.just(Decision.permit()));assertThrows(IllegalStateException.class,()->a.authorize(scope).block());assertNotNull(recovery(a,scope));}
    @Test void unconfiguredClientFailsBeforePolicy()throws Exception{var calls=new AtomicInteger();var a=authority(principal("unconfigured",Set.of("configuration:document-issuers:recover")),(p,action,r,c)->{calls.incrementAndGet();return Mono.just(Decision.permit());});assertThrows(IllegalStateException.class,()->recovery(a,scope));assertEquals(0,calls.get());}
    @Test void originalRecoveryBudgetCannotBeRenewedByLaterPolicy()throws Exception{var a=authority(principal("staff",Set.of("configuration:document-issuers:recover")),(p,action,r,c)->Mono.just(Decision.permit()));var permit=recovery(a,scope);ticks.set(5_000_000_000L);assertThrows(IllegalStateException.class,()->a.reauthorize(permit).block());}
}

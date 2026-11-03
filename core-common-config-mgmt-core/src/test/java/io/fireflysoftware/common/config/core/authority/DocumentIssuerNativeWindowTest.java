/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.authority;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import org.fireflyframework.security.api.domain.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.Operation;

class DocumentIssuerNativeWindowTest {
    static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z");
    final AtomicReference<Instant> wall=new AtomicReference<>(NOW);final AtomicLong ticks=new AtomicLong();final LongSupplier nanos=ticks::get;
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return wall.get();}};
    DocumentIssuerAuthorization.Permit permit(){var claims=Map.<String,Object>of("tenant_id",DocumentIssuerCodecTest.TENANT,"exp",NOW.plusSeconds(60),"nbf",NOW.minusSeconds(1),"aud",List.of("docs"),"azp","operator");var p=new SecurityPrincipal("checker","https://identity.example",DocumentIssuerCodecTest.TENANT,Set.of(),Set.of("configuration:document-issuers:publish"),claims,NOW,null,Set.of(),Map.of());return new DocumentIssuerAuthorization(()->Mono.just(p),(principal,a,r,c)->Mono.just(Decision.permit()),"https://identity.example","docs","tenant_id","azp","operator",clock,nanos).authorize(new DocumentIssuerAuthorization.Scope(DocumentIssuerCodecTest.TENANT,DocumentIssuerCodecTest.ISSUER,DocumentIssuerCodecTest.PUBLICATION,DocumentIssuerCodecTest.ACTION,Operation.PUBLISH,"a".repeat(64),NOW.plusSeconds(60).toEpochMilli())).block();}
    DocumentIssuerNativeWindow window(long lifetime,long before){return new DocumentIssuerNativeWindow(NOW,NOW.toEpochMilli(),NOW.toEpochMilli()+lifetime,before);}
    @Test void floorQuantumIsConservativelyChargedBeforeNativeObservation(){var p=permit();var cap=window(100,0).cap(p,clock,nanos);assertEquals(Duration.ofMillis(99),cap.remaining());ticks.set(99_000_000L);assertThrows(IllegalStateException.class,cap::remaining);}
    @Test void originalAndNativeAbsoluteLimitsRemainIndependent(){var p=permit();var cap=window(100,0).cap(p,clock,nanos);wall.set(NOW.plusMillis(100));assertThrows(IllegalStateException.class,cap::remaining);}
    @Test void malformedFloorAndRenewedNativeHorizonRefuse(){var p=permit();assertThrows(IllegalArgumentException.class,()->new DocumentIssuerNativeWindow(NOW,NOW.toEpochMilli()-1,NOW.toEpochMilli()+100,0).cap(p,clock,nanos));assertThrows(IllegalArgumentException.class,()->window(5001,0).cap(p,clock,nanos));}
    @Test void noPositiveBudgetExistsAfterOneMillisecondQuantum(){var p=permit();assertThrows(IllegalArgumentException.class,()->window(1,0).cap(p,clock,nanos));}
    @Test void nativeAnchorCannotPrecedeOriginalInvocation(){var p=permit();assertThrows(IllegalArgumentException.class,()->window(100,-1).cap(p,clock,nanos));}
    @Test void delayedNativeCallKeepsOriginalNanosAnchorAndDoesNotUseNowPlusRemaining(){var p=permit();ticks.set(50_000_000L);var stamp=NOW.plusMillis(20);var cap=new DocumentIssuerNativeWindow(stamp,stamp.toEpochMilli(),stamp.toEpochMilli()+100,20_000_000L).cap(p,clock,nanos);assertEquals(Duration.ofMillis(69),cap.remaining());ticks.set(119_000_000L);assertThrows(IllegalStateException.class,cap::remaining);}
    @Test void laterNativePassCannotDropEarlierNativeCap(){var p=permit();var first=p.narrow(window(100,0).cap(p,clock,nanos));ticks.set(50_000_000L);var second=window(1000,50_000_000L).cap(first,clock,nanos);assertEquals(Duration.ofMillis(49),second.remaining());ticks.set(99_000_000L);assertThrows(IllegalStateException.class,second::remaining);}
}

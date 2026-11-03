/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.persistence.documentissuer;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.core.services.documentissuer.impl.DocumentIssuerServiceImpl;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import java.lang.reflect.InvocationTargetException;
import java.time.*;
import java.util.*;
import org.fireflyframework.core.transaction.RootTransactionException;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Supplied mapped rows and controlled SPI; genuine Spring/readback, not native authority proof. */
class DocumentIssuerRecoveryServiceTest {
    final DocumentIssuerTransactionTest fixture=new DocumentIssuerTransactionTest();
    final DocumentIssuerTransactionTest.Driver driver=new DocumentIssuerTransactionTest.Driver();
    static Prepare request(){var p=DocumentIssuerServiceTest.request();var d=p.definition();long now=DocumentIssuerTransactionTest.NOW.toEpochMilli();return new Prepare(p.actionId(),p.publicationId(),0,new Definition(d.issuerId(),d.revision(),d.issuerCode(),d.displayName(),d.contactEmail(),d.uses(),d.source(),now-60000,now-1));}
    static byte[] body(){try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(request());}catch(Exception error){throw new AssertionError(error);}}
    DocumentIssuerAuthorization authority(boolean responseDeny){String tenant=DocumentIssuerTransactionTest.TENANT;var now=DocumentIssuerTransactionTest.NOW;var principal=new SecurityPrincipal("maker","https://identity.example",tenant,Set.of(),Set.of("configuration:document-issuers:recover"),Map.of("tenant_id",tenant,"exp",now.plusSeconds(60),"nbf",now.minusSeconds(1),"aud",List.of("docs"),"azp","staff"),now,null,Set.of(),Map.of());return new DocumentIssuerAuthorization(()->Mono.just(principal),(p,a,r,c)->Mono.just(responseDeny&&"RESPONSE".equals(c.get("phase"))?org.fireflyframework.security.api.domain.Decision.deny("ordinary denial"):org.fireflyframework.security.api.domain.Decision.permit()),"https://identity.example","docs","tenant_id","azp","staff",fixture.clock,fixture.nanos);}
    DocumentIssuerServiceImpl service(boolean deny){var a=authority(deny);return new DocumentIssuerServiceImpl(a,new DocumentIssuerTransaction(driver,a,fixture.clock,fixture.nanos));}
    @SuppressWarnings("unchecked") Receipt recover(DocumentIssuerServiceImpl service)throws Exception{var method=service.getClass().getMethod("recoverPrepare",String.class,byte[].class);try{return ((Mono<Receipt>)method.invoke(service,DocumentIssuerTransactionTest.TENANT,body())).block();}catch(InvocationTargetException error){throw (RuntimeException)error.getCause();}}
    Receipt retained(){var p=request();var intent=DocumentIssuerCodec.prepare(DocumentIssuerTransactionTest.TENANT,body());var now=DocumentIssuerTransactionTest.NOW;var receipt=new Receipt(DocumentIssuerTransactionTest.TENANT,p.definition().issuerId(),p.publicationId(),1,1,p.actionId(),Operation.PREPARE,State.PREPARED,DocumentIssuerCodec.definitionDigest(p.definition()),intent.intentDigest(),now.minusSeconds(10).toEpochMilli());var stamp=now.minusSeconds(10).atOffset(ZoneOffset.UTC);driver.actionData=Map.of("action_id",UUID.fromString(p.actionId()),"tenant_id",UUID.fromString(DocumentIssuerTransactionTest.TENANT),"issuer_id",UUID.fromString(p.definition().issuerId()),"operation","PREPARE","intent_digest",intent.intentDigest(),"result_json",DocumentIssuerCodec.receiptJson(receipt),"result_digest",DocumentIssuerCodec.receiptDigest(receipt),"recorded_at",stamp);driver.auditData=Map.of("action_id",UUID.fromString(p.actionId()),"tenant_id",UUID.fromString(DocumentIssuerTransactionTest.TENANT),"issuer_id",UUID.fromString(p.definition().issuerId()),"operation","PREPARE","intent_digest",intent.intentDigest(),"actor_issuer","https://identity.example","actor_subject","maker","recorded_at",stamp);return receipt;}
    void noWrites(){assertTrue(driver.sql.stream().noneMatch(s->s.startsWith("INSERT ")||s.startsWith("UPDATE ")||s.startsWith("DELETE ")));}
    @Test void missingRetainedActionCannotCreateOnRecovery(){assertThrows(RootTransactionException.class,()->recover(service(false)));noWrites();assertEquals(1,driver.rollbacks);assertEquals(0,driver.commits);}
    @Test void exactOwnerRecoveryReturnsOnlyOriginalExpiredOutcome()throws Exception{var expected=retained();assertEquals(expected,recover(service(false)));noWrites();assertEquals(1,driver.commits);assertEquals(1,driver.closes);assertTrue(driver.sql.stream().noneMatch(s->s.contains("cfg_document_issuer_current_source")));}
    @Test void differentRetainedOwnerCannotRecover(){retained();driver.auditData=new HashMap<>(driver.auditData);driver.auditData.put("actor_subject","other");assertThrows(RootTransactionException.class,()->recover(service(false)));noWrites();assertEquals(1,driver.rollbacks);}
    @Test void differentIntentCannotRecoverOrCreate(){retained();driver.actionData=new HashMap<>(driver.actionData);driver.actionData.put("intent_digest","b".repeat(64));assertThrows(RootTransactionException.class,()->recover(service(false)));noWrites();assertEquals(1,driver.rollbacks);}
    @Test void currentResponsePolicyDenialWithholdsHistoricalReceipt(){retained();assertThrows(IllegalStateException.class,()->recover(service(true)));noWrites();assertEquals(1,driver.commits);}
    @Test void privateRecoveryPermitCannotExposeWrites(){var a=authority(false);var p=request();var intent=DocumentIssuerCodec.prepare(DocumentIssuerTransactionTest.TENANT,body());var permit=a.authorizeRecovery(new DocumentIssuerAuthorization.Scope(DocumentIssuerTransactionTest.TENANT,p.definition().issuerId(),p.publicationId(),p.actionId(),Operation.PREPARE,intent.intentDigest(),p.definition().effectiveUntilEpochMillis())).block();var tx=new DocumentIssuerTransaction(driver,a,fixture.clock,fixture.nanos);assertThrows(RootTransactionException.class,()->tx.command(permit,session->session.open(body()).then(Mono.fromSupplier(session::writes))).block());noWrites();assertEquals(1,driver.rollbacks);}
}

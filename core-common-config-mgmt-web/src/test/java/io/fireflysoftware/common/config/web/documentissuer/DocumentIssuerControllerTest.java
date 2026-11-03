/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.web.documentissuer;

import io.fireflysoftware.common.config.core.authority.DocumentIssuerCodec;
import io.fireflysoftware.common.config.core.services.documentissuer.DocumentIssuerService;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.fireflyframework.core.transaction.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Direct controller boundary, no HTTP server, tokens or database. */
class DocumentIssuerControllerTest {
    static final String TENANT="10000000-0000-4000-8000-000000000001";
    final AtomicReference<String> method=new AtomicReference<>();final AtomicReference<byte[]> original=new AtomicReference<>();
    final AtomicReference<RuntimeException> error=new AtomicReference<>();
    DocumentIssuerService port(){return (DocumentIssuerService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DocumentIssuerService.class},(proxy,called,args)->{method.set(called.getName());if(args.length==2&&args[1] instanceof byte[] bytes)original.set(bytes.clone());return Mono.error(error.get()==null?new IllegalStateException("DOCUMENT_ISSUER_SOURCE_UNAVAILABLE"):error.get());});}
    Object controller()throws Exception{return Class.forName("io.fireflysoftware.common.config.web.controllers.documentissuer.DocumentIssuerController").getConstructor(DocumentIssuerService.class).newInstance(port());}
    @SuppressWarnings("unchecked") Object call(String name,byte[] body)throws Exception{var controller=controller();var method=controller.getClass().getMethod(name,String.class,byte[].class);try{return ((Mono<Object>)method.invoke(controller,TENANT,body)).block();}catch(InvocationTargetException failure){throw (RuntimeException)failure.getCause();}}
    @Test void normalCommandAndSourceMethodsForwardExactOriginalBytes()throws Exception{byte[] bytes=" {\"privateSourceTest\":true} ".getBytes(StandardCharsets.UTF_8);for(String name:List.of("source","prepare","publish","revoke")){assertThrows(ResponseStatusException.class,()->call(name,bytes));assertArrayEquals(bytes,original.get());assertEquals(name.equals("source")?"observeSource":name,method.get());}}
    @Test void explicitRecoveryNeverDispatchesMutation()throws Exception{for(String name:List.of("recoverPrepare","recoverPublish","recoverRevoke")){assertThrows(ResponseStatusException.class,()->call(name,"{}".getBytes(StandardCharsets.UTF_8)));assertEquals(name,method.get());}}
    @Test void malformedCurrentCannotReachAnyService()throws Exception{assertThrows(ResponseStatusException.class,()->call("current","{}".getBytes(StandardCharsets.UTF_8)));assertNull(method.get());}
    @Test void currentUsesPublicationReferenceWithoutCallerSuppliedIssuer()throws Exception{var reference=new PublicationReference(TENANT,"10000000-0000-4000-8000-000000000002",1,"a".repeat(64),new Use(Purpose.DEPOSIT_ACCOUNT_ENROLLMENT,"CONTRACT","en-US"));assertThrows(ResponseStatusException.class,()->call("current",DocumentIssuerCodec.publicationReferenceJson(reference).getBytes(StandardCharsets.UTF_8)));assertEquals("current",method.get());}
    @Test void authorityAndBoundaryFailuresExposeOnlyStaticStatus()throws Exception{for(var failure:List.of(new IllegalStateException("DOCUMENT_ISSUER_AUTHORITY_UNAVAILABLE"),new RootTransactionException(new TransactionFacts(true,false,false,true),CleanupFact.COMPLETE,RootTransactionException.Stage.ROLLBACK),new IllegalStateException("private source row value"))){error.set(failure);var observed=assertThrows(ResponseStatusException.class,()->call("prepare","{}".getBytes(StandardCharsets.UTF_8)));assertEquals(failure.getMessage().equals("DOCUMENT_ISSUER_AUTHORITY_UNAVAILABLE")?HttpStatus.FORBIDDEN:HttpStatus.SERVICE_UNAVAILABLE,observed.getStatusCode());assertFalse(observed.getReason().contains("private"));assertNull(observed.getCause());}}
    @Test void strictBodyErrorIsStatic400()throws Exception{error.set(new IllegalArgumentException("private request content"));var observed=assertThrows(ResponseStatusException.class,()->call("prepare","{}".getBytes(StandardCharsets.UTF_8)));assertEquals(HttpStatus.BAD_REQUEST,observed.getStatusCode());assertEquals("Invalid document issuer request",observed.getReason());assertNull(observed.getCause());}
}

/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.persistence.documentissuer;

import io.fireflysoftware.common.config.core.authority.DocumentIssuerAuthorization;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.Operation;
import io.r2dbc.spi.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import java.util.function.*;
import org.fireflyframework.core.transaction.RootTransactionException;
import org.fireflyframework.security.api.domain.*;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Spring manager and shared transaction source, backed by a networkless test driver. */
class DocumentIssuerTransactionTest {
    static final String TENANT="10000000-0000-4000-8000-000000000001";
    static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z");
    final AtomicLong ticks=new AtomicLong();final LongSupplier nanos=ticks::get;final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    DocumentIssuerAuthorization authority(){var claims=Map.<String,Object>of("tenant_id",TENANT,"exp",NOW.plusSeconds(60),"nbf",NOW.minusSeconds(1),"aud",List.of("docs"),"azp","operator");var p=new SecurityPrincipal("checker","https://identity.example",TENANT,Set.of(),Set.of("configuration:document-issuers:source","configuration:document-issuers:prepare","configuration:document-issuers:publish","configuration:document-issuers:current"),claims,NOW,null,Set.of(),Map.of());return new DocumentIssuerAuthorization(()->Mono.just(p),(x,a,r,c)->Mono.just(Decision.permit()),"https://identity.example","docs","tenant_id","azp","operator",clock,nanos);}
    DocumentIssuerAuthorization.Permit permit(DocumentIssuerAuthorization a){return a.authorize(new DocumentIssuerAuthorization.Scope(TENANT,"10000000-0000-4000-8000-000000000002","10000000-0000-4000-8000-000000000003","10000000-0000-4000-8000-000000000004",Operation.PUBLISH,"a".repeat(64),NOW.plusSeconds(60).toEpochMilli())).block();}
    @Test void expiredOriginalPermitCannotAcquireAConnection(){var a=authority();var p=permit(a);var d=new Driver();var tx=new DocumentIssuerTransaction(d,a,clock,nanos);ticks.set(5_000_000_000L);assertThrows(IllegalStateException.class,()->tx.command(p,s->Mono.just("forbidden")).block());assertEquals(0,d.creates);}
    @Test void differentAdapterPermitCannotBeAdoptedBeforeAcquisition(){var a=authority();var d=new Driver();var tx=new DocumentIssuerTransaction(d,a,clock,nanos);assertThrows(IllegalStateException.class,()->tx.command(permit(authority()),s->Mono.just("forbidden")).block());assertEquals(0,d.creates);}
    @Test void stalledActualBeginClosesOnlyItsCreatedLeaseBeforeError(){var a=authority();var p=permit(a);var d=new Driver();d.stallBegin=true;var tx=new DocumentIssuerTransaction(d,a,clock,nanos);var work=new AtomicInteger();StepVerifier.withVirtualTime(()->tx.command(p,s->{work.incrementAndGet();return Mono.just("forbidden");})).thenAwait(Duration.ofSeconds(6)).expectErrorSatisfies(error->{assertInstanceOf(RootTransactionException.class,error);assertEquals(RootTransactionException.Stage.BEGIN,((RootTransactionException)error).stage());}).verify(Duration.ofSeconds(2));assertEquals(1,d.creates);assertEquals(1,d.beginCancelled);assertEquals(1,d.closes);assertEquals(0,d.commits);assertEquals(0,work.get());}
    @Test void rootCannotEmitOrCommitWithoutActualNativeObservation(){var a=authority();var p=permit(a);var d=new Driver();var tx=new DocumentIssuerTransaction(d,a,clock,nanos);assertThrows(RootTransactionException.class,()->tx.command(p,s->Mono.just("forbidden")).block());assertEquals(1,d.creates);assertEquals(0,d.commits);assertEquals(1,d.rollbacks);assertEquals(1,d.closes);}
    @Test void actualNativeInvocationBindsPdpApprovedDigestBeforeProtectedWork(){var a=authority();var p=permit(a);var d=new Driver();var tx=new DocumentIssuerTransaction(d,a,clock,nanos);var result=tx.command(p,s->s.open("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).thenReturn("controlled")).block();assertEquals("controlled",result.value());assertTrue(result.transactionFacts().commitAcknowledged());assertEquals(p.scope().intentDigest(),d.bound.get(6));assertEquals(1,d.commits);assertEquals(1,d.closes);}
    @Test void earlierEffectiveHorizonAlsoReachesTheNextNativeInvocation(){var a=authority();var p=permit(a);var d=new Driver();var tx=new DocumentIssuerTransaction(d,a,clock,nanos);var first=tx.command(p,s->s.open("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).thenReturn("controlled")).block();assertEquals(NOW.plusMillis(3999),first.permit().effectiveUntil());tx.command(first.permit(),s->s.open("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).thenReturn("controlled")).block();assertEquals(NOW.plusMillis(3999).toEpochMilli(),d.bound.get(9));assertEquals(2,d.commits);}
    @Test void nativeMetadataCannotReplaceTheApprovedIssuerBeforeWork(){var a=authority();var p=permit(a);var d=new Driver();d.returnedIssuer=new UUID(0,99);var tx=new DocumentIssuerTransaction(d,a,clock,nanos);var protectedWork=new AtomicInteger();assertThrows(RootTransactionException.class,()->tx.command(p,s->s.open("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).then(Mono.fromSupplier(()->{protectedWork.incrementAndGet();return "forbidden";}))).block());assertEquals(0,protectedWork.get());assertEquals(0,d.commits);assertEquals(1,d.rollbacks);}
    static final class Driver implements ConnectionFactory {
        boolean stallBegin;int creates,beginCancelled,closes,commits,rollbacks;
        final List<String> sql=new ArrayList<>();final Map<Object,Object> bound=new LinkedHashMap<>();
        Map<String,Object> sourceData,revisionData,controlData,decisionData,actionData,auditData;
        UUID returnedIssuer;
        final Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,args)->switch(m.getName()){
            case "createStatement"->statement((String)args[0]);
            case "beginTransaction"->stallBegin?Mono.<Void>create(sink->sink.onCancel(()->beginCancelled++)):Mono.empty();
            case "commitTransaction"->{commits++;yield Mono.empty();}case "rollbackTransaction"->{rollbacks++;yield Mono.empty();}case "close"->{closes++;yield Mono.empty();}
            case "getMetadata"->new ConnectionMetadata(){public String getDatabaseProductName(){return "PostgreSQL";}public String getDatabaseVersion(){return "17";}};
            case "isAutoCommit"->true;case "getTransactionIsolationLevel"->IsolationLevel.READ_COMMITTED;case "setAutoCommit","setTransactionIsolationLevel"->Mono.empty();case "validate"->Mono.just(true);case "toString"->"NetworklessDocumentIssuerDriver";default->throw new UnsupportedOperationException(m.getName());});
        public Publisher<? extends Connection> create(){creates++;return Mono.just(connection);}public ConnectionFactoryMetadata getMetadata(){return ()->"PostgreSQL";}
        Statement statement(String text){sql.add(text);return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(proxy,method,args)->{
            if(method.getName().equals("bind")){var value=args[1] instanceof Parameter parameter?parameter.getValue():args[1];bound.put(args[0],value);return proxy;}
            if(method.getName().equals("bindNull")){bound.put(args[0],null);return proxy;}
            if(method.getName().equals("execute"))return Mono.just(result(text));
            if(method.getReturnType()==Statement.class)return proxy;
            throw new UnsupportedOperationException(method.getName());
        });}
        Result result(String text){var actualIssuer=returnedIssuer!=null?returnedIssuer:bound.get(1)!=null?bound.get(1):revisionData==null?null:revisionData.get("issuer_id");
            var fields=text.contains("cfg_document_issuer_action")&&actionData!=null?actionData:text.contains("cfg_document_issuer_audit")&&auditData!=null?auditData:text.contains("context_current")?Map.<String,Object>of("is_current",true):text.contains("cfg_document_issuer_current_source")&&sourceData!=null?sourceData:text.contains("cfg_document_issuer_revision")&&revisionData!=null?revisionData:text.contains("cfg_document_issuer_control")&&controlData!=null?controlData:text.contains("cfg_document_issuer_decision")&&decisionData!=null?decisionData:text.contains("cfg_document_issuer_open(")&&actualIssuer!=null?Map.<String,Object>of("issuer_id",actualIssuer,"recorded_at",NOW.atOffset(ZoneOffset.UTC),"db_now_millis",NOW.toEpochMilli(),"until_millis",Math.min(NOW.plusSeconds(4).toEpochMilli(),((Number)bound.get(9)).longValue())):Map.<String,Object>of();
            var keys=new ArrayList<>(fields.keySet());var columns=new ArrayList<ColumnMetadata>();
            for(var key:keys){var value=fields.get(key);columns.add((ColumnMetadata)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ColumnMetadata.class},(p,m,a)->switch(m.getName()){
                case "getName"->key;case "getJavaType"->value.getClass();case "getType"->new Type(){public Class<?> getJavaType(){return value.getClass();}public String getName(){return value.getClass().getSimpleName();}};
                case "getNullability"->Nullability.NON_NULL;case "getNativeTypeMetadata","getPrecision","getScale"->null;default->throw new UnsupportedOperationException(m.getName());}));}
            RowMetadata metadata=(RowMetadata)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{RowMetadata.class},(p,m,a)->switch(m.getName()){
                case "getColumnMetadatas"->columns;case "getColumnMetadata"->columns.get(a[0] instanceof Integer index?index:keys.indexOf((String)a[0]));case "contains"->fields.containsKey(a[0]);default->throw new UnsupportedOperationException(m.getName());});
            Row row=(Row)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Row.class},(p,m,a)->{if(m.getName().equals("get"))return fields.get(a[0] instanceof Integer index?keys.get(index):(String)a[0]);if(m.getName().equals("getMetadata"))return metadata;throw new UnsupportedOperationException(m.getName());});
            boolean nativeResult=text.contains("cfg_document_issuer_open(")&&actualIssuer!=null||text.contains("context_current()");
            boolean present=nativeResult||text.contains("cfg_document_issuer_action")&&actionData!=null||text.contains("cfg_document_issuer_audit")&&auditData!=null||text.contains("cfg_document_issuer_current_source")&&sourceData!=null||text.contains("cfg_document_issuer_revision")&&revisionData!=null||text.contains("cfg_document_issuer_control")&&controlData!=null||text.contains("cfg_document_issuer_decision")&&decisionData!=null;
            return new Result(){public Publisher<Long> getRowsUpdated(){return Mono.just(text.startsWith("INSERT ")||text.startsWith("UPDATE ")?1L:0L);}public <T>Publisher<T> map(BiFunction<Row,RowMetadata,? extends T> mapper){return present?Mono.just(mapper.apply(row,metadata)):Flux.empty();}public <T>Publisher<T> map(Function<? super io.r2dbc.spi.Readable,? extends T> mapper){return present?Mono.just(mapper.apply(row)):Flux.empty();}public Result filter(Predicate<Segment> predicate){return this;}public <T>Publisher<T> flatMap(Function<Segment,? extends Publisher<? extends T>> mapper){return Flux.empty();}};
        }
    }
}

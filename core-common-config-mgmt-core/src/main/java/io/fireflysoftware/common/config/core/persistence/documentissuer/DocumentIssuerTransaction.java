/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.persistence.documentissuer;

import io.fireflysoftware.common.config.core.authority.DocumentIssuerAuthorization;
import io.fireflysoftware.common.config.core.authority.DocumentIssuerNativeWindow;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.Operation;
import io.fireflysoftware.common.config.models.repositories.documentissuer.*;
import io.fireflysoftware.common.config.models.repositories.documentissuer.impl.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import io.r2dbc.spi.ConnectionFactory;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.core.transaction.impl.DefaultBoundedRootTransaction;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

public final class DocumentIssuerTransaction {
    private final DocumentIssuerAuthorization authority;
    private final Clock clock;
    private final LongSupplier nanos;
    private final BoundedRootTransaction transaction;
    private final DatabaseClient client;
    private final DocumentIssuerReads reads;
    private final DocumentIssuerWrites writes;
    public record Completed<T>(T value,DocumentIssuerAuthorization.Permit permit,
            DocumentIssuerNativeWindow window,TransactionFacts transactionFacts) {
        @Override public String toString(){return "DocumentIssuerRootCompletion";}
    }
    public final class Session {
        private final RootTransactionScope root;
        private final boolean readOnly;
        private DocumentIssuerAuthorization.Permit permit;
        private DocumentIssuerNativeWindow window;
        private Session(DocumentIssuerAuthorization.Permit permit,RootTransactionScope root,boolean readOnly){this.permit=permit;this.root=root;this.readOnly=readOnly;}
        public DocumentIssuerReads reads(){requireNative();return reads;}
        public DocumentIssuerWrites writes(){requireNative();if(readOnly||permit.recoveryOnly())throw unavailable();return writes;}
        public Mono<Void> open(byte[] original){
            return Mono.defer(()->{
                authority.requireCurrent(permit);
                if(window!=null||original==null||original.length==0||original.length>65536)throw unavailable();
                var body=original.clone();long before=nanos.getAsLong();var scope=permit.scope();
                var query=client.sql("SELECT * FROM public.cfg_document_issuer_open(:tenant,:issuer,:publication,:action,:operation,:original,:intent_digest,:actor_issuer,:actor_subject,:until)")
                    .bind("tenant",UUID.fromString(scope.tenantId()));
                query=scope.issuerId()==null?query.bindNull("issuer",UUID.class):query.bind("issuer",UUID.fromString(scope.issuerId()));
                return query
                    .bind("publication",UUID.fromString(scope.publicationId())).bind("action",UUID.fromString(scope.actionId()))
                    .bind("operation",scope.operation().name()).bind("original",body).bind("intent_digest",scope.intentDigest()).bind("actor_issuer",permit.actor().issuer())
                    .bind("actor_subject",permit.actor().subject()).bind("until",permit.effectiveUntil().toEpochMilli())
                    .map((row,metadata)->new Opened(Objects.requireNonNull(row.get("issuer_id",UUID.class)),new DocumentIssuerNativeWindow(Objects.requireNonNull(row.get("recorded_at",OffsetDateTime.class)).toInstant(),
                        Objects.requireNonNull(row.get("db_now_millis",Long.class)),Objects.requireNonNull(row.get("until_millis",Long.class)),before)))
                    .one().switchIfEmpty(Mono.error(unavailable())).doOnNext(observed->{
                        if(scope.issuerId()!=null&&!scope.issuerId().equals(observed.issuerId().toString()))throw unavailable();
                        permit=permit.narrowNative(observed.window(),clock,nanos);
                        if(scope.issuerId()==null)permit=permit.resolveIssuer(observed.issuerId().toString());
                        root.narrow(permit.budget());window=observed.window();
                    }).then();
            });
        }
        public DocumentIssuerAuthorization.Permit permit(){requireNative();return permit;}
        public DocumentIssuerNativeWindow window(){requireNative();return window;}
        private void requireNative(){authority.requireCurrent(permit);if(window==null)throw unavailable();root.remaining();}
        private Mono<Void> checkNative(){
            return Mono.defer(()->{
                requireNative();
                return client.sql("SELECT public.cfg_document_issuer_context_current() AS is_current")
                    .map((row,metadata)->Boolean.TRUE.equals(row.get("is_current",Boolean.class))).one()
                    .filter(Boolean::booleanValue).switchIfEmpty(Mono.error(unavailable())).then();
            });
        }
    }
    public DocumentIssuerTransaction(ConnectionFactory factory,DocumentIssuerAuthorization authority,Clock clock,LongSupplier nanos) {
        this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
        authority.requireTimeSources(clock,nanos);
        var qualified=new OwnedLeaseConnectionFactory(Objects.requireNonNull(factory));client=DatabaseClient.create(qualified);
        var template=new R2dbcEntityTemplate(client,PostgresDialect.INSTANCE);
        var manager=new R2dbcTransactionManager(qualified);manager.setEnforceReadOnly(true);
        var resources=BoundedR2dbcResources.verify(qualified,client,template,manager);
        transaction=new DefaultBoundedRootTransaction(resources,new CleanupPolicy(Duration.ofSeconds(2),Duration.ofSeconds(2)));
        reads=new DocumentIssuerReadsImpl(template);writes=new DocumentIssuerWritesImpl(template);
    }
    public <T> Mono<Completed<T>> command(DocumentIssuerAuthorization.Permit permit,Function<Session,Mono<T>> work){return execute(permit,false,work);}
    public <T> Mono<Completed<T>> read(DocumentIssuerAuthorization.Permit permit,Function<Session,Mono<T>> work){return execute(permit,true,work);}
    private <T> Mono<Completed<T>> execute(DocumentIssuerAuthorization.Permit permit,boolean readOnly,Function<Session,Mono<T>> work){
        return Mono.defer(()->{
            authority.requireCurrent(permit);Objects.requireNonNull(work);
            boolean command=Set.of(Operation.PREPARE,Operation.PUBLISH,Operation.REVOKE).contains(permit.scope().operation());
            if(command==readOnly)throw unavailable();
            var definition=new DefaultTransactionDefinition();definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            definition.setIsolationLevel(readOnly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);definition.setReadOnly(readOnly);
            return transaction.execute(permit.budget(),definition,root->{
                var session=new Session(permit,root,readOnly);
                return TransactionSynchronizationManager.forCurrentTransaction().flatMap(sync->{
                    sync.registerSynchronization(new TransactionSynchronization(){
                        @Override public Mono<Void> beforeCommit(boolean ignored){return session.checkNative().then(readOnly?Mono.empty():authority.beforeCommit(session.permit));}
                    });
                    return Mono.defer(()->work.apply(session)).map(value->{session.requireNative();return new Pending<>(Objects.requireNonNull(value),session.permit,session.window);});
                });
            }).map(completion->new Completed<>(completion.value().value(),completion.value().permit(),completion.value().window(),completion.transactionFacts()));
        });
    }
    private record Pending<T>(T value,DocumentIssuerAuthorization.Permit permit,DocumentIssuerNativeWindow window) {}
    private record Opened(UUID issuerId,DocumentIssuerNativeWindow window) {}
    private static IllegalStateException unavailable(){return new IllegalStateException("DOCUMENT_ISSUER_NATIVE_CONTEXT_REQUIRED");}
}

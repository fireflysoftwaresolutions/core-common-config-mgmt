/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.services.documentissuer.impl;

import io.fireflysoftware.common.config.core.authority.DocumentIssuerAuthorization;
import io.fireflysoftware.common.config.core.authority.DocumentIssuerCodec;
import io.fireflysoftware.common.config.core.authority.DocumentIssuerRules;
import io.fireflysoftware.common.config.core.persistence.documentissuer.DocumentIssuerTransaction;
import io.fireflysoftware.common.config.core.services.documentissuer.DocumentIssuerService;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import reactor.core.publisher.Mono;

public final class DocumentIssuerServiceImpl implements DocumentIssuerService {
    private final DocumentIssuerAuthorization authority;
    private final DocumentIssuerTransaction commands;
    private final DocumentIssuerTransaction readTransactions;
    public DocumentIssuerServiceImpl(DocumentIssuerAuthorization authority,DocumentIssuerTransaction commands) {this.authority=Objects.requireNonNull(authority);this.commands=Objects.requireNonNull(commands);this.readTransactions=null;}
    public DocumentIssuerServiceImpl(DocumentIssuerAuthorization authority,DocumentIssuerTransaction commands,DocumentIssuerTransaction reads){
        this.authority=Objects.requireNonNull(authority);this.commands=Objects.requireNonNull(commands);this.readTransactions=Objects.requireNonNull(reads);
        if(commands==reads)throw new IllegalArgumentException("Dedicated document issuer reader required");
    }
    @Override public Mono<SourceObservation> observeSource(String tenantId,byte[] original){
        return Mono.defer(()->{
            var intent=DocumentIssuerCodec.source(tenantId,original);var request=intent.request();
            if(readTransactions==null)throw unavailable();
            var scope=new DocumentIssuerAuthorization.Scope(tenantId,request.issuerId(),request.publicationId(),request.actionId(),Operation.SOURCE,intent.intentDigest(),null,request.use(),request.brandingId());
            return authority.authorize(scope).flatMap(permit->readTransactions.read(permit,session->session.open(intent.originalJson().getBytes(StandardCharsets.UTF_8))
                .then(Mono.defer(()->session.reads().source(id(tenantId),id(request.brandingId())).switchIfEmpty(Mono.error(unavailable())).map(row->{
                    var source=new SourceSnapshot(row.tenantId().toString(),row.tenantVersion(),row.brandingId().toString(),row.brandingVersion(),row.sourceGeneration(),row.tenantCode(),row.tenantName(),row.tenantActive(),row.brandingActive(),row.logoUrl(),row.primaryColor(),row.footerText());
                    if(!source.tenantId().equals(tenantId)||!source.brandingId().equals(request.brandingId()))throw unavailable();
                    DocumentIssuerCodec.validateSource(source);
                    return new SourceObservation(source,session.window().dbNowMillis(),session.window().untilMillis(),session.permit().effectiveUntil().toEpochMilli());
                }))))
                .flatMap(done->authority.reauthorize(done.permit()).then(Mono.fromSupplier(()->{done.permit().budget().remaining();return done.value();}))));
        });
    }
    @Override public Mono<Observation> current(PublicationReference reference){return currentPublication(reference,null);}
    @Override public Mono<Observation> current(Reference reference){
        return Mono.defer(()->{
            DocumentIssuerCodec.referenceDigest(reference);
            return currentPublication(new PublicationReference(reference.tenantId(),reference.publicationId(),reference.revision(),reference.definitionDigest(),reference.use()),reference.issuerId());
        });
    }
    private Mono<Observation> currentPublication(PublicationReference request,String expectedIssuer){
        return Mono.defer(()->{
            var digest=DocumentIssuerCodec.publicationReferenceDigest(request);
            if(readTransactions==null)throw unavailable();
            var scope=new DocumentIssuerAuthorization.Scope(request.tenantId(),null,request.publicationId(),UUID.randomUUID().toString(),Operation.CURRENT,digest,null,request.use());
            return authority.authorize(scope).flatMap(permit->readTransactions.read(permit,session->session.open(DocumentIssuerCodec.publicationReferenceJson(request).getBytes(StandardCharsets.UTF_8))
                .then(Mono.fromSupplier(()->new Reference(request.tenantId(),session.permit().scope().issuerId(),request.publicationId(),request.revision(),request.definitionDigest(),request.use()))))
                .flatMap(lookup->{
                    var reference=lookup.value();if(expectedIssuer!=null&&!expectedIssuer.equals(reference.issuerId()))throw unavailable();
                    return authority.reauthorize(lookup.permit()).then(Mono.defer(()->readOnce(lookup.permit(),reference))).flatMap(first->
                    authority.reauthorize(first.permit()).then(Mono.defer(()->readOnce(first.permit(),reference))).flatMap(second->{
                    var a=first.value();var b=second.value();
                    if(!a.reference().equals(b.reference())||!a.definition().equals(b.definition())||a.generation()!=b.generation()||a.sourceGeneration()!=b.sourceGeneration())throw unavailable();
                    var response=new Observation(b.reference(),b.definition(),b.generation(),b.sourceGeneration(),b.observedAtEpochMillis(),
                        Math.min(a.nativeUntilEpochMillis(),b.nativeUntilEpochMillis()),second.permit().effectiveUntil().toEpochMilli());
                    return authority.reauthorize(second.permit()).then(Mono.fromSupplier(()->{second.permit().budget().remaining();return response;}));
                    }));
                }));
        });
    }
    private Mono<DocumentIssuerTransaction.Completed<Observation>> readOnce(DocumentIssuerAuthorization.Permit permit,Reference reference){
        var request=new PublicationReference(reference.tenantId(),reference.publicationId(),reference.revision(),reference.definitionDigest(),reference.use());
        return readTransactions.read(permit,session->session.open(DocumentIssuerCodec.publicationReferenceJson(request).getBytes(StandardCharsets.UTF_8))
            .then(Mono.defer(()->session.reads().revision(id(reference.tenantId()),id(reference.publicationId())).switchIfEmpty(Mono.error(unavailable())).flatMap(revision->{
                var definition=DocumentIssuerCodec.decodeDefinition(revision.definitionJson().getBytes(StandardCharsets.UTF_8));
                if(session.window().untilMillis()>definition.effectiveUntilEpochMillis())throw unavailable();
                if(!revision.tenantId().equals(id(reference.tenantId()))||!revision.issuerId().equals(id(reference.issuerId()))
                        ||!revision.publicationId().equals(id(reference.publicationId()))||revision.revision()!=reference.revision()
                        ||!revision.definitionDigest().equals(reference.definitionDigest())||definition.source().sourceGeneration()!=revision.sourceGeneration()
                        ||!definition.source().tenantId().equals(reference.tenantId())||!definition.issuerId().equals(reference.issuerId())
                        ||!DocumentIssuerCodec.definitionDigest(definition).equals(revision.definitionDigest()))throw unavailable();
                return session.reads().control(id(reference.tenantId()),id(reference.issuerId())).switchIfEmpty(Mono.error(unavailable())).flatMap(control->
                    session.reads().publishedDecision(id(reference.tenantId()),id(reference.publicationId())).switchIfEmpty(Mono.error(unavailable())).flatMap(decision->{
                        if(!decision.tenantId().equals(id(reference.tenantId()))||!decision.issuerId().equals(id(reference.issuerId()))
                                ||!decision.publicationId().equals(id(reference.publicationId()))||decision.revision()!=reference.revision()
                                ||!"PUBLISH".equals(decision.operation())||!decision.definitionDigest().equals(reference.definitionDigest()))throw unavailable();
                        var publication=new Publication(reference.publicationId(),definition,revision.definitionDigest(),new Actor(revision.makerIssuer(),revision.makerSubject()),new Actor(decision.actorIssuer(),decision.actorSubject()));
                        var current=new Control(control.tenantId().toString(),control.issuerId().toString(),control.publicationId().toString(),control.revision(),control.generation(),State.valueOf(control.state()),control.sourceGeneration());
                        return session.reads().source(id(reference.tenantId()),id(definition.source().brandingId())).switchIfEmpty(Mono.error(unavailable())).map(source->{
                            var actual=new SourceSnapshot(source.tenantId().toString(),source.tenantVersion(),source.brandingId().toString(),source.brandingVersion(),source.sourceGeneration(),source.tenantCode(),source.tenantName(),source.tenantActive(),source.brandingActive(),source.logoUrl(),source.primaryColor(),source.footerText());
                            DocumentIssuerRules.requireCurrent(reference,publication,current,actual,session.window().dbNowMillis());
                            return new Observation(reference,definition,control.generation(),control.sourceGeneration(),session.window().dbNowMillis(),session.window().untilMillis(),session.permit().effectiveUntil().toEpochMilli());
                        });
                    }));
            }))));
    }
    @Override public Mono<Receipt> publish(String tenantId,byte[] original){return decide(tenantId,Operation.PUBLISH,original);}
    @Override public Mono<Receipt> revoke(String tenantId,byte[] original){return decide(tenantId,Operation.REVOKE,original);}
    @Override public Mono<Receipt> recoverPublish(String tenantId,byte[] original){return recoverDecision(tenantId,Operation.PUBLISH,original);}
    @Override public Mono<Receipt> recoverRevoke(String tenantId,byte[] original){return recoverDecision(tenantId,Operation.REVOKE,original);}
    private Mono<Receipt> recoverDecision(String tenantId,Operation operation,byte[] original){
        return Mono.defer(()->{
            var intent=DocumentIssuerCodec.decision(tenantId,operation,original);var request=intent.request();
            var scope=new DocumentIssuerAuthorization.Scope(tenantId,request.issuerId(),request.publicationId(),request.actionId(),operation,intent.intentDigest(),null);
            return authority.authorizeRecovery(scope).flatMap(permit->commands.command(permit,session->session.open(intent.originalJson().getBytes(StandardCharsets.UTF_8))
                .then(Mono.defer(()->session.reads().action(id(tenantId),id(request.actionId())).switchIfEmpty(Mono.error(unavailable()))
                    .flatMap(action->replayDecision(session,tenantId,operation,intent,action)))))
                .flatMap(done->authority.reauthorize(done.permit()).then(Mono.fromSupplier(()->{done.permit().budget().remaining();return done.value();}))));
        });
    }
    private Mono<Receipt> decide(String tenantId,Operation operation,byte[] original){
        return Mono.defer(()->{
            var intent=DocumentIssuerCodec.decision(tenantId,operation,original);var request=intent.request();
            // Native open resolves the immutable revision's business cutoff before any write.
            var scope=new DocumentIssuerAuthorization.Scope(tenantId,request.issuerId(),request.publicationId(),request.actionId(),operation,intent.intentDigest(),null);
            return authority.authorize(scope).flatMap(permit->commands.command(permit,session->session.open(intent.originalJson().getBytes(StandardCharsets.UTF_8))
                .then(Mono.defer(()->session.reads().action(id(tenantId),id(request.actionId()))
                    .flatMap(action->replayDecision(session,tenantId,operation,intent,action))
                    .switchIfEmpty(Mono.defer(()->applyDecision(session,tenantId,operation,intent))))))
                .flatMap(done->authority.reauthorize(done.permit()).then(Mono.fromSupplier(()->{done.permit().budget().remaining();return done.value();}))));
        });
    }
    private Mono<Receipt> replayDecision(DocumentIssuerTransaction.Session session,String tenant,Operation operation,
            DocumentIssuerCodec.Intent<Decision> intent,DocumentIssuerRows.Action action){
        return Mono.defer(()->{
            var request=intent.request();
            if(!action.tenantId().equals(id(tenant))||!action.issuerId().equals(id(request.issuerId()))||!action.actionId().equals(id(request.actionId()))
                    ||!action.operation().equals(operation.name())||!action.intentDigest().equals(intent.intentDigest()))throw conflict();
            var receipt=DocumentIssuerCodec.decodeReceipt(action.resultJson().getBytes(StandardCharsets.UTF_8));
            if(!DocumentIssuerCodec.receiptDigest(receipt).equals(action.resultDigest())||!receipt.tenantId().equals(tenant)
                    ||!receipt.issuerId().equals(request.issuerId())||!receipt.publicationId().equals(request.publicationId())
                    ||!receipt.actionId().equals(request.actionId())||receipt.revision()!=request.revision()||receipt.operation()!=operation
                    ||!receipt.intentDigest().equals(intent.intentDigest())||!receipt.definitionDigest().equals(request.definitionDigest()))throw conflict();
            return session.reads().audit(id(tenant),id(request.actionId())).switchIfEmpty(Mono.error(unavailable())).map(audit->{
                var actor=session.permit().actor();
                if(!audit.actorIssuer().equals(actor.issuer())||!audit.actorSubject().equals(actor.subject())||!audit.operation().equals(operation.name())
                        ||!audit.intentDigest().equals(intent.intentDigest()))throw unavailable();return receipt;
            });
        });
    }
    private Mono<Receipt> applyDecision(DocumentIssuerTransaction.Session session,String tenant,Operation operation,DocumentIssuerCodec.Intent<Decision> intent){
        var request=intent.request();
        return session.reads().revision(id(tenant),id(request.publicationId())).switchIfEmpty(Mono.error(unavailable())).flatMap(row->{
            var definition=DocumentIssuerCodec.decodeDefinition(row.definitionJson().getBytes(StandardCharsets.UTF_8));
            if(operation==Operation.PUBLISH&&session.window().untilMillis()>definition.effectiveUntilEpochMillis())throw unavailable();
            if(!row.tenantId().equals(id(tenant))||!row.issuerId().equals(id(request.issuerId()))||!row.publicationId().equals(id(request.publicationId()))
                    ||row.revision()!=request.revision()||!row.definitionDigest().equals(request.definitionDigest())
                    ||!definition.issuerId().equals(request.issuerId())||!definition.source().tenantId().equals(tenant)
                    ||definition.revision()!=row.revision()||definition.source().sourceGeneration()!=row.sourceGeneration()
                    ||!DocumentIssuerCodec.definitionDigest(definition).equals(row.definitionDigest()))throw conflict();
            var publication=new Publication(row.publicationId().toString(),definition,row.definitionDigest(),new Actor(row.makerIssuer(),row.makerSubject()),null);
            return session.reads().control(id(tenant),id(request.issuerId())).switchIfEmpty(Mono.error(unavailable())).flatMap(prior->{
                var control=new Control(prior.tenantId().toString(),prior.issuerId().toString(),prior.publicationId().toString(),prior.revision(),prior.generation(),State.valueOf(prior.state()),prior.sourceGeneration());
                Mono<Void> consistency;
                if(operation==Operation.PUBLISH){
                    consistency=session.reads().source(id(tenant),id(definition.source().brandingId())).switchIfEmpty(Mono.error(unavailable())).flatMap(source->{
                        var actual=new SourceSnapshot(source.tenantId().toString(),source.tenantVersion(),source.brandingId().toString(),source.brandingVersion(),source.sourceGeneration(),
                            source.tenantCode(),source.tenantName(),source.tenantActive(),source.brandingActive(),source.logoUrl(),source.primaryColor(),source.footerText());
                        DocumentIssuerRules.requirePublish(publication,request,session.permit().actor(),control,actual,session.window().dbNowMillis());return Mono.empty();
                    });
                }else consistency=Mono.fromRunnable(()->DocumentIssuerRules.requireRevoke(request,publication,control));
                return consistency.then(Mono.defer(()->{
                    long generation=Math.addExact(prior.generation(),1);if(generation==Long.MAX_VALUE)throw conflict();
                    var target=operation==Operation.PUBLISH?State.PUBLISHED:State.REVOKED;var actor=session.permit().actor();var stamp=session.window().recordedAt().atOffset(ZoneOffset.UTC);
                    var next=new DocumentIssuerRows.Control(prior.issuerId(),prior.tenantId(),prior.publicationId(),prior.revision(),generation,target.name(),prior.sourceGeneration());
                    var receipt=new Receipt(tenant,request.issuerId(),request.publicationId(),request.revision(),generation,request.actionId(),operation,target,request.definitionDigest(),intent.intentDigest(),session.window().dbNowMillis());
                    return session.writes().decision(new DocumentIssuerRows.Decision(id(request.actionId()),id(tenant),id(request.issuerId()),id(request.publicationId()),request.revision(),operation.name(),request.definitionDigest(),actor.issuer(),actor.subject(),stamp))
                        .then(Mono.defer(()->session.writes().advance(prior,next))).then(Mono.defer(()->recordAction(session,receipt))).thenReturn(receipt);
                }));
            });
        });
    }
    private Mono<Void> recordAction(DocumentIssuerTransaction.Session session,Receipt receipt){
        var stamp=session.window().recordedAt().atOffset(ZoneOffset.UTC);var actor=session.permit().actor();var result=DocumentIssuerCodec.receiptJson(receipt);var digest=DocumentIssuerCodec.receiptDigest(receipt);
        var action=id(receipt.actionId());var tenant=id(receipt.tenantId());var issuer=id(receipt.issuerId());
        return session.writes().action(new DocumentIssuerRows.Action(action,tenant,issuer,receipt.operation().name(),receipt.intentDigest(),result,digest,stamp))
            .then(Mono.defer(()->session.writes().audit(new DocumentIssuerRows.Audit(action,tenant,issuer,receipt.operation().name(),receipt.intentDigest(),actor.issuer(),actor.subject(),stamp))))
            .then(Mono.defer(()->session.writes().outbox(new DocumentIssuerRows.Outbox(action,tenant,issuer,receipt.state().name(),digest,stamp))));
    }
    @Override public Mono<Receipt> prepare(String tenantId,byte[] original){
        return Mono.defer(()->{
            var intent=DocumentIssuerCodec.prepare(tenantId,original);var request=intent.request();
            var scope=new DocumentIssuerAuthorization.Scope(tenantId,request.definition().issuerId(),request.publicationId(),request.actionId(),
                Operation.PREPARE,intent.intentDigest(),request.definition().effectiveUntilEpochMillis());
            return authority.authorize(scope).flatMap(permit->commands.command(permit,session->session.open(intent.originalJson().getBytes(StandardCharsets.UTF_8))
                .then(Mono.defer(()->session.reads().action(id(tenantId),id(request.actionId()))
                    .flatMap(existing->replay(session,intent,existing))
                    .switchIfEmpty(Mono.defer(()->create(session,intent))))))
                .flatMap(done->authority.reauthorize(done.permit()).then(Mono.fromSupplier(()->{done.permit().budget().remaining();return done.value();}))));
        });
    }
    @Override public Mono<Receipt> recoverPrepare(String tenantId,byte[] original){
        return Mono.defer(()->{
            var intent=DocumentIssuerCodec.prepare(tenantId,original);var request=intent.request();
            var scope=new DocumentIssuerAuthorization.Scope(tenantId,request.definition().issuerId(),request.publicationId(),request.actionId(),Operation.PREPARE,intent.intentDigest(),request.definition().effectiveUntilEpochMillis());
            return authority.authorizeRecovery(scope).flatMap(permit->commands.command(permit,session->session.open(intent.originalJson().getBytes(StandardCharsets.UTF_8))
                .then(Mono.defer(()->session.reads().action(id(tenantId),id(request.actionId())).switchIfEmpty(Mono.error(unavailable()))
                    .flatMap(action->replay(session,intent,action)))))
                .flatMap(done->authority.reauthorize(done.permit()).then(Mono.fromSupplier(()->{done.permit().budget().remaining();return done.value();}))));
        });
    }
    private Mono<Receipt> replay(DocumentIssuerTransaction.Session session,DocumentIssuerCodec.Intent<Prepare> intent,DocumentIssuerRows.Action action){
        return Mono.defer(()->{
            var request=intent.request();var tenant=request.definition().source().tenantId();
            if(!action.tenantId().equals(id(tenant))||!action.issuerId().equals(id(request.definition().issuerId()))
                    ||!action.actionId().equals(id(request.actionId()))||!"PREPARE".equals(action.operation())||!intent.intentDigest().equals(action.intentDigest()))throw conflict();
            var receipt=DocumentIssuerCodec.decodeReceipt(action.resultJson().getBytes(StandardCharsets.UTF_8));
            if(!DocumentIssuerCodec.receiptDigest(receipt).equals(action.resultDigest())||!receipt.tenantId().equals(tenant)
                    ||!receipt.issuerId().equals(request.definition().issuerId())||!receipt.publicationId().equals(request.publicationId())
                    ||!receipt.actionId().equals(request.actionId())||receipt.operation()!=Operation.PREPARE||receipt.revision()!=request.definition().revision()||!receipt.intentDigest().equals(intent.intentDigest())
                    ||!receipt.definitionDigest().equals(DocumentIssuerCodec.definitionDigest(request.definition())))throw conflict();
            return session.reads().audit(id(tenant),id(request.actionId())).switchIfEmpty(Mono.error(unavailable())).map(audit->{
                var actor=session.permit().actor();
                if(!audit.actorIssuer().equals(actor.issuer())||!audit.actorSubject().equals(actor.subject())
                        ||!audit.intentDigest().equals(intent.intentDigest())||!"PREPARE".equals(audit.operation()))throw unavailable();
                return receipt;
            });
        });
    }
    private Mono<Receipt> create(DocumentIssuerTransaction.Session session,DocumentIssuerCodec.Intent<Prepare> intent){
        var request=intent.request();var definition=request.definition();var tenant=id(definition.source().tenantId());var issuer=id(definition.issuerId());
        return session.reads().source(tenant,id(definition.source().brandingId())).switchIfEmpty(Mono.error(unavailable())).flatMap(row->{
            var actual=new SourceSnapshot(row.tenantId().toString(),row.tenantVersion(),row.brandingId().toString(),row.brandingVersion(),row.sourceGeneration(),
                row.tenantCode(),row.tenantName(),row.tenantActive(),row.brandingActive(),row.logoUrl(),row.primaryColor(),row.footerText());
            if(!definition.source().equals(actual))throw unavailable();
            return session.reads().control(tenant,issuer).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(prior->{
                if(prior.isEmpty()){
                    if(request.expectedGeneration()!=0||definition.revision()!=1)throw conflict();
                }else{
                    var control=prior.orElseThrow();
                    if(!control.tenantId().equals(tenant)||!control.issuerId().equals(issuer)||control.generation()!=request.expectedGeneration()
                            ||control.revision()>=Long.MAX_VALUE-1||definition.revision()!=control.revision()+1
                            ||!Set.of("PUBLISHED","REVOKED").contains(control.state()))throw conflict();
                }
                long generation=Math.addExact(request.expectedGeneration(),1);if(generation==Long.MAX_VALUE)throw conflict();
                var stamp=session.window().recordedAt().atOffset(ZoneOffset.UTC);var actor=session.permit().actor();
                var publication=id(request.publicationId());var action=id(request.actionId());var digest=DocumentIssuerCodec.definitionDigest(definition);
                var receipt=new Receipt(tenant.toString(),issuer.toString(),publication.toString(),definition.revision(),generation,action.toString(),
                    Operation.PREPARE,State.PREPARED,digest,intent.intentDigest(),session.window().dbNowMillis());
                var resultJson=DocumentIssuerCodec.receiptJson(receipt);var resultDigest=DocumentIssuerCodec.receiptDigest(receipt);
                var revision=new DocumentIssuerRows.Revision(publication,tenant,issuer,definition.revision(),DocumentIssuerCodec.definitionJson(definition),digest,
                    definition.source().sourceGeneration(),actor.issuer(),actor.subject(),stamp);
                var next=new DocumentIssuerRows.Control(issuer,tenant,publication,definition.revision(),generation,"PREPARED",definition.source().sourceGeneration());
                return session.writes().revision(revision)
                    .then(Mono.defer(()->prior.isEmpty()?session.writes().control(next):session.writes().advance(prior.orElseThrow(),next)))
                    .then(Mono.defer(()->session.writes().action(new DocumentIssuerRows.Action(action,tenant,issuer,"PREPARE",intent.intentDigest(),resultJson,resultDigest,stamp))))
                    .then(Mono.defer(()->session.writes().audit(new DocumentIssuerRows.Audit(action,tenant,issuer,"PREPARE",intent.intentDigest(),actor.issuer(),actor.subject(),stamp))))
                    .then(Mono.defer(()->session.writes().outbox(new DocumentIssuerRows.Outbox(action,tenant,issuer,"PREPARED",resultDigest,stamp))))
                    .thenReturn(receipt);
            });
        });
    }
    private static UUID id(String value){return UUID.fromString(value);}
    private static IllegalStateException conflict(){return new IllegalStateException("DOCUMENT_ISSUER_CONFLICT");}
    private static IllegalStateException unavailable(){return new IllegalStateException("DOCUMENT_ISSUER_SOURCE_UNAVAILABLE");}
}

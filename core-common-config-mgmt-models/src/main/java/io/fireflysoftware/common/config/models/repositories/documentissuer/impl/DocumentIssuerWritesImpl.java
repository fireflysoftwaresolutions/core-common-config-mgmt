/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.repositories.documentissuer.impl;

import io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.*;
import io.fireflysoftware.common.config.models.repositories.documentissuer.DocumentIssuerWrites;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.StatementMapper;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

public final class DocumentIssuerWritesImpl implements DocumentIssuerWrites {
    private final R2dbcEntityTemplate operations;
    public DocumentIssuerWritesImpl(R2dbcEntityTemplate operations) {this.operations=Objects.requireNonNull(operations);}
    @Override public Mono<Void> revision(Revision row){return one(row);}
    @Override public Mono<Void> control(Control row){return one(row);}
    @Override public Mono<Void> decision(Decision row){return one(row);}
    @Override public Mono<Void> action(Action row){return one(row);}
    @Override public Mono<Void> audit(Audit row){return one(row);}
    @Override public Mono<Void> outbox(Outbox row){return one(row);}
    private Mono<Void> one(Object row) {
        return Mono.defer(()->{
            if(row==null||!Set.of(Revision.class,Control.class,Decision.class,Action.class,Audit.class,Outbox.class).contains(row.getClass()))
                return Mono.error(new IllegalArgumentException("Closed assigned document issuer row required"));
            var entity=operations.getConverter().getMappingContext().getRequiredPersistentEntity(row.getClass());
            if(entity.hasVersionProperty()||entity.getPropertyAccessor(row).getProperty(entity.getRequiredIdProperty())==null)
                return Mono.error(new IllegalArgumentException("Assigned document issuer identity required"));
            var strategy=operations.getDataAccessStrategy();
            var insert=StatementMapper.InsertSpec.create(entity.getQualifiedTableName());
            for(var column:strategy.getOutboundRow(row).entrySet())insert=insert.withColumn(column.getKey(),column.getValue());
            return operations.getDatabaseClient().sql(strategy.getStatementMapper().getMappedObject(insert)).fetch().rowsUpdated()
                .switchIfEmpty(Mono.error(new DataIntegrityViolationException("Document issuer insert count missing")))
                .flatMap(count->count==1?Mono.<Void>empty():Mono.error(new DataIntegrityViolationException("Exact document issuer insert count required")));
        });
    }
    @Override public Mono<Void> advance(Control expected,Control next) {
        return Mono.defer(()->{
            if(expected==null||next==null||expected.issuerId()==null||expected.tenantId()==null||expected.publicationId()==null
                    ||!expected.issuerId().equals(next.issuerId())||!expected.tenantId().equals(next.tenantId())
                    ||expected.generation()<1||expected.generation()>=Long.MAX_VALUE-1||next.generation()!=expected.generation()+1
                    ||next.sourceGeneration()<1||!transition(expected,next))
                return Mono.error(new IllegalArgumentException("Exact document issuer control transition required"));
            var predicate=Criteria.where("tenantId").is(expected.tenantId()).and("issuerId").is(expected.issuerId())
                .and("publicationId").is(expected.publicationId()).and("revision").is(expected.revision())
                .and("generation").is(expected.generation()).and("state").is(expected.state()).and("sourceGeneration").is(expected.sourceGeneration());
            return operations.update(Control.class).matching(Query.query(predicate)).apply(Update.update("publicationId",next.publicationId())
                .set("revision",next.revision()).set("generation",next.generation()).set("state",next.state()).set("sourceGeneration",next.sourceGeneration()))
                .switchIfEmpty(Mono.error(new DataIntegrityViolationException("Document issuer control count missing")))
                .flatMap(count->count==1?Mono.<Void>empty():Mono.error(new DataIntegrityViolationException("Exact document issuer control count required")));
        });
    }
    private static boolean transition(Control expected,Control next) {
        if("PREPARED".equals(next.state()))return Set.of("PUBLISHED","REVOKED").contains(expected.state())
            &&next.publicationId()!=null&&!expected.publicationId().equals(next.publicationId())&&expected.revision()>0
            &&expected.revision()<Long.MAX_VALUE-1&&next.revision()==expected.revision()+1;
        return expected.publicationId().equals(next.publicationId())&&expected.revision()==next.revision()
            &&expected.sourceGeneration()==next.sourceGeneration()&&("PUBLISHED".equals(next.state())&&"PREPARED".equals(expected.state())
                ||"REVOKED".equals(next.state())&&Set.of("PREPARED","PUBLISHED").contains(expected.state()));
    }
}

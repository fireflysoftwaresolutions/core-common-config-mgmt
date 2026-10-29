/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.repositories.documentissuer.impl;

import io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.*;
import io.fireflysoftware.common.config.models.repositories.documentissuer.DocumentIssuerReads;
import java.util.UUID;
import java.util.Objects;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import reactor.core.publisher.Mono;

public final class DocumentIssuerReadsImpl implements DocumentIssuerReads {
    private final R2dbcEntityTemplate template;
    public DocumentIssuerReadsImpl(R2dbcEntityTemplate template) {this.template=Objects.requireNonNull(template);}
    @Override public Mono<Revision> revision(UUID tenantId,UUID publicationId){return one(Revision.class,tenantId,"publicationId",publicationId);}
    @Override public Mono<Control> control(UUID tenantId,UUID issuerId){return one(Control.class,tenantId,"issuerId",issuerId);}
    @Override public Mono<Decision> publishedDecision(UUID tenantId,UUID publicationId){
        return Mono.defer(()->template.select(Decision.class).matching(Query.query(scope(tenantId,"publicationId",publicationId).and("operation").is("PUBLISH"))).one());
    }
    @Override public Mono<Action> action(UUID tenantId,UUID actionId){return one(Action.class,tenantId,"actionId",actionId);}
    @Override public Mono<Audit> audit(UUID tenantId,UUID actionId){return one(Audit.class,tenantId,"actionId",actionId);}
    @Override public Mono<Source> source(UUID tenantId,UUID brandingId){return one(Source.class,tenantId,"brandingId",brandingId);}
    private <T> Mono<T> one(Class<T> type,UUID tenantId,String property,UUID id){
        return Mono.defer(()->template.select(type).matching(Query.query(scope(tenantId,property,id))).one());
    }
    private static Criteria scope(UUID tenantId,String property,UUID id){
        if(tenantId==null||id==null||tenantId.equals(new UUID(0,0))||id.equals(new UUID(0,0)))throw new IllegalArgumentException("Exact document issuer read scope required");
        return Criteria.where("tenantId").is(tenantId).and(property).is(id);
    }
}

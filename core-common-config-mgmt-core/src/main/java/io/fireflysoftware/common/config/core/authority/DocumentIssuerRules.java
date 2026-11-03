/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import java.net.URI;
import java.util.Objects;

/** Local consistency rules; native source and caller evidence must come from their owners. */
public final class DocumentIssuerRules {
    private DocumentIssuerRules() {}
    public static void requirePublish(Publication prepared, Decision decision, Actor checker,
            Control control, SourceSnapshot actual, long now) {
        retained(prepared,control);decision(decision,prepared,control);actor(checker);
        if(control.state()!=State.PREPARED||prepared.checker()!=null||prepared.maker().equals(checker))throw unavailable();
        currentSource(prepared.definition(),actual,now);
    }
    public static void requireCurrent(Reference reference, Publication publication, Control control,
            SourceSnapshot actual, long now) {
        retained(publication,control);actor(publication.checker());
        if(reference==null||control.state()!=State.PUBLISHED||publication.maker().equals(publication.checker()))throw unavailable();
        var definition=publication.definition();
        if(!Objects.equals(reference.tenantId(),control.tenantId())||!Objects.equals(reference.issuerId(),definition.issuerId())
                ||!Objects.equals(reference.publicationId(),publication.publicationId())||reference.revision()!=definition.revision()
                ||!Objects.equals(reference.definitionDigest(),publication.definitionDigest())||!definition.uses().contains(reference.use()))throw unavailable();
        currentSource(definition,actual,now);
    }
    public static void requireRevoke(Decision decision, Publication publication, Control control) {
        retained(publication,control);decision(decision,publication,control);
        if(control.state()!=State.PREPARED&&control.state()!=State.PUBLISHED)throw unavailable();
    }
    private static void retained(Publication publication,Control control) {
        if(publication==null||control==null||control.state()==null)throw unavailable();
        DocumentIssuerCodec.validate(publication.definition());actor(publication.maker());
        AuthorityWire.uuid(publication.publicationId());AuthorityWire.digest(publication.definitionDigest());
        var definition=publication.definition();
        if(!DocumentIssuerCodec.definitionDigest(definition).equals(publication.definitionDigest())
                ||!Objects.equals(control.tenantId(),definition.source().tenantId())||!Objects.equals(control.issuerId(),definition.issuerId())
                ||!Objects.equals(control.publicationId(),publication.publicationId())||control.revision()!=definition.revision()
                ||control.sourceGeneration()!=definition.source().sourceGeneration())throw unavailable();
        DocumentIssuerCodec.positive(control.generation());
    }
    private static void decision(Decision decision,Publication publication,Control control) {
        if(decision==null)throw unavailable();
        AuthorityWire.uuid(decision.actionId());DocumentIssuerCodec.text(decision.reason(),1,500);
        if(!Objects.equals(decision.issuerId(),control.issuerId())||!Objects.equals(decision.publicationId(),publication.publicationId())
                ||decision.revision()!=publication.definition().revision()||!Objects.equals(decision.definitionDigest(),publication.definitionDigest())
                ||decision.expectedGeneration()!=control.generation())throw unavailable();
    }
    private static void currentSource(Definition definition,SourceSnapshot actual,long now) {
        if(!definition.source().equals(actual)||now<definition.effectiveFromEpochMillis()||now>=definition.effectiveUntilEpochMillis())throw unavailable();
    }
    private static void actor(Actor actor) {
        if(actor==null)throw unavailable();
        DocumentIssuerCodec.text(actor.subject(),1,255);DocumentIssuerCodec.text(actor.issuer(),1,500);
        try {var issuer=URI.create(actor.issuer());if(!"https".equals(issuer.getScheme())||issuer.getHost()==null||issuer.getUserInfo()!=null||issuer.getFragment()!=null)throw unavailable();}catch(IllegalArgumentException e){throw unavailable();}
    }
    private static IllegalArgumentException unavailable(){return new IllegalArgumentException("Document issuer unavailable for requested operation");}
}

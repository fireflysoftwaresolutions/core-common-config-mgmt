/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIssuerRulesTest {
    static final Actor MAKER=new Actor("https://identity.example","maker");
    static final Actor CHECKER=new Actor("https://identity.example","checker");
    static Publication publication(Actor checker) { return new Publication(DocumentIssuerCodecTest.PUBLICATION,DocumentIssuerCodecTest.definition(),DocumentIssuerCodec.definitionDigest(DocumentIssuerCodecTest.definition()),MAKER,checker); }
    static Control control(State state) { return new Control(DocumentIssuerCodecTest.TENANT,DocumentIssuerCodecTest.ISSUER,DocumentIssuerCodecTest.PUBLICATION,1,1,state,9); }
    static Decision decision() {return new Decision(DocumentIssuerCodecTest.ACTION,DocumentIssuerCodecTest.ISSUER,DocumentIssuerCodecTest.PUBLICATION,1,publication(null).definitionDigest(),1,"APPROVED");}
    static Reference reference() {return new Reference(DocumentIssuerCodecTest.TENANT,DocumentIssuerCodecTest.ISSUER,DocumentIssuerCodecTest.PUBLICATION,1,publication(null).definitionDigest(),DocumentIssuerCodecTest.definition().uses().getFirst());}
    @Test void exactMakerCheckerPublicationAndCurrentUsePassLocalRules() {DocumentIssuerRules.requirePublish(publication(null),decision(),CHECKER,control(State.PREPARED),DocumentIssuerCodecTest.source(),2000);DocumentIssuerRules.requireCurrent(reference(),publication(CHECKER),control(State.PUBLISHED),DocumentIssuerCodecTest.source(),2000);}
    @Test void sameIssuerAndSubjectCannotCheckOwnPublication() {assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requirePublish(publication(null),decision(),MAKER,control(State.PREPARED),DocumentIssuerCodecTest.source(),2000));}
    @Test void subjectAloneIsNotGlobalActorIdentity() {DocumentIssuerRules.requirePublish(publication(null),decision(),new Actor("https://other-identity.example","maker"),control(State.PREPARED),DocumentIssuerCodecTest.source(),2000);}
    @Test void replacedThenRestoredBrandingGenerationCannotRevivePublication() {var s=DocumentIssuerCodecTest.source();var changed=new SourceSnapshot(s.tenantId(),s.tenantVersion(),s.brandingId(),s.brandingVersion(),10,s.tenantCode(),s.tenantName(),true,true,s.logoUrl(),s.primaryColor(),s.footerText());assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requireCurrent(reference(),publication(CHECKER),control(State.PUBLISHED),changed,2000));}
    @Test void sourcePresentationAndVersionsMustStillMatch() {var s=DocumentIssuerCodecTest.source();var changed=new SourceSnapshot(s.tenantId(),s.tenantVersion(),s.brandingId(),8,9,s.tenantCode(),s.tenantName(),true,true,s.logoUrl(),s.primaryColor(),s.footerText());assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requirePublish(publication(null),decision(),CHECKER,control(State.PREPARED),changed,2000));}
    @Test void onlyCurrentPublishedControlCanObserve() {for(var state:List.of(State.PREPARED,State.REVOKED))assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requireCurrent(reference(),publication(CHECKER),control(state),DocumentIssuerCodecTest.source(),2000));}
    @Test void noApprovedResultWithoutChecker() {assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requireCurrent(reference(),publication(null),control(State.PUBLISHED),DocumentIssuerCodecTest.source(),2000));}
    @Test void exactUseRequiredNotGenericActiveTenant() {var r=reference();var other=new Reference(r.tenantId(),r.issuerId(),r.publicationId(),r.revision(),r.definitionDigest(),new Use(Purpose.DEPOSIT_ACCOUNT_ENROLLMENT,"OTHER_DOCUMENT","en-US"));assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requireCurrent(other,publication(CHECKER),control(State.PUBLISHED),DocumentIssuerCodecTest.source(),2000));}
    @Test void bothWindowEdgesAreCheckedWithoutRenewal() {for(long now:List.of(999L,10000L))assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requireCurrent(reference(),publication(CHECKER),control(State.PUBLISHED),DocumentIssuerCodecTest.source(),now));DocumentIssuerRules.requireCurrent(reference(),publication(CHECKER),control(State.PUBLISHED),DocumentIssuerCodecTest.source(),1000);}
    @Test void exactTenantIssuerPublicationRevisionDigestRequired() {var r=reference();for(var mismatch:List.of(new Reference(DocumentIssuerCodecTest.BRAND,r.issuerId(),r.publicationId(),r.revision(),r.definitionDigest(),r.use()),new Reference(r.tenantId(),DocumentIssuerCodecTest.BRAND,r.publicationId(),r.revision(),r.definitionDigest(),r.use()),new Reference(r.tenantId(),r.issuerId(),DocumentIssuerCodecTest.BRAND,r.revision(),r.definitionDigest(),r.use()),new Reference(r.tenantId(),r.issuerId(),r.publicationId(),2,r.definitionDigest(),r.use()),new Reference(r.tenantId(),r.issuerId(),r.publicationId(),1,"0".repeat(64),r.use())))assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requireCurrent(mismatch,publication(CHECKER),control(State.PUBLISHED),DocumentIssuerCodecTest.source(),2000));}
    @Test void staleDecisionGenerationCannotPublish() {var d=decision();var stale=new Decision(d.actionId(),d.issuerId(),d.publicationId(),d.revision(),d.definitionDigest(),0,d.reason());assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requirePublish(publication(null),stale,CHECKER,control(State.PREPARED),DocumentIssuerCodecTest.source(),2000));}
    @Test void revokeRemainsPossibleAfterDefinitionExpiryButIsExactScoped() {DocumentIssuerRules.requireRevoke(decision(),publication(CHECKER),control(State.PUBLISHED));var d=decision();var different=new Decision(d.actionId(),d.issuerId(),DocumentIssuerCodecTest.BRAND,d.revision(),d.definitionDigest(),d.expectedGeneration(),d.reason());assertThrows(IllegalArgumentException.class,()->DocumentIssuerRules.requireRevoke(different,publication(CHECKER),control(State.PUBLISHED)));}
}

/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.common.config.core.authority;

import static org.junit.jupiter.api.Assertions.*;
import static io.fireflysoftware.common.config.core.authority.DocumentIssuerCodecTest.*;
import static io.fireflysoftware.common.config.core.authority.DocumentIssuerRulesTest.*;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class DocumentIssuerCardPurposeTest {
    Use use() { return new Use(Purpose.valueOf("CARD_ENROLLMENT"), "CONTRACT", "en-US"); }
    Definition cardDefinition() {
        var d = definition();
        return new Definition(d.issuerId(), d.revision(), d.issuerCode(), d.displayName(),
                d.contactEmail(), List.of(use()), d.source(), d.effectiveFromEpochMillis(), d.effectiveUntilEpochMillis());
    }
    Publication cardPublication() {
        var d = cardDefinition();
        return new Publication(PUBLICATION, d, DocumentIssuerCodec.definitionDigest(d), MAKER, CHECKER);
    }
    Reference cardReference() {
        return new Reference(TENANT, ISSUER, PUBLICATION, 1, cardPublication().definitionDigest(), use());
    }

    @Test void cardDefinitionHasAnExactDistinctCanonicalIntent() {
        var raw = prepareJson().replace("DEPOSIT_ACCOUNT_ENROLLMENT", "CARD_ENROLLMENT")
                .replace("ACCOUNT_AGREEMENT", "CONTRACT");
        var decoded = DocumentIssuerCodec.prepare(TENANT, bytes(raw));
        assertEquals(cardDefinition(), decoded.request().definition());
        assertNotEquals(DocumentIssuerCodec.prepare(TENANT, bytes(prepareJson())).intentDigest(), decoded.intentDigest());
    }

    @Test void exactCurrentCardUseKeepsTheOriginalOwnerSourceAndVersions() {
        DocumentIssuerRules.requireCurrent(cardReference(), cardPublication(), control(State.PUBLISHED), source(), 2000);
        var original = source();
        var changed = new SourceSnapshot(original.tenantId(), original.tenantVersion(), original.brandingId(),
                original.brandingVersion(), original.sourceGeneration() + 1, original.tenantCode(), original.tenantName(),
                true, true, original.logoUrl(), original.primaryColor(), original.footerText());
        assertThrows(IllegalArgumentException.class, () -> DocumentIssuerRules.requireCurrent(
                cardReference(), cardPublication(), control(State.PUBLISHED), changed, 2000));
    }

    @Test void depositAndInsurancePublicationsDoNotAuthorizeCardUse() {
        for (var purpose : List.of(Purpose.DEPOSIT_ACCOUNT_ENROLLMENT, Purpose.INSURANCE_TERM_PC_QUOTE)) {
            var d = cardDefinition();
            var other = new Definition(d.issuerId(), d.revision(), d.issuerCode(), d.displayName(), d.contactEmail(),
                    List.of(new Use(purpose, "CONTRACT", "en-US")), d.source(), d.effectiveFromEpochMillis(), d.effectiveUntilEpochMillis());
            var publication = new Publication(PUBLICATION, other, DocumentIssuerCodec.definitionDigest(other), MAKER, CHECKER);
            var reference = new Reference(TENANT, ISSUER, PUBLICATION, 1, publication.definitionDigest(), use());
            assertThrows(IllegalArgumentException.class, () -> DocumentIssuerRules.requireCurrent(
                    reference, publication, control(State.PUBLISHED), source(), 2000));
        }
    }

    @Test void revokedExpiredOrUnreviewedCardPublicationCannotSupplyEvidence() {
        assertThrows(IllegalArgumentException.class, () -> DocumentIssuerRules.requireCurrent(
                cardReference(), cardPublication(), control(State.REVOKED), source(), 2000));
        assertThrows(IllegalArgumentException.class, () -> DocumentIssuerRules.requireCurrent(
                cardReference(), cardPublication(), control(State.PUBLISHED), source(), 10000));
        var publication = cardPublication();
        assertThrows(IllegalArgumentException.class, () -> DocumentIssuerRules.requireCurrent(cardReference(),
                new Publication(publication.publicationId(), publication.definition(), publication.definitionDigest(), MAKER, null),
                control(State.PUBLISHED), source(), 2000));
    }

    @Test void existingDepositPermissionDoesNotSilentlyBecomeCardPermission() {
        var actor = new DocumentIssuerAuthorizationTest().consumer();
        var calls = new AtomicInteger();
        var authority = new DocumentIssuerAuthorization(() -> Mono.just(actor), (principal, action, resource, context) -> {
            calls.incrementAndGet();
            assertEquals("CARD_ENROLLMENT", context.get("purpose"));
            assertEquals("CONTRACT", context.get("documentType"));
            assertEquals("en-US", context.get("locale"));
            return Mono.just(org.fireflyframework.security.api.domain.Decision.deny("card use is not granted"));
        }, "https://identity.example", "configuration-document-issuer", "tenant_id", "azp", "document-admin",
                Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC), () -> 0L);
        var scope = new DocumentIssuerAuthorization.Scope(TENANT, ISSUER, PUBLICATION, ACTION,
                Operation.CURRENT, "a".repeat(64), null, use());
        assertThrows(IllegalStateException.class, () -> authority.authorize(scope).block());
        assertEquals(1, calls.get());
    }
}

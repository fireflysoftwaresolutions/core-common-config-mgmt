/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.interfaces.documentissuer;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/** Document configuration facts; these values do not authenticate their supplier. */
public final class DocumentIssuerContracts {
    private DocumentIssuerContracts() {}

    @Schema(name="DocumentIssuerPurpose")
    public enum Purpose { DEPOSIT_ACCOUNT_ENROLLMENT }
    @Schema(name="DocumentIssuerOperation")
    public enum Operation { PREPARE, PUBLISH, REVOKE, CURRENT, SOURCE, HISTORY, RECOVER }
    @Schema(name="DocumentIssuerState")
    public enum State { PREPARED, PUBLISHED, REVOKED }

    @Schema(name="DocumentIssuerUse")
    public record Use(Purpose purpose, String documentType, String locale) {}

    @Schema(name="DocumentIssuerSourceRequest")
    public record SourceRequest(String tenantId,String issuerId,String publicationId,String actionId,String brandingId,Use use) {
        @Override public String toString(){return "DocumentIssuerSourceRequest";}
    }
    @Schema(name="DocumentIssuerSourceObservation")
    public record SourceObservation(SourceSnapshot source,long observedAtEpochMillis,long nativeUntilEpochMillis,long effectiveUntilEpochMillis) {
        @Override public String toString(){return "DocumentIssuerSourceObservation";}
    }

    @Schema(name="DocumentIssuerSourceSnapshot")
    public record SourceSnapshot(String tenantId, long tenantVersion, String brandingId,
            long brandingVersion, long sourceGeneration, String tenantCode, String tenantName,
            boolean tenantActive, boolean brandingActive, String logoUrl,
            String primaryColor, String footerText) {
        @Override public String toString() { return "DocumentIssuerSourceSnapshot"; }
    }

    @Schema(name="DocumentIssuerDefinition")
    public record Definition(String issuerId, long revision, String issuerCode, String displayName,
            String contactEmail, List<Use> uses, SourceSnapshot source,
            long effectiveFromEpochMillis, long effectiveUntilEpochMillis) {
        public Definition { uses = uses == null ? null : List.copyOf(uses); }
        @Override public String toString() { return "DocumentIssuerDefinition"; }
    }

    @Schema(name="DocumentIssuerPrepare")
    public record Prepare(String actionId, String publicationId, long expectedGeneration,
            Definition definition) {
        @Override public String toString() { return "DocumentIssuerPrepare"; }
    }

    @Schema(name="DocumentIssuerDecision")
    public record Decision(String actionId, String issuerId, String publicationId, long revision,
            String definitionDigest, long expectedGeneration, String reason) {
        @Override public String toString() { return "DocumentIssuerDecision"; }
    }

    @Schema(name="DocumentIssuerReference")
    public record Reference(String tenantId, String issuerId, String publicationId, long revision,
            String definitionDigest, Use use) {}
    @Schema(name="DocumentIssuerPublicationReference")
    public record PublicationReference(String tenantId,String publicationId,long revision,String definitionDigest,Use use) {}

    @Schema(name="DocumentIssuerActor")
    public record Actor(String issuer, String subject) {
        @Override public String toString() { return "DocumentIssuerActor"; }
    }

    @Schema(name="DocumentIssuerPublication")
    public record Publication(String publicationId, Definition definition, String definitionDigest,
            Actor maker, Actor checker) {
        @Override public String toString() { return "DocumentIssuerPublication"; }
    }

    @Schema(name="DocumentIssuerControl")
    public record Control(String tenantId, String issuerId, String publicationId, long revision,
            long generation, State state, long sourceGeneration) {}

    /** A retained command outcome, never a substitute for CURRENT. */
    @Schema(name="DocumentIssuerReceipt")
    public record Receipt(String tenantId,String issuerId,String publicationId,long revision,long generation,
            String actionId,Operation operation,State state,String definitionDigest,String intentDigest,long recordedAtEpochMillis) {}

    @Schema(name="DocumentIssuerObservation")
    public record Observation(Reference reference,Definition definition,long generation,long sourceGeneration,
            long observedAtEpochMillis,long nativeUntilEpochMillis,long effectiveUntilEpochMillis) {
        @Override public String toString(){return "DocumentIssuerCurrentObservation";}
    }
}

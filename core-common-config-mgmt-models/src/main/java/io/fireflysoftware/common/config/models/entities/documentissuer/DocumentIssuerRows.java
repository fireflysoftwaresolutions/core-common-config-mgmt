/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.entities.documentissuer;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

public final class DocumentIssuerRows {
    private DocumentIssuerRows() {}
    @Table(value="cfg_document_issuer_current_source",schema="public")
    public record Source(@Id UUID brandingId, UUID tenantId, long tenantVersion, long brandingVersion,
            long sourceGeneration, String tenantCode, String tenantName, boolean tenantActive,
            boolean brandingActive, String logoUrl, String primaryColor, String footerText) {
        @Override public String toString(){return "DocumentIssuerCurrentSourceRow";}
    }
    @Table(value="cfg_document_issuer_revision",schema="public")
    public record Revision(@Id UUID publicationId, UUID tenantId, UUID issuerId, long revision,
            String definitionJson, String definitionDigest, long sourceGeneration,
            String makerIssuer, String makerSubject, OffsetDateTime preparedAt) {
        @Override public String toString(){return "DocumentIssuerRevisionRow";}
    }
    @Table(value="cfg_document_issuer_control",schema="public")
    public record Control(@Id UUID issuerId, UUID tenantId, UUID publicationId, long revision,
            long generation, String state, long sourceGeneration) {}
    @Table(value="cfg_document_issuer_decision",schema="public")
    public record Decision(@Id UUID actionId, UUID tenantId, UUID issuerId, UUID publicationId,
            long revision, String operation, String definitionDigest, String actorIssuer,
            String actorSubject, OffsetDateTime recordedAt) {
        @Override public String toString(){return "DocumentIssuerDecisionRow";}
    }
    @Table(value="cfg_document_issuer_action",schema="public")
    public record Action(@Id UUID actionId, UUID tenantId, UUID issuerId, String operation,
            String intentDigest, String resultJson, String resultDigest, OffsetDateTime recordedAt) {
        @Override public String toString(){return "DocumentIssuerActionRow";}
    }
    @Table(value="cfg_document_issuer_audit",schema="public")
    public record Audit(@Id UUID actionId, UUID tenantId, UUID issuerId, String operation,
            String intentDigest, String actorIssuer, String actorSubject, OffsetDateTime recordedAt) {
        @Override public String toString(){return "DocumentIssuerAuditRow";}
    }
    @Table(value="cfg_document_issuer_outbox",schema="public")
    public record Outbox(@Id UUID actionId, UUID tenantId, UUID issuerId, String eventType,
            String payloadDigest, OffsetDateTime recordedAt) {
        @Override public String toString(){return "DocumentIssuerOutboxRow";}
    }
}

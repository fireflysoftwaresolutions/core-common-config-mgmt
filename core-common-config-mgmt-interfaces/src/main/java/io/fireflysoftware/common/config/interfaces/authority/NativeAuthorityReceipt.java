package io.fireflysoftware.common.config.interfaces.authority;

public record NativeAuthorityReceipt<T>(String encoding, String tenantId, String actionId, String operation, String resourceId, String proposalId, Long proposalRevision, String originalBase64, String canonicalIntent, String intentDigest, long deadlineEpochMillis, String resultKind, T result, NativeAuthorityActor actor, long acceptedAtEpochMillis, String receiptId, String auditId, String eventId) {}

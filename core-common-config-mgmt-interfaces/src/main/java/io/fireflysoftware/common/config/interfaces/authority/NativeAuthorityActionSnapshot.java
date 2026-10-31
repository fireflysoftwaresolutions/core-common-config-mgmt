package io.fireflysoftware.common.config.interfaces.authority;

public record NativeAuthorityActionSnapshot(String tenantId,String actionId,NativeAuthorityActionState state,String canonicalReceiptJson,String receiptDigest,boolean historicalOnly) {}

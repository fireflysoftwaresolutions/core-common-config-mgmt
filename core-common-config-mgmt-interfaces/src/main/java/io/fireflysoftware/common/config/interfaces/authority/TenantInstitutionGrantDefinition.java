package io.fireflysoftware.common.config.interfaces.authority;

public record TenantInstitutionGrantDefinition(String tenantId,GrantIssueTargetV2 target,String definitionEncoding,String canonicalDefinition,String definitionDigest,long recordedAtEpochMillis) {}

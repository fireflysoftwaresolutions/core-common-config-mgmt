package io.fireflysoftware.common.config.interfaces.authority;

public record GrantHistoryRequest(String institutionId,String issuerAuthorityId,String grantId,long version) {}

package io.fireflysoftware.common.config.interfaces.authority;

public record NativeAuthorityActor(String kind, String issuer, String subject, String tenantId, String employeeId, String clientId) {}

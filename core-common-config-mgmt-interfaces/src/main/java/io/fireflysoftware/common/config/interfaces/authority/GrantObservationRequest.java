package io.fireflysoftware.common.config.interfaces.authority;

public record GrantObservationRequest(String institutionId,String issuerAuthorityId,InstitutionAuthorityUse use) {}

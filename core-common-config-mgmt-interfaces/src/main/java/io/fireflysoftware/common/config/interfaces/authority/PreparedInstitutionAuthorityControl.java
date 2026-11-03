package io.fireflysoftware.common.config.interfaces.authority;

public record PreparedInstitutionAuthorityControl(String proposalId,GrantControlTarget target,String targetCanonical,String targetDigest,String targetEncoding) {}

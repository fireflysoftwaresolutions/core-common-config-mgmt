package io.fireflysoftware.common.config.interfaces.authority;

public record PreparedInstitutionAuthorityGrant(String proposalId,GrantIssueTargetV2 target,String targetCanonical,String targetDigest,String targetEncoding) {}

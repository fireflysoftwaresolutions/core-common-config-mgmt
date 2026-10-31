package io.fireflysoftware.common.config.interfaces.authority;

public record PreparedTenantEligibilityTransition(String proposalId, TenantEligibilityTransitionTarget target, String targetCanonical, String targetDigest, String targetEncoding) {}

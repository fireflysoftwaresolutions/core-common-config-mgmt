package io.fireflysoftware.common.config.interfaces.authority;

public record PreparedStatusEligibilityTransition(String proposalId, StatusEligibilityTransitionTarget target, String targetCanonical, String targetDigest, String targetEncoding) {}

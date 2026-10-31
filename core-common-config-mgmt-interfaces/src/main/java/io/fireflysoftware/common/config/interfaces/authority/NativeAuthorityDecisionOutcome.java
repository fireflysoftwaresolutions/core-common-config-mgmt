package io.fireflysoftware.common.config.interfaces.authority;

public record NativeAuthorityDecisionOutcome(String proposalId, String state, long controlVersion, String decisionId, String targetDigest) {}

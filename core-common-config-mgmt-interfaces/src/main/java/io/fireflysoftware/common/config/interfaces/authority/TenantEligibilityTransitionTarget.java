package io.fireflysoftware.common.config.interfaces.authority;

import java.util.List;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"actionId","proposalId","proposalRevision","expectedEpoch","targetStatusId","expectedTargetStatusGeneration","targetActive","reason","evidenceReferences","notAfterEpochMillis"})
public record TenantEligibilityTransitionTarget(String actionId, String proposalId, long proposalRevision, TenantEligibilityEpoch expectedEpoch, String targetStatusId, long expectedTargetStatusGeneration, boolean targetActive, String reason, List<String> evidenceReferences, long notAfterEpochMillis) {
    public TenantEligibilityTransitionTarget { evidenceReferences = List.copyOf(evidenceReferences); }
}

package io.fireflysoftware.common.config.interfaces.authority;

import java.util.List;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"actionId","proposalId","proposalRevision","expectedStatusGeneration","targetActive","reason","evidenceReferences","notAfterEpochMillis"})
public record StatusEligibilityTransitionTarget(String actionId, String proposalId, long proposalRevision, long expectedStatusGeneration, boolean targetActive, String reason, List<String> evidenceReferences, long notAfterEpochMillis) {
    public StatusEligibilityTransitionTarget { evidenceReferences = List.copyOf(evidenceReferences); }
}

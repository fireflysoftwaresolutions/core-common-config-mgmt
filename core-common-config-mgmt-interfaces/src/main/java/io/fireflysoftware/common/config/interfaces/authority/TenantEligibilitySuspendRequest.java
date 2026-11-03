package io.fireflysoftware.common.config.interfaces.authority;

import java.util.List;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"actionId","notAfterEpochMillis","reason","evidenceReferences"})
public record TenantEligibilitySuspendRequest(String actionId, long notAfterEpochMillis, String reason, List<String> evidenceReferences) {
    public TenantEligibilitySuspendRequest { evidenceReferences = List.copyOf(evidenceReferences); }
}

package io.fireflysoftware.common.config.interfaces.authority;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"targetStatusId","targetActive","reason","evidenceReferences"})
public record TenantEligibilityTransitionDraft(String targetStatusId, boolean targetActive, String reason, java.util.List<String> evidenceReferences) {
    public TenantEligibilityTransitionDraft { evidenceReferences=java.util.List.copyOf(evidenceReferences); }
}

package io.fireflysoftware.common.config.interfaces.authority;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"targetActive","reason","evidenceReferences"})
public record StatusEligibilityTransitionDraft(boolean targetActive, String reason, java.util.List<String> evidenceReferences) {
    public StatusEligibilityTransitionDraft { evidenceReferences=java.util.List.copyOf(evidenceReferences); }
}

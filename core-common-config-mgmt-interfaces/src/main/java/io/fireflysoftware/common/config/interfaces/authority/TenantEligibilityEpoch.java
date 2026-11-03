package io.fireflysoftware.common.config.interfaces.authority;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"tenantGeneration","statusId","statusGeneration"})
public record TenantEligibilityEpoch(long tenantGeneration, String statusId, long statusGeneration) {
}

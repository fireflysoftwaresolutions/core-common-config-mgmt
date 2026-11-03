package io.fireflysoftware.common.config.interfaces.authority;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"grant","eligibilityEpoch"})
public record GrantIssueTargetV2(GrantIssueTarget grant, TenantEligibilityEpoch eligibilityEpoch) {
}

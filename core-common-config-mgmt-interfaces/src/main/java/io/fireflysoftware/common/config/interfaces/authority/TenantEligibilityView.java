package io.fireflysoftware.common.config.interfaces.authority;

public record TenantEligibilityView(String tenantId, long tenantGeneration, String statusId, long statusGeneration, boolean tenantActive, boolean statusEnabled, String statusCode, boolean eligibleNow, String lastActionId) {
}

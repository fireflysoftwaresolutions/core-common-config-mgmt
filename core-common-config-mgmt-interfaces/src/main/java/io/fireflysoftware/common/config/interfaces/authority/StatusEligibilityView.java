package io.fireflysoftware.common.config.interfaces.authority;

public record StatusEligibilityView(String statusId, long generation, boolean enabled, String statusCode, String lastActionId) {
}

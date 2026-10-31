package io.fireflysoftware.common.config.interfaces.authority;

public record NativeAuthorityActionResult<T>(boolean replay, NativeAuthorityReceipt<T> receipt, TenantEligibilityView currentTenantEligibility, StatusEligibilityView currentStatusEligibility) {}

package io.fireflysoftware.common.config.core.services.authority;

import io.fireflysoftware.common.config.interfaces.authority.*;
import reactor.core.publisher.Mono;

public interface TenantEligibilityService {
    Mono<NativeAuthorityActionResult<TenantEligibilityView>> suspendTenant(String tenantId,byte[] original);
    Mono<NativeAuthorityActionResult<PreparedTenantEligibilityTransition>> prepareTenantTransition(String tenantId,byte[] original);
    Mono<NativeAuthorityActionResult<TenantEligibilityView>> transitionTenant(String tenantId,byte[] original);
    Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideLifecycleProposal(String tenantId,String proposalId,String operation,byte[] original);
    Mono<NativeAuthorityActionResult<StatusEligibilityView>> suspendStatus(String statusId,byte[] original);
    Mono<NativeAuthorityActionResult<PreparedStatusEligibilityTransition>> prepareStatusTransition(String statusId,byte[] original);
    Mono<NativeAuthorityActionResult<StatusEligibilityView>> transitionStatus(String statusId,byte[] original);
    Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideStatusProposal(String statusId,String proposalId,String operation,byte[] original);

    Mono<NativeAuthorityActionResult<TenantEligibilityView>> suspendTenantAsPlatform(String tenantId,byte[] original);
    Mono<NativeAuthorityActionResult<PreparedTenantEligibilityTransition>> prepareTenantTransitionAsPlatform(String tenantId,byte[] original);
    Mono<NativeAuthorityActionResult<TenantEligibilityView>> transitionTenantAsPlatform(String tenantId,byte[] original);
    Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideTenantProposalAsPlatform(String tenantId,String proposalId,String operation,byte[] original);

}

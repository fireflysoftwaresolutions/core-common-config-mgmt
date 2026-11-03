package io.fireflysoftware.common.config.core.services.authority;

import io.fireflysoftware.common.config.interfaces.authority.*;
import reactor.core.publisher.Mono;

public interface InstitutionAuthorityService {
    Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityGrant>> prepareBootstrapGrant(String tenantId,byte[] original);
    Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityGrant>> prepareGrant(String tenantId,byte[] original);
    Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideBootstrapProposal(String tenantId,String institutionId,String proposalId,String operation,byte[] original);
    Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideProposal(String tenantId,String institutionId,String proposalId,String operation,byte[] original);
    Mono<NativeAuthorityActionResult<TenantInstitutionGrantDefinition>> issueBootstrapGrant(String tenantId,String institutionId,byte[] original);
    Mono<NativeAuthorityActionResult<TenantInstitutionGrantDefinition>> issueGrant(String tenantId,String institutionId,byte[] original);
    Mono<InstitutionAuthorityGrantObservation> observe(String tenantId,String institutionId,String issuerAuthorityId,InstitutionAuthorityUse use);
    Mono<InstitutionAuthorityGrantHistory> history(String tenantId,String institutionId,String issuerAuthorityId,String grantId,long version);
    Mono<InstitutionAuthorityGrantObservation> observeAsPlatform(String tenantId,String institutionId,String issuerAuthorityId,InstitutionAuthorityUse use);
    Mono<InstitutionAuthorityGrantHistory> historyAsPlatform(String tenantId,String institutionId,String issuerAuthorityId,String grantId,long version);

    Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityControl>> prepareControl(String tenantId,String institutionId,byte[] original);
    Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityControl>> prepareControlAsPlatform(String tenantId,String institutionId,byte[] original);
    Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideControlProposal(String tenantId,String institutionId,String grantId,String proposalId,String operation,byte[] original);
    Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideControlProposalAsPlatform(String tenantId,String institutionId,String grantId,String proposalId,String operation,byte[] original);
    Mono<NativeAuthorityActionResult<GrantAuthorityControlView>> control(String tenantId,String institutionId,byte[] original);
    Mono<NativeAuthorityActionResult<GrantAuthorityControlView>> controlAsPlatform(String tenantId,String institutionId,byte[] original);

}

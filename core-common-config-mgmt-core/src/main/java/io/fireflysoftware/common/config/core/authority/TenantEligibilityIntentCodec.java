package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.*;
import java.util.*;

public final class TenantEligibilityIntentCodec {
    private TenantEligibilityIntentCodec() {}
    public static InstitutionAuthorityIntent<TenantEligibilitySuspendRequest> decodeSuspend(String tenantId,byte[] bytes) {
        AuthorityWire.uuid(tenantId);var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),TenantEligibilitySuspendRequest.class,Set.of());
        common(request.actionId(),request.notAfterEpochMillis(),request.reason(),request.evidenceReferences());
        return intent("FF_TENANT_ELIGIBILITY_SUSPEND_V1",tenantId,original,request);
    }
    public static InstitutionAuthorityIntent<StatusEligibilitySuspendRequest> decodeStatusSuspend(String statusId,byte[] bytes) {
        AuthorityWire.uuid(statusId);var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),StatusEligibilitySuspendRequest.class,Set.of());
        common(request.actionId(),request.notAfterEpochMillis(),request.reason(),request.evidenceReferences());
        return intent("FF_STATUS_ELIGIBILITY_SUSPEND_V1",statusId,original,request);
    }
    public static InstitutionAuthorityIntent<TenantEligibilityTransitionTarget> decodeTransition(String tenantId,byte[] bytes) {
        AuthorityWire.uuid(tenantId);var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),TenantEligibilityTransitionTarget.class,Set.of());
        common(request.actionId(),request.notAfterEpochMillis(),request.reason(),request.evidenceReferences());
        proposal(request.proposalId(),request.proposalRevision());epoch(request.expectedEpoch());AuthorityWire.uuid(request.targetStatusId());generation(request.expectedTargetStatusGeneration());
        if(request.targetStatusId().equals(request.expectedEpoch().statusId())&&request.expectedTargetStatusGeneration()!=request.expectedEpoch().statusGeneration()) throw AuthorityWire.bad();
        return intent("FF_TENANT_ELIGIBILITY_TRANSITION_V1",tenantId,original,request);
    }
    public static InstitutionAuthorityIntent<StatusEligibilityTransitionTarget> decodeStatusTransition(String statusId,byte[] bytes) {
        AuthorityWire.uuid(statusId);var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),StatusEligibilityTransitionTarget.class,Set.of());
        common(request.actionId(),request.notAfterEpochMillis(),request.reason(),request.evidenceReferences());
        proposal(request.proposalId(),request.proposalRevision());generation(request.expectedStatusGeneration());
        return intent("FF_STATUS_ELIGIBILITY_TRANSITION_V1",statusId,original,request);
    }
    public static InstitutionAuthorityIntent<TenantEligibilityPrepareRequest> decodePrepare(String tenantId,byte[] bytes) {
        AuthorityWire.uuid(tenantId);var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),TenantEligibilityPrepareRequest.class,Set.of());
        var draft=request.draft();common(request.actionId(),request.notAfterEpochMillis(),draft.reason(),draft.evidenceReferences());
        AuthorityWire.uuid(request.targetActionId());AuthorityWire.uuid(draft.targetStatusId());
        if(request.actionId().equals(request.targetActionId())) throw AuthorityWire.bad();
        return intent("FF_TENANT_ELIGIBILITY_PREPARE_V1",tenantId,original,request);
    }
    public static InstitutionAuthorityIntent<StatusEligibilityPrepareRequest> decodeStatusPrepare(String statusId,byte[] bytes) {
        AuthorityWire.uuid(statusId);var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),StatusEligibilityPrepareRequest.class,Set.of());
        var draft=request.draft();common(request.actionId(),request.notAfterEpochMillis(),draft.reason(),draft.evidenceReferences());
        AuthorityWire.uuid(request.targetActionId());if(request.actionId().equals(request.targetActionId())) throw AuthorityWire.bad();
        return intent("FF_STATUS_ELIGIBILITY_PREPARE_V1",statusId,original,request);
    }
    static void epoch(TenantEligibilityEpoch epoch) {
        if(epoch==null) throw AuthorityWire.bad();generation(epoch.tenantGeneration());AuthorityWire.uuid(epoch.statusId());generation(epoch.statusGeneration());
    }
    static void generation(long value) { if(value<0 || value==Long.MAX_VALUE) throw AuthorityWire.bad(); }
    private static void proposal(String id,long revision) { AuthorityWire.uuid(id);if(revision<1) throw AuthorityWire.bad(); }
    private static void common(String action,long deadline,String reason,List<String> refs) {
        AuthorityWire.uuid(action);if(deadline < -2208988800000L || deadline > 253402300799999L) throw AuthorityWire.bad();AuthorityWire.text(reason,500);
        if(refs.size()>32 || new HashSet<>(refs).size()!=refs.size()) throw AuthorityWire.bad();refs.forEach(ref->AuthorityWire.text(ref,500));
    }
    private static <T> InstitutionAuthorityIntent<T> intent(String encoding,String resourceId,String original,T request) {
        var canonical=AuthorityWire.token(encoding)+AuthorityWire.recordToken(resourceId,request);
        return new InstitutionAuthorityIntent<>(original,request,canonical,AuthorityWire.sha(canonical));
    }
}

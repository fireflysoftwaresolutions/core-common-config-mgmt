package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.*;
import java.util.*;

public final class NativeAuthorityGrantReconciliationCodec {
    private NativeAuthorityGrantReconciliationCodec() {}
    public record Target(InstitutionAuthorityIntent<NativeGrantActionReconciliationRequest> intent,InstitutionAuthorityIntent<?> original,String institutionId,String proposalId,String resourceId,long proposalRevision) {}
    public static InstitutionAuthorityIntent<NativeGrantActionReconciliationRequest> decode(String tenant,String action,byte[] bytes) {return target(tenant,action,bytes).intent();}
    public static Target target(String tenant,String action,byte[] bytes) {
        AuthorityWire.uuid(tenant);AuthorityWire.uuid(action);var raw=AuthorityWire.original(bytes,98304);var request=AuthorityWire.record(AuthorityWire.parse(raw),NativeGrantActionReconciliationRequest.class,Set.of());
        byte[] original;try {original=Base64.getDecoder().decode(request.originalBase64());}catch(Exception error){throw AuthorityWire.bad();}
        if(!Base64.getEncoder().encodeToString(original).equals(request.originalBase64())) throw AuthorityWire.bad();
        InstitutionAuthorityIntent<?> target;String expectedAction,institution,proposal,resource;long revision;
        if(request.operation()==NativeGrantActionOperation.GRANT_ISSUE) {
            var value=InstitutionAuthorityGrantV2Codec.decodeIssue(tenant,original);target=value;var grant=value.request().grant();expectedAction=grant.actionId();institution=grant.institutionId();proposal=grant.proposalId();resource=proposal;revision=grant.proposalRevision();
        } else {
            var value=InstitutionAuthorityGrantControlCodec.decodeTarget(tenant,original);target=value;var control=value.request();expectedAction=control.actionId();institution=control.institutionId();proposal=control.proposalId();resource=control.grantId();revision=control.proposalRevision();
        }
        if(!action.equals(expectedAction)) throw AuthorityWire.bad();
        var canonical=AuthorityWire.token("FF_NATIVE_GRANT_ACTION_RECONCILE_V2")+AuthorityWire.recordToken(tenant,action,request.operation(),request.originalBase64(),target.canonicalIntent());
        return new Target(new InstitutionAuthorityIntent<>(raw,request,canonical,AuthorityWire.sha(canonical)),target,institution,proposal,resource,revision);
    }
}

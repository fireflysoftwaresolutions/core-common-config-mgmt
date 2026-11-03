package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.*;
import java.util.*;

public final class InstitutionAuthorityGrantControlCodec {
    private InstitutionAuthorityGrantControlCodec() {}
    public static InstitutionAuthorityIntent<GrantControlTarget> decodeTarget(String tenant,byte[] bytes) {
        AuthorityWire.uuid(tenant);var original=AuthorityWire.original(bytes,65536);var target=AuthorityWire.record(AuthorityWire.parse(original),GrantControlTarget.class,Set.of());
        for(var id:List.of(target.actionId(),target.proposalId(),target.grantId(),target.institutionId(),target.issuerAuthorityId())) AuthorityWire.uuid(id);
        if(target.proposalRevision()<1 || target.version()<1) throw AuthorityWire.bad();TenantEligibilityIntentCodec.epoch(target.expectedEpoch());state(target.expectedControlVersion(),target.expectedState(),target.targetState());common(target.notAfterEpochMillis(),target.reason(),target.evidenceReferences());
        return intent("FF_INSTITUTION_GRANT_CONTROL_V2",original,target,tenant,target);
    }
    public static InstitutionAuthorityIntent<GrantControlPrepareRequest> decodePrepare(String tenant,String institution,byte[] bytes) {
        AuthorityWire.uuid(tenant);AuthorityWire.uuid(institution);var original=AuthorityWire.original(bytes,65536);var request=AuthorityWire.record(AuthorityWire.parse(original),GrantControlPrepareRequest.class,Set.of());var draft=request.draft();
        for(var id:List.of(request.actionId(),request.targetActionId(),draft.grantId(),draft.issuerAuthorityId())) AuthorityWire.uuid(id);
        if(request.actionId().equals(request.targetActionId()) || draft.version()<1) throw AuthorityWire.bad();state(draft.expectedControlVersion(),draft.expectedState(),draft.targetState());common(request.notAfterEpochMillis(),draft.reason(),draft.evidenceReferences());
        return intent("FF_INSTITUTION_GRANT_CONTROL_PREPARE_V2",original,request,tenant,institution,request);
    }
    private static void state(long version,String before,String after) {if(version<0 || version==Long.MAX_VALUE || !Set.of("ACTIVE","SUSPENDED").contains(before) || !Set.of("ACTIVE","SUSPENDED","REVOKED").contains(after) || before.equals(after)) throw AuthorityWire.bad();}
    private static void common(long deadline,String reason,List<String> evidence) {if(deadline < -2208988800000L || deadline>253402300799999L) throw AuthorityWire.bad();AuthorityWire.text(reason,500);if(evidence.size()>32 || new HashSet<>(evidence).size()!=evidence.size()) throw AuthorityWire.bad();evidence.forEach(item->AuthorityWire.text(item,500));}
    private static <T> InstitutionAuthorityIntent<T> intent(String encoding,String original,T request,Object... fields) {var canonical=AuthorityWire.token(encoding)+AuthorityWire.recordToken(fields);return new InstitutionAuthorityIntent<>(original,request,canonical,AuthorityWire.sha(canonical));}
}

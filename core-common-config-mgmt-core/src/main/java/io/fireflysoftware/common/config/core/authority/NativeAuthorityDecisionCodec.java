package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.InstitutionAuthorityDecisionRequest;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class NativeAuthorityDecisionCodec {
    private NativeAuthorityDecisionCodec() {}
    public static InstitutionAuthorityIntent<String> proposalRead(String tenantId,String proposalId,String targetOperation,String resourceId) {
        AuthorityWire.uuid(tenantId);AuthorityWire.uuid(proposalId);AuthorityWire.uuid(resourceId);
        if(!Set.of("TENANT_TRANSITION","STATUS_TRANSITION","GRANT_ISSUE","GRANT_CONTROL").contains(targetOperation)) throw AuthorityWire.bad();
        var canonical=AuthorityWire.token("FF_NATIVE_AUTHORITY_PROPOSAL_READ_V1")+AuthorityWire.recordToken(tenantId,proposalId,targetOperation,resourceId);
        return new InstitutionAuthorityIntent<>("{\"proposalId\":\""+proposalId+"\"}",proposalId,canonical,AuthorityWire.sha(canonical));
    }
    public static InstitutionAuthorityIntent<InstitutionAuthorityDecisionRequest> decode(String tenantId,String proposalId,String operation,byte[] bytes,String retainedCanonicalTarget) {
        AuthorityWire.uuid(tenantId);AuthorityWire.uuid(proposalId);
        if(!Set.of("submit","approve","reject","withdraw").contains(operation)) throw AuthorityWire.bad();
        if(retainedCanonicalTarget==null || retainedCanonicalTarget.isBlank() || retainedCanonicalTarget.getBytes(StandardCharsets.UTF_8).length>131072) throw AuthorityWire.bad();
        var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),InstitutionAuthorityDecisionRequest.class,Set.of());
        AuthorityWire.uuid(request.actionId());AuthorityWire.digest(request.targetIntentDigest());AuthorityWire.text(request.reason(),500);
        if(request.expectedProposalControlVersion()<0 || request.expectedProposalControlVersion()==Long.MAX_VALUE || request.proposalRevision()<1 || !AuthorityWire.sha(retainedCanonicalTarget).equals(request.targetIntentDigest())) throw AuthorityWire.bad();
        var canonical=AuthorityWire.token("FF_NATIVE_AUTHORITY_DECISION_V2")+AuthorityWire.recordToken(tenantId,proposalId,operation,request,retainedCanonicalTarget);
        return new InstitutionAuthorityIntent<>(original,request,canonical,AuthorityWire.sha(canonical));
    }
}

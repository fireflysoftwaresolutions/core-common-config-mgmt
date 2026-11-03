package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.*;
import java.util.*;

public final class InstitutionAuthorityGrantPrepareCodec {
    private InstitutionAuthorityGrantPrepareCodec() {}
    public static InstitutionAuthorityIntent<GrantPrepareRequest> decode(String tenantId,byte[] bytes) {
        AuthorityWire.uuid(tenantId);var original=AuthorityWire.original(bytes,65536);var node=AuthorityWire.parse(original);
        if(!node.isObject() || node.size()!=4) throw AuthorityWire.bad();var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);
        if(!names.equals(Set.of("actionId","targetActionId","notAfterEpochMillis","draft")) || !node.get("actionId").isTextual() || !node.get("targetActionId").isTextual() || !node.get("notAfterEpochMillis").isIntegralNumber() || !node.get("notAfterEpochMillis").canConvertToLong()) throw AuthorityWire.bad();
        var draft=AuthorityWire.record(node.get("draft"),GrantIssueDraft.class,Set.of("existingInstitutionId","existingIssuerAuthorityId"));
        var request=new GrantPrepareRequest(node.get("actionId").textValue(),node.get("targetActionId").textValue(),node.get("notAfterEpochMillis").longValue(),draft);
        AuthorityWire.uuid(request.actionId());AuthorityWire.uuid(request.targetActionId());
        if(request.actionId().equals(request.targetActionId()) || request.notAfterEpochMillis() < -2208988800000L || request.notAfterEpochMillis() > 253402300799999L) throw AuthorityWire.bad();
        if(draft.existingInstitutionId()!=null) AuthorityWire.uuid(draft.existingInstitutionId());if(draft.existingIssuerAuthorityId()!=null) AuthorityWire.uuid(draft.existingIssuerAuthorityId());
        if(draft.existingIssuerAuthorityId()!=null && draft.existingInstitutionId()==null || (draft.existingInstitutionId()==null || draft.existingIssuerAuthorityId()==null) && draft.expectedGrantHeadVersion()!=0) throw AuthorityWire.bad();
        if(draft.expectedGrantHeadVersion()<0 || draft.expectedGrantHeadVersion()==Long.MAX_VALUE || draft.effectiveFromEpochMillis() < -2208988800000L || draft.effectiveToEpochMillis()>253402300799999L || draft.effectiveFromEpochMillis()>=draft.effectiveToEpochMillis() || draft.uses().isEmpty() || draft.uses().size()>3) throw AuthorityWire.bad();
        for(int index=1;index<draft.uses().size();index++) if(draft.uses().get(index-1).name().compareTo(draft.uses().get(index).name())>=0) throw AuthorityWire.bad();
        AuthorityWire.text(draft.reason(),500);if(draft.evidenceReferences().size()>32 || new HashSet<>(draft.evidenceReferences()).size()!=draft.evidenceReferences().size()) throw AuthorityWire.bad();draft.evidenceReferences().forEach(value->AuthorityWire.text(value,500));
        var canonical=AuthorityWire.token("FF_TENANT_INSTITUTION_GRANT_PREPARE_V2")+AuthorityWire.recordToken(tenantId,request);
        return new InstitutionAuthorityIntent<>(original,request,canonical,AuthorityWire.sha(canonical));
    }
}

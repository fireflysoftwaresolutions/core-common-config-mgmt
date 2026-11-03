package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.GrantIssueTargetV2;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class InstitutionAuthorityGrantV2Codec {
    private InstitutionAuthorityGrantV2Codec() {}
    public static InstitutionAuthorityIntent<GrantIssueTargetV2> decodeIssue(String tenantId,byte[] bytes) {
        AuthorityWire.uuid(tenantId);var original=AuthorityWire.original(bytes,65536);var tree=AuthorityWire.parse(original);
        var target=AuthorityWire.record(tree,GrantIssueTargetV2.class,Set.of());
        var grant=InstitutionAuthorityIntentCodec.decodeIssue(tenantId,tree.get("grant").toString().getBytes(StandardCharsets.UTF_8));
        if(!grant.request().equals(target.grant())) throw AuthorityWire.bad();
        TenantEligibilityIntentCodec.epoch(target.eligibilityEpoch());
        var canonical=AuthorityWire.token("FF_TENANT_INSTITUTION_GRANT_V2")+AuthorityWire.recordToken(tenantId,"GRANT_ISSUE",target);
        return new InstitutionAuthorityIntent<>(original,target,canonical,AuthorityWire.sha(canonical));
    }
}

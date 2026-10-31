package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fireflysoftware.common.config.interfaces.authority.TenantInstitutionGrantDefinition;
import java.util.Set;

public final class InstitutionAuthorityGrantDefinitionCodec {
    private static final String ENCODING="FF_TENANT_INSTITUTION_GRANT_DEFINITION_V2";
    private static final JsonMapper JSON=new JsonMapper();
    private InstitutionAuthorityGrantDefinitionCodec() {}
    public static TenantInstitutionGrantDefinition define(String tenantId,byte[] target,long recordedAtEpochMillis) {
        var intent=InstitutionAuthorityGrantV2Codec.decodeIssue(tenantId,target);
        if(recordedAtEpochMillis < -2208988800000L || recordedAtEpochMillis>253402300799999L) throw AuthorityWire.bad();
        var canonical=AuthorityWire.token(ENCODING)+AuthorityWire.recordToken(tenantId,intent.canonicalIntent(),recordedAtEpochMillis);
        return new TenantInstitutionGrantDefinition(tenantId,intent.request(),ENCODING,canonical,AuthorityWire.sha(canonical),recordedAtEpochMillis);
    }
    public static TenantInstitutionGrantDefinition decodeDefinition(String tenantId,byte[] original) {
        var parsed=AuthorityWire.record(AuthorityWire.parse(AuthorityWire.original(original,131072)),TenantInstitutionGrantDefinition.class,Set.of());
        try {
            var expected=define(tenantId,JSON.writeValueAsBytes(parsed.target()),parsed.recordedAtEpochMillis());
            if(!expected.equals(parsed)) throw AuthorityWire.bad();return parsed;
        } catch(Exception error) {throw AuthorityWire.bad();}
    }
}

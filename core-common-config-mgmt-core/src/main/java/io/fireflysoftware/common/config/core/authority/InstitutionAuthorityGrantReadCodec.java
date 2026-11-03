package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fireflysoftware.common.config.interfaces.authority.*;

public final class InstitutionAuthorityGrantReadCodec {
    private InstitutionAuthorityGrantReadCodec() {}
    public static InstitutionAuthorityIntent<GrantObservationRequest> observe(String tenant,String institution,String issuer,InstitutionAuthorityUse use) {
        ids(tenant,institution,issuer);if(use==null) throw AuthorityWire.bad();var request=new GrantObservationRequest(institution,issuer,use);
        return intent("FF_TENANT_INSTITUTION_GRANT_OBSERVE_V2",request,tenant,institution,issuer,use);
    }
    public static InstitutionAuthorityIntent<GrantHistoryRequest> history(String tenant,String institution,String issuer,String grant,long version) {
        ids(tenant,institution,issuer);AuthorityWire.uuid(grant);if(version<1) throw AuthorityWire.bad();var request=new GrantHistoryRequest(institution,issuer,grant,version);
        return intent("FF_TENANT_INSTITUTION_GRANT_HISTORY_V2",request,tenant,institution,issuer,grant,version);
    }
    private static void ids(String tenant,String institution,String issuer){AuthorityWire.uuid(tenant);AuthorityWire.uuid(institution);AuthorityWire.uuid(issuer);}
    private static <T> InstitutionAuthorityIntent<T> intent(String encoding,T request,Object... fields) {
        var canonical=AuthorityWire.token(encoding)+AuthorityWire.recordToken(fields);
        try{return new InstitutionAuthorityIntent<>(new JsonMapper().writeValueAsString(request),request,canonical,AuthorityWire.sha(canonical));}catch(Exception error){throw AuthorityWire.bad();}
    }
}

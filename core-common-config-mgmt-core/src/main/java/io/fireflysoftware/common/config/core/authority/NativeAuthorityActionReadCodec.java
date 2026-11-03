package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.NativeAuthorityActionReadRequest;

public final class NativeAuthorityActionReadCodec {
    private NativeAuthorityActionReadCodec() {}
    public static InstitutionAuthorityIntent<NativeAuthorityActionReadRequest> read(String tenant,String action) {
        AuthorityWire.uuid(tenant);AuthorityWire.uuid(action);var request=new NativeAuthorityActionReadRequest(action);
        var canonical=AuthorityWire.token("FF_NATIVE_AUTHORITY_ACTION_READ_V1")+AuthorityWire.recordToken(tenant,action);
        return new InstitutionAuthorityIntent<>("{\"actionId\":\""+action+"\"}",request,canonical,AuthorityWire.sha(canonical));
    }
}

package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityGrantReconciliationCodecTest {
    @Test void originalFullTargetAndBytesAreBoundToReconciliationScope() throws Exception {
        var tenant="00000000-0000-0000-0000-000000000001";var action="00000000-0000-0000-0000-000000000002";
        var fixture=new com.fasterxml.jackson.databind.json.JsonMapper().readTree(getClass().getResourceAsStream("/authority/institution-grant-definition-v2-golden.json"));
        var original=new com.fasterxml.jackson.databind.json.JsonMapper().readTree(fixture.get("original").textValue()).get("target").toString();
        var body="{\"operation\":\"GRANT_ISSUE\",\"originalBase64\":\""+Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8))+"\"}";
        var target=InstitutionAuthorityGrantV2Codec.decodeIssue(tenant,original.getBytes(StandardCharsets.UTF_8));var actual=target.request().grant().actionId();
        var intent=NativeAuthorityGrantReconciliationCodec.decode(tenant,actual,body.getBytes(StandardCharsets.UTF_8));assertTrue(intent.canonicalIntent().contains(target.canonicalIntent()));
        assertThrows(IllegalArgumentException.class,()->NativeAuthorityGrantReconciliationCodec.decode(tenant,action,body.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->NativeAuthorityGrantReconciliationCodec.decode(tenant,actual,body.replace("GRANT_ISSUE","GRANT_CONTROL").getBytes(StandardCharsets.UTF_8)));
        var spaced=body.replace(Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8)),Base64.getEncoder().encodeToString((" "+original).getBytes(StandardCharsets.UTF_8)));assertNotEquals(intent.intentDigest(),NativeAuthorityGrantReconciliationCodec.decode(tenant,actual,spaced.getBytes(StandardCharsets.UTF_8)).intentDigest());
    }
}

package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityGrantV2CodecTest {
    static com.fasterxml.jackson.databind.JsonNode vector() throws Exception {
        try(var in=InstitutionAuthorityGrantV2CodecTest.class.getResourceAsStream("/authority/institution-eligibility-v2-golden.json")){return new JsonMapper().readTree(in);}
    }
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void literalV2TargetBindsBothGenerationsAndStatusMembership() throws Exception {
        var v=vector();var result=InstitutionAuthorityGrantV2Codec.decodeIssue(v.get("tenantId").textValue(),bytes(v.get("grantV2Original").textValue()));
        assertEquals(v.get("grantV2Canonical").textValue(),result.canonicalIntent());assertEquals(v.get("grantV2Digest").textValue(),result.intentDigest());
        assertEquals(2,result.request().eligibilityEpoch().tenantGeneration());assertEquals(3,result.request().eligibilityEpoch().statusGeneration());
        for(var body:java.util.List.of(v.get("grantV2Original").textValue().replace("\"tenantGeneration\":2","\"tenantGeneration\":3"),v.get("grantV2Original").textValue().replace("\"statusGeneration\":3","\"statusGeneration\":4"),v.get("grantV2Original").textValue().replace("000000000010","000000000011"))) {
            assertNotEquals(result.intentDigest(),InstitutionAuthorityGrantV2Codec.decodeIssue(v.get("tenantId").textValue(),bytes(body)).intentDigest());
        }
    }
    @Test void nativeV2DecodeNeverFallsBackToUnboundV1() throws Exception {
        var v=vector();var tree=new JsonMapper().readTree(v.get("grantV2Original").textValue());
        assertThrows(IllegalArgumentException.class,()->InstitutionAuthorityGrantV2Codec.decodeIssue(v.get("tenantId").textValue(),bytes(tree.get("grant").toString())));
        for(var replacement:java.util.List.of("-1","1e0","null","9223372036854775808")) {
            var body=v.get("grantV2Original").textValue().replace("\"tenantGeneration\":2","\"tenantGeneration\":"+replacement);
            assertThrows(IllegalArgumentException.class,()->InstitutionAuthorityGrantV2Codec.decodeIssue(v.get("tenantId").textValue(),bytes(body)));
        }
    }
}

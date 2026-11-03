package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityGrantDefinitionCodecTest {
    @Test void literalDefinitionBindsFullTargetAndDatabaseRecordedTime() throws Exception {
        com.fasterxml.jackson.databind.JsonNode vector;try(var in=getClass().getResourceAsStream("/authority/institution-grant-definition-v2-golden.json")){vector=new JsonMapper().readTree(in);}
        var original=vector.get("original").textValue();var tenant=vector.get("tenantId").textValue();
        var definition=InstitutionAuthorityGrantDefinitionCodec.decodeDefinition(tenant,original.getBytes(StandardCharsets.UTF_8));assertEquals(vector.get("canonical").textValue(),definition.canonicalDefinition());assertEquals(vector.get("digest").textValue(),definition.definitionDigest());
        for(var invalid:java.util.List.of(original.replace("1767225600123","1767225600124"),original.replace("\"tenantGeneration\":2","\"tenantGeneration\":3"),original.replace("\"target\":{","\"license\":true,\"target\":{"))) assertThrows(IllegalArgumentException.class,()->InstitutionAuthorityGrantDefinitionCodec.decodeDefinition(tenant,invalid.getBytes(StandardCharsets.UTF_8)));
    }
}

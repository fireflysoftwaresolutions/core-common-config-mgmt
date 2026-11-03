package io.fireflysoftware.common.config.web.tenantbinding.impl;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingGovernedContracts.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Strict typed HTTP decoding; nested authority extras, duplicate members and coercion are refused. */
public final class TenantResourceBindingGovernanceRequestCodecImpl {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(65536).maxNumberLength(20).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private TenantResourceBindingGovernanceRequestCodecImpl(){}
    public static Intent intent(byte[] body){try{var root=object(body);nullableBranches(root);return JSON.treeToValue(root,Intent.class);}catch(Exception error){throw invalid();}}
    public static ActionRequest action(byte[] body){try{var root=object(body);if(!(root.get("intent") instanceof ObjectNode intent))throw invalid();nullableBranches(intent);return JSON.treeToValue(root,ActionRequest.class);}catch(Exception error){throw invalid();}}
    private static ObjectNode object(byte[] body)throws Exception{if(body==null||body.length==0||body.length>65536)throw invalid();String original=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();var tree=JSON.readTree(original);if(!(tree instanceof ObjectNode object))throw invalid();return object;}
    private static void nullableBranches(ObjectNode intent){for(String key:java.util.List.of("preparation","decision","control"))if(!intent.has(key))intent.putNull(key);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact typed binding governance request required");}
}

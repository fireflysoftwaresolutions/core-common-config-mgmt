package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fireflysoftware.common.config.interfaces.authority.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public final class NativeAuthorityResultReader {
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private NativeAuthorityResultReader() {}
    public static <T> NativeAuthorityActionResult<T> read(String nativeJson,InstitutionAuthorityAuthorization.Admission admission,String actionId,String operation,String resultKind,InstitutionAuthorityIntent<?> intent,Class<T> resultType,Function<byte[],? extends InstitutionAuthorityIntent<?>> decoder) {
        try {
            var node=AuthorityWire.parse(nativeJson);var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);
            if(!names.equals(Set.of("replay","receipt")) && !names.equals(Set.of("replay","receipt","currentEligibility"))) throw AuthorityWire.bad();
            if(!node.get("replay").isBoolean()) throw AuthorityWire.bad();
            var negative=node.get("receipt").path("resultKind").asText().equals("NO_EFFECT_FINAL");
            var type=JSON.getTypeFactory().constructParametricType(NativeAuthorityReceipt.class,negative?JsonNode.class:resultType);
            @SuppressWarnings("unchecked") NativeAuthorityReceipt<T> receipt=(NativeAuthorityReceipt<T>)JSON.convertValue(node.get("receipt"),type);
            if(!receipt.encoding().equals("FF_NATIVE_INSTITUTION_RECEIPT_JSON_V1") || !receipt.tenantId().equals(admission.scope().tenantId()) || !receipt.actionId().equals(actionId)
                || !receipt.operation().equals(operation) || !receipt.resourceId().equals(admission.scope().resourceId()) || !receipt.resultKind().equals(negative?"NO_EFFECT_FINAL":resultKind)
                || !receipt.canonicalIntent().equals(intent.canonicalIntent()) || !receipt.intentDigest().equals(intent.intentDigest()) || receipt.result()==null || receipt.actor()==null) throw AuthorityWire.bad();
            var original=Base64.getDecoder().decode(receipt.originalBase64());
            if(!Base64.getEncoder().encodeToString(original).equals(receipt.originalBase64()) || !decoder.apply(original).canonicalIntent().equals(intent.canonicalIntent())) throw AuthorityWire.bad();
            if(!node.get("replay").booleanValue() && !Arrays.equals(original,intent.originalJson().getBytes(StandardCharsets.UTF_8))) throw AuthorityWire.bad();
            for(var id:List.of(receipt.receiptId(),receipt.auditId(),receipt.eventId())) AuthorityWire.uuid(id);
            if(negative) {
                if(!node.get("replay").booleanValue() || !Set.of("GRANT_ISSUE","GRANT_CONTROL").contains(operation) || receipt.acceptedAtEpochMillis()<receipt.deadlineEpochMillis()
                    || !node.get("receipt").get("result").equals(AuthorityWire.parse("{\"state\":\"NO_EFFECT_FINAL\",\"reason\":\"ADMISSION_DEADLINE_ELAPSED\"}"))) throw AuthorityWire.bad();
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Native action has a final no-effect receipt");
            }
            TenantEligibilityView tenant=null;StatusEligibilityView status=null;
            if(node.has("currentEligibility")) {
                if(node.get("currentEligibility").has("tenantId")) tenant=JSON.convertValue(node.get("currentEligibility"),TenantEligibilityView.class);
                else status=JSON.convertValue(node.get("currentEligibility"),StatusEligibilityView.class);
            }
            return new NativeAuthorityActionResult<>(node.get("replay").booleanValue(),receipt,tenant,status);
        } catch(ResponseStatusException error) {throw error;} catch(Exception error) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Retained native authority result failed validation");}
    }
}

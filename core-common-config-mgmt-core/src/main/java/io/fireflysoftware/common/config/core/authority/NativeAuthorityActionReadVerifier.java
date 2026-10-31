package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.JsonNode;
import io.fireflysoftware.common.config.interfaces.authority.*;
import io.fireflysoftware.common.config.models.entities.authority.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

public final class NativeAuthorityActionReadVerifier {
    private NativeAuthorityActionReadVerifier() {}
    public static NativeAuthorityActionSnapshot absent(String tenant,String action) {
        AuthorityWire.uuid(tenant);AuthorityWire.uuid(action);return new NativeAuthorityActionSnapshot(tenant,action,NativeAuthorityActionState.UNKNOWN,null,null,true);
    }
    public static NativeAuthorityActionSnapshot retained(String tenant,String action,ActionRow row,ReceiptRow receipt) {
        try {
            var key=new ActionKey(UUID.fromString(tenant),UUID.fromString(action));
            if(!key.equals(row.id()) || !key.equals(receipt.id()) || !Objects.equals(row.receiptId(),receipt.receiptId()) || !AuthorityWire.sha(receipt.canonicalPayload()).equals(receipt.payloadDigest())) throw AuthorityWire.bad();
            var payload=AuthorityWire.parse(receipt.canonicalPayload());if(!payload.equals(AuthorityWire.parse(receipt.payload().asString()))) throw AuthorityWire.bad();
            var fields=new HashSet<String>();payload.fieldNames().forEachRemaining(fields::add);if(!fields.equals(Set.of("encoding","tenantId","actionId","operation","resourceId","proposalId","proposalRevision","originalBase64","canonicalIntent","intentDigest","deadlineEpochMillis","resultKind","result","actor","acceptedAtEpochMillis","receiptId","auditId","eventId"))) throw AuthorityWire.bad();
            equal(payload,"encoding","FF_NATIVE_INSTITUTION_RECEIPT_JSON_V1");equal(payload,"tenantId",tenant);equal(payload,"actionId",action);equal(payload,"operation",row.operation());equal(payload,"resourceId",row.resourceId());equal(payload,"proposalId",row.proposalId());equal(payload,"proposalRevision",row.proposalRevision());equal(payload,"originalBase64",Base64.getEncoder().encodeToString(row.originalRequest()));equal(payload,"canonicalIntent",row.canonicalIntent());equal(payload,"intentDigest",row.intentDigest());equal(payload,"deadlineEpochMillis",row.deadlineEpochMillis());equal(payload,"resultKind",row.resultKind());equal(payload,"receiptId",row.receiptId());equal(payload,"auditId",row.auditId());equal(payload,"eventId",row.eventId());equal(payload,"acceptedAtEpochMillis",row.admittedAt().toInstant().toEpochMilli());
            if(!AuthorityWire.sha(row.canonicalIntent()).equals(row.intentDigest()) || !payload.get("result").equals(AuthorityWire.parse(row.result().asString()))) throw AuthorityWire.bad();
            var actor=AuthorityWire.record(payload.get("actor"),NativeAuthorityActor.class,Set.of("employeeId","clientId"));AuthorityWire.uuid(actor.tenantId());
            if(!Objects.equals(actor.kind(),row.actorKind()) || !Objects.equals(actor.issuer(),row.actorIssuer()) || !Objects.equals(actor.subject(),row.actorSubject()) || !Objects.equals(actor.employeeId(),row.actorEmployeeId()==null?null:row.actorEmployeeId().toString()) || !Objects.equals(actor.clientId(),row.actorClientId())) throw AuthorityWire.bad();
            if(!Set.of("STAFF","MACHINE").contains(actor.kind()) || actor.kind().equals("STAFF")&&actor.employeeId()==null || actor.kind().equals("MACHINE")&&(actor.employeeId()!=null || actor.clientId()==null)) throw AuthorityWire.bad();
            var state=row.resultKind().equals("NO_EFFECT_FINAL")?NativeAuthorityActionState.NO_EFFECT_FINAL:NativeAuthorityActionState.RECEIPT_FOUND;
            return new NativeAuthorityActionSnapshot(tenant,action,state,receipt.canonicalPayload(),receipt.payloadDigest(),true);
        } catch(Exception error) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Retained native action failed validation");}
    }
    private static void equal(JsonNode payload,String name,Object value) {
        var node=payload.get(name);if(value==null) {if(node==null || !node.isNull()) throw AuthorityWire.bad();}
        else if(value instanceof Long number) {if(node==null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()!=number) throw AuthorityWire.bad();}
        else if(node==null || !node.isTextual() || !node.textValue().equals(value.toString())) throw AuthorityWire.bad();
    }
}

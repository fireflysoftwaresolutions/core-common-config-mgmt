package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityGrantReadVerifierTest {
    @Test void historicalFullContentCannotBecomeCurrentAuthorityOrCrossIssuerHistory() throws Exception {
        var mapper=new JsonMapper();com.fasterxml.jackson.databind.JsonNode vector;try(var in=getClass().getResourceAsStream("/authority/institution-grant-definition-v2-golden.json")){vector=mapper.readTree(in);}
        var definition=mapper.readTree(vector.get("original").textValue());var tenant=definition.get("tenantId").textValue();var grant=definition.get("target").get("grant");var request=InstitutionAuthorityGrantReadCodec.history(tenant,grant.get("institutionId").textValue(),grant.get("issuerAuthorityId").textValue(),grant.get("grantId").textValue(),grant.get("version").longValue()).request();
        var historical="{\"definition\":"+definition+",\"historicalOnly\":true}";assertTrue(InstitutionAuthorityGrantReadVerifier.history(tenant,request,historical).historicalOnly());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->InstitutionAuthorityGrantReadVerifier.history(tenant,request,historical.replace("\"historicalOnly\":true","\"historicalOnly\":false")));
    }
    @Test void currentReadRequiresMatchingEpochUseAndFiniteWindow() throws Exception {
        var mapper=new JsonMapper();com.fasterxml.jackson.databind.JsonNode vector;try(var in=getClass().getResourceAsStream("/authority/institution-grant-definition-v2-golden.json")){vector=mapper.readTree(in);}
        var definition=InstitutionAuthorityGrantDefinitionCodec.decodeDefinition(vector.get("tenantId").textValue(),vector.get("original").textValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));var grant=definition.target().grant();var epoch=definition.target().eligibilityEpoch();var use=grant.uses().getFirst();var observed=grant.effectiveFromEpochMillis()+1000;
        var live=new io.fireflysoftware.common.config.interfaces.authority.TenantEligibilityView(definition.tenantId(),epoch.tenantGeneration(),epoch.statusId(),epoch.statusGeneration(),true,true,"ACTIVE",true,grant.actionId());
        var value=new io.fireflysoftware.common.config.interfaces.authority.InstitutionAuthorityGrantObservation(definition.tenantId(),grant.institutionId(),grant.issuerAuthorityId(),use,"CURRENT_AUTHORIZED",definition,new io.fireflysoftware.common.config.interfaces.authority.GrantAuthorityControlView("ACTIVE",0,grant.actionId()),grant.version(),live,observed,observed+30000,null);
        var request=InstitutionAuthorityGrantReadCodec.observe(definition.tenantId(),grant.institutionId(),grant.issuerAuthorityId(),use).request();var json=mapper.writeValueAsString(value);assertEquals("CURRENT_AUTHORIZED",InstitutionAuthorityGrantReadVerifier.observation(definition.tenantId(),request,json).state());
        var badEpoch=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(json);((com.fasterxml.jackson.databind.node.ObjectNode)badEpoch.get("tenantEligibility")).put("tenantGeneration",epoch.tenantGeneration()+1);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->InstitutionAuthorityGrantReadVerifier.observation(definition.tenantId(),request,badEpoch.toString()));
        var badWindow=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(json);badWindow.put("validUntilEpochMillis",observed+30001);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->InstitutionAuthorityGrantReadVerifier.observation(definition.tenantId(),request,badWindow.toString()));
    }

}

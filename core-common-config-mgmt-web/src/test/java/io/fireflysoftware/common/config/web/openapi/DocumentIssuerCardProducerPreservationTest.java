package io.fireflysoftware.common.config.web.openapi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class DocumentIssuerCardProducerPreservationTest {
    private JsonNode original()throws Exception{return TenantResourceBindingGovernanceProducerTest.YAML.readTree(Files.readAllBytes(Path.of("../core-common-config-mgmt-sdk/src/main/resources/openapi.yml")));}
    private JsonNode card()throws Exception{var doc=original();((ArrayNode)doc.at("/components/schemas/DocumentIssuerUse/properties/purpose/enum")).add("CARD_ENROLLMENT");return doc;}
    @Test void exactCardExtensionPreservesEveryOriginalOperationAndComponentHash()throws Exception{
        var old=original();TenantResourceBindingGovernanceProducerTest.original135(old);var actual=card();assertThrows(AssertionError.class,()->TenantResourceBindingGovernanceProducerTest.original135(actual));var before=actual.deepCopy();TenantResourceBindingGovernanceProducerTest.original135WithCardPurpose(actual);assertEquals(before,actual);
    }
    @Test void missingReorderedOrAdditionalPurposeCannotEnterTheRecognizedExtension()throws Exception{
        assertThrows(AssertionError.class,()->TenantResourceBindingGovernanceProducerTest.original135WithCardPurpose(original()));
        for(var extra:java.util.List.of("CARD_ISSUANCE","SIGNATURE","CARD_ENROLLMENT")){var actual=card();((ArrayNode)actual.at("/components/schemas/DocumentIssuerUse/properties/purpose/enum")).add(extra);assertThrows(AssertionError.class,()->TenantResourceBindingGovernanceProducerTest.original135WithCardPurpose(actual));}
        var actual=card();((ArrayNode)actual.at("/components/schemas/DocumentIssuerUse/properties/purpose/enum")).set(0,TenantResourceBindingGovernanceProducerTest.JSON.getNodeFactory().textNode("CARD_ENROLLMENT"));assertThrows(AssertionError.class,()->TenantResourceBindingGovernanceProducerTest.original135WithCardPurpose(actual));
    }
    @Test void changingAnotherPropertyOfTheSameSchemaStillFailsOriginalHash()throws Exception{
        var actual=card();((ObjectNode)actual.at("/components/schemas/DocumentIssuerUse/properties/documentType")).put("type","integer");assertThrows(AssertionError.class,()->TenantResourceBindingGovernanceProducerTest.original135WithCardPurpose(actual));
    }
    @Test void changingAnOriginalOperationStillFailsOriginalHash()throws Exception{
        var actual=card();var operations=TenantResourceBindingGovernanceProducerTest.operations(actual);var operation=operations.values().iterator().next();((ObjectNode)operation).put("summary","unrelated contract modification");assertThrows(AssertionError.class,()->TenantResourceBindingGovernanceProducerTest.original135WithCardPurpose(actual));
    }
}

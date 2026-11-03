package io.fireflysoftware.common.config.web.openapi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fireflysoftware.common.config.core.authority.TenantResourceBindingGovernedActions;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("openapi-gen")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes=OpenApiGenApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={"spring.main.web-application-type=reactive","springdoc.api-docs.enabled=true","springdoc.api-docs.version=OPENAPI_3_0","firefly.configuration.document-issuer.enabled=true","firefly.configuration.tenant-resource-binding.enabled=true","firefly.configuration.tenant-resource-binding.governance-http-enabled=true"})
class TenantResourceBindingGovernanceProducerTest {
    @Autowired ApplicationContext context;
    static final String BASE="/api/v1/tenant-resource-bindings/governance";
    static final Set<String> NEW=Set.of(BASE+"/intents\npost\nprepareTenantResourceBindingIntent",BASE+"/actions\npost\nexecuteTenantResourceBindingAction",BASE+"/actions/observations\npost\nobserveTenantResourceBindingAction");
    static final ObjectMapper JSON=new ObjectMapper(),YAML=new ObjectMapper(new YAMLFactory());
    static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static JsonNode sorted(JsonNode value){if(value.isObject()){var result=JSON.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);keys.forEach(key->result.set(key,sorted(value.get(key))));return result;}if(value.isArray()){var result=JSON.createArrayNode();value.forEach(item->result.add(sorted(item)));return result;}return value;}
    static Map<String,JsonNode> operations(JsonNode doc){var result=new TreeMap<String,JsonNode>();var ids=new HashSet<String>();doc.path("paths").fields().forEachRemaining(path->path.getValue().fields().forEachRemaining(method->{if(Set.of("get","post","put","patch","delete","head","options","trace").contains(method.getKey())){String id=method.getValue().path("operationId").asText();assertFalse(id.isBlank());assertTrue(ids.add(id));assertNull(result.put(path.getKey()+"\n"+method.getKey()+"\n"+id,method.getValue()));}}));return result;}
    static JsonNode baseline()throws Exception{try(var input=TenantResourceBindingGovernanceProducerTest.class.getResourceAsStream("/tenant-resource-binding/original135-semantic-identity.json")){assertNotNull(input);return JSON.readTree(input);}}
    static void original135(JsonNode doc)throws Exception{var baseline=baseline();var actual=operations(doc);assertEquals(135,baseline.path("operations").size());for(var iterator=baseline.path("operations").fields();iterator.hasNext();){var row=iterator.next();assertTrue(actual.containsKey(row.getKey()),row.getKey());assertEquals(row.getValue().asText(),sha(JSON.writeValueAsBytes(sorted(actual.get(row.getKey())))),row.getKey());}for(var iterator=baseline.path("components").fields();iterator.hasNext();){var row=iterator.next();int split=row.getKey().indexOf('/');var value=doc.path("components").path(row.getKey().substring(0,split)).path(row.getKey().substring(split+1));assertFalse(value.isMissingNode(),row.getKey());assertEquals(row.getValue().asText(),sha(JSON.writeValueAsBytes(sorted(value))),row.getKey());}}
    static void original135WithCardPurpose(JsonNode doc)throws Exception{
        var purpose=doc.at("/components/schemas/DocumentIssuerUse/properties/purpose/enum");
        assertEquals(JSON.valueToTree(List.of("DEPOSIT_ACCOUNT_ENROLLMENT","INSURANCE_TERM_PC_QUOTE","CARD_ENROLLMENT")),purpose);
        var original=doc.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)original.at("/components/schemas/DocumentIssuerUse/properties/purpose/enum")).remove(2);
        original135(original);
    }
    static JsonNode resolved(JsonNode doc,JsonNode value){return value.has("$ref")?doc.at(value.path("$ref").asText().substring(1)):value;}
    static void governedSchema(JsonNode doc){
        var intent=doc.at("/components/schemas/TenantResourceBindingGovernedIntent");var properties=new TreeSet<String>();intent.path("properties").fieldNames().forEachRemaining(properties::add);assertEquals(Set.of("kind","preparation","decision","control"),properties);assertFalse(intent.path("additionalProperties").asBoolean(true));
        for(String key:NEW){var parts=key.split("\n");var operation=doc.path("paths").path(parts[0]).path(parts[1]);assertEquals(parts[2],operation.path("operationId").asText());assertTrue(operation.path("security").toString().contains("tenantResourceBindingBearer"));assertTrue(operation.path("x-disable-idempotency").asBoolean());for(var parameter:operation.path("parameters"))assertNotEquals("X-Idempotency-Key",parameter.path("name").asText());}
        var states=new TreeSet<String>();resolved(doc,doc.at("/components/schemas/TenantResourceBindingGovernedActionResult/properties/status")).path("enum").forEach(value->states.add(value.asText()));assertEquals(Set.of("COMMITTED","COMMITTED_RESPONSE_UNAVAILABLE","OUTCOME_UNKNOWN"),states);
    }
    @Test @Order(1) void originalProducerPreservesAll135OperationAndComponentSemanticsAndAddsOnlyThreeBindingRoutes()throws Exception{
        long started=System.nanoTime();boolean captured=false;
        try{
        original135(YAML.readTree(Files.readAllBytes(Path.of("../core-common-config-mgmt-sdk/src/main/resources/openapi.yml"))));
        var client=WebTestClient.bindToApplicationContext(context).configureClient().codecs(configurer->configurer.defaultCodecs().maxInMemorySize(4*1024*1024)).responseTimeout(Duration.ofSeconds(30)).build();byte[] json=client.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();byte[] yaml=client.get().uri("/v3/api-docs.yaml").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();assertNotNull(json);assertNotNull(yaml);assertTrue(json.length>0&&json.length<=4*1024*1024&&yaml.length>0&&yaml.length<=4*1024*1024);var actual=JSON.readTree(json);assertEquals(actual,YAML.readTree(yaml));var expected=new TreeSet<String>();baseline().path("operations").fieldNames().forEachRemaining(expected::add);expected.addAll(NEW);assertEquals(138,expected.size());assertEquals(expected,operations(actual).keySet());original135WithCardPurpose(actual);governedSchema(actual);Files.createDirectories(Path.of("target"));Files.write(Path.of("target/binding-governance-openapi.json"),json);Files.write(Path.of("target/binding-governance-openapi.yml"),yaml);captured=true;
        }finally{Files.createDirectories(Path.of("target"));Files.write(Path.of("target/binding-governance-producer-timing.json"),JSON.writeValueAsBytes(Map.of("metadataOnlyWaitSeconds",30,"producerTestElapsedNanos",System.nanoTime()-started,"rawCaptureWritten",captured,"businessBudgetSecondsUnchanged",5,"ordinaryReactorCount",1)));}
    }
    @Test @Order(2) void documentationProducerCreatesNoGovernanceOwnerOrBusinessConnectionFactory(){assertEquals(0,context.getBeanNamesForType(TenantResourceBindingGovernedActions.class,false,false).length);assertEquals(0,context.getBeanNamesForType(io.r2dbc.spi.ConnectionFactory.class,false,false).length);}
    @Test @Order(3) void guardedBoundaryCopiesOnlyTheSameVerified138RawProducerIntoTheOriginalSdkCanonical()throws Exception{
        if(!Boolean.getBoolean("owned.config.binding-governance-sdk-boundary.enabled"))return;
        assertEquals("validated-hosted-manual-main-v1",System.getProperty("owned.config.binding-governance-sdk-boundary.hosted"));var root=Path.of(System.getProperty("owned.config.binding-governance-sdk-boundary.root")).normalize();assertTrue(root.isAbsolute());assertEquals(root,root.toRealPath());assertEquals(Path.of("..").toRealPath(),root);assertEquals(Path.of(System.getProperty("owned.config.binding-governance-sdk-boundary.workspace"),"qualification","config-binding-governance-chain","core-common-config-mgmt").toRealPath(),root);
        var canonical=root.resolve("core-common-config-mgmt-sdk/src/main/resources/openapi.yml");assertTrue(Files.isRegularFile(canonical,LinkOption.NOFOLLOW_LINKS));assertEquals(canonical,canonical.toRealPath());var preimage=Files.readAllBytes(canonical);assertTrue(preimage.length>0&&preimage.length<=4*1024*1024);assertEquals(System.getProperty("owned.config.binding-governance-sdk-boundary.preimage-sha256"),sha(preimage));original135(YAML.readTree(preimage));
        byte[] json=Files.readAllBytes(Path.of("target/binding-governance-openapi.json")),yaml=Files.readAllBytes(Path.of("target/binding-governance-openapi.yml"));assertTrue(json.length>0&&json.length<=4*1024*1024&&yaml.length>0&&yaml.length<=4*1024*1024);var actual=JSON.readTree(json);assertEquals(actual,YAML.readTree(yaml));original135WithCardPurpose(actual);assertEquals(138,operations(actual).size());governedSchema(actual);Files.write(Path.of("target/binding-governance-sdk-preimage.yml"),preimage,StandardOpenOption.CREATE_NEW);Files.write(canonical,yaml,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);assertArrayEquals(yaml,Files.readAllBytes(canonical));
    }
}

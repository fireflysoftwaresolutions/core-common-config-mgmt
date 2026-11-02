/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.common.config.sdk;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Reads actual raw producer resources, including complex YAML path keys; no hand-authored SDK schema. */
class QuotePurposeOriginalOperationsGeneratedContractTest {
    private static Set<String> operations(JsonNode document){var paths=document.get("paths");assertNotNull(paths);assertTrue(paths.isObject());var result=new TreeSet<String>();var ids=new HashSet<String>();paths.fields().forEachRemaining(path->path.getValue().fields().forEachRemaining(method->{if(Set.of("get","post","put","patch","delete","head","options","trace").contains(method.getKey())){var id=method.getValue().get("operationId");assertNotNull(id);assertTrue(id.isTextual());assertTrue(ids.add(id.textValue()));assertTrue(result.add(path.getKey()+"\n"+method.getKey()+"\n"+id.textValue()));}}));assertFalse(result.isEmpty());return result;}
    private static String digest(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    @Test void everyOriginalOperationSurvivesActualProducerAndSdkGeneration()throws Exception{
        byte[] actual;try(var resource=getClass().getResourceAsStream("/openapi.yml")){assertNotNull(resource);actual=resource.readAllBytes();}
        var input=System.getProperty("firefly.qualification.config-original-preimage");byte[] before=input==null?actual:Files.readAllBytes(Path.of(input));
        var yaml=new ObjectMapper(new YAMLFactory());var original=operations(yaml.readTree(before));var observed=operations(yaml.readTree(actual));
        if(Boolean.getBoolean("owned.config.binding-governance-sdk-boundary.enabled")){
            var expected=new TreeSet<>(original);expected.addAll(Set.of("/api/v1/tenant-resource-bindings/governance/intents\npost\nprepareTenantResourceBindingIntent","/api/v1/tenant-resource-bindings/governance/actions\npost\nexecuteTenantResourceBindingAction","/api/v1/tenant-resource-bindings/governance/actions/observations\npost\nobserveTenantResourceBindingAction"));assertEquals(138,expected.size());assertEquals(expected,observed);
        }else assertEquals(original,observed);
        var json=new ObjectMapper();var receipt=Map.of("preimageSha256",digest(before),"actualProducerSha256",digest(actual),"originalOperationCount",original.size(),"actualOperationCount",observed.size(),"operations",observed);
        Files.writeString(Path.of("target/quote-purpose-original-operations.json"),json.writeValueAsString(receipt));
    }
}

package io.fireflysoftware.common.config.sdk;

import io.fireflysoftware.common.config.sdk.invoker.ApiClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Observes actual original-generator output; no handwritten/generated-client substitutes or invented members. */
class TenantResourceBindingGovernanceGeneratedContractTest {
    static final Set<String> IDS=Set.of("prepareTenantResourceBindingIntent","executeTenantResourceBindingAction","observeTenantResourceBindingAction");
    @Test void originalSdkResourceIsTheExactFresh138ProducerWhenOwnedBoundaryIsEnabled()throws Exception{
        byte[] raw;try(var input=getClass().getResourceAsStream("/openapi.yml")){assertNotNull(input);raw=input.readAllBytes();}var doc=new ObjectMapper(new YAMLFactory()).readTree(raw);var ids=new HashSet<String>();doc.path("paths").fields().forEachRemaining(path->path.getValue().fields().forEachRemaining(method->{if(Set.of("get","post","put","patch","delete","head","options","trace").contains(method.getKey()))assertTrue(ids.add(method.getValue().path("operationId").asText()));}));
        if(Boolean.getBoolean("owned.config.binding-governance-sdk-boundary.enabled")){assertEquals(138,ids.size());assertTrue(ids.containsAll(IDS));assertArrayEquals(Files.readAllBytes(Path.of("../core-common-config-mgmt-web/target/binding-governance-openapi.yml")),raw);}else assertTrue(ids.size()==135||ids.size()==138);
        var module=Path.of(".").toRealPath();assertEquals(module.resolve("target/classes"),Path.of(ApiClient.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());assertEquals(1,Collections.list(ApiClient.class.getClassLoader().getResources(ApiClient.class.getName().replace('.','/')+".class")).size());
    }
    @Test void allThreeTypedMethodsAreObservedFromFreshGeneratedCompiledApiClasses()throws Exception{
        var generated=Path.of("target/generated-sources/src/gen/java/main/io/fireflysoftware/common/config/sdk/api");assertTrue(Files.isDirectory(generated,LinkOption.NOFOLLOW_LINKS));var observed=new HashMap<String,java.lang.reflect.Method>();
        try(var files=Files.list(generated)){for(var source:files.filter(path->path.getFileName().toString().endsWith(".java")).toList()){var type=Class.forName("io.fireflysoftware.common.config.sdk.api."+source.getFileName().toString().replace(".java",""));for(var method:type.getDeclaredMethods())if(java.lang.reflect.Modifier.isPublic(method.getModifiers())&&IDS.contains(method.getName())){assertNull(observed.put(method.getName(),method));assertEquals(reactor.core.publisher.Mono.class,method.getReturnType());assertEquals(1,method.getParameterCount());assertEquals("io.fireflysoftware.common.config.sdk.model",method.getParameterTypes()[0].getPackageName());assertEquals(Path.of("target/classes").toRealPath(),Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());}}}
        if(Boolean.getBoolean("owned.config.binding-governance-sdk-boundary.enabled")||!observed.isEmpty())assertEquals(IDS,observed.keySet());
    }
}

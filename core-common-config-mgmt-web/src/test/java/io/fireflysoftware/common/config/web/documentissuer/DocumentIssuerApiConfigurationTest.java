/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.web.documentissuer;

import io.fireflysoftware.common.config.core.services.documentissuer.DocumentIssuerService;
import java.lang.reflect.Field;
import java.util.*;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIssuerApiConfigurationTest {
    Class<?> configuration()throws Exception{return Class.forName("io.fireflysoftware.common.config.web.documentissuer.DocumentIssuerApiConfiguration");}
    ApplicationContextRunner runner()throws Exception{return new ApplicationContextRunner().withUserConfiguration(configuration()).withBean(SecurityContextPort.class,()->()->Mono.error(new AssertionError("No caller observation at startup"))).withBean(PolicyDecisionPort.class,()->(p,a,r,c)->Mono.error(new AssertionError("No policy call at startup"))).withBean(ResourceServerProperties.class,()->{var p=new ResourceServerProperties();p.setStrictPolicy(true);p.setIssuer("https://identity.example");p.setJwkSetUri("https://identity.example/jwks");p.setAudiences(List.of("docs"));return p;});}
    String[] settings(){String command=java.util.UUID.randomUUID().toString(),reader=java.util.UUID.randomUUID().toString();return new String[]{"firefly.configuration.document-issuer.enabled=true","firefly.configuration.document-issuer.command-username=issuer_command","firefly.configuration.document-issuer.command-password="+command,"firefly.configuration.document-issuer.reader-username=issuer_reader","firefly.configuration.document-issuer.reader-password="+reader,"firefly.configuration.document-issuer.audience=docs","firefly.configuration.document-issuer.tenant-claim=tenant_id","firefly.configuration.document-issuer.client-claim=azp","firefly.configuration.document-issuer.client-ids[0]=staff","spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:5432/unconnected","spring.r2dbc.username=legacy"};}
    @Test void defaultOffNeedsNoIssuerCredentialsOrPools()throws Exception{runner().run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(DocumentIssuerService.class).isEmpty());assertTrue(c.getBeansOfType(io.r2dbc.spi.ConnectionFactory.class).isEmpty());assertFalse(c.containsBean("documentIssuerApiResources"));});}
    @Test void enabledMissingCredentialsRefusesStartup()throws Exception{runner().withPropertyValues("firefly.configuration.document-issuer.enabled=true").run(c->{assertNotNull(c.getStartupFailure());});}
    @Test void enabledResourcesOwnTwoLazyPoolsWithoutPublicFactoryBeans()throws Exception{runner().withPropertyValues(settings()).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(DocumentIssuerService.class).size());assertTrue(c.getBeansOfType(io.r2dbc.spi.ConnectionFactory.class).isEmpty());Object resources=c.getBean("documentIssuerApiResources");for(String name:List.of("commandPool","readerPool")){try{Field field=resources.getClass().getDeclaredField(name);field.setAccessible(true);var pool=(io.r2dbc.pool.ConnectionPool)field.get(resources);var metrics=pool.getMetrics().orElseThrow();assertEquals(0,metrics.allocatedSize());assertEquals(0,metrics.acquiredSize());assertEquals(0,metrics.pendingAcquireSize());}catch(ReflectiveOperationException error){throw new AssertionError(error);}}});}
    @Test void commandReaderAndLegacyCredentialsCannotAlias()throws Exception{for(String property:List.of("firefly.configuration.document-issuer.reader-username=issuer_command","firefly.configuration.document-issuer.command-username=legacy"))runner().withPropertyValues(settings()).withPropertyValues(property).run(c->assertNotNull(c.getStartupFailure()));}
    @Test void configuredAudienceMustBeProtectedByExistingResourceServer()throws Exception{runner().withPropertyValues(settings()).withPropertyValues("firefly.configuration.document-issuer.audience=other").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void metadataProfileCannotConstructNativePools()throws Exception{runner().withPropertyValues(settings()).withPropertyValues("spring.profiles.active=openapi-gen").run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(DocumentIssuerService.class).isEmpty());assertFalse(c.containsBean("documentIssuerApiResources"));});}
}

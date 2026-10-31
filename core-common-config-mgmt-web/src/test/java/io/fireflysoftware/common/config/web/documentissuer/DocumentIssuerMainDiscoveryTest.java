/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.web.documentissuer;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.env.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Main-linked definition discovery; no context refresh or Main startup claim. */
class DocumentIssuerMainDiscoveryTest {
    DefaultListableBeanFactory scan(boolean enabled,boolean metadata)throws Exception{
        var main=Class.forName("io.fireflysoftware.common.config.web.CommonPlatformConfigMgmtApplication");var registry=new DefaultListableBeanFactory();var scanner=new ClassPathBeanDefinitionScanner(registry);var environment=new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("owned-source-settings",Map.of("firefly.configuration.document-issuer.enabled",enabled)));
        if(metadata)environment.setActiveProfiles("openapi-gen");scanner.setEnvironment(environment);
        var packages=Arrays.stream(main.getAnnotation(SpringBootApplication.class).scanBasePackages()).filter(x->x.startsWith("io.fireflysoftware.common.config")).toArray(String[]::new);assertEquals(1,packages.length);scanner.scan(packages);return registry;
    }
    @Test void actualMainPackagesDoNotDiscoverDefaultOffIssuerBeans()throws Exception{var registry=scan(false,false);assertFalse(registry.containsBeanDefinition("documentIssuerController"));assertFalse(registry.containsBeanDefinition("documentIssuerApiConfiguration"));assertFalse(registry.containsBeanDefinition("documentIssuerApiSettings"));assertEquals(1,registry.getSingletonCount());assertInstanceOf(org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport.class,registry.getSingleton("autoConfigurationReport"));}
    @Test void optInDiscoveryIncludesOnlyExplicitConfigurationAndController()throws Exception{var registry=scan(true,false);assertTrue(registry.containsBeanDefinition("documentIssuerController"));assertTrue(registry.containsBeanDefinition("documentIssuerApiConfiguration"));assertFalse(registry.containsBeanDefinition("documentIssuerApiSettings"));assertFalse(registry.containsBeanDefinition("documentIssuerApiResources"));}
    @Test void metadataDiscoveryCannotEscapeIntoNativePoolConfiguration()throws Exception{var registry=scan(true,true);assertTrue(registry.containsBeanDefinition("documentIssuerController"));assertFalse(registry.containsBeanDefinition("documentIssuerApiConfiguration"));assertFalse(registry.containsBeanDefinition("documentIssuerApiResources"));}
}

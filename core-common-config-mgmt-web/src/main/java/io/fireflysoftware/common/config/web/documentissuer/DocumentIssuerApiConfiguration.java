/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.web.documentissuer;

import io.fireflysoftware.common.config.core.services.documentissuer.DocumentIssuerService;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.spi.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@Profile("!openapi-gen")
@ConditionalOnProperty(prefix="firefly.configuration.document-issuer",name="enabled",havingValue="true")
@ConditionalOnMissingBean(DocumentIssuerService.class)
public class DocumentIssuerApiConfiguration {
    @Bean(destroyMethod="close")
    public DocumentIssuerApiResources documentIssuerApiResources(Environment environment,ResourceServerProperties security,SecurityContextPort callers,PolicyDecisionPort policies){
        var settings=Binder.get(environment).bind("firefly.configuration.document-issuer",Bindable.of(DocumentIssuerApiSettings.class)).orElseThrow(DocumentIssuerApiSettings::unavailable);
        return new DocumentIssuerApiResources(settings,environment.getRequiredProperty("spring.r2dbc.url"),environment.getProperty("spring.r2dbc.username"),security,callers,policies);
    }
    @Bean public DocumentIssuerService documentIssuerService(DocumentIssuerApiResources resources){return resources.service();}
}

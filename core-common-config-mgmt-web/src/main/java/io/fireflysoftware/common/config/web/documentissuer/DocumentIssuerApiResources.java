/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.web.documentissuer;

import io.fireflysoftware.common.config.core.authority.DocumentIssuerAuthorization;
import io.fireflysoftware.common.config.core.persistence.documentissuer.DocumentIssuerTransaction;
import io.fireflysoftware.common.config.core.services.documentissuer.DocumentIssuerService;
import io.fireflysoftware.common.config.core.services.documentissuer.impl.DocumentIssuerServiceImpl;
import io.r2dbc.pool.*;
import io.r2dbc.spi.*;
import java.time.*;
import java.util.function.LongSupplier;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.spi.*;

/** Private lazy pools; no ConnectionFactory bean can replace or expose the legacy pool. */
public final class DocumentIssuerApiResources implements AutoCloseable {
    private final ConnectionPool commandPool;
    private final ConnectionPool readerPool;
    private final DocumentIssuerService service;
    DocumentIssuerApiResources(DocumentIssuerApiSettings settings,String url,String legacyUser,
            ResourceServerProperties security,SecurityContextPort callers,PolicyDecisionPort policies) {
        if(settings.commandUsername().equals(legacyUser)||settings.readerUsername().equals(legacyUser)
                ||!security.isStrictPolicy()||security.getIssuer()==null||security.getIssuer().isBlank()
                ||security.getJwkSetUri()==null||security.getJwkSetUri().isBlank()||!security.getAudiences().contains(settings.audience()))throw DocumentIssuerApiSettings.unavailable();
        ConnectionPool command=null,reader=null;
        try {
            var original=ConnectionFactoryOptions.parse(url);
            if(!"postgresql".equals(original.getValue(ConnectionFactoryOptions.DRIVER))||original.hasOption(ConnectionFactoryOptions.USER)||original.hasOption(ConnectionFactoryOptions.PASSWORD))throw DocumentIssuerApiSettings.unavailable();
            Clock clock=Clock.systemUTC();LongSupplier nanos=System::nanoTime;
            var authority=new DocumentIssuerAuthorization(callers,policies,security.getIssuer(),settings.audience(),settings.tenantClaim(),settings.clientClaim(),settings.clientIds(),clock,nanos);
            command=pool(original,settings.commandUsername(),settings.commandPassword());
            reader=pool(original,settings.readerUsername(),settings.readerPassword());
            service=new DocumentIssuerServiceImpl(authority,new DocumentIssuerTransaction(command,authority,clock,nanos),new DocumentIssuerTransaction(reader,authority,clock,nanos));
            commandPool=command;readerPool=reader;
        } catch(RuntimeException failure) {
            boolean complete=dispose(reader);complete=dispose(command)&&complete;
            throw new IllegalStateException(complete?"Document issuer resource graph unavailable":"Document issuer resource graph and cleanup unavailable");
        }
    }
    private static ConnectionPool pool(ConnectionFactoryOptions original,String username,String password){
        var factory=ConnectionFactories.get(original.mutate().option(ConnectionFactoryOptions.USER,username).option(ConnectionFactoryOptions.PASSWORD,password).build());
        return new ConnectionPool(ConnectionPoolConfiguration.builder(factory).initialSize(0).maxSize(4)
            .maxAcquireTime(Duration.ofSeconds(2)).maxCreateConnectionTime(Duration.ofSeconds(2)).maxIdleTime(Duration.ofSeconds(30)).build());
    }
    DocumentIssuerService service(){return service;}
    private static boolean dispose(ConnectionPool pool){if(pool==null)return true;try{pool.disposeLater().block(Duration.ofSeconds(2));return true;}catch(RuntimeException failure){return false;}}
    @Override public void close(){boolean complete=dispose(readerPool);complete=dispose(commandPool)&&complete;if(!complete)throw new IllegalStateException("Document issuer resource cleanup unavailable");}
    @Override public String toString(){return "DocumentIssuerApiResources";}
}

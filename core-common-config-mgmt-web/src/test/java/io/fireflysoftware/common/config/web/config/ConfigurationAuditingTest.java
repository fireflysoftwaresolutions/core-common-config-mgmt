package io.fireflysoftware.common.config.web.config;

import io.fireflysoftware.common.config.models.entities.ApiProcessMapping;
import io.fireflysoftware.common.config.models.entities.Tenant;
import io.fireflysoftware.common.config.web.CommonPlatformConfigMgmtApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.auditing.ReactiveIsNewAwareAuditingHandler;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.r2dbc.mapping.R2dbcSimpleTypeHolder;
import java.time.*;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationAuditingTest {
    @Test void standardUtcProviderAuditsBothOwnerTimestampTypesAndPreservesCreation() throws Exception {
        assertEquals("configurationAuditDateTimeProvider",CommonPlatformConfigMgmtApplication.class.getAnnotation(EnableR2dbcAuditing.class).dateTimeProviderRef());
        var previous=TimeZone.getDefault();
        try {TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            try(var app=new AnnotationConfigApplicationContext(Class.forName("io.fireflysoftware.common.config.web.config.ConfigurationAuditingConfiguration"))) {
                var provider=app.getBean("configurationAuditDateTimeProvider",DateTimeProvider.class);var sample=(OffsetDateTime)provider.getNow().orElseThrow();assertEquals(ZoneOffset.UTC,sample.getOffset());
                var context=new R2dbcMappingContext();context.setSimpleTypeHolder(R2dbcSimpleTypeHolder.HOLDER);context.getRequiredPersistentEntity(Tenant.class);context.getRequiredPersistentEntity(ApiProcessMapping.class);var handler=new ReactiveIsNewAwareAuditingHandler(PersistentEntities.of(context));handler.setDateTimeProvider(provider);
                var before=Instant.now();var tenant=handler.markAudited(new Tenant()).cast(Tenant.class).block();var mapping=handler.markAudited(new ApiProcessMapping()).cast(ApiProcessMapping.class).block();var after=Instant.now();
                assertNotNull(tenant);assertNotNull(mapping);assertNotNull(tenant.getCreatedAt());assertNotNull(tenant.getUpdatedAt());assertNotNull(mapping.getCreatedAt());assertNotNull(mapping.getUpdatedAt());
                assertFalse(tenant.getCreatedAt().toInstant(ZoneOffset.UTC).isBefore(before));assertFalse(tenant.getCreatedAt().toInstant(ZoneOffset.UTC).isAfter(after));assertEquals(ZoneOffset.UTC,mapping.getCreatedAt().getOffset());assertFalse(mapping.getCreatedAt().toInstant().isBefore(before));assertFalse(mapping.getCreatedAt().toInstant().isAfter(after));
                var tenantCreated=tenant.getCreatedAt();var mappingCreated=mapping.getCreatedAt();tenant.setVersion(4L);mapping.setVersion(7L);var changedTenant=handler.markAudited(tenant).cast(Tenant.class).block();var changedMapping=handler.markAudited(mapping).cast(ApiProcessMapping.class).block();assertEquals(tenantCreated,changedTenant.getCreatedAt());assertEquals(mappingCreated,changedMapping.getCreatedAt());assertFalse(changedTenant.getUpdatedAt().isBefore(tenantCreated));assertFalse(changedMapping.getUpdatedAt().isBefore(mappingCreated));
            }
        }finally{TimeZone.setDefault(previous);}
    }
}

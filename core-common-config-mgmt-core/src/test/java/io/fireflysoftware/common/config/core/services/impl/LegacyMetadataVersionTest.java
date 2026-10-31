package io.fireflysoftware.common.config.core.services.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyMetadataVersionTest {
    @ParameterizedTest
    @ValueSource(strings={"Tenant","TenantStatus","Provider","ProviderStatus","ProviderType","ProviderTenant","ProviderParameter","ProviderValueMapping","TenantBranding"})
    void loadedOptimisticVersionSurvivesReadOnlyDtoMapping(String type) throws Exception {
        var root="io.fireflysoftware.common.config.";var entityType=Class.forName(root+"models.entities."+type);var dtoType=Class.forName(root+"interfaces.dtos."+type+"DTO");var repositoryType=Class.forName(root+"models.repositories."+type+"Repository");var mapperType=Class.forName(root+"core.mappers."+type+"Mapper");var serviceType=Class.forName(root+"core.services.impl."+type+"ServiceImpl");var id=UUID.randomUUID();var created=LocalDateTime.of(2026,1,1,0,0);var stored=entityType.getDeclaredConstructor().newInstance();ReflectionTestUtils.setField(stored,"id",id);ReflectionTestUtils.setField(stored,"version",7L);ReflectionTestUtils.setField(stored,"createdAt",created);var dto=dtoType.getDeclaredConstructor().newInstance();var mapped=entityType.getDeclaredConstructor().newInstance();ReflectionTestUtils.setField(mapped,"id",id);assertNull(ReflectionTestUtils.getField(mapped,"version"));
        var repository=mock(repositoryType,invocation->{if(invocation.getMethod().getName().equals("findById"))return Mono.just(stored);if(invocation.getMethod().getName().equals("save")){var value=invocation.getArgument(0);assertEquals(7L,ReflectionTestUtils.getField(value,"version"));assertEquals(created,ReflectionTestUtils.getField(value,"createdAt"));return Mono.just(value);}return RETURNS_DEFAULTS.answer(invocation);});var mapper=mock(mapperType,invocation->switch(invocation.getMethod().getName()){case "toEntity"->mapped;case "toDTO"->dto;default->RETURNS_DEFAULTS.answer(invocation);});var service=serviceType.getDeclaredConstructor().newInstance();ReflectionTestUtils.setField(service,"repository",repository);ReflectionTestUtils.setField(service,"mapper",mapper);assertSame(dto,((Mono<?>)serviceType.getMethod("update",UUID.class,dtoType).invoke(service,id,dto)).block());
    }
}

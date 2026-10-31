/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.common.config.core.services.impl;

import io.fireflysoftware.common.config.core.mappers.TenantBrandingMapper;
import io.fireflysoftware.common.config.core.services.TenantBrandingService;
import io.fireflysoftware.common.config.interfaces.dtos.TenantBrandingDTO;
import io.fireflysoftware.common.config.models.entities.TenantBranding;
import io.fireflysoftware.common.config.models.repositories.TenantBrandingRepository;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Service
public class TenantBrandingServiceImpl implements TenantBrandingService {

    @Autowired
    private TenantBrandingRepository repository;

    @Autowired
    private TenantBrandingMapper mapper;

    @Override
    public Mono<TenantBrandingDTO> getById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant branding not found with id: " + id)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<TenantBrandingDTO> getByTenantId(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant branding not found for tenant id: " + tenantId)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaginationResponse<TenantBrandingDTO>> filter(FilterRequest<TenantBrandingDTO> filterRequest) {
        return FilterUtils.createFilter(
                TenantBranding.class,
                mapper::toDTO
        ).filter(filterRequest);
    }

    @Override
    public Mono<TenantBrandingDTO> create(TenantBrandingDTO tenantBrandingDTO) {
        tenantBrandingDTO.setId(null);
        TenantBranding entity = mapper.toEntity(tenantBrandingDTO);
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<TenantBrandingDTO> update(UUID id, TenantBrandingDTO tenantBrandingDTO) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant branding not found with id: " + id)))
                .flatMap(existingEntity -> {
                    tenantBrandingDTO.setId(id);
                    TenantBranding updatedEntity = mapper.toEntity(tenantBrandingDTO);
                    updatedEntity.setCreatedAt(existingEntity.getCreatedAt());
                    updatedEntity.setVersion(existingEntity.getVersion());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant branding not found with id: " + id)))
                .flatMap(entity -> repository.delete(entity));
    }
}


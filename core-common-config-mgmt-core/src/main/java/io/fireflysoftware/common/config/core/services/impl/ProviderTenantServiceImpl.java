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

import io.fireflysoftware.common.config.core.mappers.ProviderTenantMapper;
import io.fireflysoftware.common.config.core.services.ProviderTenantService;
import io.fireflysoftware.common.config.interfaces.dtos.ProviderTenantDTO;
import io.fireflysoftware.common.config.models.entities.ProviderTenant;
import io.fireflysoftware.common.config.models.repositories.ProviderTenantRepository;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Implementation of the ProviderTenantService interface
 */
@Service
public class ProviderTenantServiceImpl implements ProviderTenantService {

    @Autowired
    private ProviderTenantRepository repository;

    @Autowired
    private ProviderTenantMapper mapper;

    @Override
    public Mono<ProviderTenantDTO> getById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider-Tenant relationship not found with id: " + id)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaginationResponse<ProviderTenantDTO>> filter(FilterRequest<ProviderTenantDTO> filterRequest) {
        return FilterUtils.createFilter(
                ProviderTenant.class,
                mapper::toDTO
        ).filter(filterRequest);
    }

    @Override
    public Mono<ProviderTenantDTO> create(ProviderTenantDTO providerTenantDTO) {
        // Set ID to null to ensure a new entity is created
        providerTenantDTO.setId(null);

        // Convert DTO to entity
        ProviderTenant entity = mapper.toEntity(providerTenantDTO);

        // Save entity and return mapped DTO
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<ProviderTenantDTO> update(UUID id, ProviderTenantDTO providerTenantDTO) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider-Tenant relationship not found with id: " + id)))
                .flatMap(existingEntity -> {
                    // Set the ID from the path parameter
                    providerTenantDTO.setId(id);

                    // Convert DTO to entity
                    ProviderTenant updatedEntity = mapper.toEntity(providerTenantDTO);

                    // Preserve created date
                    updatedEntity.setCreatedAt(existingEntity.getCreatedAt());
                    updatedEntity.setVersion(existingEntity.getVersion());

                    // Save updated entity
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider-Tenant relationship not found with id: " + id)))
                .flatMap(entity -> repository.delete(entity));
    }
}


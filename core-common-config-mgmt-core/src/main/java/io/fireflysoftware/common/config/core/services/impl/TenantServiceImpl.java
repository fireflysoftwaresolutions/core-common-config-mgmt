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

import io.fireflysoftware.common.config.core.mappers.TenantMapper;
import io.fireflysoftware.common.config.core.services.TenantService;
import io.fireflysoftware.common.config.interfaces.dtos.TenantDTO;
import io.fireflysoftware.common.config.models.entities.Tenant;
import io.fireflysoftware.common.config.models.repositories.TenantRepository;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Implementation of the TenantService interface
 */
@Service
public class TenantServiceImpl implements TenantService {

    @Autowired
    private TenantRepository repository;

    @Autowired
    private TenantMapper mapper;

    @Override
    public Mono<TenantDTO> getById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant not found with id: " + id)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaginationResponse<TenantDTO>> filter(FilterRequest<TenantDTO> filterRequest) {
        return FilterUtils.createFilter(
                Tenant.class,
                mapper::toDTO
        ).filter(filterRequest);
    }

    @Override
    public Mono<TenantDTO> create(TenantDTO tenantDTO) {
        // Set ID to null to ensure a new entity is created
        tenantDTO.setId(null);

        // Convert DTO to entity
        Tenant entity = mapper.toEntity(tenantDTO);

        // Save entity and return mapped DTO
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<TenantDTO> update(UUID id, TenantDTO tenantDTO) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant not found with id: " + id)))
                .flatMap(existingEntity -> {
                    // Set the ID from the path parameter
                    tenantDTO.setId(id);

                    // Convert DTO to entity
                    Tenant updatedEntity = mapper.toEntity(tenantDTO);

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
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant not found with id: " + id)))
                .flatMap(entity -> repository.delete(entity));
    }
}


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

import io.fireflysoftware.common.config.core.mappers.TenantStatusMapper;
import io.fireflysoftware.common.config.core.services.TenantStatusService;
import io.fireflysoftware.common.config.interfaces.dtos.TenantStatusDTO;
import io.fireflysoftware.common.config.models.entities.TenantStatus;
import io.fireflysoftware.common.config.models.repositories.TenantStatusRepository;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Implementation of the TenantStatusService interface
 */
@Service
public class TenantStatusServiceImpl implements TenantStatusService {

    @Autowired
    private TenantStatusRepository repository;

    @Autowired
    private TenantStatusMapper mapper;

    @Override
    public Mono<TenantStatusDTO> getById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant status not found with id: " + id)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaginationResponse<TenantStatusDTO>> filter(FilterRequest<TenantStatusDTO> filterRequest) {
        return FilterUtils.createFilter(
                TenantStatus.class,
                mapper::toDTO
        ).filter(filterRequest);
    }

    @Override
    public Mono<TenantStatusDTO> create(TenantStatusDTO tenantStatusDTO) {
        // Set ID to null to ensure a new entity is created
        tenantStatusDTO.setId(null);

        // Convert DTO to entity
        TenantStatus entity = mapper.toEntity(tenantStatusDTO);

        // Save entity and return mapped DTO
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<TenantStatusDTO> update(UUID id, TenantStatusDTO tenantStatusDTO) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant status not found with id: " + id)))
                .flatMap(existingEntity -> {
                    // Set the ID from the path parameter
                    tenantStatusDTO.setId(id);

                    // Convert DTO to entity
                    TenantStatus updatedEntity = mapper.toEntity(tenantStatusDTO);

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
                .switchIfEmpty(Mono.error(new RuntimeException("Tenant status not found with id: " + id)))
                .flatMap(entity -> repository.delete(entity));
    }
}


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

import io.fireflysoftware.common.config.core.mappers.ProviderStatusMapper;
import io.fireflysoftware.common.config.core.services.ProviderStatusService;
import io.fireflysoftware.common.config.interfaces.dtos.ProviderStatusDTO;
import io.fireflysoftware.common.config.models.entities.ProviderStatus;
import io.fireflysoftware.common.config.models.repositories.ProviderStatusRepository;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Implementation of the ProviderStatusService interface
 */
@Service
public class ProviderStatusServiceImpl implements ProviderStatusService {

    @Autowired
    private ProviderStatusRepository repository;

    @Autowired
    private ProviderStatusMapper mapper;

    @Override
    public Mono<ProviderStatusDTO> getById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider status not found with id: " + id)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaginationResponse<ProviderStatusDTO>> filter(FilterRequest<ProviderStatusDTO> filterRequest) {
        return FilterUtils.createFilter(
                ProviderStatus.class,
                mapper::toDTO
        ).filter(filterRequest);
    }

    @Override
    public Mono<ProviderStatusDTO> create(ProviderStatusDTO providerStatusDTO) {
        // Set ID to null to ensure a new entity is created
        providerStatusDTO.setId(null);

        // Convert DTO to entity
        ProviderStatus entity = mapper.toEntity(providerStatusDTO);

        // Save entity and return mapped DTO
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<ProviderStatusDTO> update(UUID id, ProviderStatusDTO providerStatusDTO) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider status not found with id: " + id)))
                .flatMap(existingEntity -> {
                    // Set the ID from the path parameter
                    providerStatusDTO.setId(id);

                    // Convert DTO to entity
                    ProviderStatus updatedEntity = mapper.toEntity(providerStatusDTO);

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
                .switchIfEmpty(Mono.error(new RuntimeException("Provider status not found with id: " + id)))
                .flatMap(entity -> repository.delete(entity));
    }
}

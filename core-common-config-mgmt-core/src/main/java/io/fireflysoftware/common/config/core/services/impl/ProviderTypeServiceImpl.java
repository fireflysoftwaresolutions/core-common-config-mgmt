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

import io.fireflysoftware.common.config.core.mappers.ProviderTypeMapper;
import io.fireflysoftware.common.config.core.services.ProviderTypeService;
import io.fireflysoftware.common.config.interfaces.dtos.ProviderTypeDTO;
import io.fireflysoftware.common.config.models.entities.ProviderType;
import io.fireflysoftware.common.config.models.repositories.ProviderTypeRepository;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Implementation of the ProviderTypeService interface
 */
@Service
public class ProviderTypeServiceImpl implements ProviderTypeService {

    @Autowired
    private ProviderTypeRepository repository;

    @Autowired
    private ProviderTypeMapper mapper;

    @Override
    public Mono<ProviderTypeDTO> getById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider type not found with id: " + id)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaginationResponse<ProviderTypeDTO>> filter(FilterRequest<ProviderTypeDTO> filterRequest) {
        return FilterUtils.createFilter(
                ProviderType.class,
                mapper::toDTO
        ).filter(filterRequest);
    }

    @Override
    public Mono<ProviderTypeDTO> create(ProviderTypeDTO providerTypeDTO) {
        // Set ID to null to ensure a new entity is created
        providerTypeDTO.setId(null);

        // Convert DTO to entity
        ProviderType entity = mapper.toEntity(providerTypeDTO);

        // Save entity and return mapped DTO
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<ProviderTypeDTO> update(UUID id, ProviderTypeDTO providerTypeDTO) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider type not found with id: " + id)))
                .flatMap(existingEntity -> {
                    // Set the ID from the path parameter
                    providerTypeDTO.setId(id);

                    // Convert DTO to entity
                    ProviderType updatedEntity = mapper.toEntity(providerTypeDTO);

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
                .switchIfEmpty(Mono.error(new RuntimeException("Provider type not found with id: " + id)))
                .flatMap(entity -> repository.delete(entity));
    }
}

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

import io.fireflysoftware.common.config.core.mappers.ProviderMapper;
import io.fireflysoftware.common.config.core.services.ProviderService;
import io.fireflysoftware.common.config.interfaces.dtos.ProviderDTO;
import io.fireflysoftware.common.config.models.entities.Provider;
import io.fireflysoftware.common.config.models.repositories.ProviderRepository;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Implementation of the ProviderService interface
 */
@Service
public class ProviderServiceImpl implements ProviderService {

    @Autowired
    private ProviderRepository repository;

    @Autowired
    private ProviderMapper mapper;

    @Override
    public Mono<ProviderDTO> getById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider not found with id: " + id)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaginationResponse<ProviderDTO>> filter(FilterRequest<ProviderDTO> filterRequest) {
        return FilterUtils.createFilter(
                Provider.class,
                mapper::toDTO
        ).filter(filterRequest);
    }

    @Override
    public Mono<ProviderDTO> create(ProviderDTO providerDTO) {
        // Set ID to null to ensure a new entity is created
        providerDTO.setId(null);

        // Convert DTO to entity
        Provider entity = mapper.toEntity(providerDTO);

        // Save entity and return mapped DTO
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<ProviderDTO> update(UUID id, ProviderDTO providerDTO) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider not found with id: " + id)))
                .flatMap(existingEntity -> {
                    // Set the ID from the path parameter
                    providerDTO.setId(id);

                    // Convert DTO to entity
                    Provider updatedEntity = mapper.toEntity(providerDTO);

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
                .switchIfEmpty(Mono.error(new RuntimeException("Provider not found with id: " + id)))
                .flatMap(entity -> repository.delete(entity));
    }
}

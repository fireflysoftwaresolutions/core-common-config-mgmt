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

import io.fireflysoftware.common.config.core.mappers.ProviderValueMappingMapper;
import io.fireflysoftware.common.config.core.services.ProviderValueMappingService;
import io.fireflysoftware.common.config.interfaces.dtos.ProviderValueMappingDTO;
import io.fireflysoftware.common.config.models.entities.ProviderValueMapping;
import io.fireflysoftware.common.config.models.repositories.ProviderValueMappingRepository;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Service
public class ProviderValueMappingServiceImpl implements ProviderValueMappingService {

    @Autowired
    private ProviderValueMappingRepository repository;

    @Autowired
    private ProviderValueMappingMapper mapper;

    @Override
    public Mono<ProviderValueMappingDTO> getById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider value mapping not found with id: " + id)))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaginationResponse<ProviderValueMappingDTO>> filter(FilterRequest<ProviderValueMappingDTO> filterRequest) {
        return FilterUtils.createFilter(
                ProviderValueMapping.class,
                mapper::toDTO
        ).filter(filterRequest);
    }

    @Override
    public Mono<ProviderValueMappingDTO> create(ProviderValueMappingDTO providerValueMappingDTO) {
        providerValueMappingDTO.setId(null);
        ProviderValueMapping entity = mapper.toEntity(providerValueMappingDTO);
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<ProviderValueMappingDTO> update(UUID id, ProviderValueMappingDTO providerValueMappingDTO) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider value mapping not found with id: " + id)))
                .flatMap(existingEntity -> {
                    providerValueMappingDTO.setId(id);
                    ProviderValueMapping updatedEntity = mapper.toEntity(providerValueMappingDTO);
                    updatedEntity.setCreatedAt(existingEntity.getCreatedAt());
                    updatedEntity.setVersion(existingEntity.getVersion());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Provider value mapping not found with id: " + id)))
                .flatMap(entity -> repository.delete(entity));
    }
}


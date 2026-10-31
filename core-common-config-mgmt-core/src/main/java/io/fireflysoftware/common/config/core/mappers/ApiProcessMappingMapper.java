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

package io.fireflysoftware.common.config.core.mappers;

import io.fireflysoftware.common.config.interfaces.dtos.ApiProcessMappingDTO;
import io.fireflysoftware.common.config.models.entities.ApiProcessMapping;
import io.r2dbc.postgresql.codec.Json;
import org.mapstruct.Named;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * MapStruct mapper for ApiProcessMapping entity and DTO conversions.
 * 
 * @author Firefly Development Team
 * @since 1.0.0
 */
@Mapper(componentModel = "spring")
public interface ApiProcessMappingMapper {

    /**
     * Converts an entity to a DTO.
     * 
     * @param entity the entity to convert
     * @return the DTO
     */
    @Mapping(target = "parameters", qualifiedByName = "jsonParametersToString")
    ApiProcessMappingDTO toDTO(ApiProcessMapping entity);

    /**
     * Converts a DTO to an entity for creation.
     * Ignores audit fields that are managed by the database.
     * 
     * @param dto the DTO to convert
     * @return the entity
     */
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "parameters", qualifiedByName = "stringToJsonParameters")
    ApiProcessMapping toEntity(ApiProcessMappingDTO dto);

    /**
     * Updates an existing entity from a DTO.
     * Preserves the ID, version, and audit fields.
     * 
     * @param dto the source DTO
     * @param entity the target entity to update
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "parameters", qualifiedByName = "stringToJsonParameters")
    void updateEntityFromDTO(ApiProcessMappingDTO dto, @MappingTarget ApiProcessMapping entity);

    @Named("stringToJsonParameters")
    default Json stringToJsonParameters(String value) {
        return value == null ? null : Json.of(value);
    }

    @Named("jsonParametersToString")
    default String jsonParametersToString(Json value) {
        return value == null ? null : value.asString();
    }
}

package io.fireflysoftware.common.config.core.mappers;

import io.fireflysoftware.common.config.interfaces.dtos.ApiProcessMappingDTO;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.junit.jupiter.api.Assertions.*;

class ApiProcessMappingJsonTest {
    @Test void jsonParametersUseDriverTypeWhilePublicDtoRetainsStringContract() {
        var mapper=Mappers.getMapper(ApiProcessMappingMapper.class);var dto=new ApiProcessMappingDTO();dto.setParameters("{\"fictional\":1}");var entity=mapper.toEntity(dto);assertEquals(dto.getParameters(),assertInstanceOf(Json.class,entity.getParameters()).asString());assertEquals(dto.getParameters(),mapper.toDTO(entity).getParameters());
        dto.setParameters("{\"fictional\":2}");mapper.updateEntityFromDTO(dto,entity);assertEquals(dto.getParameters(),assertInstanceOf(Json.class,entity.getParameters()).asString());dto.setParameters(null);mapper.updateEntityFromDTO(dto,entity);assertNull(entity.getParameters());assertNull(mapper.toDTO(entity).getParameters());
    }
}

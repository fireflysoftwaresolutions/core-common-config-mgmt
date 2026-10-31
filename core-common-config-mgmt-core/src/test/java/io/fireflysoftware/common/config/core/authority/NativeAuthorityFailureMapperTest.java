package io.fireflysoftware.common.config.core.authority;

import io.r2dbc.spi.R2dbcException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityFailureMapperTest {
    @Test void nativeProtocolErrorsKeepTheirClientMeaningWithoutLeakingDatabaseText() {
        for(var item:java.util.Map.of("P0002",404,"22023",400,"42501",403,"55000",409,"40001",409,"23505",409,"23514",503,"08006",503).entrySet()) {
            var result=(ResponseStatusException)NativeAuthorityFailureMapper.translate(new R2dbcException("private database details",item.getKey(),0) {});assertEquals(item.getValue(),result.getStatusCode().value());assertFalse(result.getReason().contains("private database details"));
        }
        var denial=new ResponseStatusException(HttpStatus.FORBIDDEN,"Current policy denied");assertSame(denial,NativeAuthorityFailureMapper.translate(denial));
    }
}

package io.fireflysoftware.common.config.core.authority;

import io.r2dbc.spi.R2dbcException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class NativeAuthorityFailureMapper {
    private NativeAuthorityFailureMapper() {}
    public static Throwable translate(Throwable original) {
        if(original instanceof ResponseStatusException) return original;
        var error=original;
        for(int depth=0;error!=null && depth<16;depth++,error=error.getCause()) {
            if(error instanceof ResponseStatusException) return error;
            if(error instanceof R2dbcException nativeError) {
                var status=switch(nativeError.getSqlState()==null?"":nativeError.getSqlState()) {
                    case "22023" -> HttpStatus.BAD_REQUEST;
                    case "P0002" -> HttpStatus.NOT_FOUND;
                    case "42501" -> HttpStatus.FORBIDDEN;
                    case "55000","40001","23505" -> HttpStatus.CONFLICT;
                    default -> HttpStatus.SERVICE_UNAVAILABLE;
                };
                return new ResponseStatusException(status,"Native authority operation could not be admitted");
            }
        }
        return original;
    }
}

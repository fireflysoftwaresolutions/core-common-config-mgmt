package io.fireflysoftware.common.config.core.services.authority;

import io.fireflysoftware.common.config.interfaces.authority.NativeAuthorityActionSnapshot;
import reactor.core.publisher.Mono;

public interface NativeAuthorityActionService {
    Mono<NativeAuthorityActionSnapshot> read(String tenantId,String actionId);
    Mono<NativeAuthorityActionSnapshot> readAsPlatform(String tenantId,String actionId);
    Mono<NativeAuthorityActionSnapshot> reconcileGrant(String tenantId,String actionId,byte[] original);
    Mono<NativeAuthorityActionSnapshot> reconcileGrantAsPlatform(String tenantId,String actionId,byte[] original);
}

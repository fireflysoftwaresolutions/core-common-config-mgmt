/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.repositories.authority;

import io.fireflysoftware.common.config.models.entities.authority.*;
import org.springframework.data.repository.Repository;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface NativeIssuerRepository extends Repository<IssuerRow, UUID> {
    Mono<IssuerRow> findByIssuerAuthorityId(UUID issuerAuthorityId);
}

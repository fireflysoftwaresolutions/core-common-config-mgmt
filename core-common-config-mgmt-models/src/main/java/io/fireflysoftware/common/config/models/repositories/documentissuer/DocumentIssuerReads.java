/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.repositories.documentissuer;

import io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.*;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface DocumentIssuerReads {
    Mono<Revision> revision(UUID tenantId,UUID publicationId);
    Mono<Control> control(UUID tenantId,UUID issuerId);
    Mono<Decision> publishedDecision(UUID tenantId,UUID publicationId);
    Mono<Action> action(UUID tenantId,UUID actionId);
    Mono<Audit> audit(UUID tenantId,UUID actionId);
    Mono<Source> source(UUID tenantId,UUID brandingId);
}

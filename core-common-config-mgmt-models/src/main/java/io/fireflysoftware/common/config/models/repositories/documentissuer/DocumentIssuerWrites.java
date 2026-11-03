/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.repositories.documentissuer;

import io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.*;
import reactor.core.publisher.Mono;

public interface DocumentIssuerWrites {
    Mono<Void> revision(Revision row);
    Mono<Void> control(Control row);
    Mono<Void> decision(Decision row);
    Mono<Void> action(Action row);
    Mono<Void> audit(Audit row);
    Mono<Void> outbox(Outbox row);
    Mono<Void> advance(Control expected, Control next);
}

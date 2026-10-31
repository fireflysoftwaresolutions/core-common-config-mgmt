/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.services.documentissuer;

import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.Receipt;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.Reference;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.Observation;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.PublicationReference;
import reactor.core.publisher.Mono;

public interface DocumentIssuerService {
    Mono<io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.SourceObservation> observeSource(String tenantId,byte[] original);
    Mono<Receipt> prepare(String tenantId,byte[] original);
    Mono<Receipt> publish(String tenantId,byte[] original);
    Mono<Receipt> revoke(String tenantId,byte[] original);
    Mono<Receipt> recoverPrepare(String tenantId,byte[] original);
    Mono<Receipt> recoverPublish(String tenantId,byte[] original);
    Mono<Receipt> recoverRevoke(String tenantId,byte[] original);
    Mono<Observation> current(Reference resolvedReference);
    Mono<Observation> current(PublicationReference publicationReference);
}

/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.entities.authority;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Embedded;
import io.r2dbc.postgresql.codec.Json;

@Table("cfg_native_outbox")
public record OutboxRow(@Id @Embedded(onEmpty=Embedded.OnEmpty.USE_NULL) OutboxKey id, UUID actionId, String eventKind, Json payload, String canonicalPayload, String payloadDigest) {}

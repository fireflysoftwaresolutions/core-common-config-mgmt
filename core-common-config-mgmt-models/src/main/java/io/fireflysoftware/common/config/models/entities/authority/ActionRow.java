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
import java.time.OffsetDateTime;
import io.r2dbc.postgresql.codec.Json;

@Table("cfg_native_action")
public record ActionRow(@Id @Embedded(onEmpty=Embedded.OnEmpty.USE_NULL) ActionKey id, String operation, UUID resourceId, UUID proposalId, Long proposalRevision, byte[] originalRequest, String canonicalIntent, String intentDigest, Long deadlineEpochMillis, String actorKind, String actorIssuer, String actorSubject, UUID actorEmployeeId, String actorClientId, String resultKind, Json result, UUID receiptId, UUID auditId, UUID eventId, OffsetDateTime admittedAt) {}

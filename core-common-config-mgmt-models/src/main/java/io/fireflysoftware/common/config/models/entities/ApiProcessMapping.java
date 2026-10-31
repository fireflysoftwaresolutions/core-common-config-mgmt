/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.common.config.models.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import io.r2dbc.postgresql.codec.Json;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity representing a mapping from an API operation to a process plugin.
 * 
 * <p>This entity is central to the Firefly Plugin Architecture, enabling
 * dynamic resolution of which process plugin handles each API operation
 * based on tenant, product, and channel context.</p>
 * 
 * <h3>Resolution Priority</h3>
 * <p>When multiple mappings match, they are resolved in this order:</p>
 * <ol>
 *   <li>Tenant + Product + Channel specific</li>
 *   <li>Tenant + Product specific</li>
 *   <li>Tenant + Channel specific</li>
 *   <li>Tenant specific</li>
 *   <li>Default (vanilla) mapping (tenant_id is NULL)</li>
 * </ol>
 * 
 * @author Firefly Development Team
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("api_process_mappings")
public class ApiProcessMapping {

    @Id
    private UUID id;

    /**
     * Tenant ID - NULL means this is a default/vanilla mapping.
     */
    @Column("tenant_id")
    private UUID tenantId;

    /**
     * Product ID - NULL means mapping applies to all products.
     */
    @Column("product_id")
    private UUID productId;

    /**
     * Channel type (e.g., MOBILE, WEB, API, BRANCH) - NULL means all channels.
     */
    @Column("channel_type")
    private String channelType;

    /**
     * API path pattern (e.g., "/api/v1/accounts").
     */
    @Column("api_path")
    private String apiPath;

    /**
     * HTTP method (GET, POST, PUT, DELETE, PATCH).
     */
    @Column("http_method")
    private String httpMethod;

    /**
     * The API operation identifier (e.g., "createAccount", "getBalance").
     */
    @Column("operation_id")
    private String operationId;

    /**
     * The process plugin ID to execute for this operation.
     */
    @Column("process_id")
    private String processId;

    /**
     * Optional version constraint - NULL uses the latest version.
     */
    @Column("process_version")
    private String processVersion;

    /**
     * Lower values have higher priority when multiple mappings match.
     */
    @Column("priority")
    private Integer priority;

    /**
     * Whether this mapping is active.
     */
    @Column("is_active")
    private Boolean isActive;

    /**
     * When this mapping becomes effective.
     */
    @Column("effective_from")
    private OffsetDateTime effectiveFrom;

    /**
     * When this mapping expires.
     */
    @Column("effective_to")
    private OffsetDateTime effectiveTo;

    /**
     * Additional configuration parameters (JSON).
     */
    @Column("parameters")
    private Json parameters;

    /**
     * Description for documentation.
     */
    @Column("description")
    private String description;

    /**
     * User who created this mapping.
     */
    @Column("created_by")
    private String createdBy;

    /**
     * User who last updated this mapping.
     */
    @Column("updated_by")
    private String updatedBy;

    @Version
    @Column("version")
    private Long version;

    @CreatedDate
    @Column("created_at")
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private OffsetDateTime updatedAt;

    /**
     * Checks if this is a vanilla/default mapping.
     * 
     * @return true if tenant_id is null
     */
    public boolean isVanilla() {
        return tenantId == null;
    }

    /**
     * Checks if this mapping is currently effective based on time constraints.
     * 
     * @return true if currently effective
     */
    public boolean isCurrentlyEffective() {
        if (!Boolean.TRUE.equals(isActive)) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (effectiveFrom != null && now.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && now.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    /**
     * Calculates the specificity score for this mapping.
     * Higher score = more specific (higher priority).
     * 
     * @return the specificity score
     */
    public int getSpecificityScore() {
        int score = 0;
        if (tenantId != null) score += 100;
        if (productId != null) score += 10;
        if (channelType != null && !channelType.isEmpty()) score += 1;
        return score;
    }
}

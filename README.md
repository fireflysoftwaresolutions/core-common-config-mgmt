<a href="https://fireflysoftware.io/openplatform/">
<picture>
  <source media="(max-width: 640px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-narrow-dark.svg">
  <source media="(max-width: 640px)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-narrow-light.svg">
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-dark.svg">
  <img src="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-light.svg" alt="Firefly OpenPlatform - Core Common - Configuration Management" width="100%">
</picture>
</a>

# Firefly OpenPlatform - Core Common - Configuration Management

Developed by the Firefly Software Foundation. [dev@fireflysoftware.io](mailto:dev@fireflysoftware.io)

[Website](https://fireflysoftware.io/) · [OpenPlatform](https://fireflysoftware.io/openplatform/) · [Module catalogue](https://fireflysoftware.io/openplatform/modules/)

**Enterprise-grade multi-tenant configuration management for the Firefly open-source core banking platform**

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/firefly-oss/core-common-config-mgmt)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-3.5.10-green.svg)](https://spring.io/projects/spring-boot)

## Overview

The Firefly Configuration Management Service is the **central configuration hub** for the Firefly core banking platform. It provides comprehensive management of multi-tenant configurations, external provider integrations, banking channels, feature flags, and audit trails. This service enables:

- **Multi-Tenant Management**: Complete isolation of configurations for multiple banks/financial institutions
- **Provider Integration**: Dynamic configuration of external services (KYC, payments, cards, BaaS, etc.)
- **Channel Configuration**: Flexible management of banking channels (Web, Mobile, ATM, Branch, Call Center, API, Open Banking)
- **Feature Flags**: Gradual feature rollout with percentage-based targeting
- **Dynamic Parameters**: EAV pattern for extensible configuration without schema changes
- **Environment Configuration**: Environment-specific settings (DEV, QA, STAGING, PROD)
- **Audit Trail**: Complete change tracking with rollback capability
- **Webhook Management**: Centralized webhook configuration with retry logic
- **Custom Branding**: Visual customization per tenant
- **Secure Credential Management**: Integration with `core-common-security-vault` for secure storage of API keys, passwords, and secrets

For native institution grants, governed tenant/status eligibility, current observations, and deployment prerequisites, see [Institution authority](docs/institution-authority.md).

For configuration-owned document issuer publication, finite current observations, original-action recovery, and optional deployment, see [Governed document issuers](docs/document-issuers.md).

## Quick Start

### Prerequisites

- Java 25+
- PostgreSQL 14+
- Maven 3.8+

### Build and Run

```bash
# Clone the repository
git clone https://github.com/firefly-oss/core-common-config-mgmt.git
cd core-common-config-mgmt

# Build the project
mvn clean install

# Run the service
cd core-common-config-mgmt-web
mvn spring-boot:run
```

### Access the API

Once running, access the Swagger UI at:
```
http://localhost:8080/swagger-ui.html
```

## Security & Credential Management

**Important**: This service **does NOT store credentials directly**. All sensitive information (API keys, passwords, secrets) is encrypted and stored securely in the `core-common-security-vault` microservice using AES-256-GCM encryption.

### How It Works

1. **Configuration Storage**: This service stores only a **credential UUID** (`credentialVaultId`) as a reference
2. **Credential Retrieval**: Applications retrieve the UUID and then decrypt the actual credential from the vault
3. **Security Benefits**:
   - Credentials encrypted with AES-256-GCM in the vault
   - Centralized credential rotation without config changes
   - Complete audit trail of all credential access
   - Fine-grained access control (IP, service, environment restrictions)
   - Compliance with PCI-DSS, SOC2, ISO27001

### Example: Storing a Secret Parameter

```json
{
  "providerId": "uuid-stripe-provider",
  "tenantId": "uuid-acme-bank",
  "parameterName": "api_key",
  "isSecret": true,
  "credentialVaultId": "550e8400-e29b-41d4-a716-446655440000",
  "parameterValue": null
}
```

**Note**: The `credentialVaultId` is the UUID of the credential in the security-vault.

### Example: Consuming a Secret Parameter

```java
// 1. Get parameter configuration
ProviderParameterDTO param = configClient.getProviderParameter(providerId, tenantId, "api_key");

// 2. If secret, decrypt from vault
if (param.getIsSecret()) {
    UUID credentialId = UUID.fromString(param.getCredentialVaultId());

    AccessRequest accessRequest = AccessRequest.builder()
        .userId(userId)
        .serviceName("payment-service")
        .ipAddress(ipAddress)
        .environment("production")
        .reason("Processing payment")
        .build();

    String apiKey = vaultClient.decryptCredential(credentialId, accessRequest).block();
    // Use apiKey...
}
```

**See the complete guide**: [Security Vault Integration](./docs/SECURITY_VAULT_INTEGRATION.md)

## Architecture

### Module Structure

```
core-common-config-mgmt/
├── core-common-config-mgmt-models/      # Entities, repositories, database migrations
├── core-common-config-mgmt-interfaces/  # DTOs and API contracts
├── core-common-config-mgmt-core/        # Business logic and services
├── core-common-config-mgmt-web/         # REST API controllers
└── core-common-config-mgmt-sdk/         # Auto-generated Java client
```

### Key Entities (16 Total)

#### Tenant Management (4 entities)
- **Tenant**: Isolated instance of Firefly (e.g., a bank, brand, or region)
- **TenantStatus**: Tenant lifecycle states (ACTIVE, SUSPENDED, INACTIVE, TRIAL, EXPIRED)
- **TenantBranding**: Visual customization (logos, colors, fonts, CSS)
- **TenantSettings**: Operational configuration (44 fields for security, limits, notifications, compliance)

#### Provider Management (6 entities)
- **Provider**: External service integration (KYC, payments, cards, BaaS, etc.)
- **ProviderType**: Provider categories (KYC, PAYMENT, CARD, BAAS, ACCOUNT, TRANSACTION, COMPLIANCE, NOTIFICATION, DOCUMENT, ANALYTICS)
- **ProviderStatus**: Provider states (ACTIVE, INACTIVE, MAINTENANCE, DEPRECATED)
- **ProviderParameter**: Dynamic configuration values using EAV pattern (API keys, URLs, timeouts, etc.)
- **ProviderValueMapping**: Value translation between Firefly and providers
- **ProviderTenant**: Many-to-many relationship with priority and failover support

#### Channel Management (2 entities)
- **ChannelConfig**: Banking channel configuration (WEB_BANKING, MOBILE_BANKING, ATM, BRANCH, CALL_CENTER, API, OPEN_BANKING)
- **ChannelConfigParameter**: Dynamic channel parameters using EAV pattern (limits, security, features, availability)

### Feature & Configuration Management (4 entities)
- **FeatureFlag**: Feature toggles with percentage-based rollout and user targeting
- **EnvironmentConfig**: Environment-specific configuration (DEV, QA, STAGING, PROD)
- **ConfigurationAudit**: Complete audit trail with rollback capability
- **WebhookConfig**: Centralized webhook management with authentication and retry logic

#### Plugin Architecture Support (1 entity)
- **ApiProcessMapping**: Maps API operations to process plugins for dynamic business logic routing

## Documentation

Comprehensive documentation is available in the [`docs/`](./docs) directory:

### Quick Reference
- **[System Verification](./docs/SYSTEM_VERIFICATION.md)** - Complete verification report with all components validated
- **[Complete System Audit](./docs/COMPLETE_SYSTEM_AUDIT.md)** - Full system audit with validation matrices
- **[Architecture and Usage Guide](./docs/ARCHITECTURE_AND_USAGE.md)** - Detailed architecture, data model, and real-world examples

### Detailed Guides
- **[Getting Started](./docs/README.md)** - Introduction and key concepts
- **[Tenant Management](./docs/tenants.md)** - Managing tenants and their lifecycle
- **[Provider Management](./docs/providers.md)** - Configuring external providers
- **[Parameter Configuration](./docs/parameters.md)** - Dynamic parameter management

### Security & Integration
- **[Security Vault Integration](./docs/SECURITY_VAULT_INTEGRATION.md)** - **NEW!** Complete guide for secure credential management with `core-common-security-vault`
- **[Secure Configuration Examples](./docs/examples/SecureConfigurationExample.java)** - Code examples for consuming secret parameters

## Technology Stack

- **Java 25** - Modern Java with latest features
- **Spring Boot 3.5.10** - Application framework
- **Spring WebFlux** - Reactive web framework
- **R2DBC** - Reactive database connectivity
- **PostgreSQL** - Primary database
- **Flyway** - Database migrations
- **MapStruct** - Object mapping
- **Lombok** - Boilerplate reduction
- **OpenAPI/Swagger** - API documentation
- **Maven** - Build tool

## Database Schema

The service uses a PostgreSQL database with **17 tables** (plus flyway_schema_history):

### Tenant Management (4 tables)
- `tenants` - Tenant configurations (25 columns)
- `tenant_statuses` - Tenant lifecycle states (8 columns)
- `tenant_brandings` - Visual customization per tenant (19 columns)
- `tenant_settings` - Operational configuration (44 columns)

### Provider Management (6 tables)
- `providers` - External service providers (16 columns)
- `provider_types` - Categories of providers (8 columns)
- `provider_statuses` - Provider states (8 columns)
- `provider_parameters` - Dynamic configuration parameters using EAV pattern (20 columns)
- `provider_value_mappings` - Value translation mappings (14 columns)
- `provider_tenants` - Many-to-many relationship with priority (15 columns)

### Channel Management (2 tables)
- `channel_configs` - Banking channel configuration (13 columns)
- `channel_config_parameters` - Dynamic channel parameters using EAV pattern (15 columns)

### Feature & Configuration Management (4 tables)
- `feature_flags` - Feature toggles with rollout control (16 columns)
- `environment_configs` - Environment-specific configuration (14 columns)
- `configuration_audits` - Complete audit trail (16 columns)
- `webhook_configs` - Webhook configuration (24 columns)

### API Process Management (1 table)
- `api_process_mappings` - Mappings between API operations and business processes

**Total: 17 tables with indexes, foreign keys, and unique constraints**

Migrations are managed by Flyway and located in `core-common-config-mgmt-models/src/main/resources/db/migration/`.

## API Endpoints

The service exposes **16 REST controllers** with approximately **80 endpoints**. All endpoints support reactive programming with Mono/Flux return types.

### Tenant Management
- `/api/v1/tenants` - Tenant CRUD and filtering
- `/api/v1/tenant-statuses` - Tenant status management
- `/api/v1/tenant-brandings` - Branding configuration
- `/api/v1/tenant-settings` - Operational settings

### Provider Management
- `/api/v1/providers` - Provider CRUD and filtering
- `/api/v1/provider-types` - Provider type management
- `/api/v1/provider-statuses` - Provider status management
- `/api/v1/provider-parameters` - Dynamic parameter configuration
- `/api/v1/provider-value-mappings` - Value mapping management
- `/api/v1/provider-tenants` - Provider-tenant associations

### Channel Management
- `/api/v1/channel-configs` - Channel configuration
- `/api/v1/channel-config-parameters` - Dynamic channel parameters

### Feature & Configuration Management
- `/api/v1/feature-flags` - Feature toggle management
- `/api/v1/environment-configs` - Environment-specific configuration
- `/api/v1/configuration-audits` - Audit trail (read-only)
- `/api/v1/webhook-configs` - Webhook configuration

### Plugin Architecture
- `/api/v1/api-process-mappings` - API-to-Process mapping configuration
  - `GET /resolve?operationId=X&tenantId=Y` - Resolve process for an operation
  - `GET /vanilla` - List vanilla (default) mappings
  - `GET /tenants/{tenantId}/mappings` - Tenant-specific mappings
  - `GET /processes/{processId}` - Mappings by process
  - `POST /cache/invalidate` - Cache invalidation

**Each controller provides:**
- `GET /{id}` - Get by ID
- `POST /filter` - Filter with pagination and criteria
- `POST` - Create new entity
- `PUT /{id}` - Update existing entity
- `DELETE /{id}` - Delete entity

For complete API documentation with request/response schemas, visit the **Swagger UI** at `http://localhost:8080/swagger-ui.html` when the service is running.

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

- **Documentation**: [docs/](./docs)
- **Issues**: [GitHub Issues](https://github.com/firefly-oss/core-common-config-mgmt/issues)
- **Community**: [Firefly Community](https://community.firefly-banking.org)
- **Website**: [firefly-banking.org](https://firefly-banking.org)

## About Firefly

Firefly is an open-source core banking platform designed for modern financial institutions. It provides:

- Multi-tenant architecture
- Microservices-based design
- Extensive provider integrations
- Complete customization capabilities
- Enterprise-grade security
- Regulatory compliance support

Learn more at [firefly-banking.org](https://firefly-banking.org)

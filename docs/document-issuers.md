# Governed document issuers

The configuration service owns tenant-specific issuer definitions, their immutable publications, and current controls. A definition binds an issuer to an exact tenant and branding snapshot, source generation, effective window, and allowed document uses. The supported purpose is `DEPOSIT_ACCOUNT_ENROLLMENT`, with explicit document type and locale. Configuration facts do not establish regulated institution authority, template approval, document generation, fetched logo bytes, or a signature.

Template content and localized revisions belong to the reference master-data owner. Products and document generation must resolve their own exact references and consume a fresh issuer CURRENT observation before use. That downstream integration remains open.

## Lifecycle and API

All eight operations are JSON `POST` requests under `/api/v1/tenants/{tenantId}/document-issuers`. Exact fields and generated models are defined by the [original producer specification](../core-common-config-mgmt-sdk/src/main/resources/openapi.yml).

| Suffix | Operation ID | Result |
| --- | --- | --- |
| `/source-observations` | `observeDocumentIssuerSource` | Finite tenant/branding source observation |
| `/preparations` | `prepareDocumentIssuer` | Immutable preparation receipt |
| `/publications` | `publishDocumentIssuer` | Publication receipt after maker/checker checks |
| `/revocations` | `revokeDocumentIssuer` | Retained revocation receipt |
| `/current-observations` | `observeCurrentDocumentIssuer` | Finite observation of an exact published reference |
| `/preparation-recoveries` | `recoverDocumentIssuerPreparation` | Original preparation receipt |
| `/publication-recoveries` | `recoverDocumentIssuerPublication` | Original publication receipt |
| `/revocation-recoveries` | `recoverDocumentIssuerRevocation` | Original revocation receipt |

Observe the exact tenant/branding source, prepare a complete immutable definition, then publish that definition with a distinct maker/checker identity. Decisions bind the publication, revision, definition digest, expected generation, and original action. Revocation retains the publication and receipts while preventing new current use.

CURRENT accepts the tenant, publication ID, revision, definition digest, and document use. It resolves the issuer on the server and validates the stored definition, published decision, current control, active source, and effective window. It returns `observedAtEpochMillis`, `nativeUntilEpochMillis`, and `effectiveUntilEpochMillis`. Consumers must retain these bounds and refuse expired or mismatched observations. Reads cannot renew the original authority budget.

Recovery requires the original request and actor plus current recovery permission and policy. It can return a retained preparation, publication, or revocation outcome after the original business window expires. An unknown or mismatched action fails; recovery cannot create an outcome, write through its read-only permit, make a publication current, or extend its business window. Successful responses use `Cache-Control: no-store`; transparent idempotency caching is disabled for these routes.

## Security and activation

The API is disabled by default. Enabling `firefly.configuration.document-issuer.enabled=true` requires all of the following properties under `firefly.configuration.document-issuer`:

| Properties | Requirement |
| --- | --- |
| `command-username`, `command-password` | Dedicated restricted command login |
| `reader-username`, `reader-password` | Separate restricted reader login |
| `audience`, `client-ids` | Explicit protected audience and allowed client set |
| `tenant-claim`, `client-claim` | Distinct claim names for exact tenant and client binding |

Neither login may alias the legacy application login. Credentials come from deployment configuration. The existing resource server must have strict policy, issuer, JWKS URI, and a matching protected audience. Each operation checks the verified principal's issuer, subject, tenant, client, audience, token window, operation scope, and current PDP decision for the exact resource and intent. Policy grants with obligations are refused. Mutation commit and response checks remain within the original paired wall-clock and monotonic budget.

The opt-in configuration creates two private lazy pools and retains the legacy connection factory. Business services use interfaces with implementations in `impl`; issuer persistence uses mapped Spring Data operations on the owning connection and bounded transaction. Startup configuration does not bypass native role, source-generation, admission, or cleanup checks.

## Independent migration histories

The default application keeps the original `classpath:db/migration` V1–V5 history. Optional V6 is installed separately from `classpath:db/document-issuer-migration`, with its own `cfg_document_issuer_migrations.flyway_schema_history` and restricted installer. Runtime roles and the document guard cannot access that history schema. Do not add another automatic Main migrator, invent original-history entries, or supply installer credentials to API pools.

Follow the [deployment contract](../ops/document-issuer/DEPLOYMENT.md) for administrator preparation, exact history settings, role restrictions, and activation prerequisites. Validate both histories and their actual installers/checksums before enabling the API.

## Qualification boundary

The original source reactor passed 263 executed cases in 44 suites: 226 configuration-owner cases and 37 bounded-transaction framework cases, with zero failures, errors, or skips. This selected build excludes native integration tests and Javadoc; it does not qualify the full release workflow or adoption of corrected released dependencies.

The actual original `OpenApiGenApplication`, Springdoc capture, and Java Generator produced a 134-operation specification including these eight issuer operations. The complete parsed canonical SDK input matched the captured producer document; generated route, method, authentication, source, compiled-class, packaged-resource, and JAR bindings were checked. The SDK contained 138 generated Java files and 550 compiled classes. No SDK Java or replacement specification was handwritten.

Separate native PostgreSQL qualification exercised the original and optional histories, restricted roles, source/prepare/publish/current/revoke behavior, original receipt recovery after expiry, and the same physical database across restart. Business Java and migration bytes were unchanged during the producer qualification. Actual default-off/enabled production Main startup, signed issuer HTTP, product/template/layout/asset consumption, signing, deployment, and legal or financial admission remain open. No Maven installation, release, or same-version artifact replacement is established by these results.

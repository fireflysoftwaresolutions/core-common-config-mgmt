Document issuer migrations are an explicit deployment step. The default application still resolves only the original `classpath:db/migration` V1–V5. The feature is disabled unless `firefly.configuration.document-issuer.enabled=true` is explicitly configured.

Validate original V1–V5 and their checksums first. Then an authorized deployment administrator applies `bootstrap.sql` and `deployment-history.sql`, using the exact restricted installer selected by `firefly.cfg_doc.installer`. The history schema must be initially empty and owned only by that installer. Existing unexpected owners, grants, functions or relations are refused. Runtime roles and the protected document guard receive no history-schema privileges.

Run the document migration separately with these Flyway settings:

- `locations=classpath:db/document-issuer-migration`
- `defaultSchema=cfg_document_issuer_migrations`
- `schemas=cfg_document_issuer_migrations`
- `table=flyway_schema_history`
- `createSchemas=false`, `baselineOnMigrate=false`, `cleanDisabled=true`, `validateOnMigrate=true`

V6 keeps its explicit `public` and private object qualification. Its version and SQL bytes are preserved; it is the first migration in the independent document history, without invented V1–V5 entries. Validate both histories and attest actual V6 installer/checksum and runtime roles before enabling the API. Do not supply installer or administrator credentials to the API pools. Do not add another automatic Main Flyway migrator, repair history or disable the original migrations.

The opt-in API requires distinct command and reader credentials, an existing verified resource-server configuration, an explicit allowed client set and audience, and the existing current policy decision port. It creates private lazy pools; the legacy connection factory is not replaced. SOURCE returns configuration facts, not approval. CURRENT requires the exact immutable publication reference and supported document use. URI fields describe configuration metadata and do not prove fetched asset bytes or regulated institution authority.

Recovery uses the explicit current `configuration:document-issuers:recover` permission and current policy for the original action/intent. It returns only a retained outcome belonging to the same original actor. An absent or mismatched action cannot create a new decision, and a recovery permit cannot expose writes. Recovery does not make a historical publication current or renew its original business window.

Before activation, qualify dedicated-history migration/restart, default-off Main startup, original metadata/SDK generation, authenticated HTTP and deployment roles. Native schema qualification alone does not establish application or regulated acceptance.

V7 is a forward extension for `INSURANCE_TERM_PC_QUOTE` uses. Apply it through the same dedicated document history and restricted installer before using that purpose. V6 bytes and existing deposit canonical framing remain unchanged. V7 replaces only the purpose validator and temporarily delegates the same guarded schema privilege as V6, then revokes it; runtime identities receive no new privileges. Both owner APIs and freshly generated SDKs must advertise the purpose. A quote reference confirms current issuer metadata for the quote; it does not authorize issuing a policy, signing a contract, rendering document bytes, or fetching branding assets.

Migrate policy context explicitly when enabling V7. CURRENT and SOURCE supply the exact requested `purpose`, `documentType`, and `locale`. Generic PREPARE, PUBLISH, REVOKE, and recovery retain their action, resource, scopes, operation, and exact intent digest but now identify their context as `purpose=DOCUMENT_ISSUER_ADMINISTRATION`; they previously mislabeled generic administration as deposit enrollment. Policies must explicitly allow this administration context. There is no fallback to a deposit or quote permission. Positive CURRENT still requires the selected use to belong to the immutable maker/checker publication and all current source generations to match.

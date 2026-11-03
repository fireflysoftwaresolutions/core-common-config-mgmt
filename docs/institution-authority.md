# Institution authority and tenant eligibility

The configuration owner stores native institution/issuer identities, tenant/status eligibility generations, governed proposals, and immutable grant facts with current controls. Native identity allocation works for non-bank institutions. A Bank row, customer LegalEntity, or display name does not confer issuer authority or a regulatory license.

Use the [generated API specification](../core-common-config-mgmt-sdk/src/main/resources/openapi.yml) for exact routes, operation IDs, closed request fields, and receipt envelopes. The SDK is generated from the actual producer; do not replace its specification or client with handwritten equivalents. The packaged `api-spec/openapi.yml` resource is derived from canonical `openapi.yml` during `process-resources`; it is not maintained as a second source specification.

## Admission and control

Prepare a complete grant intent, submit it, obtain approval from a distinct verified staff identity, then execute the exact approved target. Native financial execution accepts the V2 grant/eligibility-epoch wrapper. It has no V1 fallback. Preparation does not itself grant authority.

`GrantIssueDraft.existingInstitutionId` and `existingIssuerAuthorityId` are required nullable fields. Supply explicit `null` when requesting native allocation. There is no `allocationId` request field.

Every native action requires current authorization for the verified principal and exact tenant/resource/intent. Automated execution retains its actual MACHINE/client identity. Original receipts remain immutable, and current permission is checked before replay. Current observations are finite evidence; historical facts and receipts do not authorize new use.

Tenant/status suspension disables current admission. Governed recovery advances eligibility generations. Previous grants and pre-disable approvals cannot silently revive; re-admission needs a fresh complete approved grant version at the current epoch. An independently approved same-generation resume is distinct from re-admission after recovery. Revocation retains history.

Action absence is UNKNOWN. Only the native reconciliation operation may establish a durable no-effect result. Grant-issue and grant-control reconciliation are implemented; tenant/status reconciliation and downstream organization calendar execution are separate work.

## Deployment prerequisites

Use separate migration and restricted application database credentials. Native PostgreSQL functions, restrictive runtime privileges, current principal verification, and current PDP decisions are part of the execution boundary. Configure real issuer/JWKS, audience, platform tenant, and policy-provider settings; the qualification fixtures are not deployment defaults. Ordinary metadata permissions remain separate from native authority permissions.

This source candidate was qualified with genuine framework source modules containing opt-in required JWT tenant projection and `UtcOffsetDateTimeProvider`. Coordinated released framework artifacts containing those APIs are a separate adoption prerequisite. Older cached binaries with the same version label are not a substitute. External identity/policy-provider and deployment acceptance remain separate from the local source/runtime qualification.

package firefly.configuration

default allow = false

qualified_subject {
    data.qualification.principal_tenants[input.subject.subject] == input.subject.tenantId
}

native_resource {
    parts := split(input.resource, "/")
    parts[1] == "api"
    parts[2] == "v1"
    parts[3] == "tenants"
    data.qualification.scopes[input.subject.subject][parts[4]] == true
    parts[5] == {"institution-authorities", "eligibility"}[_]
}

native_resource {
    parts := split(input.resource, "/")
    parts[1] == "api"
    parts[2] == "v1"
    parts[3] == "platform"
    parts[4] == "tenants"
    data.qualification.scopes[input.subject.subject][parts[5]] == true
    parts[6] == {"institution-authorities", "eligibility"}[_]
}

allow {
    input.action == {"GET", "POST"}[_]
    input.subject.authorities[_] == "AUTHORITY_OPERATOR"
    data.qualification.enabled == true
    qualified_subject
    native_resource
}

allow {
    startswith(input.action, "configuration.institution.authority.")
    input.subject.authorities[_] == "AUTHORITY_OPERATOR"
    data.qualification.enabled == true
    qualified_subject
    data.qualification.permissions[input.subject.subject][input.action] == true
    data.qualification.scopes[input.subject.subject][input.context.tenantId] == true
    input.context.actorSubject == input.subject.subject
    input.context.actorTenantId == input.subject.tenantId
    input.context.resourceId != ""
    input.context.intentDigest == crypto.sha256(input.context.canonicalIntent)
    input.resource == sprintf("tenant/%s/%s/%s", [input.context.tenantId, input.context.resourceType, input.context.resourceId])
}

metadata_read_role {
    input.subject.authorities[_] == {"METADATA_READER", "METADATA_ADMIN"}[_]
}

metadata_write_role {
    input.subject.authorities[_] == {"METADATA_WRITER", "METADATA_ADMIN"}[_]
}

metadata_tenant_resource {
    parts := split(input.resource, "/")
    count(parts) == 5
    parts[1] == "api"
    parts[2] == "v1"
    parts[3] == "tenants"
    data.qualification.scopes[input.subject.subject][parts[4]] == true
}

metadata_read_resource {
    metadata_tenant_resource
}

metadata_read_resource {
    parts := split(input.resource, "/")
    count(parts) == 5
    parts[1] == "api"
    parts[2] == "v1"
    parts[3] == "tenant-statuses"
    data.qualification.metadata_statuses[input.subject.subject][parts[4]] == true
}

metadata_read_resource {
    input.resource == "/api/v1/api-process-mappings/vanilla"
    data.qualification.metadata_admin[input.subject.subject] == true
}

allow {
    input.action == "GET"
    metadata_read_role
    qualified_subject
    data.qualification.enabled == true
    data.qualification.metadata_read[input.subject.subject] == true
    metadata_read_resource
}

metadata_write_resource {
    metadata_tenant_resource
    input.action == {"PUT", "PATCH"}[_]
}

metadata_write_resource {
    regex.match("^/api/v1/api-process-mappings(/[0-9a-f-]{36})?$", input.resource)
    data.qualification.metadata_admin[input.subject.subject] == true
}

allow {
    input.action == {"POST", "PUT", "PATCH", "DELETE"}[_]
    metadata_write_role
    qualified_subject
    data.qualification.enabled == true
    data.qualification.metadata_write[input.subject.subject] == true
    metadata_write_resource
}

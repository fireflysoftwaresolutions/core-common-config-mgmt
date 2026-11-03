-- Preserve raw json until closed-shape and lexical checks finish; jsonb would erase duplicate keys.
CREATE FUNCTION public.cfg_native_invalid() RETURNS VOID
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN RAISE EXCEPTION 'Invalid native institution authority intent' USING ERRCODE='22023'; END $$;

CREATE FUNCTION public.cfg_native_shape(p_value JSON,p_fields TEXT[]) RETURNS VOID
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE n BIGINT; unique_n BIGINT; actual TEXT[]; expected TEXT[];
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'object' THEN PERFORM public.cfg_native_invalid(); END IF;
    SELECT count(*),count(DISTINCT e.key COLLATE "C"),array_agg(e.key ORDER BY e.key COLLATE "C") INTO n,unique_n,actual FROM json_each(p_value) e;
    SELECT array_agg(f ORDER BY f COLLATE "C") INTO expected FROM unnest(p_fields) f;
    IF n<>cardinality(p_fields) OR unique_n<>n OR actual IS DISTINCT FROM expected THEN PERFORM public.cfg_native_invalid(); END IF;
END $$;

CREATE FUNCTION public.cfg_native_s(p_value TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE STRICT SET search_path=pg_catalog,public,pg_temp AS $$
SELECT 'S'||octet_length(convert_to(p_value,'UTF8'))::text||':'||p_value $$;

CREATE FUNCTION public.cfg_native_string(p_value JSON,p_max INTEGER) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE value TEXT; units BIGINT; code INTEGER; blank BOOLEAN:=true;
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'string' THEN PERFORM public.cfg_native_invalid(); END IF;
    value:=p_value#>>'{}';
    IF value IS NULL OR btrim(value)='' THEN PERFORM public.cfg_native_invalid(); END IF;
    units:=0;
    FOR i IN 1..char_length(value) LOOP
        code:=ascii(substr(value,i,1));
        IF code BETWEEN 0 AND 31 OR code BETWEEN 127 AND 159 OR code BETWEEN 55296 AND 57343 OR code=65533 THEN PERFORM public.cfg_native_invalid(); END IF;
        units:=units+CASE WHEN code>65535 THEN 2 ELSE 1 END;
        IF code NOT IN (32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288) THEN blank:=false; END IF;
    END LOOP;
    IF units>p_max OR blank THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN public.cfg_native_s(value);
END $$;

CREATE FUNCTION public.cfg_native_uuid(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE value TEXT;
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'string' THEN PERFORM public.cfg_native_invalid(); END IF;
    value:=p_value#>>'{}';
    IF value !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' OR value='00000000-0000-0000-0000-000000000000' THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN public.cfg_native_s(value);
END $$;

CREATE FUNCTION public.cfg_native_long(p_value JSON,p_min BIGINT,p_max BIGINT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE value BIGINT; raw TEXT;
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'number' THEN PERFORM public.cfg_native_invalid(); END IF;
    raw:=btrim(p_value::text);
    IF raw !~ '^(0|-?[1-9][0-9]*)$' THEN PERFORM public.cfg_native_invalid(); END IF;
    BEGIN value:=raw::bigint; EXCEPTION WHEN numeric_value_out_of_range OR invalid_text_representation THEN PERFORM public.cfg_native_invalid(); END;
    IF value<p_min OR value>p_max THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN 'I'||value::text||';';
END $$;

CREATE FUNCTION public.cfg_native_bool(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'boolean' THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN CASE WHEN p_value::text='true' THEN 'B1;' ELSE 'B0;' END;
END $$;

CREATE FUNCTION public.cfg_native_evidence(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE result TEXT; item JSON; seen TEXT[]:=ARRAY[]::text[]; value TEXT;
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'array' OR json_array_length(p_value)>32 THEN PERFORM public.cfg_native_invalid(); END IF;
    result:='A'||json_array_length(p_value)::text||':';
    FOR item IN SELECT x FROM json_array_elements(p_value) x LOOP
        result:=result||public.cfg_native_string(item,500);value:=item#>>'{}';
        IF value=ANY(seen) THEN PERFORM public.cfg_native_invalid(); END IF;
        seen:=array_append(seen,value);
    END LOOP;
    RETURN result;
END $$;

CREATE FUNCTION public.cfg_native_uses(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE result TEXT; item JSON; previous TEXT; value TEXT;
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'array' OR json_array_length(p_value) NOT BETWEEN 1 AND 3 THEN PERFORM public.cfg_native_invalid(); END IF;
    result:='A'||json_array_length(p_value)::text||':';
    FOR item IN SELECT x FROM json_array_elements(p_value) x LOOP
        IF json_typeof(item) IS DISTINCT FROM 'string' THEN PERFORM public.cfg_native_invalid(); END IF;
        value:=item#>>'{}';
        IF value NOT IN ('ACCOUNTING_BOOK','CASH_OPERATIONS','PRODUCT_ISSUANCE') OR previous IS NOT NULL AND previous COLLATE "C">=value COLLATE "C" THEN PERFORM public.cfg_native_invalid(); END IF;
        result:=result||public.cfg_native_s(value);previous:=value;
    END LOOP;
    RETURN result;
END $$;

CREATE FUNCTION public.cfg_native_epoch_intent(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['tenantGeneration','statusId','statusGeneration']);
    RETURN 'R3:'||public.cfg_native_long(p_value->'tenantGeneration',0,9223372036854775806)||public.cfg_native_uuid(p_value->'statusId')||public.cfg_native_long(p_value->'statusGeneration',0,9223372036854775806);
END $$;

CREATE FUNCTION public.cfg_native_grant_v2_intent(p_tenant UUID,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE grant_value JSON; grant_token TEXT; epoch_token TEXT; expected BIGINT; version BIGINT;
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_tenant::text));
    PERFORM public.cfg_native_shape(p_value,ARRAY['grant','eligibilityEpoch']);grant_value:=p_value->'grant';
    PERFORM public.cfg_native_shape(grant_value,ARRAY['actionId','proposalId','proposalRevision','grantId','institutionId','issuerAuthorityId','version','expectedGrantHeadVersion','uses','effectiveFromEpochMillis','effectiveToEpochMillis','reason','evidenceReferences']);
    grant_token:='R13:'||public.cfg_native_uuid(grant_value->'actionId')||public.cfg_native_uuid(grant_value->'proposalId')||public.cfg_native_long(grant_value->'proposalRevision',1,9223372036854775807)||public.cfg_native_uuid(grant_value->'grantId')||public.cfg_native_uuid(grant_value->'institutionId')||public.cfg_native_uuid(grant_value->'issuerAuthorityId')||public.cfg_native_long(grant_value->'version',1,9223372036854775807)||public.cfg_native_long(grant_value->'expectedGrantHeadVersion',0,9223372036854775806)||public.cfg_native_uses(grant_value->'uses')||public.cfg_native_long(grant_value->'effectiveFromEpochMillis',-2208988800000,253402300799999)||public.cfg_native_long(grant_value->'effectiveToEpochMillis',-2208988800000,253402300799999)||public.cfg_native_string(grant_value->'reason',500)||public.cfg_native_evidence(grant_value->'evidenceReferences');
    expected:=(grant_value->>'expectedGrantHeadVersion')::bigint;version:=(grant_value->>'version')::bigint;
    IF version<>expected+1 OR (grant_value->>'effectiveFromEpochMillis')::bigint>=(grant_value->>'effectiveToEpochMillis')::bigint THEN PERFORM public.cfg_native_invalid(); END IF;
    epoch_token:=public.cfg_native_epoch_intent(p_value->'eligibilityEpoch');
    RETURN public.cfg_native_s('FF_TENANT_INSTITUTION_GRANT_V2')||'R3:'||public.cfg_native_s(p_tenant::text)||public.cfg_native_s('GRANT_ISSUE')||'R2:'||grant_token||epoch_token;
END $$;

CREATE FUNCTION public.cfg_native_suspend_intent(p_resource UUID,p_value JSON,p_status BOOLEAN) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_resource::text));
    IF p_status IS NULL THEN PERFORM public.cfg_native_invalid(); END IF;
    PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','notAfterEpochMillis','reason','evidenceReferences']);
    RETURN public.cfg_native_s(CASE WHEN p_status THEN 'FF_STATUS_ELIGIBILITY_SUSPEND_V1' ELSE 'FF_TENANT_ELIGIBILITY_SUSPEND_V1' END)||'R2:'||public.cfg_native_s(p_resource::text)||'R4:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_long(p_value->'notAfterEpochMillis',-2208988800000,253402300799999)||public.cfg_native_string(p_value->'reason',500)||public.cfg_native_evidence(p_value->'evidenceReferences');
END $$;

CREATE FUNCTION public.cfg_native_tenant_transition_intent(p_tenant UUID,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE token TEXT;
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_tenant::text));
    PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','proposalId','proposalRevision','expectedEpoch','targetStatusId','expectedTargetStatusGeneration','targetActive','reason','evidenceReferences','notAfterEpochMillis']);
    token:='R10:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'proposalId')||public.cfg_native_long(p_value->'proposalRevision',1,9223372036854775807)||public.cfg_native_epoch_intent(p_value->'expectedEpoch')||public.cfg_native_uuid(p_value->'targetStatusId')||public.cfg_native_long(p_value->'expectedTargetStatusGeneration',0,9223372036854775806)||public.cfg_native_bool(p_value->'targetActive')||public.cfg_native_string(p_value->'reason',500)||public.cfg_native_evidence(p_value->'evidenceReferences')||public.cfg_native_long(p_value->'notAfterEpochMillis',-2208988800000,253402300799999);
    IF (p_value->>'targetStatusId')=(p_value->'expectedEpoch'->>'statusId') AND (p_value->>'expectedTargetStatusGeneration')<>(p_value->'expectedEpoch'->>'statusGeneration') THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN public.cfg_native_s('FF_TENANT_ELIGIBILITY_TRANSITION_V1')||'R2:'||public.cfg_native_s(p_tenant::text)||token;
END $$;

CREATE FUNCTION public.cfg_native_status_transition_intent(p_status UUID,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_status::text));
    PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','proposalId','proposalRevision','expectedStatusGeneration','targetActive','reason','evidenceReferences','notAfterEpochMillis']);
    RETURN public.cfg_native_s('FF_STATUS_ELIGIBILITY_TRANSITION_V1')||'R2:'||public.cfg_native_s(p_status::text)||'R8:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'proposalId')||public.cfg_native_long(p_value->'proposalRevision',1,9223372036854775807)||public.cfg_native_long(p_value->'expectedStatusGeneration',0,9223372036854775806)||public.cfg_native_bool(p_value->'targetActive')||public.cfg_native_string(p_value->'reason',500)||public.cfg_native_evidence(p_value->'evidenceReferences')||public.cfg_native_long(p_value->'notAfterEpochMillis',-2208988800000,253402300799999);
END $$;

CREATE TABLE public.cfg_native_status_eligibility (
    status_id UUID PRIMARY KEY REFERENCES public.tenant_statuses(id) ON DELETE RESTRICT,
    generation BIGINT NOT NULL CHECK(generation>=0),
    enabled BOOLEAN NOT NULL,
    registered_tenant_id UUID NOT NULL,
    registered_action_id UUID NOT NULL,
    last_action_tenant_id UUID,
    last_action_id UUID,
    CHECK((last_action_id IS NULL)=(last_action_tenant_id IS NULL)),
    CHECK(status_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE public.cfg_native_tenant_eligibility (
    tenant_id UUID PRIMARY KEY REFERENCES public.tenants(id) ON DELETE RESTRICT,
    generation BIGINT NOT NULL CHECK(generation>=0),
    current_status_id UUID NOT NULL REFERENCES public.tenant_statuses(id) ON DELETE RESTRICT,
    enabled BOOLEAN NOT NULL,
    registered_action_id UUID NOT NULL,
    last_action_id UUID,
    CHECK(tenant_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE public.cfg_native_institution (
    institution_id UUID PRIMARY KEY,
    allocation_tenant UUID NOT NULL REFERENCES public.cfg_native_tenant_eligibility(tenant_id) ON DELETE RESTRICT,
    allocation_action_id UUID NOT NULL,
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK(institution_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE public.cfg_native_issuer (
    issuer_authority_id UUID PRIMARY KEY,
    institution_id UUID NOT NULL REFERENCES public.cfg_native_institution(institution_id) ON DELETE RESTRICT,
    allocation_tenant UUID NOT NULL,
    allocation_action_id UUID NOT NULL,
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(institution_id,issuer_authority_id),
    CHECK(issuer_authority_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE public.cfg_native_proposal (
    tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE RESTRICT,
    proposal_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    institution_id UUID REFERENCES public.cfg_native_institution(institution_id) ON DELETE RESTRICT,
    issuer_authority_id UUID,
    target_operation TEXT NOT NULL CHECK(target_operation IN ('GRANT_ISSUE','GRANT_CONTROL','TENANT_TRANSITION','STATUS_TRANSITION')),
    target_action_id UUID NOT NULL,
    maker_issuer TEXT NOT NULL CHECK(length(maker_issuer) BETWEEN 1 AND 500),
    maker_subject TEXT NOT NULL CHECK(length(maker_subject) BETWEEN 1 AND 255),
    maker_employee_id UUID NOT NULL CHECK(maker_employee_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,proposal_id),
    UNIQUE(tenant_id,target_action_id),
    FOREIGN KEY(institution_id,issuer_authority_id) REFERENCES public.cfg_native_issuer(institution_id,issuer_authority_id) ON DELETE RESTRICT,
    CHECK(proposal_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE public.cfg_native_proposal_revision (
    tenant_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK(revision=1),
    target_encoding TEXT NOT NULL CHECK(target_encoding IN ('FF_TENANT_INSTITUTION_GRANT_V2','FF_TENANT_ELIGIBILITY_TRANSITION_V1','FF_STATUS_ELIGIBILITY_TRANSITION_V1','FF_INSTITUTION_GRANT_CONTROL_V2')),
    original_prepare BYTEA NOT NULL CHECK(octet_length(original_prepare) BETWEEN 1 AND 65536),
    original_target BYTEA NOT NULL CHECK(octet_length(original_target) BETWEEN 1 AND 65536),
    canonical_target TEXT NOT NULL CHECK(octet_length(canonical_target) BETWEEN 1 AND 131072),
    target_digest TEXT NOT NULL CHECK(target_digest ~ '^[0-9a-f]{64}$'),
    not_after_epoch_millis BIGINT NOT NULL CHECK(not_after_epoch_millis BETWEEN -2208988800000 AND 253402300799999),
    tenant_generation BIGINT CHECK(tenant_generation>=0),
    status_id UUID REFERENCES public.tenant_statuses(id) ON DELETE RESTRICT,
    status_generation BIGINT CHECK(status_generation>=0),
    target_status_id UUID REFERENCES public.tenant_statuses(id) ON DELETE RESTRICT,
    target_status_generation BIGINT CHECK(target_status_generation>=0),
    PRIMARY KEY(tenant_id,proposal_id,revision),
    FOREIGN KEY(tenant_id,proposal_id) REFERENCES public.cfg_native_proposal(tenant_id,proposal_id) ON DELETE RESTRICT,
    CHECK(target_digest=encode(sha256(convert_to(canonical_target,'UTF8')),'hex'))
);
CREATE TABLE public.cfg_native_proposal_control (
    tenant_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','WITHDRAWN','EXECUTION_ADMITTED','NO_EFFECT_FINAL')),
    control_version BIGINT NOT NULL CHECK(control_version>=0),
    decision_id UUID,
    execution_action_id UUID,
    PRIMARY KEY(tenant_id,proposal_id),
    FOREIGN KEY(tenant_id,proposal_id) REFERENCES public.cfg_native_proposal(tenant_id,proposal_id) ON DELETE RESTRICT
);
CREATE TABLE public.cfg_native_decision (
    tenant_id UUID NOT NULL,
    decision_id UUID NOT NULL,
    action_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    operation TEXT NOT NULL CHECK(operation IN ('submit','approve','reject','withdraw')),
    target_digest TEXT NOT NULL CHECK(target_digest ~ '^[0-9a-f]{64}$'),
    actor_issuer TEXT NOT NULL,
    actor_subject TEXT NOT NULL,
    employee_id UUID NOT NULL CHECK(employee_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    original_decision BYTEA NOT NULL CHECK(octet_length(original_decision) BETWEEN 1 AND 65536),
    canonical_decision TEXT NOT NULL,
    decision_digest TEXT NOT NULL CHECK(decision_digest=encode(sha256(convert_to(canonical_decision,'UTF8')),'hex')),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,decision_id),
    UNIQUE(tenant_id,action_id),
    FOREIGN KEY(tenant_id,proposal_id,revision) REFERENCES public.cfg_native_proposal_revision(tenant_id,proposal_id,revision) ON DELETE RESTRICT
);
CREATE TABLE public.cfg_native_grant_version (
    tenant_id UUID NOT NULL,
    grant_id UUID NOT NULL CHECK(grant_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    version BIGINT NOT NULL CHECK(version>0),
    institution_id UUID NOT NULL,
    issuer_authority_id UUID NOT NULL,
    uses TEXT[] NOT NULL CHECK(cardinality(uses) BETWEEN 1 AND 3),
    effective_from_epoch_millis BIGINT NOT NULL CHECK(effective_from_epoch_millis>=-2208988800000),
    effective_to_epoch_millis BIGINT NOT NULL CHECK(effective_to_epoch_millis<=253402300799999),
    tenant_generation BIGINT NOT NULL CHECK(tenant_generation>=0),
    status_id UUID NOT NULL REFERENCES public.tenant_statuses(id) ON DELETE RESTRICT,
    status_generation BIGINT NOT NULL CHECK(status_generation>=0),
    proposal_id UUID NOT NULL,
    proposal_revision BIGINT NOT NULL,
    issue_action_id UUID NOT NULL,
    definition_encoding TEXT NOT NULL CHECK(definition_encoding='FF_TENANT_INSTITUTION_GRANT_DEFINITION_V2'),
    canonical_definition TEXT NOT NULL CHECK(octet_length(canonical_definition) BETWEEN 1 AND 131072),
    definition_digest TEXT NOT NULL CHECK(definition_digest=encode(sha256(convert_to(canonical_definition,'UTF8')),'hex')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,grant_id,version),
    UNIQUE(tenant_id,institution_id,issuer_authority_id,version),
    UNIQUE(tenant_id,issue_action_id),
    FOREIGN KEY(tenant_id) REFERENCES public.cfg_native_tenant_eligibility(tenant_id) ON DELETE RESTRICT,
    FOREIGN KEY(institution_id,issuer_authority_id) REFERENCES public.cfg_native_issuer(institution_id,issuer_authority_id) ON DELETE RESTRICT,
    FOREIGN KEY(tenant_id,proposal_id,proposal_revision) REFERENCES public.cfg_native_proposal_revision(tenant_id,proposal_id,revision) ON DELETE RESTRICT,
    CHECK(effective_from_epoch_millis<effective_to_epoch_millis)
);
CREATE TABLE public.cfg_native_grant_head (
    tenant_id UUID NOT NULL,
    institution_id UUID NOT NULL,
    issuer_authority_id UUID NOT NULL,
    grant_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK(version>0),
    head_version BIGINT NOT NULL CHECK(head_version=version),
    PRIMARY KEY(tenant_id,institution_id,issuer_authority_id),
    FOREIGN KEY(tenant_id,grant_id,version) REFERENCES public.cfg_native_grant_version(tenant_id,grant_id,version) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(institution_id,issuer_authority_id) REFERENCES public.cfg_native_issuer(institution_id,issuer_authority_id) ON DELETE RESTRICT
);
CREATE TABLE public.cfg_native_grant_control (
    tenant_id UUID NOT NULL,
    grant_id UUID NOT NULL,
    version BIGINT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('ACTIVE','SUSPENDED','REVOKED')),
    control_version BIGINT NOT NULL CHECK(control_version>=0),
    last_action_id UUID NOT NULL,
    PRIMARY KEY(tenant_id,grant_id,version),
    FOREIGN KEY(tenant_id,grant_id,version) REFERENCES public.cfg_native_grant_version(tenant_id,grant_id,version) ON DELETE RESTRICT
);
CREATE TABLE public.cfg_native_action (
    tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE RESTRICT,
    action_id UUID NOT NULL CHECK(action_id<>'00000000-0000-0000-0000-000000000000'::uuid),
    operation TEXT NOT NULL,
    resource_id UUID NOT NULL,
    proposal_id UUID,
    proposal_revision BIGINT,
    original_request BYTEA NOT NULL CHECK(octet_length(original_request) BETWEEN 1 AND 65536),
    canonical_intent TEXT NOT NULL CHECK(octet_length(canonical_intent) BETWEEN 1 AND 262144),
    intent_digest TEXT NOT NULL CHECK(intent_digest=encode(sha256(convert_to(canonical_intent,'UTF8')),'hex')),
    deadline_epoch_millis BIGINT NOT NULL,
    actor_kind TEXT NOT NULL CHECK(actor_kind IN ('STAFF','MACHINE')),
    actor_issuer TEXT NOT NULL,
    actor_subject TEXT NOT NULL,
    actor_employee_id UUID,
    actor_client_id TEXT,
    result_kind TEXT NOT NULL CHECK(result_kind IN ('PREPARED','DECIDED','APPLIED','NO_EFFECT_FINAL')),
    result JSONB NOT NULL CHECK(jsonb_typeof(result)='object'),
    receipt_id UUID NOT NULL,
    audit_id UUID NOT NULL,
    event_id UUID NOT NULL,
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,action_id),
    UNIQUE(tenant_id,receipt_id),UNIQUE(tenant_id,audit_id),UNIQUE(tenant_id,event_id),
    FOREIGN KEY(tenant_id,proposal_id,proposal_revision) REFERENCES public.cfg_native_proposal_revision(tenant_id,proposal_id,revision) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CHECK((actor_kind='STAFF' AND actor_employee_id IS NOT NULL) OR (actor_kind='MACHINE' AND actor_employee_id IS NULL AND actor_client_id IS NOT NULL))
);
CREATE TABLE public.cfg_native_receipt (
    tenant_id UUID NOT NULL,
    action_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
    canonical_payload TEXT NOT NULL,
    payload_digest TEXT NOT NULL CHECK(payload_digest=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),
    PRIMARY KEY(tenant_id,action_id),UNIQUE(tenant_id,receipt_id),
    FOREIGN KEY(tenant_id,action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE public.cfg_native_audit (
    tenant_id UUID NOT NULL,action_id UUID NOT NULL,audit_id UUID NOT NULL,
    payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
    canonical_payload TEXT NOT NULL,
    payload_digest TEXT NOT NULL CHECK(payload_digest=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),
    PRIMARY KEY(tenant_id,audit_id),UNIQUE(tenant_id,action_id),
    FOREIGN KEY(tenant_id,action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE public.cfg_native_outbox (
    tenant_id UUID NOT NULL,action_id UUID NOT NULL,event_id UUID NOT NULL,
    event_kind TEXT NOT NULL,
    payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
    canonical_payload TEXT NOT NULL,
    payload_digest TEXT NOT NULL CHECK(payload_digest=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),
    PRIMARY KEY(tenant_id,event_id),UNIQUE(tenant_id,action_id),
    FOREIGN KEY(tenant_id,action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE public.cfg_native_outbox_delivery (
    tenant_id UUID NOT NULL,event_id UUID NOT NULL,attempt BIGINT NOT NULL DEFAULT 0 CHECK(attempt>=0),
    lease_owner TEXT,lease_until TIMESTAMPTZ,delivered_at TIMESTAMPTZ,
    PRIMARY KEY(tenant_id,event_id),
    FOREIGN KEY(tenant_id,event_id) REFERENCES public.cfg_native_outbox(tenant_id,event_id) ON DELETE RESTRICT
);
CREATE TABLE public.cfg_native_eligibility_event (
    tenant_id UUID NOT NULL,action_id UUID NOT NULL,resource_kind TEXT NOT NULL CHECK(resource_kind IN ('TENANT','STATUS')),
    resource_id UUID NOT NULL,old_generation BIGINT NOT NULL CHECK(old_generation>=0),new_generation BIGINT NOT NULL CHECK(new_generation=old_generation+1),
    before_state JSONB NOT NULL CHECK(jsonb_typeof(before_state)='object'),
    after_state JSONB NOT NULL CHECK(jsonb_typeof(after_state)='object'),
    PRIMARY KEY(tenant_id,action_id),
    FOREIGN KEY(tenant_id,action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE public.cfg_native_grant_control_event (
    tenant_id UUID NOT NULL,action_id UUID NOT NULL,grant_id UUID NOT NULL,version BIGINT NOT NULL,
    old_control_version BIGINT NOT NULL CHECK(old_control_version>=0),new_control_version BIGINT NOT NULL CHECK(new_control_version=old_control_version+1),
    old_state TEXT NOT NULL CHECK(old_state IN ('ACTIVE','SUSPENDED')),
    new_state TEXT NOT NULL CHECK(new_state IN ('ACTIVE','SUSPENDED','REVOKED') AND old_state<>new_state),
    PRIMARY KEY(tenant_id,action_id),
    FOREIGN KEY(tenant_id,action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,grant_id,version) REFERENCES public.cfg_native_grant_version(tenant_id,grant_id,version) ON DELETE RESTRICT
);
ALTER TABLE public.cfg_native_institution ADD CONSTRAINT cfg_institution_allocation_action FOREIGN KEY(allocation_tenant,allocation_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.cfg_native_issuer ADD CONSTRAINT cfg_issuer_allocation_action FOREIGN KEY(allocation_tenant,allocation_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.cfg_native_grant_version ADD CONSTRAINT cfg_grant_issue_action FOREIGN KEY(tenant_id,issue_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.cfg_native_proposal_control ADD CONSTRAINT cfg_proposal_decision FOREIGN KEY(tenant_id,decision_id) REFERENCES public.cfg_native_decision(tenant_id,decision_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.cfg_native_tenant_eligibility ADD CONSTRAINT cfg_tenant_registration_action FOREIGN KEY(tenant_id,registered_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.cfg_native_tenant_eligibility ADD CONSTRAINT cfg_tenant_last_action FOREIGN KEY(tenant_id,last_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.cfg_native_status_eligibility ADD CONSTRAINT cfg_status_registration_action FOREIGN KEY(registered_tenant_id,registered_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.cfg_native_status_eligibility ADD CONSTRAINT cfg_status_last_action FOREIGN KEY(last_action_tenant_id,last_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

DO $$
DECLARE name TEXT; role_value RECORD;
BEGIN
    FOREACH name IN ARRAY ARRAY['cfg_native_owner','cfg_native_guard','cfg_native_runtime'] LOOP
        SELECT * INTO role_value FROM pg_catalog.pg_roles WHERE rolname=name;
        IF NOT FOUND THEN
            EXECUTE format('CREATE ROLE %I %s NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEDB NOCREATEROLE',name,CASE WHEN name='cfg_native_runtime' THEN 'LOGIN' ELSE 'NOLOGIN' END);
        ELSIF role_value.rolsuper OR role_value.rolbypassrls OR role_value.rolinherit OR role_value.rolcreatedb OR role_value.rolcreaterole OR role_value.rolcanlogin IS DISTINCT FROM (name='cfg_native_runtime') THEN
            RAISE EXCEPTION 'Restricted native role configuration required' USING ERRCODE='42501';
        END IF;
    END LOOP;
    EXECUTE format('GRANT cfg_native_owner,cfg_native_guard TO %I',current_user);
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE,CREATE ON SCHEMA public TO cfg_native_owner,cfg_native_guard;
GRANT USAGE ON SCHEMA public TO cfg_native_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.tenants,public.tenant_statuses TO cfg_native_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.tenant_brandings,public.provider_types,public.provider_statuses,
    public.providers,public.provider_parameters,public.provider_value_mappings,public.provider_tenants,
    public.feature_flags,public.tenant_settings,public.webhook_configs,public.environment_configs,
    public.channel_configs,public.channel_config_parameters,public.api_process_mappings TO cfg_native_runtime;
GRANT SELECT,INSERT ON public.configuration_audits TO cfg_native_runtime;
GRANT SELECT ON public.tenants,public.tenant_statuses TO cfg_native_guard;
GRANT UPDATE(active,tenant_status_id,version,updated_at) ON public.tenants TO cfg_native_guard;
GRANT UPDATE(active,version,updated_at) ON public.tenant_statuses TO cfg_native_guard;

CREATE FUNCTION public.cfg_native_context_current(p_tenant UUID DEFAULT NULL) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE actual_tenant UUID; until_millis BIGINT; command_millis BIGINT;
BEGIN
    IF current_setting('firefly.cfg.xid',true) IS DISTINCT FROM pg_current_xact_id()::text THEN RETURN false; END IF;
    actual_tenant:=nullif(current_setting('firefly.cfg.tenant',true),'')::uuid;
    until_millis:=nullif(current_setting('firefly.cfg.authority_until',true),'')::bigint;
    command_millis:=nullif(current_setting('firefly.cfg.command_until',true),'')::bigint;
    RETURN coalesce(actual_tenant IS NOT NULL AND (p_tenant IS NULL OR actual_tenant=p_tenant)
      AND until_millis>floor(extract(epoch FROM clock_timestamp())*1000)::bigint
      AND command_millis>floor(extract(epoch FROM clock_timestamp())*1000)::bigint,false);
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN false;
END $$;

CREATE FUNCTION public.cfg_native_require_context(p_tenant UUID,p_action UUID,p_operation TEXT,p_resource UUID,p_resource_type TEXT,p_intent TEXT,p_staff BOOLEAN) RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE kind TEXT; employee TEXT; client TEXT;
BEGIN
    IF current_user<>'cfg_native_guard' OR NOT public.cfg_native_context_current(p_tenant) OR p_staff IS NULL
      OR current_setting('firefly.cfg.action',true) IS DISTINCT FROM p_action::text
      OR current_setting('firefly.cfg.operation',true) IS DISTINCT FROM p_operation
      OR current_setting('firefly.cfg.resource',true) IS DISTINCT FROM p_resource::text
      OR current_setting('firefly.cfg.resource_type',true) IS DISTINCT FROM p_resource_type
      OR current_setting('firefly.cfg.intent',true) IS DISTINCT FROM encode(sha256(convert_to(p_intent,'UTF8')),'hex')
    THEN RAISE EXCEPTION 'Current exact native admission required' USING ERRCODE='42501'; END IF;
    PERFORM public.cfg_native_string(to_json(nullif(current_setting('firefly.cfg.actor_issuer',true),'')),500);
    PERFORM public.cfg_native_string(to_json(nullif(current_setting('firefly.cfg.actor_subject',true),'')),255);
    kind:=current_setting('firefly.cfg.actor_kind',true);employee:=nullif(current_setting('firefly.cfg.employee',true),'');client:=nullif(current_setting('firefly.cfg.client',true),'');
    IF kind IS NULL OR kind NOT IN ('STAFF','MACHINE') OR p_staff AND kind<>'STAFF'
      OR kind='STAFF' AND employee IS NULL OR kind='MACHINE' AND (employee IS NOT NULL OR client IS NULL)
    THEN RAISE EXCEPTION 'Actual staff or machine identity required' USING ERRCODE='42501'; END IF;
    IF kind='STAFF' THEN PERFORM public.cfg_native_uuid(to_json(employee));
    ELSE PERFORM public.cfg_native_string(to_json(client),255); END IF;
END $$;

-- Parent source rows exist before governance, avoiding hashed absent-row lock aliases.
CREATE FUNCTION public.cfg_native_lock_statuses(p_ids UUID[],p_scope_tenant UUID,p_action UUID,p_exclusive BOOLEAN DEFAULT false) RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE status_key UUID; source public.tenant_statuses%ROWTYPE; control public.cfg_native_status_eligibility%ROWTYPE;
BEGIN
    IF NOT public.cfg_native_context_current(p_scope_tenant) THEN RAISE EXCEPTION 'Current native scope required' USING ERRCODE='42501'; END IF;
    FOR status_key IN SELECT DISTINCT value FROM unnest(p_ids) value WHERE value IS NOT NULL ORDER BY value LOOP
        IF p_exclusive THEN SELECT * INTO source FROM public.tenant_statuses s WHERE s.id=status_key FOR UPDATE;
        ELSE SELECT * INTO source FROM public.tenant_statuses s WHERE s.id=status_key FOR SHARE; END IF;
        IF NOT FOUND THEN PERFORM public.cfg_native_invalid(); END IF;
        IF NOT public.cfg_native_context_current(p_scope_tenant) THEN RAISE EXCEPTION 'Native authority expired after parent wait' USING ERRCODE='42501'; END IF;
        INSERT INTO public.cfg_native_status_eligibility(status_id,generation,enabled,registered_tenant_id,registered_action_id)
          VALUES(status_key,0,source.active IS TRUE,p_scope_tenant,p_action) ON CONFLICT(status_id) DO NOTHING;
        IF p_exclusive THEN SELECT * INTO control FROM public.cfg_native_status_eligibility s WHERE s.status_id=status_key FOR UPDATE;
        ELSE SELECT * INTO control FROM public.cfg_native_status_eligibility s WHERE s.status_id=status_key FOR SHARE; END IF;
        IF control.enabled IS DISTINCT FROM (source.active IS TRUE) THEN RAISE EXCEPTION 'Status source/control mismatch' USING ERRCODE='23514'; END IF;
    END LOOP;
END $$;

CREATE FUNCTION public.cfg_native_lock_tenant(p_tenant UUID,p_action UUID,p_target_status UUID DEFAULT NULL) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE candidate UUID; source public.tenants%ROWTYPE; control public.cfg_native_tenant_eligibility%ROWTYPE; status public.cfg_native_status_eligibility%ROWTYPE; code TEXT;
BEGIN
    IF NOT public.cfg_native_context_current(p_tenant) THEN RAISE EXCEPTION 'Current native tenant scope required' USING ERRCODE='42501'; END IF;
    SELECT t.tenant_status_id INTO candidate FROM public.tenants t WHERE t.id=p_tenant;
    IF NOT FOUND THEN PERFORM public.cfg_native_invalid(); END IF;
    PERFORM public.cfg_native_lock_statuses(ARRAY[candidate,p_target_status],p_tenant,p_action,false);
    SELECT * INTO source FROM public.tenants t WHERE t.id=p_tenant FOR UPDATE;
    IF NOT FOUND THEN PERFORM public.cfg_native_invalid(); END IF;
    IF source.tenant_status_id IS DISTINCT FROM candidate THEN RAISE EXCEPTION 'Tenant parent changed; retry whole transaction' USING ERRCODE='40001'; END IF;
    IF NOT public.cfg_native_context_current(p_tenant) THEN RAISE EXCEPTION 'Native authority expired after tenant wait' USING ERRCODE='42501'; END IF;
    INSERT INTO public.cfg_native_tenant_eligibility(tenant_id,generation,current_status_id,enabled,registered_action_id)
      VALUES(p_tenant,0,candidate,source.active IS TRUE,p_action) ON CONFLICT(tenant_id) DO NOTHING;
    SELECT * INTO control FROM public.cfg_native_tenant_eligibility t WHERE t.tenant_id=p_tenant FOR UPDATE;
    IF control.current_status_id IS DISTINCT FROM candidate OR control.enabled IS DISTINCT FROM (source.active IS TRUE) THEN RAISE EXCEPTION 'Tenant source/control mismatch' USING ERRCODE='23514'; END IF;
    SELECT * INTO status FROM public.cfg_native_status_eligibility s WHERE s.status_id=candidate;
    SELECT s.code INTO code FROM public.tenant_statuses s WHERE s.id=candidate;
    RETURN jsonb_build_object('tenantId',p_tenant,'tenantGeneration',control.generation,'statusId',candidate,'statusGeneration',status.generation,'tenantActive',control.enabled,'statusEnabled',status.enabled,'statusCode',code,'eligibleNow',control.enabled AND status.enabled AND code='ACTIVE');
END $$;

CREATE FUNCTION public.cfg_native_existing_action(p_tenant UUID,p_action UUID,p_canonical TEXT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE action public.cfg_native_action%ROWTYPE; receipt JSONB;
BEGIN
    SELECT * INTO action FROM public.cfg_native_action a WHERE a.tenant_id=p_tenant AND a.action_id=p_action;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF action.canonical_intent IS DISTINCT FROM p_canonical THEN RAISE EXCEPTION 'Native action intent conflict' USING ERRCODE='55000'; END IF;
    SELECT r.payload INTO receipt FROM public.cfg_native_receipt r WHERE r.tenant_id=p_tenant AND r.action_id=p_action;
    IF receipt IS NULL THEN RAISE EXCEPTION 'Incomplete native action receipt' USING ERRCODE='23514'; END IF;
    RETURN jsonb_build_object('replay',true,'receipt',receipt);
END $$;

CREATE FUNCTION public.cfg_native_finish_action(p_tenant UUID,p_action UUID,p_operation TEXT,p_resource UUID,p_proposal UUID,p_revision BIGINT,p_original BYTEA,p_canonical TEXT,p_deadline BIGINT,p_result_kind TEXT,p_result JSONB) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE receipt_id UUID:=gen_random_uuid();audit_id UUID:=gen_random_uuid();event_id UUID:=gen_random_uuid();admitted TIMESTAMPTZ:=clock_timestamp();payload JSON;actor JSONB;digest TEXT;
BEGIN
    IF current_user<>'cfg_native_guard' OR NOT public.cfg_native_context_current(p_tenant) THEN RAISE EXCEPTION 'Current native result admission required' USING ERRCODE='42501'; END IF;
    IF p_result_kind<>'NO_EFFECT_FINAL' AND floor(extract(epoch FROM clock_timestamp())*1000)::bigint>=p_deadline THEN RAISE EXCEPTION 'Native command deadline elapsed' USING ERRCODE='42501'; END IF;
    digest:=encode(sha256(convert_to(p_canonical,'UTF8')),'hex');
    actor:=jsonb_build_object('kind',current_setting('firefly.cfg.actor_kind'),'issuer',current_setting('firefly.cfg.actor_issuer'),'subject',current_setting('firefly.cfg.actor_subject'),'tenantId',current_setting('firefly.cfg.actor_tenant'),'employeeId',nullif(current_setting('firefly.cfg.employee',true),''),'clientId',nullif(current_setting('firefly.cfg.client',true),''));
    payload:=json_build_object('encoding','FF_NATIVE_INSTITUTION_RECEIPT_JSON_V1','tenantId',p_tenant,'actionId',p_action,'operation',p_operation,'resourceId',p_resource,'proposalId',p_proposal,'proposalRevision',p_revision,'originalBase64',replace(encode(p_original,'base64'),E'\n',''),'canonicalIntent',p_canonical,'intentDigest',digest,'deadlineEpochMillis',p_deadline,'resultKind',p_result_kind,'result',p_result,'actor',actor,'acceptedAtEpochMillis',floor(extract(epoch FROM admitted)*1000)::bigint,'receiptId',receipt_id,'auditId',audit_id,'eventId',event_id);
    INSERT INTO public.cfg_native_action(tenant_id,action_id,operation,resource_id,proposal_id,proposal_revision,original_request,canonical_intent,intent_digest,deadline_epoch_millis,actor_kind,actor_issuer,actor_subject,actor_employee_id,actor_client_id,result_kind,result,receipt_id,audit_id,event_id,admitted_at)
      VALUES(p_tenant,p_action,p_operation,p_resource,p_proposal,p_revision,p_original,p_canonical,digest,p_deadline,current_setting('firefly.cfg.actor_kind'),current_setting('firefly.cfg.actor_issuer'),current_setting('firefly.cfg.actor_subject'),nullif(current_setting('firefly.cfg.employee',true),'')::uuid,nullif(current_setting('firefly.cfg.client',true),''),p_result_kind,p_result,receipt_id,audit_id,event_id,admitted);
    INSERT INTO public.cfg_native_receipt(tenant_id,action_id,receipt_id,payload,canonical_payload,payload_digest)
      VALUES(p_tenant,p_action,receipt_id,payload::jsonb,payload::text,encode(sha256(convert_to(payload::text,'UTF8')),'hex'));
    INSERT INTO public.cfg_native_audit(tenant_id,action_id,audit_id,payload,canonical_payload,payload_digest)
      VALUES(p_tenant,p_action,audit_id,payload::jsonb,payload::text,encode(sha256(convert_to(payload::text,'UTF8')),'hex'));
    INSERT INTO public.cfg_native_outbox(tenant_id,action_id,event_id,event_kind,payload,canonical_payload,payload_digest)
      VALUES(p_tenant,p_action,event_id,'configuration.institution.'||lower(p_operation),payload::jsonb,payload::text,encode(sha256(convert_to(payload::text,'UTF8')),'hex'));
    RETURN jsonb_build_object('replay',false,'receipt',payload::jsonb);
END $$;

CREATE FUNCTION public.cfg_native_tenant_view(p_tenant UUID) RETURNS JSONB
LANGUAGE sql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
SELECT jsonb_build_object('tenantId',t.id,'tenantGeneration',coalesce(e.generation,0),'statusId',s.id,'statusGeneration',coalesce(se.generation,0),'tenantActive',t.active IS TRUE,'statusEnabled',s.active IS TRUE,'statusCode',s.code,'eligibleNow',t.active IS TRUE AND s.active IS TRUE AND s.code='ACTIVE','lastActionId',e.last_action_id)
FROM public.tenants t JOIN public.tenant_statuses s ON s.id=t.tenant_status_id
LEFT JOIN public.cfg_native_tenant_eligibility e ON e.tenant_id=t.id
LEFT JOIN public.cfg_native_status_eligibility se ON se.status_id=s.id WHERE t.id=p_tenant $$;

CREATE FUNCTION public.cfg_native_suspend_tenant(p_tenant UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;canonical TEXT;action UUID;deadline BIGINT;existing JSONB;before_state JSONB;after_state JSONB;result JSONB;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_suspend_intent(p_tenant,body,false);action:=(body->>'actionId')::uuid;deadline:=(body->>'notAfterEpochMillis')::bigint;
    PERFORM public.cfg_native_require_context(p_tenant,action,'TENANT_SUSPEND',p_tenant,'TENANT_ELIGIBILITY',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing||jsonb_build_object('currentEligibility',public.cfg_native_tenant_view(p_tenant)); END IF;
    before_state:=public.cfg_native_lock_tenant(p_tenant,action);
    PERFORM public.cfg_native_require_context(p_tenant,action,'TENANT_SUSPEND',p_tenant,'TENANT_ELIGIBILITY',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing||jsonb_build_object('currentEligibility',public.cfg_native_tenant_view(p_tenant)); END IF;
    IF deadline<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Native suspend deadline elapsed' USING ERRCODE='42501'; END IF;
    PERFORM set_config('firefly.cfg.lifecycle_write','TENANT',true);
    UPDATE public.tenants SET active=false,version=coalesce(version,0)+1,updated_at=clock_timestamp() WHERE id=p_tenant;
    UPDATE public.cfg_native_tenant_eligibility SET enabled=false,generation=generation+1,last_action_id=action WHERE tenant_id=p_tenant;
    after_state:=public.cfg_native_tenant_view(p_tenant);
    INSERT INTO public.cfg_native_eligibility_event(tenant_id,action_id,resource_kind,resource_id,old_generation,new_generation,before_state,after_state)
      VALUES(p_tenant,action,'TENANT',p_tenant,(before_state->>'tenantGeneration')::bigint,(after_state->>'tenantGeneration')::bigint,before_state,after_state);
    result:=public.cfg_native_finish_action(p_tenant,action,'TENANT_SUSPEND',p_tenant,NULL,NULL,p_original,canonical,deadline,'APPLIED',after_state);
    RETURN result||jsonb_build_object('currentEligibility',after_state);
END $$;

CREATE FUNCTION public.cfg_native_is_governed(p_kind TEXT,p_id UUID) RETURNS BOOLEAN
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
SELECT CASE WHEN p_kind='TENANT' THEN EXISTS(SELECT 1 FROM public.cfg_native_tenant_eligibility WHERE tenant_id=p_id)
WHEN p_kind='STATUS' THEN EXISTS(SELECT 1 FROM public.cfg_native_status_eligibility WHERE status_id=p_id) ELSE true END $$;

-- INVOKER preserves the caller role: a definer trigger would mistake every legacy writer for its owner.
CREATE FUNCTION public.cfg_native_source_eligibility_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE governed BOOLEAN;operation TEXT;resource TEXT;
BEGIN
    governed:=public.cfg_native_is_governed(CASE WHEN TG_TABLE_NAME='tenants' THEN 'TENANT' ELSE 'STATUS' END,OLD.id);
    IF NOT governed THEN IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF;
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retained governed identity' USING ERRCODE='42501'; END IF;
    IF NEW.id IS DISTINCT FROM OLD.id THEN RAISE EXCEPTION 'Retained governed identity' USING ERRCODE='42501'; END IF;
    IF TG_TABLE_NAME='tenants' THEN
        IF NEW.active IS NOT DISTINCT FROM OLD.active AND NEW.tenant_status_id IS NOT DISTINCT FROM OLD.tenant_status_id THEN RETURN NEW; END IF;
        operation:=current_setting('firefly.cfg.operation',true);resource:=current_setting('firefly.cfg.resource',true);
        IF current_user<>'cfg_native_guard' OR current_setting('firefly.cfg.lifecycle_write',true) IS DISTINCT FROM 'TENANT'
          OR NOT public.cfg_native_context_current(OLD.id) OR operation NOT IN ('TENANT_SUSPEND','TENANT_TRANSITION')
          OR resource IS DISTINCT FROM OLD.id::text THEN RAISE EXCEPTION 'Use actual native tenant lifecycle' USING ERRCODE='42501'; END IF;
    ELSE
        IF NEW.code IS DISTINCT FROM OLD.code THEN RAISE EXCEPTION 'Retained governed status identity' USING ERRCODE='42501'; END IF;
        IF NEW.active IS NOT DISTINCT FROM OLD.active THEN RETURN NEW; END IF;
        operation:=current_setting('firefly.cfg.operation',true);resource:=current_setting('firefly.cfg.resource',true);
        IF current_user<>'cfg_native_guard' OR current_setting('firefly.cfg.lifecycle_write',true) IS DISTINCT FROM 'STATUS'
          OR NOT public.cfg_native_context_current() OR operation NOT IN ('STATUS_SUSPEND','STATUS_TRANSITION')
          OR resource IS DISTINCT FROM OLD.id::text OR current_setting('firefly.cfg.platform_recovery',true) IS DISTINCT FROM 'true'
        THEN RAISE EXCEPTION 'Use actual native status lifecycle' USING ERRCODE='42501'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cfg_native_tenant_source_guard BEFORE UPDATE OR DELETE ON public.tenants FOR EACH ROW EXECUTE FUNCTION public.cfg_native_source_eligibility_guard();
CREATE TRIGGER cfg_native_status_source_guard BEFORE UPDATE OR DELETE ON public.tenant_statuses FOR EACH ROW EXECUTE FUNCTION public.cfg_native_source_eligibility_guard();

CREATE FUNCTION public.cfg_native_immutable_guard() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN RAISE EXCEPTION 'Native institution evidence is immutable' USING ERRCODE='42501'; END $$;

CREATE FUNCTION public.cfg_native_mutable_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE value JSONB;scope_tenant UUID;action UUID;operation TEXT;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Native control deletion denied' USING ERRCODE='42501'; END IF;
    value:=to_jsonb(NEW);scope_tenant:=nullif(current_setting('firefly.cfg.tenant',true),'')::uuid;action:=nullif(current_setting('firefly.cfg.action',true),'')::uuid;operation:=current_setting('firefly.cfg.operation',true);
    IF current_user<>'cfg_native_guard' OR NOT public.cfg_native_context_current(scope_tenant) THEN RAISE EXCEPTION 'Native control admission required' USING ERRCODE='42501'; END IF;
    IF value ? 'tenant_id' AND (value->>'tenant_id')::uuid IS DISTINCT FROM scope_tenant THEN RAISE EXCEPTION 'Native control tenant mismatch' USING ERRCODE='42501'; END IF;
    IF TG_OP='INSERT' THEN
        IF TG_TABLE_NAME IN ('cfg_native_tenant_eligibility','cfg_native_status_eligibility') THEN
            IF (value->>'generation')::bigint<>0 OR (value->>'registered_action_id')::uuid IS DISTINCT FROM action OR value->>'last_action_id' IS NOT NULL THEN RAISE EXCEPTION 'Exact native registration required' USING ERRCODE='23514'; END IF;
        ELSIF TG_TABLE_NAME='cfg_native_proposal_control' THEN
            IF NEW.control_version<>0 OR NEW.state<>'DRAFT' OR operation<>'PREPARE' THEN RAISE EXCEPTION 'Exact native draft required' USING ERRCODE='23514'; END IF;
        ELSIF TG_TABLE_NAME='cfg_native_grant_head' THEN
            IF NEW.version<>1 OR operation<>'GRANT_ISSUE' THEN RAISE EXCEPTION 'Exact first grant head required' USING ERRCODE='23514'; END IF;
        ELSIF TG_TABLE_NAME='cfg_native_grant_control' THEN
            IF NEW.control_version<>0 OR NEW.state<>'ACTIVE' OR operation<>'GRANT_ISSUE' OR NEW.last_action_id IS DISTINCT FROM action THEN RAISE EXCEPTION 'Exact issued grant control required' USING ERRCODE='23514'; END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME='cfg_native_tenant_eligibility' THEN
        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.registered_action_id IS DISTINCT FROM OLD.registered_action_id OR NEW.generation<>OLD.generation+1 OR NEW.last_action_id IS DISTINCT FROM action OR operation NOT IN ('TENANT_SUSPEND','TENANT_TRANSITION') THEN RAISE EXCEPTION 'Exact tenant generation transition required' USING ERRCODE='23514'; END IF;
    ELSIF TG_TABLE_NAME='cfg_native_status_eligibility' THEN
        IF NEW.status_id IS DISTINCT FROM OLD.status_id OR NEW.registered_action_id IS DISTINCT FROM OLD.registered_action_id OR NEW.registered_tenant_id IS DISTINCT FROM OLD.registered_tenant_id OR NEW.generation<>OLD.generation+1 OR NEW.last_action_id IS DISTINCT FROM action OR NEW.last_action_tenant_id IS DISTINCT FROM scope_tenant OR operation NOT IN ('STATUS_SUSPEND','STATUS_TRANSITION') THEN RAISE EXCEPTION 'Exact status generation transition required' USING ERRCODE='23514'; END IF;
    ELSIF TG_TABLE_NAME='cfg_native_proposal_control' THEN
        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.proposal_id IS DISTINCT FROM OLD.proposal_id OR NEW.control_version<>OLD.control_version+1 OR NOT ((OLD.state='DRAFT' AND NEW.state IN ('SUBMITTED','WITHDRAWN','NO_EFFECT_FINAL')) OR (OLD.state='SUBMITTED' AND NEW.state IN ('APPROVED','REJECTED','WITHDRAWN','NO_EFFECT_FINAL')) OR (OLD.state='APPROVED' AND NEW.state IN ('WITHDRAWN','EXECUTION_ADMITTED','NO_EFFECT_FINAL'))) THEN RAISE EXCEPTION 'Exact proposal transition required' USING ERRCODE='23514'; END IF;
    ELSIF TG_TABLE_NAME='cfg_native_grant_head' THEN
        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.institution_id IS DISTINCT FROM OLD.institution_id OR NEW.issuer_authority_id IS DISTINCT FROM OLD.issuer_authority_id OR NEW.grant_id IS DISTINCT FROM OLD.grant_id OR NEW.version<>OLD.version+1 OR operation<>'GRANT_ISSUE' THEN RAISE EXCEPTION 'Exact next grant head required' USING ERRCODE='23514'; END IF;
    ELSIF TG_TABLE_NAME='cfg_native_grant_control' THEN
        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.grant_id IS DISTINCT FROM OLD.grant_id OR NEW.version IS DISTINCT FROM OLD.version OR NEW.control_version<>OLD.control_version+1 OR NEW.last_action_id IS DISTINCT FROM action OR OLD.state='REVOKED' OR NEW.state=OLD.state OR operation<>'GRANT_CONTROL' THEN RAISE EXCEPTION 'Exact grant control transition required' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION public.cfg_native_complete_action() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE value JSONB:=to_jsonb(NEW);tenant UUID;target_action UUID;action public.cfg_native_action%ROWTYPE;receipt public.cfg_native_receipt%ROWTYPE;audit public.cfg_native_audit%ROWTYPE;event public.cfg_native_outbox%ROWTYPE;effect public.cfg_native_eligibility_event%ROWTYPE;source_active BOOLEAN;generation BIGINT;grant_effect public.cfg_native_grant_version%ROWTYPE;grant_head public.cfg_native_grant_head%ROWTYPE;grant_control public.cfg_native_grant_control%ROWTYPE;grant_proposal public.cfg_native_proposal%ROWTYPE;grant_proposal_control public.cfg_native_proposal_control%ROWTYPE;grant_decision public.cfg_native_decision%ROWTYPE;grant_body JSON;target JSON;binding JSON;control_effect public.cfg_native_grant_control_event%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME IN ('cfg_native_institution','cfg_native_issuer') THEN tenant:=(value->>'allocation_tenant')::uuid;target_action:=(value->>'allocation_action_id')::uuid;
    ELSIF TG_TABLE_NAME='cfg_native_tenant_eligibility' THEN tenant:=(value->>'tenant_id')::uuid;target_action:=CASE WHEN TG_OP='INSERT' THEN (value->>'registered_action_id')::uuid ELSE (value->>'last_action_id')::uuid END;
    ELSIF TG_TABLE_NAME='cfg_native_status_eligibility' THEN tenant:=CASE WHEN TG_OP='INSERT' THEN (value->>'registered_tenant_id')::uuid ELSE (value->>'last_action_tenant_id')::uuid END;target_action:=CASE WHEN TG_OP='INSERT' THEN (value->>'registered_action_id')::uuid ELSE (value->>'last_action_id')::uuid END;
    ELSE tenant:=(value->>'tenant_id')::uuid;target_action:=(value->>'action_id')::uuid;END IF;
    SELECT * INTO action FROM public.cfg_native_action a WHERE a.tenant_id=tenant AND a.action_id=target_action;
    IF NOT FOUND OR NOT public.cfg_native_context_current(tenant) THEN RAISE EXCEPTION 'Complete current native action required' USING ERRCODE='23514'; END IF;
    IF action.result_kind<>'NO_EFFECT_FINAL' AND floor(extract(epoch FROM clock_timestamp())*1000)::bigint>=action.deadline_epoch_millis THEN RAISE EXCEPTION 'Native final-row deadline elapsed' USING ERRCODE='42501'; END IF;
    SELECT * INTO receipt FROM public.cfg_native_receipt r WHERE r.tenant_id=tenant AND r.action_id=target_action;
    SELECT * INTO audit FROM public.cfg_native_audit a WHERE a.tenant_id=tenant AND a.action_id=target_action;
    SELECT * INTO event FROM public.cfg_native_outbox o WHERE o.tenant_id=tenant AND o.action_id=target_action;
    IF receipt.receipt_id IS DISTINCT FROM action.receipt_id OR audit.audit_id IS DISTINCT FROM action.audit_id OR event.event_id IS DISTINCT FROM action.event_id
      OR receipt.payload IS DISTINCT FROM receipt.canonical_payload::jsonb OR receipt.payload IS DISTINCT FROM audit.payload OR receipt.payload IS DISTINCT FROM event.payload
      OR receipt.payload->>'tenantId' IS DISTINCT FROM tenant::text OR receipt.payload->>'actionId' IS DISTINCT FROM target_action::text
      OR receipt.payload->>'intentDigest' IS DISTINCT FROM action.intent_digest OR receipt.payload->>'operation' IS DISTINCT FROM action.operation
      OR receipt.payload->'result' IS DISTINCT FROM action.result OR receipt.payload->>'resultKind' IS DISTINCT FROM action.result_kind
      OR decode(receipt.payload->>'originalBase64','base64') IS DISTINCT FROM action.original_request
    THEN RAISE EXCEPTION 'Native effect receipt audit outbox mismatch' USING ERRCODE='23514'; END IF;
    IF action.operation IN ('TENANT_SUSPEND','TENANT_TRANSITION','STATUS_SUSPEND','STATUS_TRANSITION') THEN
        SELECT * INTO effect FROM public.cfg_native_eligibility_event e WHERE e.tenant_id=tenant AND e.action_id=target_action;
        IF NOT FOUND OR effect.resource_id IS DISTINCT FROM action.resource_id OR effect.after_state IS DISTINCT FROM action.result THEN RAISE EXCEPTION 'Native eligibility effect missing' USING ERRCODE='23514'; END IF;
        IF effect.resource_kind='TENANT' THEN
            SELECT t.active,c.generation INTO source_active,generation FROM public.tenants t JOIN public.cfg_native_tenant_eligibility c ON c.tenant_id=t.id WHERE t.id=effect.resource_id AND c.last_action_id=target_action;
            IF NOT FOUND OR source_active IS DISTINCT FROM (effect.after_state->>'tenantActive')::boolean OR generation IS DISTINCT FROM effect.new_generation THEN RAISE EXCEPTION 'Native tenant final source mismatch' USING ERRCODE='23514'; END IF;
        ELSE
            SELECT s.active,c.generation INTO source_active,generation FROM public.tenant_statuses s JOIN public.cfg_native_status_eligibility c ON c.status_id=s.id WHERE s.id=effect.resource_id AND c.last_action_id=target_action AND c.last_action_tenant_id=tenant;
            IF NOT FOUND OR source_active IS DISTINCT FROM (effect.after_state->>'enabled')::boolean OR generation IS DISTINCT FROM effect.new_generation THEN RAISE EXCEPTION 'Native status final source mismatch' USING ERRCODE='23514'; END IF;
        END IF;
    END IF;
    IF action.result_kind='NO_EFFECT_FINAL' THEN
        SELECT * INTO grant_proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=tenant AND p.proposal_id=action.proposal_id;
        SELECT * INTO grant_proposal_control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=tenant AND c.proposal_id=action.proposal_id;
        IF action.operation NOT IN ('GRANT_ISSUE','GRANT_CONTROL') OR current_setting('firefly.cfg.operation',true) IS DISTINCT FROM 'RECONCILE'
          OR current_setting('firefly.cfg.resource_type',true) IS DISTINCT FROM 'ACTION' OR current_setting('firefly.cfg.resource',true) IS DISTINCT FROM target_action::text
          OR floor(extract(epoch FROM action.admitted_at)*1000)::bigint < action.deadline_epoch_millis
          OR grant_proposal.target_operation IS DISTINCT FROM action.operation OR grant_proposal.target_action_id IS DISTINCT FROM target_action
          OR action.resource_id IS DISTINCT FROM (CASE WHEN action.operation='GRANT_ISSUE' THEN action.proposal_id ELSE grant_proposal.resource_id END)
          OR action.canonical_intent IS DISTINCT FROM (CASE WHEN action.operation='GRANT_ISSUE' THEN public.cfg_native_grant_v2_intent(tenant,convert_from(action.original_request,'UTF8')::json) ELSE public.cfg_native_grant_control_intent(tenant,convert_from(action.original_request,'UTF8')::json) END)
          OR grant_proposal.proposal_id IS NULL OR grant_proposal_control.proposal_id IS NULL OR grant_proposal_control.state NOT IN ('NO_EFFECT_FINAL','REJECTED','WITHDRAWN') OR grant_proposal_control.execution_action_id IS NOT NULL
          OR NOT EXISTS(SELECT 1 FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=tenant AND r.proposal_id=action.proposal_id AND r.revision=action.proposal_revision AND r.canonical_target=action.canonical_intent AND r.not_after_epoch_millis=action.deadline_epoch_millis)
          OR action.result IS DISTINCT FROM jsonb_build_object('state','NO_EFFECT_FINAL','reason','ADMISSION_DEADLINE_ELAPSED')
          OR EXISTS(SELECT 1 FROM public.cfg_native_grant_version g WHERE g.tenant_id=tenant AND g.issue_action_id=target_action)
          OR EXISTS(SELECT 1 FROM public.cfg_native_grant_control_event e WHERE e.tenant_id=tenant AND e.action_id=target_action)
        THEN RAISE EXCEPTION 'Complete exact deadline negative fence required' USING ERRCODE='23514'; END IF;
    END IF;
    IF action.operation='GRANT_ISSUE' AND action.result_kind='APPLIED' THEN
        grant_body:=convert_from(action.original_request,'UTF8')::json;target:=grant_body->'grant';binding:=grant_body->'eligibilityEpoch';
        SELECT * INTO grant_effect FROM public.cfg_native_grant_version g WHERE g.tenant_id=tenant AND g.issue_action_id=target_action;
        SELECT * INTO grant_head FROM public.cfg_native_grant_head h WHERE h.tenant_id=tenant AND h.institution_id=grant_effect.institution_id AND h.issuer_authority_id=grant_effect.issuer_authority_id;
        SELECT * INTO grant_control FROM public.cfg_native_grant_control c WHERE c.tenant_id=tenant AND c.grant_id=grant_effect.grant_id AND c.version=grant_effect.version;
        SELECT * INTO grant_proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=tenant AND p.proposal_id=grant_effect.proposal_id;
        SELECT * INTO grant_proposal_control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=tenant AND c.proposal_id=grant_effect.proposal_id;
        SELECT * INTO grant_decision FROM public.cfg_native_decision d WHERE d.tenant_id=tenant AND d.decision_id=grant_proposal_control.decision_id;
        IF grant_effect.grant_id IS NULL OR grant_head.grant_id IS DISTINCT FROM grant_effect.grant_id OR grant_head.version IS DISTINCT FROM grant_effect.version
          OR grant_head.head_version IS DISTINCT FROM grant_effect.version OR grant_control.state IS DISTINCT FROM 'ACTIVE' OR grant_control.control_version IS DISTINCT FROM 0::bigint OR grant_control.last_action_id IS DISTINCT FROM target_action
          OR grant_proposal_control.state IS DISTINCT FROM 'EXECUTION_ADMITTED' OR grant_proposal_control.execution_action_id IS DISTINCT FROM target_action
          OR grant_decision.operation IS DISTINCT FROM 'approve' OR grant_decision.employee_id=grant_proposal.maker_employee_id OR grant_decision.actor_issuer=grant_proposal.maker_issuer AND grant_decision.actor_subject=grant_proposal.maker_subject
          OR grant_decision.target_digest IS DISTINCT FROM encode(sha256(convert_to(action.canonical_intent,'UTF8')),'hex')
          OR public.cfg_native_grant_v2_intent(tenant,grant_body) IS DISTINCT FROM action.canonical_intent
          OR grant_effect.grant_id IS DISTINCT FROM (target->>'grantId')::uuid OR grant_effect.version IS DISTINCT FROM (target->>'version')::bigint
          OR grant_effect.institution_id IS DISTINCT FROM (target->>'institutionId')::uuid OR grant_effect.issuer_authority_id IS DISTINCT FROM (target->>'issuerAuthorityId')::uuid
          OR grant_effect.uses IS DISTINCT FROM ARRAY(SELECT json_array_elements_text(target->'uses'))
          OR grant_effect.effective_from_epoch_millis IS DISTINCT FROM (target->>'effectiveFromEpochMillis')::bigint OR grant_effect.effective_to_epoch_millis IS DISTINCT FROM (target->>'effectiveToEpochMillis')::bigint
          OR grant_effect.tenant_generation IS DISTINCT FROM (binding->>'tenantGeneration')::bigint OR grant_effect.status_id IS DISTINCT FROM (binding->>'statusId')::uuid OR grant_effect.status_generation IS DISTINCT FROM (binding->>'statusGeneration')::bigint
          OR grant_effect.proposal_id IS DISTINCT FROM (target->>'proposalId')::uuid OR grant_effect.proposal_revision IS DISTINCT FROM (target->>'proposalRevision')::bigint
          OR grant_effect.canonical_definition IS DISTINCT FROM public.cfg_native_grant_definition_intent(tenant,grant_body,floor(extract(epoch FROM grant_effect.recorded_at)*1000)::bigint)
          OR action.result IS DISTINCT FROM public.cfg_native_grant_view(tenant,grant_effect.grant_id,grant_effect.version)
        THEN RAISE EXCEPTION 'Complete exact approved grant effect required' USING ERRCODE='23514'; END IF;
    END IF;
    IF action.operation='GRANT_CONTROL' AND action.result_kind='APPLIED' THEN
        grant_body:=convert_from(action.original_request,'UTF8')::json;
        SELECT * INTO control_effect FROM public.cfg_native_grant_control_event e WHERE e.tenant_id=tenant AND e.action_id=target_action;
        SELECT * INTO grant_control FROM public.cfg_native_grant_control c WHERE c.tenant_id=tenant AND c.grant_id=(grant_body->>'grantId')::uuid AND c.version=(grant_body->>'version')::bigint;
        SELECT * INTO grant_proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=tenant AND p.proposal_id=(grant_body->>'proposalId')::uuid;
        SELECT * INTO grant_proposal_control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=tenant AND c.proposal_id=(grant_body->>'proposalId')::uuid;
        SELECT * INTO grant_decision FROM public.cfg_native_decision d WHERE d.tenant_id=tenant AND d.decision_id=grant_proposal_control.decision_id;
        IF control_effect.action_id IS NULL OR control_effect.grant_id IS DISTINCT FROM (grant_body->>'grantId')::uuid OR control_effect.version IS DISTINCT FROM (grant_body->>'version')::bigint
          OR control_effect.old_control_version IS DISTINCT FROM (grant_body->>'expectedControlVersion')::bigint OR control_effect.new_control_version IS DISTINCT FROM (grant_body->>'expectedControlVersion')::bigint+1
          OR control_effect.old_state IS DISTINCT FROM grant_body->>'expectedState' OR control_effect.new_state IS DISTINCT FROM grant_body->>'targetState'
          OR grant_control.state IS DISTINCT FROM grant_body->>'targetState' OR grant_control.control_version IS DISTINCT FROM control_effect.new_control_version OR grant_control.last_action_id IS DISTINCT FROM target_action
          OR grant_proposal_control.state IS DISTINCT FROM 'EXECUTION_ADMITTED' OR grant_proposal_control.execution_action_id IS DISTINCT FROM target_action OR grant_decision.operation IS DISTINCT FROM 'approve'
          OR grant_decision.employee_id=grant_proposal.maker_employee_id OR grant_decision.actor_issuer=grant_proposal.maker_issuer AND grant_decision.actor_subject=grant_proposal.maker_subject
          OR grant_decision.target_digest IS DISTINCT FROM action.intent_digest OR public.cfg_native_grant_control_intent(tenant,grant_body) IS DISTINCT FROM action.canonical_intent
          OR action.result IS DISTINCT FROM jsonb_build_object('state',grant_control.state,'controlVersion',grant_control.control_version,'lastActionId',grant_control.last_action_id)
        THEN RAISE EXCEPTION 'Complete exact approved grant-control effect required' USING ERRCODE='23514'; END IF;
    END IF;
    IF EXISTS(SELECT 1 FROM public.cfg_native_tenant_eligibility c JOIN public.tenants t ON t.id=c.tenant_id WHERE c.tenant_id=tenant AND (c.enabled IS DISTINCT FROM (t.active IS TRUE) OR c.current_status_id IS DISTINCT FROM t.tenant_status_id))
      OR EXISTS(SELECT 1 FROM public.cfg_native_status_eligibility c JOIN public.tenant_statuses s ON s.id=c.status_id WHERE (c.registered_tenant_id=tenant AND c.registered_action_id=target_action OR c.last_action_tenant_id=tenant AND c.last_action_id=target_action) AND c.enabled IS DISTINCT FROM (s.active IS TRUE))
    THEN RAISE EXCEPTION 'Native source and generation control diverged' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END $$;

DO $$
DECLARE name TEXT;tenant_column TEXT;function_value RECORD;
BEGIN
    FOREACH name IN ARRAY ARRAY['cfg_native_institution','cfg_native_issuer','cfg_native_proposal','cfg_native_proposal_revision','cfg_native_decision','cfg_native_grant_version','cfg_native_action','cfg_native_receipt','cfg_native_audit','cfg_native_outbox','cfg_native_eligibility_event','cfg_native_grant_control_event'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.cfg_native_immutable_guard()',name||'_immutable',name);
    END LOOP;
    FOREACH name IN ARRAY ARRAY['cfg_native_status_eligibility','cfg_native_tenant_eligibility','cfg_native_proposal_control','cfg_native_grant_head','cfg_native_grant_control','cfg_native_outbox_delivery'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.cfg_native_mutable_guard()',name||'_mutation',name);
    END LOOP;
    FOREACH name IN ARRAY ARRAY['cfg_native_institution','cfg_native_issuer','cfg_native_decision','cfg_native_action','cfg_native_receipt','cfg_native_audit','cfg_native_outbox','cfg_native_eligibility_event','cfg_native_grant_control_event','cfg_native_status_eligibility','cfg_native_tenant_eligibility'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER %I AFTER INSERT OR UPDATE ON public.%I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.cfg_native_complete_action()',name||'_complete',name);
    END LOOP;
    FOR name IN SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='r' AND c.relname LIKE 'cfg_native_%' LOOP
        EXECUTE format('ALTER TABLE public.%I OWNER TO cfg_native_owner',name);
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC,cfg_native_runtime',name);
        EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON public.%I TO cfg_native_guard',name);
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',name);EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',name);
        tenant_column:=CASE WHEN name IN ('cfg_native_institution','cfg_native_issuer') THEN 'allocation_tenant' WHEN name='cfg_native_status_eligibility' THEN NULL ELSE 'tenant_id' END;
        IF tenant_column IS NULL THEN
            EXECUTE format('CREATE POLICY %I ON public.%I FOR ALL TO cfg_native_guard USING(public.cfg_native_context_current()) WITH CHECK(public.cfg_native_context_current())',name||'_scope',name);
        ELSE
            EXECUTE format('CREATE POLICY %I ON public.%I FOR ALL TO cfg_native_guard USING(public.cfg_native_context_current(%I)) WITH CHECK(public.cfg_native_context_current(%I))',name||'_scope',name,tenant_column,tenant_column);
        END IF;
    END LOOP;
    CREATE POLICY cfg_native_tenant_membership_lookup ON public.cfg_native_tenant_eligibility FOR SELECT TO cfg_native_guard USING(true);
    CREATE POLICY cfg_native_status_membership_lookup ON public.cfg_native_status_eligibility FOR SELECT TO cfg_native_guard USING(true);
    FOR function_value IN SELECT p.oid,p.oid::regprocedure AS identity FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname LIKE 'cfg_native_%' LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO cfg_native_guard',function_value.identity);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,cfg_native_runtime',function_value.identity);
    END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION public.cfg_native_is_governed(TEXT,UUID),public.cfg_native_context_current(UUID),public.cfg_native_suspend_tenant(UUID,BYTEA) TO cfg_native_runtime;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;

ALTER TABLE public.cfg_native_proposal ADD COLUMN prepare_action_id UUID NOT NULL;
ALTER TABLE public.cfg_native_proposal ADD CONSTRAINT cfg_proposal_prepare_action FOREIGN KEY(tenant_id,prepare_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.cfg_native_decision ADD CONSTRAINT cfg_decision_action FOREIGN KEY(tenant_id,action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION public.cfg_native_prepare_transition_intent(p_resource UUID,p_value JSON,p_status BOOLEAN) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE draft JSON;token TEXT;
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_resource::text));
    PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','targetActionId','notAfterEpochMillis','draft']);draft:=p_value->'draft';
    IF p_status THEN
        PERFORM public.cfg_native_shape(draft,ARRAY['targetActive','reason','evidenceReferences']);
        token:='R3:'||public.cfg_native_bool(draft->'targetActive')||public.cfg_native_string(draft->'reason',500)||public.cfg_native_evidence(draft->'evidenceReferences');
    ELSE
        PERFORM public.cfg_native_shape(draft,ARRAY['targetStatusId','targetActive','reason','evidenceReferences']);
        token:='R4:'||public.cfg_native_uuid(draft->'targetStatusId')||public.cfg_native_bool(draft->'targetActive')||public.cfg_native_string(draft->'reason',500)||public.cfg_native_evidence(draft->'evidenceReferences');
    END IF;
    RETURN public.cfg_native_s(CASE WHEN p_status THEN 'FF_STATUS_ELIGIBILITY_PREPARE_V1' ELSE 'FF_TENANT_ELIGIBILITY_PREPARE_V1' END)||'R2:'||public.cfg_native_s(p_resource::text)||'R4:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'targetActionId')||public.cfg_native_long(p_value->'notAfterEpochMillis',-2208988800000,253402300799999)||token;
END $$;

CREATE FUNCTION public.cfg_native_action_gate(p_tenant UUID,p_action UUID) RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    -- Take the bounded audit-scope FK key-share before the last action gate, never after it.
    PERFORM 1 FROM public.tenants t WHERE t.id=p_tenant FOR KEY SHARE;
    IF NOT FOUND THEN PERFORM public.cfg_native_invalid(); END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('cfg-native-action/'||p_tenant::text||'/'||p_action::text,0));
END $$;

CREATE FUNCTION public.cfg_native_prepare_transition(p_tenant UUID,p_resource UUID,p_status BOOLEAN,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;draft JSON;canonical TEXT;action UUID;target_action UUID;deadline BIGINT;existing JSONB;before_state JSONB;target JSON;target_text TEXT;target_digest TEXT;encoding TEXT;proposal UUID;status_generation BIGINT;status_id UUID;expected_tenant_generation BIGINT;expected_status_generation BIGINT;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;draft:=body->'draft';canonical:=public.cfg_native_prepare_transition_intent(p_resource,body,p_status);action:=(body->>'actionId')::uuid;target_action:=(body->>'targetActionId')::uuid;deadline:=(body->>'notAfterEpochMillis')::bigint;
    IF action=target_action THEN PERFORM public.cfg_native_invalid(); END IF;
    IF p_status AND current_setting('firefly.cfg.platform_recovery',true) IS DISTINCT FROM 'true' OR NOT p_status AND p_tenant<>p_resource THEN RAISE EXCEPTION 'Exact lifecycle preparation scope required' USING ERRCODE='42501'; END IF;
    PERFORM public.cfg_native_require_context(p_tenant,action,'PREPARE',p_resource,CASE WHEN p_status THEN 'STATUS_ELIGIBILITY' ELSE 'TENANT_ELIGIBILITY' END,canonical,true);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    IF p_status THEN
        PERFORM public.cfg_native_lock_statuses(ARRAY[p_resource],p_tenant,action,false);
        SELECT s.generation INTO expected_status_generation FROM public.cfg_native_status_eligibility s WHERE s.status_id=p_resource;status_id:=p_resource;
    ELSE
        before_state:=public.cfg_native_lock_tenant(p_tenant,action,(draft->>'targetStatusId')::uuid);
        expected_tenant_generation:=(before_state->>'tenantGeneration')::bigint;expected_status_generation:=(before_state->>'statusGeneration')::bigint;status_id:=(before_state->>'statusId')::uuid;
        SELECT s.generation INTO status_generation FROM public.cfg_native_status_eligibility s WHERE s.status_id=(draft->>'targetStatusId')::uuid;
    END IF;
    PERFORM public.cfg_native_action_gate(p_tenant,action);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    PERFORM public.cfg_native_require_context(p_tenant,action,'PREPARE',p_resource,CASE WHEN p_status THEN 'STATUS_ELIGIBILITY' ELSE 'TENANT_ELIGIBILITY' END,canonical,true);
    IF deadline<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Native preparation deadline elapsed' USING ERRCODE='42501'; END IF;
    proposal:=gen_random_uuid();
    IF p_status THEN
        target:=json_build_object('actionId',target_action,'proposalId',proposal,'proposalRevision',1,'expectedStatusGeneration',expected_status_generation,'targetActive',(draft->>'targetActive')::boolean,'reason',draft->>'reason','evidenceReferences',draft->'evidenceReferences','notAfterEpochMillis',deadline);
        target_text:=public.cfg_native_status_transition_intent(p_resource,target);encoding:='FF_STATUS_ELIGIBILITY_TRANSITION_V1';
    ELSE
        target:=json_build_object('actionId',target_action,'proposalId',proposal,'proposalRevision',1,'expectedEpoch',json_build_object('tenantGeneration',expected_tenant_generation,'statusId',status_id,'statusGeneration',expected_status_generation),'targetStatusId',(draft->>'targetStatusId')::uuid,'expectedTargetStatusGeneration',status_generation,'targetActive',(draft->>'targetActive')::boolean,'reason',draft->>'reason','evidenceReferences',draft->'evidenceReferences','notAfterEpochMillis',deadline);
        target_text:=public.cfg_native_tenant_transition_intent(p_resource,target);encoding:='FF_TENANT_ELIGIBILITY_TRANSITION_V1';
    END IF;
    target_digest:=encode(sha256(convert_to(target_text,'UTF8')),'hex');
    INSERT INTO public.cfg_native_proposal(tenant_id,proposal_id,resource_id,target_operation,target_action_id,maker_issuer,maker_subject,maker_employee_id,prepare_action_id)
      VALUES(p_tenant,proposal,p_resource,CASE WHEN p_status THEN 'STATUS_TRANSITION' ELSE 'TENANT_TRANSITION' END,target_action,current_setting('firefly.cfg.actor_issuer'),current_setting('firefly.cfg.actor_subject'),current_setting('firefly.cfg.employee')::uuid,action);
    INSERT INTO public.cfg_native_proposal_revision(tenant_id,proposal_id,revision,target_encoding,original_prepare,original_target,canonical_target,target_digest,not_after_epoch_millis,tenant_generation,status_id,status_generation,target_status_id,target_status_generation)
      VALUES(p_tenant,proposal,1,encoding,p_original,convert_to(target::text,'UTF8'),target_text,target_digest,deadline,expected_tenant_generation,status_id,expected_status_generation,CASE WHEN p_status THEN NULL ELSE (draft->>'targetStatusId')::uuid END,status_generation);
    INSERT INTO public.cfg_native_proposal_control(tenant_id,proposal_id,state,control_version) VALUES(p_tenant,proposal,'DRAFT',0);
    RETURN public.cfg_native_finish_action(p_tenant,action,CASE WHEN p_status THEN 'STATUS_PREPARE' ELSE 'TENANT_PREPARE' END,p_resource,proposal,1,p_original,canonical,deadline,'PREPARED',jsonb_build_object('proposalId',proposal,'target',target,'targetCanonical',target_text,'targetDigest',target_digest,'targetEncoding',encoding));
END $$;

CREATE FUNCTION public.cfg_native_environment(p_operation TEXT) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path=pg_catalog,public,pg_temp AS $$
WITH protected(name) AS (SELECT unnest(ARRAY[
    'cfg_native_status_eligibility','cfg_native_tenant_eligibility','cfg_native_institution','cfg_native_issuer',
    'cfg_native_proposal','cfg_native_proposal_revision','cfg_native_proposal_control','cfg_native_decision',
    'cfg_native_grant_version','cfg_native_grant_head','cfg_native_grant_control','cfg_native_action',
    'cfg_native_receipt','cfg_native_audit','cfg_native_outbox','cfg_native_outbox_delivery',
    'cfg_native_eligibility_event','cfg_native_grant_control_event'])),
relations AS (SELECT c.* FROM protected p JOIN pg_class c ON c.relname=p.name JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'),
required(name) AS (SELECT CASE p_operation
    WHEN 'PREPARE' THEN 'cfg_native_prepare_transition' WHEN 'SUBMIT' THEN 'cfg_native_decide'
    WHEN 'APPROVE' THEN 'cfg_native_decide' WHEN 'REJECT' THEN 'cfg_native_decide' WHEN 'WITHDRAW' THEN 'cfg_native_decide'
    WHEN 'TENANT_SUSPEND' THEN 'cfg_native_suspend_tenant' WHEN 'TENANT_TRANSITION' THEN 'cfg_native_transition_tenant'
    WHEN 'STATUS_SUSPEND' THEN 'cfg_native_suspend_status' WHEN 'STATUS_TRANSITION' THEN 'cfg_native_transition_status'
    WHEN 'GRANT_ISSUE' THEN 'cfg_native_issue_grant_v2' WHEN 'GRANT_CONTROL' THEN 'cfg_native_control_grant_v2'
    WHEN 'READ_ACTION' THEN 'cfg_native_context_current' WHEN 'HISTORY' THEN 'cfg_native_context_current' WHEN 'READ_PROPOSAL' THEN 'cfg_native_context_current'
    WHEN 'OBSERVE' THEN 'cfg_native_observe_grant' WHEN 'RECONCILE' THEN 'cfg_native_reconcile_grant' END),
command AS (SELECT p.* FROM required r JOIN pg_proc p ON p.proname=r.name JOIN pg_namespace n ON n.oid=p.pronamespace AND n.nspname='public'),
guard AS (SELECT * FROM pg_roles WHERE rolname='cfg_native_guard')
SELECT coalesce(current_user='cfg_native_runtime' AND NOT r.rolsuper AND NOT r.rolbypassrls AND NOT r.rolinherit
    AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT has_schema_privilege(current_user,'public','CREATE')
    AND NOT pg_has_role(current_user,'cfg_native_owner','MEMBER') AND NOT pg_has_role(current_user,'cfg_native_guard','MEMBER')
    AND (SELECT count(*)=18 AND bool_and(relrowsecurity AND relforcerowsecurity AND NOT pg_has_role(current_user,relowner,'MEMBER')
        AND NOT has_table_privilege(current_user,oid,'INSERT,UPDATE,DELETE,TRUNCATE')) FROM relations)
    AND (SELECT count(*)=1 AND bool_and(NOT rolsuper AND NOT rolbypassrls AND NOT rolcanlogin AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit) FROM guard)
    AND (SELECT count(*)=1 AND bool_and(proowner=(SELECT oid FROM guard) AND proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']
        AND (prosecdef OR proname='cfg_native_context_current')) FROM command)
    AND EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname='cfg_native_source_eligibility_guard' AND NOT p.prosecdef AND p.proowner=(SELECT oid FROM guard)
        AND p.proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']),false)
FROM pg_roles r WHERE r.rolname=current_user $$;

CREATE FUNCTION public.cfg_native_establish_context(p_tenant UUID,p_institution UUID,p_operation TEXT,p_resource_type TEXT,p_resource UUID,p_action UUID,p_intent TEXT,p_kind TEXT,p_issuer TEXT,p_subject TEXT,p_actor_tenant UUID,p_employee UUID,p_client TEXT,p_recovery BOOLEAN,p_until BIGINT,p_deadline BIGINT) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE now_millis BIGINT:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
BEGIN
    IF NOT public.cfg_native_environment(p_operation) OR p_tenant IS NULL OR p_actor_tenant IS NULL OR p_resource IS NULL OR p_action IS NULL
      OR p_intent IS NULL OR p_intent !~ '^[0-9a-f]{64}$' OR p_kind IS NULL OR p_kind NOT IN ('STAFF','MACHINE')
      OR p_kind='STAFF' AND p_employee IS NULL OR p_kind='MACHINE' AND (p_employee IS NOT NULL OR p_client IS NULL)
      OR p_issuer IS NULL OR length(p_issuer) NOT BETWEEN 1 AND 500 OR p_subject IS NULL OR length(p_subject) NOT BETWEEN 1 AND 255
      OR p_recovery IS NULL OR NOT p_recovery AND p_actor_tenant<>p_tenant
      OR p_until IS NULL OR p_until<=now_millis OR p_deadline IS NULL OR p_deadline<=now_millis
      OR p_deadline>253402300799999 OR p_resource_type IS NULL OR p_resource_type NOT IN ('INSTITUTION_AUTHORITY','PROPOSAL','ACTION','GRANT','TENANT_ELIGIBILITY','STATUS_ELIGIBILITY')
    THEN RAISE EXCEPTION 'Restricted native context required' USING ERRCODE='42501'; END IF;
    PERFORM set_config('firefly.cfg.xid',pg_current_xact_id()::text,true),set_config('firefly.cfg.tenant',p_tenant::text,true),
      set_config('firefly.cfg.institution',coalesce(p_institution::text,''),true),set_config('firefly.cfg.operation',p_operation,true),
      set_config('firefly.cfg.resource_type',p_resource_type,true),set_config('firefly.cfg.resource',p_resource::text,true),
      set_config('firefly.cfg.action',p_action::text,true),set_config('firefly.cfg.intent',p_intent,true),
      set_config('firefly.cfg.actor_kind',p_kind,true),set_config('firefly.cfg.actor_issuer',p_issuer,true),set_config('firefly.cfg.actor_subject',p_subject,true),
      set_config('firefly.cfg.actor_tenant',p_actor_tenant::text,true),set_config('firefly.cfg.employee',coalesce(p_employee::text,''),true),
      set_config('firefly.cfg.client',coalesce(p_client,''),true),set_config('firefly.cfg.platform_recovery',p_recovery::text,true),
      set_config('firefly.cfg.authority_until',least(p_until,now_millis+30000)::text,true),set_config('firefly.cfg.command_until',least(p_deadline,now_millis+30000)::text,true);
    RETURN public.cfg_native_context_current(p_tenant);
END $$;

-- Read-only repository policies retain exact action scope; native commands own all mutations.
GRANT SELECT ON public.cfg_native_action,public.cfg_native_receipt TO cfg_native_runtime;
CREATE POLICY cfg_native_action_repository_read ON public.cfg_native_action FOR SELECT TO cfg_native_runtime
    USING(public.cfg_native_context_current(tenant_id) AND action_id=CASE WHEN current_setting('firefly.cfg.operation',true)='READ_ACTION' THEN nullif(current_setting('firefly.cfg.resource',true),'')::uuid ELSE nullif(current_setting('firefly.cfg.action',true),'')::uuid END);
CREATE POLICY cfg_native_receipt_repository_read ON public.cfg_native_receipt FOR SELECT TO cfg_native_runtime
    USING(public.cfg_native_context_current(tenant_id) AND action_id=CASE WHEN current_setting('firefly.cfg.operation',true)='READ_ACTION' THEN nullif(current_setting('firefly.cfg.resource',true),'')::uuid ELSE nullif(current_setting('firefly.cfg.action',true),'')::uuid END);

GRANT CREATE ON SCHEMA public TO cfg_native_guard;
DO $$ DECLARE function_value RECORD;
BEGIN
    FOR function_value IN SELECT p.oid::regprocedure AS identity FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname LIKE 'cfg_native_%' LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO cfg_native_guard',function_value.identity);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,cfg_native_runtime',function_value.identity);
    END LOOP;
END $$;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
GRANT EXECUTE ON FUNCTION public.cfg_native_is_governed(TEXT,UUID),public.cfg_native_context_current(UUID),public.cfg_native_suspend_tenant(UUID,BYTEA),public.cfg_native_prepare_transition(UUID,UUID,BOOLEAN,BYTEA),public.cfg_native_environment(TEXT),public.cfg_native_establish_context(UUID,UUID,TEXT,TEXT,UUID,UUID,TEXT,TEXT,TEXT,TEXT,UUID,UUID,TEXT,BOOLEAN,BIGINT,BIGINT) TO cfg_native_runtime;

CREATE FUNCTION public.cfg_native_decision_intent(p_tenant UUID,p_proposal UUID,p_operation TEXT,p_value JSON,p_target TEXT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE token TEXT;target_digest TEXT;
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_tenant::text));PERFORM public.cfg_native_uuid(to_json(p_proposal::text));
    IF p_operation IS NULL OR p_operation NOT IN ('submit','approve','reject','withdraw') OR p_target IS NULL OR octet_length(p_target) NOT BETWEEN 1 AND 131072 THEN PERFORM public.cfg_native_invalid(); END IF;
    PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','expectedProposalControlVersion','proposalRevision','targetIntentDigest','reason']);
    target_digest:=p_value->>'targetIntentDigest';
    IF target_digest IS NULL OR target_digest IS DISTINCT FROM encode(sha256(convert_to(p_target,'UTF8')),'hex') THEN PERFORM public.cfg_native_invalid(); END IF;
    token:='R5:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_long(p_value->'expectedProposalControlVersion',0,9223372036854775806)||public.cfg_native_long(p_value->'proposalRevision',1,9223372036854775807)||public.cfg_native_string(p_value->'targetIntentDigest',64)||public.cfg_native_string(p_value->'reason',500);
    RETURN public.cfg_native_s('FF_NATIVE_AUTHORITY_DECISION_V2')||'R5:'||public.cfg_native_s(p_tenant::text)||public.cfg_native_s(p_proposal::text)||public.cfg_native_s(p_operation)||token||public.cfg_native_s(p_target);
END $$;

CREATE FUNCTION public.cfg_native_lock_proposal_target(p_tenant UUID,p_proposal UUID,p_action UUID) RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;live JSONB;status_generation BIGINT;approved_grant JSON;head public.cfg_native_grant_head%ROWTYPE;
BEGIN
    SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.proposal_id=p_proposal;
    IF NOT FOUND THEN RAISE EXCEPTION 'Exact native proposal required' USING ERRCODE='55000'; END IF;
    SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=p_tenant AND r.proposal_id=p_proposal AND r.revision=1;
    IF NOT FOUND THEN RAISE EXCEPTION 'Retained native target required' USING ERRCODE='23514'; END IF;
    IF proposal.target_operation='TENANT_TRANSITION' THEN
        IF proposal.resource_id<>p_tenant THEN RAISE EXCEPTION 'Exact tenant target required' USING ERRCODE='55000'; END IF;
        live:=public.cfg_native_lock_tenant(p_tenant,p_action,revision.target_status_id);
        SELECT generation INTO status_generation FROM public.cfg_native_status_eligibility WHERE status_id=revision.target_status_id;
        IF revision.tenant_generation IS DISTINCT FROM (live->>'tenantGeneration')::bigint OR revision.status_id IS DISTINCT FROM (live->>'statusId')::uuid
          OR revision.status_generation IS DISTINCT FROM (live->>'statusGeneration')::bigint OR revision.target_status_generation IS DISTINCT FROM status_generation
        THEN RAISE EXCEPTION 'Native tenant proposal epoch changed' USING ERRCODE='55000'; END IF;
    ELSIF proposal.target_operation='STATUS_TRANSITION' THEN
        IF current_setting('firefly.cfg.platform_recovery',true) IS DISTINCT FROM 'true' THEN RAISE EXCEPTION 'Current platform status authority required' USING ERRCODE='42501'; END IF;
        PERFORM public.cfg_native_lock_statuses(ARRAY[proposal.resource_id],p_tenant,p_action,false);
        SELECT generation INTO status_generation FROM public.cfg_native_status_eligibility WHERE status_id=proposal.resource_id;
        IF revision.status_generation IS DISTINCT FROM status_generation THEN RAISE EXCEPTION 'Native status proposal epoch changed' USING ERRCODE='55000'; END IF;
    ELSIF proposal.target_operation='GRANT_ISSUE' THEN
        approved_grant:=convert_from(revision.original_target,'UTF8')::json->'grant';
        IF (approved_grant->>'expectedGrantHeadVersion')::bigint=0 AND current_setting('firefly.cfg.platform_recovery',true) IS DISTINCT FROM 'true' THEN RAISE EXCEPTION 'Current platform bootstrap authority required' USING ERRCODE='42501'; END IF;
        live:=public.cfg_native_lock_tenant(p_tenant,p_action);
        IF (live->>'eligibleNow')::boolean IS DISTINCT FROM true OR revision.tenant_generation IS DISTINCT FROM (live->>'tenantGeneration')::bigint
          OR revision.status_id IS DISTINCT FROM (live->>'statusId')::uuid OR revision.status_generation IS DISTINCT FROM (live->>'statusGeneration')::bigint
        THEN RAISE EXCEPTION 'Current exact grant eligibility epoch required' USING ERRCODE='55000'; END IF;
        IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM proposal.institution_id::text THEN RAISE EXCEPTION 'Exact retained institution scope required' USING ERRCODE='42501'; END IF;
        PERFORM 1 FROM public.cfg_native_institution i WHERE i.institution_id=proposal.institution_id FOR KEY SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Retained institution identity required' USING ERRCODE='23514'; END IF;
        PERFORM 1 FROM public.cfg_native_issuer i WHERE i.institution_id=proposal.institution_id AND i.issuer_authority_id=proposal.issuer_authority_id FOR KEY SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Retained issuer identity required' USING ERRCODE='23514'; END IF;
        SELECT * INTO head FROM public.cfg_native_grant_head h WHERE h.tenant_id=p_tenant AND h.institution_id=proposal.institution_id AND h.issuer_authority_id=proposal.issuer_authority_id FOR SHARE;
        IF coalesce(head.version,0)<>(approved_grant->>'expectedGrantHeadVersion')::bigint OR head.grant_id IS NOT NULL AND head.grant_id IS DISTINCT FROM (approved_grant->>'grantId')::uuid THEN RAISE EXCEPTION 'Current exact grant head required' USING ERRCODE='55000'; END IF;
    ELSIF proposal.target_operation='GRANT_CONTROL' THEN
        approved_grant:=convert_from(revision.original_target,'UTF8')::json;
        IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM proposal.institution_id::text THEN RAISE EXCEPTION 'Exact grant-control institution scope required' USING ERRCODE='42501'; END IF;
        PERFORM public.cfg_native_lock_grant_control(p_tenant,proposal.institution_id,proposal.issuer_authority_id,(approved_grant->>'grantId')::uuid,(approved_grant->>'version')::bigint,p_action,(approved_grant->>'expectedControlVersion')::bigint,approved_grant->>'expectedState',approved_grant->>'targetState',approved_grant->'expectedEpoch');
    ELSE RAISE EXCEPTION 'Implemented exact native target required' USING ERRCODE='55000'; END IF;
    -- This single known audit identity is part of the lock plan, never discovered from a later FK.
    PERFORM 1 FROM public.tenants t WHERE t.id=p_tenant FOR KEY SHARE;
    IF NOT FOUND THEN PERFORM public.cfg_native_invalid(); END IF;
END $$;

CREATE FUNCTION public.cfg_native_decide(p_tenant UUID,p_proposal UUID,p_operation TEXT,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;action UUID;canonical TEXT;existing JSONB;decision UUID;next_state TEXT;employee UUID;issuer TEXT;subject TEXT;maker BOOLEAN;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;
    SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.proposal_id=p_proposal;
    SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=p_tenant AND r.proposal_id=p_proposal AND r.revision=1;
    IF proposal.proposal_id IS NULL OR revision.proposal_id IS NULL THEN RAISE EXCEPTION 'Exact native proposal required' USING ERRCODE='55000'; END IF;
    canonical:=public.cfg_native_decision_intent(p_tenant,p_proposal,p_operation,body,revision.canonical_target);action:=(body->>'actionId')::uuid;
    PERFORM public.cfg_native_require_context(p_tenant,action,upper(p_operation),p_proposal,'PROPOSAL',canonical,true);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    PERFORM public.cfg_native_lock_proposal_target(p_tenant,p_proposal,action);
    SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=p_tenant AND c.proposal_id=p_proposal FOR UPDATE;
    PERFORM public.cfg_native_action_gate(p_tenant,action);
    PERFORM public.cfg_native_require_context(p_tenant,action,upper(p_operation),p_proposal,'PROPOSAL',canonical,true);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    IF control.proposal_id IS NULL OR control.control_version<>(body->>'expectedProposalControlVersion')::bigint OR revision.revision<>(body->>'proposalRevision')::bigint
      OR revision.not_after_epoch_millis<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Current native proposal control required' USING ERRCODE='55000'; END IF;
    employee:=current_setting('firefly.cfg.employee')::uuid;issuer:=current_setting('firefly.cfg.actor_issuer');subject:=current_setting('firefly.cfg.actor_subject');
    maker:=employee=proposal.maker_employee_id AND issuer=proposal.maker_issuer AND subject=proposal.maker_subject;
    IF p_operation IN ('submit','withdraw') THEN
        IF NOT maker THEN RAISE EXCEPTION 'Actual proposal maker required' USING ERRCODE='42501'; END IF;
        IF p_operation='submit' AND control.state='DRAFT' THEN next_state:='SUBMITTED';
        ELSIF p_operation='withdraw' AND control.state IN ('DRAFT','SUBMITTED','APPROVED') THEN next_state:='WITHDRAWN';
        ELSE RAISE EXCEPTION 'Current native proposal state required' USING ERRCODE='55000'; END IF;
    ELSE
        IF employee=proposal.maker_employee_id OR issuer=proposal.maker_issuer AND subject=proposal.maker_subject THEN RAISE EXCEPTION 'Independent verified staff checker required' USING ERRCODE='42501'; END IF;
        IF control.state<>'SUBMITTED' THEN RAISE EXCEPTION 'Submitted native proposal required' USING ERRCODE='55000'; END IF;
        next_state:=CASE WHEN p_operation='approve' THEN 'APPROVED' ELSE 'REJECTED' END;
    END IF;
    decision:=gen_random_uuid();
    INSERT INTO public.cfg_native_decision(tenant_id,decision_id,action_id,proposal_id,revision,operation,target_digest,actor_issuer,actor_subject,employee_id,original_decision,canonical_decision,decision_digest)
      VALUES(p_tenant,decision,action,p_proposal,revision.revision,p_operation,revision.target_digest,issuer,subject,employee,p_original,canonical,encode(sha256(convert_to(canonical,'UTF8')),'hex'));
    UPDATE public.cfg_native_proposal_control SET state=next_state,control_version=control.control_version+1,decision_id=decision WHERE tenant_id=p_tenant AND proposal_id=p_proposal;
    RETURN public.cfg_native_finish_action(p_tenant,action,upper(p_operation),p_proposal,p_proposal,revision.revision,p_original,canonical,revision.not_after_epoch_millis,'DECIDED',jsonb_build_object('proposalId',p_proposal,'state',next_state,'controlVersion',control.control_version+1,'decisionId',decision,'targetDigest',revision.target_digest));
END $$;

CREATE FUNCTION public.cfg_native_transition_tenant(p_tenant UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;canonical TEXT;action UUID;proposal_key UUID;proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;before_state JSONB;after_state JSONB;existing JSONB;deadline BIGINT;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_tenant_transition_intent(p_tenant,body);action:=(body->>'actionId')::uuid;proposal_key:=(body->>'proposalId')::uuid;deadline:=(body->>'notAfterEpochMillis')::bigint;
    PERFORM public.cfg_native_require_context(p_tenant,action,'TENANT_TRANSITION',p_tenant,'TENANT_ELIGIBILITY',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing||jsonb_build_object('currentEligibility',public.cfg_native_tenant_view(p_tenant)); END IF;
    PERFORM public.cfg_native_lock_proposal_target(p_tenant,proposal_key,action);before_state:=public.cfg_native_tenant_view(p_tenant);
    SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.proposal_id=proposal_key;
    SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=p_tenant AND r.proposal_id=proposal_key AND r.revision=(body->>'proposalRevision')::bigint;
    SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=p_tenant AND c.proposal_id=proposal_key FOR UPDATE;
    PERFORM public.cfg_native_action_gate(p_tenant,action);
    PERFORM public.cfg_native_require_context(p_tenant,action,'TENANT_TRANSITION',p_tenant,'TENANT_ELIGIBILITY',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing||jsonb_build_object('currentEligibility',public.cfg_native_tenant_view(p_tenant)); END IF;
    IF proposal.target_operation IS DISTINCT FROM 'TENANT_TRANSITION' OR proposal.resource_id IS DISTINCT FROM p_tenant OR proposal.target_action_id IS DISTINCT FROM action
      OR revision.canonical_target IS DISTINCT FROM canonical OR control.state IS DISTINCT FROM 'APPROVED' OR control.execution_action_id IS NOT NULL
      OR deadline<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Exact approved current tenant target required' USING ERRCODE='55000'; END IF;
    PERFORM set_config('firefly.cfg.lifecycle_write','TENANT',true);
    UPDATE public.tenants SET active=(body->>'targetActive')::boolean,tenant_status_id=(body->>'targetStatusId')::uuid,version=coalesce(version,0)+1,updated_at=clock_timestamp() WHERE id=p_tenant;
    UPDATE public.cfg_native_tenant_eligibility SET enabled=(body->>'targetActive')::boolean,current_status_id=(body->>'targetStatusId')::uuid,generation=generation+1,last_action_id=action WHERE tenant_id=p_tenant;
    UPDATE public.cfg_native_proposal_control SET state='EXECUTION_ADMITTED',control_version=control.control_version+1,execution_action_id=action WHERE tenant_id=p_tenant AND cfg_native_proposal_control.proposal_id=proposal_key;
    after_state:=public.cfg_native_tenant_view(p_tenant);
    INSERT INTO public.cfg_native_eligibility_event(tenant_id,action_id,resource_kind,resource_id,old_generation,new_generation,before_state,after_state)
      VALUES(p_tenant,action,'TENANT',p_tenant,(before_state->>'tenantGeneration')::bigint,(after_state->>'tenantGeneration')::bigint,before_state,after_state);
    RETURN public.cfg_native_finish_action(p_tenant,action,'TENANT_TRANSITION',p_tenant,proposal_key,revision.revision,p_original,canonical,deadline,'APPLIED',after_state)||jsonb_build_object('currentEligibility',after_state);
END $$;

GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_decision_intent(UUID,UUID,TEXT,JSON,TEXT) OWNER TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_lock_proposal_target(UUID,UUID,UUID) OWNER TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_decide(UUID,UUID,TEXT,BYTEA) OWNER TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_transition_tenant(UUID,BYTEA) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_decision_intent(UUID,UUID,TEXT,JSON,TEXT),public.cfg_native_lock_proposal_target(UUID,UUID,UUID),public.cfg_native_decide(UUID,UUID,TEXT,BYTEA),public.cfg_native_transition_tenant(UUID,BYTEA) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_decide(UUID,UUID,TEXT,BYTEA),public.cfg_native_transition_tenant(UUID,BYTEA) TO cfg_native_runtime;

GRANT SELECT ON public.cfg_native_proposal,public.cfg_native_proposal_revision,public.cfg_native_proposal_control TO cfg_native_runtime;
CREATE POLICY cfg_native_proposal_repository_read ON public.cfg_native_proposal FOR SELECT TO cfg_native_runtime
    USING(public.cfg_native_context_current(tenant_id) AND current_setting('firefly.cfg.operation',true)='HISTORY' AND current_setting('firefly.cfg.resource_type',true)='PROPOSAL' AND proposal_id=nullif(current_setting('firefly.cfg.resource',true),'')::uuid);
CREATE POLICY cfg_native_revision_repository_read ON public.cfg_native_proposal_revision FOR SELECT TO cfg_native_runtime
    USING(public.cfg_native_context_current(tenant_id) AND current_setting('firefly.cfg.operation',true)='HISTORY' AND current_setting('firefly.cfg.resource_type',true)='PROPOSAL' AND proposal_id=nullif(current_setting('firefly.cfg.resource',true),'')::uuid);
CREATE POLICY cfg_native_proposal_control_repository_read ON public.cfg_native_proposal_control FOR SELECT TO cfg_native_runtime
    USING(public.cfg_native_context_current(tenant_id) AND current_setting('firefly.cfg.operation',true)='HISTORY' AND current_setting('firefly.cfg.resource_type',true)='PROPOSAL' AND proposal_id=nullif(current_setting('firefly.cfg.resource',true),'')::uuid);

CREATE FUNCTION public.cfg_native_status_view(p_status UUID) RETURNS JSONB
LANGUAGE sql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
SELECT jsonb_build_object('statusId',s.id,'generation',e.generation,'enabled',e.enabled,'statusCode',s.code,'lastActionId',e.last_action_id)
FROM public.tenant_statuses s JOIN public.cfg_native_status_eligibility e ON e.status_id=s.id WHERE s.id=p_status $$;

CREATE FUNCTION public.cfg_native_suspend_status(p_tenant UUID,p_status UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;canonical TEXT;action UUID;deadline BIGINT;existing JSONB;before_state JSONB;after_state JSONB;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_suspend_intent(p_status,body,true);action:=(body->>'actionId')::uuid;deadline:=(body->>'notAfterEpochMillis')::bigint;
    IF current_setting('firefly.cfg.platform_recovery',true) IS DISTINCT FROM 'true' THEN RAISE EXCEPTION 'Current platform status authority required' USING ERRCODE='42501'; END IF;
    PERFORM public.cfg_native_require_context(p_tenant,action,'STATUS_SUSPEND',p_status,'STATUS_ELIGIBILITY',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing||jsonb_build_object('currentEligibility',public.cfg_native_status_view(p_status)); END IF;
    PERFORM public.cfg_native_lock_statuses(ARRAY[p_status],p_tenant,action,true);
    PERFORM public.cfg_native_action_gate(p_tenant,action);
    PERFORM public.cfg_native_require_context(p_tenant,action,'STATUS_SUSPEND',p_status,'STATUS_ELIGIBILITY',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing||jsonb_build_object('currentEligibility',public.cfg_native_status_view(p_status)); END IF;
    IF deadline<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Native status deadline elapsed' USING ERRCODE='42501'; END IF;
    before_state:=public.cfg_native_status_view(p_status);
    PERFORM set_config('firefly.cfg.lifecycle_write','STATUS',true);
    UPDATE public.tenant_statuses SET active=false,version=coalesce(version,0)+1,updated_at=clock_timestamp() WHERE id=p_status;
    UPDATE public.cfg_native_status_eligibility SET enabled=false,generation=generation+1,last_action_tenant_id=p_tenant,last_action_id=action WHERE status_id=p_status;
    after_state:=public.cfg_native_status_view(p_status);
    INSERT INTO public.cfg_native_eligibility_event(tenant_id,action_id,resource_kind,resource_id,old_generation,new_generation,before_state,after_state)
      VALUES(p_tenant,action,'STATUS',p_status,(before_state->>'generation')::bigint,(after_state->>'generation')::bigint,before_state,after_state);
    RETURN public.cfg_native_finish_action(p_tenant,action,'STATUS_SUSPEND',p_status,NULL,NULL,p_original,canonical,deadline,'APPLIED',after_state)||jsonb_build_object('currentEligibility',after_state);
END $$;

CREATE FUNCTION public.cfg_native_transition_status(p_tenant UUID,p_status UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;canonical TEXT;action UUID;proposal_key UUID;deadline BIGINT;proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;existing JSONB;before_state JSONB;after_state JSONB;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_status_transition_intent(p_status,body);action:=(body->>'actionId')::uuid;proposal_key:=(body->>'proposalId')::uuid;deadline:=(body->>'notAfterEpochMillis')::bigint;
    IF current_setting('firefly.cfg.platform_recovery',true) IS DISTINCT FROM 'true' THEN RAISE EXCEPTION 'Current platform status authority required' USING ERRCODE='42501'; END IF;
    PERFORM public.cfg_native_require_context(p_tenant,action,'STATUS_TRANSITION',p_status,'STATUS_ELIGIBILITY',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing||jsonb_build_object('currentEligibility',public.cfg_native_status_view(p_status)); END IF;
    SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.proposal_id=proposal_key;
    SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=p_tenant AND r.proposal_id=proposal_key AND r.revision=(body->>'proposalRevision')::bigint;
    IF proposal.target_operation IS DISTINCT FROM 'STATUS_TRANSITION' OR proposal.resource_id IS DISTINCT FROM p_status OR proposal.target_action_id IS DISTINCT FROM action OR revision.canonical_target IS DISTINCT FROM canonical THEN RAISE EXCEPTION 'Exact retained status target required' USING ERRCODE='55000'; END IF;
    PERFORM public.cfg_native_lock_statuses(ARRAY[p_status],p_tenant,action,true);
    before_state:=public.cfg_native_status_view(p_status);
    IF (before_state->>'generation')::bigint IS DISTINCT FROM revision.status_generation THEN RAISE EXCEPTION 'Native status proposal epoch changed' USING ERRCODE='55000'; END IF;
    PERFORM 1 FROM public.tenants t WHERE t.id=p_tenant FOR KEY SHARE;
    IF NOT FOUND THEN PERFORM public.cfg_native_invalid(); END IF;
    SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=p_tenant AND c.proposal_id=proposal_key FOR UPDATE;
    PERFORM public.cfg_native_action_gate(p_tenant,action);
    PERFORM public.cfg_native_require_context(p_tenant,action,'STATUS_TRANSITION',p_status,'STATUS_ELIGIBILITY',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing||jsonb_build_object('currentEligibility',public.cfg_native_status_view(p_status)); END IF;
    IF control.state IS DISTINCT FROM 'APPROVED' OR control.execution_action_id IS NOT NULL OR deadline<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Exact approved current status target required' USING ERRCODE='55000'; END IF;
    PERFORM set_config('firefly.cfg.lifecycle_write','STATUS',true);
    UPDATE public.tenant_statuses SET active=(body->>'targetActive')::boolean,version=coalesce(version,0)+1,updated_at=clock_timestamp() WHERE id=p_status;
    UPDATE public.cfg_native_status_eligibility SET enabled=(body->>'targetActive')::boolean,generation=generation+1,last_action_tenant_id=p_tenant,last_action_id=action WHERE status_id=p_status;
    UPDATE public.cfg_native_proposal_control SET state='EXECUTION_ADMITTED',control_version=control.control_version+1,execution_action_id=action WHERE tenant_id=p_tenant AND proposal_id=proposal_key;
    after_state:=public.cfg_native_status_view(p_status);
    INSERT INTO public.cfg_native_eligibility_event(tenant_id,action_id,resource_kind,resource_id,old_generation,new_generation,before_state,after_state)
      VALUES(p_tenant,action,'STATUS',p_status,(before_state->>'generation')::bigint,(after_state->>'generation')::bigint,before_state,after_state);
    RETURN public.cfg_native_finish_action(p_tenant,action,'STATUS_TRANSITION',p_status,proposal_key,revision.revision,p_original,canonical,deadline,'APPLIED',after_state)||jsonb_build_object('currentEligibility',after_state);
END $$;

GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_status_view(UUID) OWNER TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_suspend_status(UUID,UUID,BYTEA) OWNER TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_transition_status(UUID,UUID,BYTEA) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_status_view(UUID),public.cfg_native_suspend_status(UUID,UUID,BYTEA),public.cfg_native_transition_status(UUID,UUID,BYTEA) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_suspend_status(UUID,UUID,BYTEA),public.cfg_native_transition_status(UUID,UUID,BYTEA) TO cfg_native_runtime;

CREATE FUNCTION public.cfg_native_grant_prepare_intent(p_tenant UUID,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE draft JSON;institution_token TEXT;issuer_token TEXT;expected BIGINT;draft_token TEXT;
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_tenant::text));
    PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','targetActionId','notAfterEpochMillis','draft']);draft:=p_value->'draft';
    PERFORM public.cfg_native_shape(draft,ARRAY['existingInstitutionId','existingIssuerAuthorityId','expectedGrantHeadVersion','uses','effectiveFromEpochMillis','effectiveToEpochMillis','reason','evidenceReferences']);
    institution_token:=CASE WHEN json_typeof(draft->'existingInstitutionId')='null' THEN 'N;' ELSE public.cfg_native_uuid(draft->'existingInstitutionId') END;
    issuer_token:=CASE WHEN json_typeof(draft->'existingIssuerAuthorityId')='null' THEN 'N;' ELSE public.cfg_native_uuid(draft->'existingIssuerAuthorityId') END;
    PERFORM public.cfg_native_long(draft->'expectedGrantHeadVersion',0,9223372036854775806);expected:=(draft->>'expectedGrantHeadVersion')::bigint;
    IF institution_token='N;' AND issuer_token<>'N;' OR (institution_token='N;' OR issuer_token='N;') AND expected<>0 OR p_value->>'actionId'=p_value->>'targetActionId' THEN PERFORM public.cfg_native_invalid(); END IF;
    draft_token:='R8:'||institution_token||issuer_token||public.cfg_native_long(draft->'expectedGrantHeadVersion',0,9223372036854775806)||public.cfg_native_uses(draft->'uses')||public.cfg_native_long(draft->'effectiveFromEpochMillis',-2208988800000,253402300799999)||public.cfg_native_long(draft->'effectiveToEpochMillis',-2208988800000,253402300799999)||public.cfg_native_string(draft->'reason',500)||public.cfg_native_evidence(draft->'evidenceReferences');
    IF (draft->>'effectiveFromEpochMillis')::bigint>=(draft->>'effectiveToEpochMillis')::bigint THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN public.cfg_native_s('FF_TENANT_INSTITUTION_GRANT_PREPARE_V2')||'R2:'||public.cfg_native_s(p_tenant::text)||'R4:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'targetActionId')||public.cfg_native_long(p_value->'notAfterEpochMillis',-2208988800000,253402300799999)||draft_token;
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_grant_prepare_intent(UUID,JSON) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_grant_prepare_intent(UUID,JSON) FROM PUBLIC,cfg_native_runtime;

CREATE POLICY cfg_native_institution_identity_lookup ON public.cfg_native_institution FOR SELECT TO cfg_native_guard USING(true);
CREATE POLICY cfg_native_issuer_identity_lookup ON public.cfg_native_issuer FOR SELECT TO cfg_native_guard USING(true);

CREATE FUNCTION public.cfg_native_prepare_grant(p_tenant UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;draft JSON;canonical TEXT;action UUID;target_action UUID;deadline BIGINT;existing JSONB;live JSONB;institution UUID;issuer UUID;head public.cfg_native_grant_head%ROWTYPE;grant_key UUID;proposal UUID;target JSON;target_text TEXT;target_digest TEXT;expected BIGINT;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_grant_prepare_intent(p_tenant,body);draft:=body->'draft';action:=(body->>'actionId')::uuid;target_action:=(body->>'targetActionId')::uuid;deadline:=(body->>'notAfterEpochMillis')::bigint;expected:=(draft->>'expectedGrantHeadVersion')::bigint;
    PERFORM public.cfg_native_require_context(p_tenant,action,'PREPARE',p_tenant,'INSTITUTION_AUTHORITY',canonical,true);
    IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM coalesce(draft->>'existingInstitutionId','') THEN RAISE EXCEPTION 'Exact institution preparation scope required' USING ERRCODE='42501'; END IF;
    IF expected=0 AND current_setting('firefly.cfg.platform_recovery',true) IS DISTINCT FROM 'true' THEN RAISE EXCEPTION 'Current platform bootstrap authority required' USING ERRCODE='42501'; END IF;
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    live:=public.cfg_native_lock_tenant(p_tenant,action);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    IF (live->>'eligibleNow')::boolean IS DISTINCT FROM true THEN RAISE EXCEPTION 'Existing eligible Tenant required' USING ERRCODE='55000'; END IF;
    institution:=(draft->>'existingInstitutionId')::uuid;issuer:=(draft->>'existingIssuerAuthorityId')::uuid;
    IF institution IS NOT NULL THEN
        PERFORM 1 FROM public.cfg_native_institution i WHERE i.institution_id=institution FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Retained institution identity required' USING ERRCODE='55000'; END IF;
    END IF;
    IF issuer IS NOT NULL THEN
        PERFORM 1 FROM public.cfg_native_issuer i WHERE i.institution_id=institution AND i.issuer_authority_id=issuer FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Exact retained issuer identity required' USING ERRCODE='55000'; END IF;
        SELECT * INTO head FROM public.cfg_native_grant_head h WHERE h.tenant_id=p_tenant AND h.institution_id=institution AND h.issuer_authority_id=issuer FOR SHARE;
    END IF;
    IF coalesce(head.version,0)<>expected THEN RAISE EXCEPTION 'Current grant head required' USING ERRCODE='55000'; END IF;
    IF institution IS NULL THEN
        institution:=gen_random_uuid();INSERT INTO public.cfg_native_institution(institution_id,allocation_tenant,allocation_action_id) VALUES(institution,p_tenant,action);
    END IF;
    IF issuer IS NULL THEN
        issuer:=gen_random_uuid();INSERT INTO public.cfg_native_issuer(issuer_authority_id,institution_id,allocation_tenant,allocation_action_id) VALUES(issuer,institution,p_tenant,action);
    END IF;
    PERFORM public.cfg_native_action_gate(p_tenant,action);
    PERFORM public.cfg_native_require_context(p_tenant,action,'PREPARE',p_tenant,'INSTITUTION_AUTHORITY',canonical,true);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    IF deadline<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Native grant preparation deadline elapsed' USING ERRCODE='42501'; END IF;
    IF EXISTS(SELECT 1 FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.target_action_id=target_action) OR EXISTS(SELECT 1 FROM public.cfg_native_action a WHERE a.tenant_id=p_tenant AND a.action_id=target_action) THEN RAISE EXCEPTION 'Unreserved target action required' USING ERRCODE='55000'; END IF;
    grant_key:=coalesce(head.grant_id,gen_random_uuid());proposal:=gen_random_uuid();
    target:=json_build_object('grant',json_build_object('actionId',target_action,'proposalId',proposal,'proposalRevision',1,'grantId',grant_key,'institutionId',institution,'issuerAuthorityId',issuer,'version',expected+1,'expectedGrantHeadVersion',expected,'uses',draft->'uses','effectiveFromEpochMillis',(draft->>'effectiveFromEpochMillis')::bigint,'effectiveToEpochMillis',(draft->>'effectiveToEpochMillis')::bigint,'reason',draft->>'reason','evidenceReferences',draft->'evidenceReferences'),'eligibilityEpoch',json_build_object('tenantGeneration',(live->>'tenantGeneration')::bigint,'statusId',(live->>'statusId')::uuid,'statusGeneration',(live->>'statusGeneration')::bigint));
    target_text:=public.cfg_native_grant_v2_intent(p_tenant,target);target_digest:=encode(sha256(convert_to(target_text,'UTF8')),'hex');
    INSERT INTO public.cfg_native_proposal(tenant_id,proposal_id,resource_id,institution_id,issuer_authority_id,target_operation,target_action_id,maker_issuer,maker_subject,maker_employee_id,prepare_action_id)
      VALUES(p_tenant,proposal,institution,institution,issuer,'GRANT_ISSUE',target_action,current_setting('firefly.cfg.actor_issuer'),current_setting('firefly.cfg.actor_subject'),current_setting('firefly.cfg.employee')::uuid,action);
    INSERT INTO public.cfg_native_proposal_revision(tenant_id,proposal_id,revision,target_encoding,original_prepare,original_target,canonical_target,target_digest,not_after_epoch_millis,tenant_generation,status_id,status_generation)
      VALUES(p_tenant,proposal,1,'FF_TENANT_INSTITUTION_GRANT_V2',p_original,convert_to(target::text,'UTF8'),target_text,target_digest,deadline,(live->>'tenantGeneration')::bigint,(live->>'statusId')::uuid,(live->>'statusGeneration')::bigint);
    INSERT INTO public.cfg_native_proposal_control(tenant_id,proposal_id,state,control_version) VALUES(p_tenant,proposal,'DRAFT',0);
    RETURN public.cfg_native_finish_action(p_tenant,action,'GRANT_PREPARE',p_tenant,proposal,1,p_original,canonical,deadline,'PREPARED',jsonb_build_object('proposalId',proposal,'target',target,'targetCanonical',target_text,'targetDigest',target_digest,'targetEncoding','FF_TENANT_INSTITUTION_GRANT_V2'));
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_prepare_grant(UUID,BYTEA) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_prepare_grant(UUID,BYTEA) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_prepare_grant(UUID,BYTEA) TO cfg_native_runtime;

CREATE FUNCTION public.cfg_native_grant_definition_intent(p_tenant UUID,p_target JSON,p_recorded BIGINT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    RETURN public.cfg_native_s('FF_TENANT_INSTITUTION_GRANT_DEFINITION_V2')||'R3:'||public.cfg_native_s(p_tenant::text)||public.cfg_native_s(public.cfg_native_grant_v2_intent(p_tenant,p_target))||public.cfg_native_long(to_json(p_recorded),-2208988800000,253402300799999);
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_grant_definition_intent(UUID,JSON,BIGINT) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_grant_definition_intent(UUID,JSON,BIGINT) FROM PUBLIC,cfg_native_runtime;

CREATE FUNCTION public.cfg_native_grant_view(p_tenant UUID,p_grant UUID,p_version BIGINT) RETURNS JSONB
LANGUAGE sql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
SELECT jsonb_build_object('tenantId',g.tenant_id,'target',convert_from(r.original_target,'UTF8')::jsonb,'definitionEncoding',g.definition_encoding,'canonicalDefinition',g.canonical_definition,'definitionDigest',g.definition_digest,'recordedAtEpochMillis',floor(extract(epoch FROM g.recorded_at)*1000)::bigint)
FROM public.cfg_native_grant_version g JOIN public.cfg_native_proposal_revision r ON r.tenant_id=g.tenant_id AND r.proposal_id=g.proposal_id AND r.revision=g.proposal_revision
WHERE g.tenant_id=p_tenant AND g.grant_id=p_grant AND g.version=p_version $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_grant_view(UUID,UUID,BIGINT) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_grant_view(UUID,UUID,BIGINT) FROM PUBLIC,cfg_native_runtime;

CREATE FUNCTION public.cfg_native_issue_grant_v2(p_tenant UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;grant_value JSON;epoch JSON;canonical TEXT;action UUID;proposal_key UUID;proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;existing JSONB;live JSONB;recorded TIMESTAMPTZ;recorded_millis BIGINT;definition TEXT;grant_key UUID;version_value BIGINT;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_grant_v2_intent(p_tenant,body);grant_value:=body->'grant';epoch:=body->'eligibilityEpoch';action:=(grant_value->>'actionId')::uuid;proposal_key:=(grant_value->>'proposalId')::uuid;grant_key:=(grant_value->>'grantId')::uuid;version_value:=(grant_value->>'version')::bigint;
    PERFORM public.cfg_native_require_context(p_tenant,action,'GRANT_ISSUE',proposal_key,'PROPOSAL',canonical,false);
    IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM grant_value->>'institutionId' THEN RAISE EXCEPTION 'Exact grant institution scope required' USING ERRCODE='42501'; END IF;
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    PERFORM public.cfg_native_lock_proposal_target(p_tenant,proposal_key,action);live:=public.cfg_native_tenant_view(p_tenant);
    SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.proposal_id=proposal_key;
    SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=p_tenant AND r.proposal_id=proposal_key AND r.revision=(grant_value->>'proposalRevision')::bigint;
    SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=p_tenant AND c.proposal_id=proposal_key FOR UPDATE;
    PERFORM public.cfg_native_action_gate(p_tenant,action);
    PERFORM public.cfg_native_require_context(p_tenant,action,'GRANT_ISSUE',proposal_key,'PROPOSAL',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    IF proposal.target_operation IS DISTINCT FROM 'GRANT_ISSUE' OR proposal.target_action_id IS DISTINCT FROM action OR revision.canonical_target IS DISTINCT FROM canonical
      OR proposal.institution_id IS DISTINCT FROM (grant_value->>'institutionId')::uuid OR proposal.issuer_authority_id IS DISTINCT FROM (grant_value->>'issuerAuthorityId')::uuid
      OR control.state IS DISTINCT FROM 'APPROVED' OR control.execution_action_id IS NOT NULL
      OR (live->>'eligibleNow')::boolean IS DISTINCT FROM true OR (epoch->>'tenantGeneration')::bigint IS DISTINCT FROM (live->>'tenantGeneration')::bigint
      OR (epoch->>'statusId')::uuid IS DISTINCT FROM (live->>'statusId')::uuid OR (epoch->>'statusGeneration')::bigint IS DISTINCT FROM (live->>'statusGeneration')::bigint
      OR revision.not_after_epoch_millis<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint
    THEN RAISE EXCEPTION 'Exact independently approved current grant target required' USING ERRCODE='55000'; END IF;
    recorded:=clock_timestamp();recorded_millis:=floor(extract(epoch FROM recorded)*1000)::bigint;
    IF (grant_value->>'effectiveToEpochMillis')::bigint<=recorded_millis THEN RAISE EXCEPTION 'Unexpired grant effective range required' USING ERRCODE='55000'; END IF;
    definition:=public.cfg_native_grant_definition_intent(p_tenant,body,recorded_millis);
    INSERT INTO public.cfg_native_grant_version(tenant_id,grant_id,version,institution_id,issuer_authority_id,uses,effective_from_epoch_millis,effective_to_epoch_millis,tenant_generation,status_id,status_generation,proposal_id,proposal_revision,issue_action_id,definition_encoding,canonical_definition,definition_digest,recorded_at)
      VALUES(p_tenant,grant_key,version_value,(grant_value->>'institutionId')::uuid,(grant_value->>'issuerAuthorityId')::uuid,ARRAY(SELECT json_array_elements_text(grant_value->'uses')),(grant_value->>'effectiveFromEpochMillis')::bigint,(grant_value->>'effectiveToEpochMillis')::bigint,(epoch->>'tenantGeneration')::bigint,(epoch->>'statusId')::uuid,(epoch->>'statusGeneration')::bigint,proposal_key,revision.revision,action,'FF_TENANT_INSTITUTION_GRANT_DEFINITION_V2',definition,encode(sha256(convert_to(definition,'UTF8')),'hex'),recorded);
    IF (grant_value->>'expectedGrantHeadVersion')::bigint=0 THEN
        INSERT INTO public.cfg_native_grant_head(tenant_id,institution_id,issuer_authority_id,grant_id,version,head_version)
          VALUES(p_tenant,(grant_value->>'institutionId')::uuid,(grant_value->>'issuerAuthorityId')::uuid,grant_key,version_value,version_value);
    ELSE
        UPDATE public.cfg_native_grant_head SET version=version_value,head_version=version_value
          WHERE tenant_id=p_tenant AND institution_id=(grant_value->>'institutionId')::uuid AND issuer_authority_id=(grant_value->>'issuerAuthorityId')::uuid AND grant_id=grant_key AND version=(grant_value->>'expectedGrantHeadVersion')::bigint;
        IF NOT FOUND THEN RAISE EXCEPTION 'Exact next grant head required' USING ERRCODE='55000'; END IF;
    END IF;
    INSERT INTO public.cfg_native_grant_control(tenant_id,grant_id,version,state,control_version,last_action_id) VALUES(p_tenant,grant_key,version_value,'ACTIVE',0,action);
    UPDATE public.cfg_native_proposal_control SET state='EXECUTION_ADMITTED',control_version=control.control_version+1,execution_action_id=action WHERE tenant_id=p_tenant AND proposal_id=proposal_key;
    RETURN public.cfg_native_finish_action(p_tenant,action,'GRANT_ISSUE',proposal_key,proposal_key,revision.revision,p_original,canonical,revision.not_after_epoch_millis,'APPLIED',public.cfg_native_grant_view(p_tenant,grant_key,version_value));
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_issue_grant_v2(UUID,BYTEA) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_issue_grant_v2(UUID,BYTEA) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_issue_grant_v2(UUID,BYTEA) TO cfg_native_runtime;

CREATE FUNCTION public.cfg_native_observe_grant(p_tenant UUID,p_institution UUID,p_issuer UUID,p_use TEXT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE canonical TEXT;action UUID;head public.cfg_native_grant_head%ROWTYPE;grant_value public.cfg_native_grant_version%ROWTYPE;control public.cfg_native_grant_control%ROWTYPE;live JSONB;definition JSONB;observed BIGINT;valid_until BIGINT;reason TEXT;state TEXT;
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_tenant::text));PERFORM public.cfg_native_uuid(to_json(p_institution::text));PERFORM public.cfg_native_uuid(to_json(p_issuer::text));
    IF p_use IS NULL OR p_use NOT IN ('ACCOUNTING_BOOK','CASH_OPERATIONS','PRODUCT_ISSUANCE') THEN PERFORM public.cfg_native_invalid(); END IF;
    canonical:=public.cfg_native_s('FF_TENANT_INSTITUTION_GRANT_OBSERVE_V2')||'R4:'||public.cfg_native_s(p_tenant::text)||public.cfg_native_s(p_institution::text)||public.cfg_native_s(p_issuer::text)||public.cfg_native_s(p_use);action:=nullif(current_setting('firefly.cfg.action',true),'')::uuid;
    PERFORM public.cfg_native_require_context(p_tenant,action,'OBSERVE',p_issuer,'GRANT',canonical,false);
    IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM p_institution::text THEN RAISE EXCEPTION 'Exact observation institution scope required' USING ERRCODE='42501'; END IF;
    SELECT * INTO head FROM public.cfg_native_grant_head h WHERE h.tenant_id=p_tenant AND h.institution_id=p_institution AND h.issuer_authority_id=p_issuer;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('tenantId',p_tenant,'institutionId',p_institution,'issuerAuthorityId',p_issuer,'requestedUse',p_use,'state','NO_CURRENT_AUTHORITY','definition',NULL,'control',NULL,'headVersion',NULL,'tenantEligibility',public.cfg_native_tenant_view(p_tenant),'observedAtEpochMillis',floor(extract(epoch FROM clock_timestamp())*1000)::bigint,'validUntilEpochMillis',NULL,'denialReason','NO_GRANT');
    END IF;
    PERFORM public.cfg_native_lock_tenant(p_tenant,action);live:=public.cfg_native_tenant_view(p_tenant);
    SELECT * INTO head FROM public.cfg_native_grant_head h WHERE h.tenant_id=p_tenant AND h.institution_id=p_institution AND h.issuer_authority_id=p_issuer FOR SHARE;
    SELECT * INTO grant_value FROM public.cfg_native_grant_version g WHERE g.tenant_id=p_tenant AND g.grant_id=head.grant_id AND g.version=head.version;
    SELECT * INTO control FROM public.cfg_native_grant_control c WHERE c.tenant_id=p_tenant AND c.grant_id=head.grant_id AND c.version=head.version FOR SHARE;
    PERFORM public.cfg_native_require_context(p_tenant,action,'OBSERVE',p_issuer,'GRANT',canonical,false);
    IF grant_value.grant_id IS NULL OR control.grant_id IS NULL THEN RAISE EXCEPTION 'Complete native grant state required' USING ERRCODE='23514'; END IF;
    definition:=public.cfg_native_grant_view(p_tenant,head.grant_id,head.version);observed:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
    IF (live->>'eligibleNow')::boolean IS DISTINCT FROM true THEN reason:='TENANT_DISABLED';
    ELSIF grant_value.tenant_generation IS DISTINCT FROM (live->>'tenantGeneration')::bigint OR grant_value.status_id IS DISTINCT FROM (live->>'statusId')::uuid OR grant_value.status_generation IS DISTINCT FROM (live->>'statusGeneration')::bigint THEN reason:='ELIGIBILITY_CHANGED';
    ELSIF control.state<>'ACTIVE' THEN reason:='GRANT_'||control.state;
    ELSIF NOT p_use=ANY(grant_value.uses) THEN reason:='USE_NOT_GRANTED';
    ELSIF observed<grant_value.effective_from_epoch_millis OR observed>=grant_value.effective_to_epoch_millis THEN reason:='NOT_EFFECTIVE';
    END IF;
    IF reason IS NULL THEN valid_until:=least(observed+30000,grant_value.effective_to_epoch_millis,current_setting('firefly.cfg.authority_until')::bigint,current_setting('firefly.cfg.command_until')::bigint);state:='CURRENT_AUTHORIZED';ELSE state:='NO_CURRENT_AUTHORITY';END IF;
    IF NOT public.cfg_native_context_current(p_tenant) THEN RAISE EXCEPTION 'Current observation authority expired' USING ERRCODE='42501'; END IF;
    RETURN jsonb_build_object('tenantId',p_tenant,'institutionId',p_institution,'issuerAuthorityId',p_issuer,'requestedUse',p_use,'state',state,'definition',definition,'control',jsonb_build_object('state',control.state,'controlVersion',control.control_version,'lastActionId',control.last_action_id),'headVersion',head.head_version,'tenantEligibility',live,'observedAtEpochMillis',observed,'validUntilEpochMillis',valid_until,'denialReason',reason);
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_observe_grant(UUID,UUID,UUID,TEXT) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_observe_grant(UUID,UUID,UUID,TEXT) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_observe_grant(UUID,UUID,UUID,TEXT) TO cfg_native_runtime;

CREATE FUNCTION public.cfg_native_read_grant_history(p_tenant UUID,p_institution UUID,p_issuer UUID,p_grant UUID,p_version BIGINT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE canonical TEXT;action UUID;definition JSONB;
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_tenant::text));PERFORM public.cfg_native_uuid(to_json(p_institution::text));PERFORM public.cfg_native_uuid(to_json(p_issuer::text));PERFORM public.cfg_native_uuid(to_json(p_grant::text));PERFORM public.cfg_native_long(to_json(p_version),1,9223372036854775807);
    canonical:=public.cfg_native_s('FF_TENANT_INSTITUTION_GRANT_HISTORY_V2')||'R5:'||public.cfg_native_s(p_tenant::text)||public.cfg_native_s(p_institution::text)||public.cfg_native_s(p_issuer::text)||public.cfg_native_s(p_grant::text)||public.cfg_native_long(to_json(p_version),1,9223372036854775807);action:=nullif(current_setting('firefly.cfg.action',true),'')::uuid;
    PERFORM public.cfg_native_require_context(p_tenant,action,'HISTORY',p_grant,'GRANT',canonical,false);
    IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM p_institution::text THEN RAISE EXCEPTION 'Exact historical institution scope required' USING ERRCODE='42501'; END IF;
    PERFORM 1 FROM public.cfg_native_grant_version g WHERE g.tenant_id=p_tenant AND g.institution_id=p_institution AND g.issuer_authority_id=p_issuer AND g.grant_id=p_grant AND g.version=p_version;
    IF NOT FOUND THEN RAISE EXCEPTION 'Historical grant version not found' USING ERRCODE='P0002'; END IF;
    definition:=public.cfg_native_grant_view(p_tenant,p_grant,p_version);
    IF NOT public.cfg_native_context_current(p_tenant) THEN RAISE EXCEPTION 'Current historical-read authority expired' USING ERRCODE='42501'; END IF;
    RETURN jsonb_build_object('definition',definition,'historicalOnly',true);
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_read_grant_history(UUID,UUID,UUID,UUID,BIGINT) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_read_grant_history(UUID,UUID,UUID,UUID,BIGINT) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_read_grant_history(UUID,UUID,UUID,UUID,BIGINT) TO cfg_native_runtime;

CREATE FUNCTION public.cfg_native_control_states(p_version JSON,p_before JSON,p_after JSON) RETURNS VOID
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    PERFORM public.cfg_native_long(p_version,0,9223372036854775806);
    PERFORM public.cfg_native_string(p_before,9);PERFORM public.cfg_native_string(p_after,9);
    IF (p_before#>>'{}') NOT IN ('ACTIVE','SUSPENDED') OR (p_after#>>'{}') NOT IN ('ACTIVE','SUSPENDED','REVOKED') OR (p_before#>>'{}')=(p_after#>>'{}') THEN PERFORM public.cfg_native_invalid(); END IF;
END $$;
CREATE FUNCTION public.cfg_native_grant_control_intent(p_tenant UUID,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_tenant::text));
    PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','proposalId','proposalRevision','grantId','version','institutionId','issuerAuthorityId','expectedControlVersion','expectedState','targetState','expectedEpoch','reason','evidenceReferences','notAfterEpochMillis']);
    PERFORM public.cfg_native_control_states(p_value->'expectedControlVersion',p_value->'expectedState',p_value->'targetState');
    RETURN public.cfg_native_s('FF_INSTITUTION_GRANT_CONTROL_V2')||'R2:'||public.cfg_native_s(p_tenant::text)||'R14:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'proposalId')||public.cfg_native_long(p_value->'proposalRevision',1,9223372036854775807)||public.cfg_native_uuid(p_value->'grantId')||public.cfg_native_long(p_value->'version',1,9223372036854775807)||public.cfg_native_uuid(p_value->'institutionId')||public.cfg_native_uuid(p_value->'issuerAuthorityId')||public.cfg_native_long(p_value->'expectedControlVersion',0,9223372036854775806)||public.cfg_native_string(p_value->'expectedState',9)||public.cfg_native_string(p_value->'targetState',9)||public.cfg_native_epoch_intent(p_value->'expectedEpoch')||public.cfg_native_string(p_value->'reason',500)||public.cfg_native_evidence(p_value->'evidenceReferences')||public.cfg_native_long(p_value->'notAfterEpochMillis',-2208988800000,253402300799999);
END $$;
CREATE FUNCTION public.cfg_native_grant_control_prepare_intent(p_tenant UUID,p_institution UUID,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE draft JSON;
BEGIN
    PERFORM public.cfg_native_uuid(to_json(p_tenant::text));PERFORM public.cfg_native_uuid(to_json(p_institution::text));
    PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','targetActionId','notAfterEpochMillis','draft']);draft:=p_value->'draft';
    PERFORM public.cfg_native_shape(draft,ARRAY['grantId','version','issuerAuthorityId','expectedControlVersion','expectedState','targetState','reason','evidenceReferences']);
    PERFORM public.cfg_native_control_states(draft->'expectedControlVersion',draft->'expectedState',draft->'targetState');
    IF p_value->>'actionId'=p_value->>'targetActionId' THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN public.cfg_native_s('FF_INSTITUTION_GRANT_CONTROL_PREPARE_V2')||'R3:'||public.cfg_native_s(p_tenant::text)||public.cfg_native_s(p_institution::text)||'R4:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'targetActionId')||public.cfg_native_long(p_value->'notAfterEpochMillis',-2208988800000,253402300799999)||'R8:'||public.cfg_native_uuid(draft->'grantId')||public.cfg_native_long(draft->'version',1,9223372036854775807)||public.cfg_native_uuid(draft->'issuerAuthorityId')||public.cfg_native_long(draft->'expectedControlVersion',0,9223372036854775806)||public.cfg_native_string(draft->'expectedState',9)||public.cfg_native_string(draft->'targetState',9)||public.cfg_native_string(draft->'reason',500)||public.cfg_native_evidence(draft->'evidenceReferences');
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_control_states(JSON,JSON,JSON) OWNER TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_grant_control_intent(UUID,JSON) OWNER TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_grant_control_prepare_intent(UUID,UUID,JSON) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_control_states(JSON,JSON,JSON),public.cfg_native_grant_control_intent(UUID,JSON),public.cfg_native_grant_control_prepare_intent(UUID,UUID,JSON) FROM PUBLIC,cfg_native_runtime;

CREATE FUNCTION public.cfg_native_lock_grant_control(p_tenant UUID,p_institution UUID,p_issuer UUID,p_grant UUID,p_version BIGINT,p_action UUID,p_expected_control BIGINT,p_expected_state TEXT,p_target_state TEXT,p_epoch JSON DEFAULT NULL) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE grant_value public.cfg_native_grant_version%ROWTYPE;control public.cfg_native_grant_control%ROWTYPE;head public.cfg_native_grant_head%ROWTYPE;live JSONB;
BEGIN
    SELECT * INTO grant_value FROM public.cfg_native_grant_version g WHERE g.tenant_id=p_tenant AND g.institution_id=p_institution AND g.issuer_authority_id=p_issuer AND g.grant_id=p_grant AND g.version=p_version;
    IF NOT FOUND THEN RAISE EXCEPTION 'Exact retained grant version required' USING ERRCODE='55000'; END IF;
    PERFORM public.cfg_native_lock_tenant(p_tenant,p_action);live:=public.cfg_native_tenant_view(p_tenant);
    PERFORM 1 FROM public.cfg_native_institution i WHERE i.institution_id=p_institution FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Retained institution identity required' USING ERRCODE='23514'; END IF;
    PERFORM 1 FROM public.cfg_native_issuer i WHERE i.institution_id=p_institution AND i.issuer_authority_id=p_issuer FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Retained issuer identity required' USING ERRCODE='23514'; END IF;
    IF p_target_state='ACTIVE' THEN
        SELECT * INTO head FROM public.cfg_native_grant_head h WHERE h.tenant_id=p_tenant AND h.institution_id=p_institution AND h.issuer_authority_id=p_issuer FOR SHARE;
        IF head.grant_id IS DISTINCT FROM p_grant OR head.version IS DISTINCT FROM p_version OR (live->>'eligibleNow')::boolean IS DISTINCT FROM true
          OR grant_value.tenant_generation IS DISTINCT FROM (live->>'tenantGeneration')::bigint OR grant_value.status_id IS DISTINCT FROM (live->>'statusId')::uuid OR grant_value.status_generation IS DISTINCT FROM (live->>'statusGeneration')::bigint
        THEN RAISE EXCEPTION 'Fresh admission required for changed grant eligibility' USING ERRCODE='55000'; END IF;
    END IF;
    SELECT * INTO control FROM public.cfg_native_grant_control c WHERE c.tenant_id=p_tenant AND c.grant_id=p_grant AND c.version=p_version FOR UPDATE;
    IF control.control_version IS DISTINCT FROM p_expected_control OR control.state IS DISTINCT FROM p_expected_state OR control.state='REVOKED' OR p_expected_state=p_target_state THEN RAISE EXCEPTION 'Current exact grant control required' USING ERRCODE='55000'; END IF;
    IF p_epoch IS NOT NULL AND ((p_epoch->>'tenantGeneration')::bigint IS DISTINCT FROM (live->>'tenantGeneration')::bigint OR (p_epoch->>'statusId')::uuid IS DISTINCT FROM (live->>'statusId')::uuid OR (p_epoch->>'statusGeneration')::bigint IS DISTINCT FROM (live->>'statusGeneration')::bigint) THEN RAISE EXCEPTION 'Approved grant control epoch changed' USING ERRCODE='55000'; END IF;
    IF NOT public.cfg_native_context_current(p_tenant) THEN RAISE EXCEPTION 'Current grant control authority expired' USING ERRCODE='42501'; END IF;
    RETURN live;
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_lock_grant_control(UUID,UUID,UUID,UUID,BIGINT,UUID,BIGINT,TEXT,TEXT,JSON) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_lock_grant_control(UUID,UUID,UUID,UUID,BIGINT,UUID,BIGINT,TEXT,TEXT,JSON) FROM PUBLIC,cfg_native_runtime;

CREATE FUNCTION public.cfg_native_prepare_grant_control(p_tenant UUID,p_institution UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;draft JSON;canonical TEXT;action UUID;target_action UUID;deadline BIGINT;grant_key UUID;version_value BIGINT;issuer UUID;existing JSONB;live JSONB;proposal UUID;target JSON;target_text TEXT;target_digest TEXT;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_grant_control_prepare_intent(p_tenant,p_institution,body);draft:=body->'draft';action:=(body->>'actionId')::uuid;target_action:=(body->>'targetActionId')::uuid;deadline:=(body->>'notAfterEpochMillis')::bigint;grant_key:=(draft->>'grantId')::uuid;version_value:=(draft->>'version')::bigint;issuer:=(draft->>'issuerAuthorityId')::uuid;
    PERFORM public.cfg_native_require_context(p_tenant,action,'PREPARE',grant_key,'GRANT',canonical,true);
    IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM p_institution::text THEN RAISE EXCEPTION 'Exact grant-control institution required' USING ERRCODE='42501'; END IF;
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    live:=public.cfg_native_lock_grant_control(p_tenant,p_institution,issuer,grant_key,version_value,action,(draft->>'expectedControlVersion')::bigint,draft->>'expectedState',draft->>'targetState');
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    PERFORM public.cfg_native_action_gate(p_tenant,action);
    PERFORM public.cfg_native_require_context(p_tenant,action,'PREPARE',grant_key,'GRANT',canonical,true);
    IF deadline<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Grant-control preparation deadline elapsed' USING ERRCODE='42501'; END IF;
    IF EXISTS(SELECT 1 FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.target_action_id=target_action) OR EXISTS(SELECT 1 FROM public.cfg_native_action a WHERE a.tenant_id=p_tenant AND a.action_id=target_action) THEN RAISE EXCEPTION 'Unreserved target action required' USING ERRCODE='55000'; END IF;
    proposal:=gen_random_uuid();target:=json_build_object('actionId',target_action,'proposalId',proposal,'proposalRevision',1,'grantId',grant_key,'version',version_value,'institutionId',p_institution,'issuerAuthorityId',issuer,'expectedControlVersion',(draft->>'expectedControlVersion')::bigint,'expectedState',draft->>'expectedState','targetState',draft->>'targetState','expectedEpoch',json_build_object('tenantGeneration',(live->>'tenantGeneration')::bigint,'statusId',(live->>'statusId')::uuid,'statusGeneration',(live->>'statusGeneration')::bigint),'reason',draft->>'reason','evidenceReferences',draft->'evidenceReferences','notAfterEpochMillis',deadline);
    target_text:=public.cfg_native_grant_control_intent(p_tenant,target);target_digest:=encode(sha256(convert_to(target_text,'UTF8')),'hex');
    INSERT INTO public.cfg_native_proposal(tenant_id,proposal_id,resource_id,institution_id,issuer_authority_id,target_operation,target_action_id,maker_issuer,maker_subject,maker_employee_id,prepare_action_id)
      VALUES(p_tenant,proposal,grant_key,p_institution,issuer,'GRANT_CONTROL',target_action,current_setting('firefly.cfg.actor_issuer'),current_setting('firefly.cfg.actor_subject'),current_setting('firefly.cfg.employee')::uuid,action);
    INSERT INTO public.cfg_native_proposal_revision(tenant_id,proposal_id,revision,target_encoding,original_prepare,original_target,canonical_target,target_digest,not_after_epoch_millis,tenant_generation,status_id,status_generation)
      VALUES(p_tenant,proposal,1,'FF_INSTITUTION_GRANT_CONTROL_V2',p_original,convert_to(target::text,'UTF8'),target_text,target_digest,deadline,(live->>'tenantGeneration')::bigint,(live->>'statusId')::uuid,(live->>'statusGeneration')::bigint);
    INSERT INTO public.cfg_native_proposal_control(tenant_id,proposal_id,state,control_version) VALUES(p_tenant,proposal,'DRAFT',0);
    RETURN public.cfg_native_finish_action(p_tenant,action,'GRANT_CONTROL_PREPARE',grant_key,proposal,1,p_original,canonical,deadline,'PREPARED',jsonb_build_object('proposalId',proposal,'target',target,'targetCanonical',target_text,'targetDigest',target_digest,'targetEncoding','FF_INSTITUTION_GRANT_CONTROL_V2'));
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_prepare_grant_control(UUID,UUID,BYTEA) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_prepare_grant_control(UUID,UUID,BYTEA) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_prepare_grant_control(UUID,UUID,BYTEA) TO cfg_native_runtime;

CREATE FUNCTION public.cfg_native_control_grant_v2(p_tenant UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;canonical TEXT;action UUID;proposal_key UUID;grant_key UUID;version_value BIGINT;deadline BIGINT;existing JSONB;proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;after_state JSONB;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_grant_control_intent(p_tenant,body);action:=(body->>'actionId')::uuid;proposal_key:=(body->>'proposalId')::uuid;grant_key:=(body->>'grantId')::uuid;version_value:=(body->>'version')::bigint;deadline:=(body->>'notAfterEpochMillis')::bigint;
    PERFORM public.cfg_native_require_context(p_tenant,action,'GRANT_CONTROL',grant_key,'GRANT',canonical,false);
    IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM body->>'institutionId' THEN RAISE EXCEPTION 'Exact grant-control institution scope required' USING ERRCODE='42501'; END IF;
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    PERFORM public.cfg_native_lock_proposal_target(p_tenant,proposal_key,action);
    SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.proposal_id=proposal_key;
    SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=p_tenant AND r.proposal_id=proposal_key AND r.revision=(body->>'proposalRevision')::bigint;
    SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=p_tenant AND c.proposal_id=proposal_key FOR UPDATE;
    PERFORM public.cfg_native_action_gate(p_tenant,action);PERFORM public.cfg_native_require_context(p_tenant,action,'GRANT_CONTROL',grant_key,'GRANT',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,action,canonical);IF existing IS NOT NULL THEN RETURN existing; END IF;
    IF proposal.target_operation IS DISTINCT FROM 'GRANT_CONTROL' OR proposal.resource_id IS DISTINCT FROM grant_key OR proposal.institution_id IS DISTINCT FROM (body->>'institutionId')::uuid OR proposal.issuer_authority_id IS DISTINCT FROM (body->>'issuerAuthorityId')::uuid OR proposal.target_action_id IS DISTINCT FROM action
      OR revision.canonical_target IS DISTINCT FROM canonical OR control.state IS DISTINCT FROM 'APPROVED' OR control.execution_action_id IS NOT NULL OR deadline<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Exact approved current grant-control target required' USING ERRCODE='55000'; END IF;
    UPDATE public.cfg_native_grant_control SET state=body->>'targetState',control_version=control_version+1,last_action_id=action
      WHERE tenant_id=p_tenant AND grant_id=grant_key AND version=version_value AND control_version=(body->>'expectedControlVersion')::bigint AND state=body->>'expectedState';
    IF NOT FOUND THEN RAISE EXCEPTION 'Exact grant-control compare-and-set required' USING ERRCODE='55000'; END IF;
    INSERT INTO public.cfg_native_grant_control_event(tenant_id,action_id,grant_id,version,old_control_version,new_control_version,old_state,new_state)
      VALUES(p_tenant,action,grant_key,version_value,(body->>'expectedControlVersion')::bigint,(body->>'expectedControlVersion')::bigint+1,body->>'expectedState',body->>'targetState');
    UPDATE public.cfg_native_proposal_control SET state='EXECUTION_ADMITTED',control_version=control.control_version+1,execution_action_id=action WHERE tenant_id=p_tenant AND proposal_id=proposal_key;
    after_state:=jsonb_build_object('state',body->>'targetState','controlVersion',(body->>'expectedControlVersion')::bigint+1,'lastActionId',action);
    RETURN public.cfg_native_finish_action(p_tenant,action,'GRANT_CONTROL',grant_key,proposal_key,revision.revision,p_original,canonical,deadline,'APPLIED',after_state);
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_control_grant_v2(UUID,BYTEA) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_control_grant_v2(UUID,BYTEA) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_control_grant_v2(UUID,BYTEA) TO cfg_native_runtime;

CREATE FUNCTION public.cfg_native_reconcile_intent(p_tenant UUID,p_action UUID,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE operation TEXT;encoded TEXT;original BYTEA;target JSON;canonical TEXT;target_action UUID;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['operation','originalBase64']);operation:=p_value->>'operation';
    IF operation IS NULL OR operation NOT IN ('GRANT_ISSUE','GRANT_CONTROL') THEN PERFORM public.cfg_native_invalid(); END IF;
    encoded:=p_value->>'originalBase64';IF encoded IS NULL OR json_typeof(p_value->'originalBase64')<>'string' THEN PERFORM public.cfg_native_invalid(); END IF;
    original:=decode(encoded,'base64');IF replace(encode(original,'base64'),E'\n','')<>encoded OR octet_length(original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
    target:=convert_from(original,'UTF8')::json;
    IF operation='GRANT_ISSUE' THEN canonical:=public.cfg_native_grant_v2_intent(p_tenant,target);target_action:=(target->'grant'->>'actionId')::uuid;
    ELSE canonical:=public.cfg_native_grant_control_intent(p_tenant,target);target_action:=(target->>'actionId')::uuid;END IF;
    IF target_action IS DISTINCT FROM p_action THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN public.cfg_native_s('FF_NATIVE_GRANT_ACTION_RECONCILE_V2')||'R5:'||public.cfg_native_s(p_tenant::text)||public.cfg_native_s(p_action::text)||public.cfg_native_s(operation)||public.cfg_native_s(encoded)||public.cfg_native_s(canonical);
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_reconcile_intent(UUID,UUID,JSON) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_reconcile_intent(UUID,UUID,JSON) FROM PUBLIC,cfg_native_runtime;

CREATE FUNCTION public.cfg_native_reconcile_grant(p_tenant UUID,p_action UUID,p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE body JSON;target JSON;grant_value JSON;canonical TEXT;original BYTEA;target_canonical TEXT;operation TEXT;proposal_key UUID;resource UUID;institution UUID;revision_number BIGINT;candidate UUID;parent_after UUID;existing JSONB;proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;
BEGIN
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 98304 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::json;canonical:=public.cfg_native_reconcile_intent(p_tenant,p_action,body);
    PERFORM public.cfg_native_require_context(p_tenant,p_action,'RECONCILE',p_action,'ACTION',canonical,false);
    original:=decode(body->>'originalBase64','base64');target:=convert_from(original,'UTF8')::json;operation:=body->>'operation';
    IF operation='GRANT_ISSUE' THEN grant_value:=target->'grant';resource:=(grant_value->>'proposalId')::uuid;target_canonical:=public.cfg_native_grant_v2_intent(p_tenant,target);
    ELSE grant_value:=target;resource:=(target->>'grantId')::uuid;target_canonical:=public.cfg_native_grant_control_intent(p_tenant,target);END IF;
    proposal_key:=(grant_value->>'proposalId')::uuid;revision_number:=(grant_value->>'proposalRevision')::bigint;institution:=(grant_value->>'institutionId')::uuid;
    IF current_setting('firefly.cfg.institution',true) IS DISTINCT FROM institution::text THEN RAISE EXCEPTION 'Exact reconciliation institution required' USING ERRCODE='42501'; END IF;
    existing:=public.cfg_native_existing_action(p_tenant,p_action,target_canonical);IF existing IS NOT NULL THEN RETURN existing;END IF;
    SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_tenant AND p.proposal_id=proposal_key;
    SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=p_tenant AND r.proposal_id=proposal_key AND r.revision=revision_number;
    IF proposal.target_action_id IS DISTINCT FROM p_action OR proposal.target_operation IS DISTINCT FROM operation OR proposal.resource_id IS DISTINCT FROM (CASE WHEN operation='GRANT_ISSUE' THEN institution ELSE resource END)
      OR proposal.institution_id IS DISTINCT FROM institution OR proposal.issuer_authority_id IS DISTINCT FROM (grant_value->>'issuerAuthorityId')::uuid OR revision.canonical_target IS DISTINCT FROM target_canonical
    THEN RAISE EXCEPTION 'Exact retained reconciliation target required' USING ERRCODE='55000'; END IF;
    -- Reconciliation locks existing parents without registering a new eligibility generation.
    SELECT t.tenant_status_id INTO candidate FROM public.tenants t WHERE t.id=p_tenant;
    IF NOT FOUND THEN PERFORM public.cfg_native_invalid();END IF;
    PERFORM 1 FROM public.tenant_statuses s WHERE s.id=candidate FOR SHARE;
    IF NOT FOUND THEN PERFORM public.cfg_native_invalid();END IF;
    SELECT t.tenant_status_id INTO parent_after FROM public.tenants t WHERE t.id=p_tenant FOR SHARE;
    IF parent_after IS DISTINCT FROM candidate THEN RAISE EXCEPTION 'Tenant parent changed; retry whole transaction' USING ERRCODE='40001';END IF;
    SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=p_tenant AND c.proposal_id=proposal_key FOR UPDATE;
    PERFORM public.cfg_native_action_gate(p_tenant,p_action);
    PERFORM public.cfg_native_require_context(p_tenant,p_action,'RECONCILE',p_action,'ACTION',canonical,false);
    existing:=public.cfg_native_existing_action(p_tenant,p_action,target_canonical);IF existing IS NOT NULL THEN RETURN existing;END IF;
    IF revision.not_after_epoch_millis>floor(extract(epoch FROM clock_timestamp())*1000)::bigint OR control.state IS NULL OR control.state NOT IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','WITHDRAWN') OR control.execution_action_id IS NOT NULL THEN RAISE EXCEPTION 'Elapsed retained admission deadline required for negative fence' USING ERRCODE='55000';END IF;
    IF control.state IN ('DRAFT','SUBMITTED','APPROVED') THEN UPDATE public.cfg_native_proposal_control SET state='NO_EFFECT_FINAL',control_version=control.control_version+1 WHERE tenant_id=p_tenant AND proposal_id=proposal_key AND control_version=control.control_version;END IF;
    RETURN public.cfg_native_finish_action(p_tenant,p_action,operation,resource,proposal_key,revision_number,original,target_canonical,revision.not_after_epoch_millis,'NO_EFFECT_FINAL',jsonb_build_object('state','NO_EFFECT_FINAL','reason','ADMISSION_DEADLINE_ELAPSED'));
END $$;
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER FUNCTION public.cfg_native_reconcile_grant(UUID,UUID,BYTEA) OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON FUNCTION public.cfg_native_reconcile_grant(UUID,UUID,BYTEA) FROM PUBLIC,cfg_native_runtime;
GRANT EXECUTE ON FUNCTION public.cfg_native_reconcile_grant(UUID,UUID,BYTEA) TO cfg_native_runtime;

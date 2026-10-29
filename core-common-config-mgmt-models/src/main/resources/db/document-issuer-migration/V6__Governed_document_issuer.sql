-- BEGIN 00-installation-contract.sql
DO $installation$
DECLARE guard_row RECORD; installer RECORD;
BEGIN
    IF current_user<>session_user OR (current_setting('role',TRUE) IS DISTINCT FROM 'none'
            AND current_setting('role',TRUE) IS DISTINCT FROM session_user::TEXT)
            OR current_setting('server_encoding')<>'UTF8' THEN
        RAISE EXCEPTION 'Restricted document installer identity required' USING ERRCODE='42501';
    END IF;
    SELECT * INTO guard_row FROM pg_roles WHERE rolname='cfg_document_issuer_guard';
    IF NOT FOUND OR guard_row.rolcanlogin OR guard_row.rolinherit OR guard_row.rolsuper OR guard_row.rolbypassrls
            OR guard_row.rolcreatedb OR guard_row.rolcreaterole OR guard_row.rolreplication
            OR EXISTS(SELECT 1 FROM pg_auth_members WHERE member=guard_row.oid)
            OR has_schema_privilege(guard_row.oid,'public','CREATE')
            OR has_database_privilege(guard_row.oid,current_database(),'CREATE,TEMPORARY') THEN
        RAISE EXCEPTION 'Protected document guard required' USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='cfg_document_issuer_private' AND nspowner=guard_row.oid)
            OR NOT has_schema_privilege(guard_row.oid,'public','USAGE') THEN
        RAISE EXCEPTION 'Prepared document private schema required' USING ERRCODE='42501';
    END IF;
    SELECT * INTO installer FROM pg_roles WHERE rolname=current_user;
    IF NOT installer.rolcanlogin OR installer.rolsuper OR installer.rolbypassrls OR installer.rolcreatedb
            OR installer.rolcreaterole OR installer.rolreplication
            OR NOT has_schema_privilege(installer.oid,'public','CREATE WITH GRANT OPTION')
            OR has_database_privilege(installer.oid,current_database(),'CREATE,TEMPORARY')
            OR (SELECT count(*) FROM pg_auth_members WHERE member=installer.oid)<>1
            OR NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=installer.oid AND roleid=guard_row.oid
                AND inherit_option AND set_option AND NOT admin_option)
            OR NOT has_table_privilege(installer.oid,'public.tenants','TRIGGER')
            OR NOT has_table_privilege(installer.oid,'public.tenant_brandings','TRIGGER') THEN
        RAISE EXCEPTION 'Exact restricted document installer privileges required' USING ERRCODE='42501';
    END IF;
    GRANT CREATE ON SCHEMA public TO cfg_document_issuer_guard;
    PERFORM set_config('firefly.cfg_doc.install.temporary_guard_create','true',TRUE);
END $installation$;

-- END 00-installation-contract.sql
-- BEGIN 01-source-witness.sql
-- Source generation survives legacy deletion/reinsertion and is never an approval.
CREATE TABLE public.cfg_document_issuer_source_catalogue (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton)
);
INSERT INTO public.cfg_document_issuer_source_catalogue(singleton) VALUES (TRUE);
CREATE TABLE public.cfg_document_issuer_source_clock (
    tenant_id UUID PRIMARY KEY,
    generation BIGINT NOT NULL CHECK (generation >= 0 AND generation < 9223372036854775807)
);
INSERT INTO public.cfg_document_issuer_source_clock(tenant_id,generation)
SELECT id,1 FROM public.tenants;
CREATE TABLE public.cfg_document_issuer_source_change (
    change_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0 AND generation < 9223372036854775807),
    relation_name TEXT NOT NULL CHECK (relation_name IN ('tenants','tenant_brandings')),
    operation TEXT NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
    recorded_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id,generation)
);
CREATE TABLE cfg_document_issuer_private.source_witness (
    ordinal BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    xid XID8 NOT NULL,
    backend_pid INTEGER NOT NULL,
    db_session_user NAME NOT NULL,
    relation_name TEXT NOT NULL CHECK (relation_name IN ('tenants','tenant_brandings')),
    operation TEXT NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
    affected_tenant_ids UUID[] NOT NULL CHECK (cardinality(affected_tenant_ids) BETWEEN 1 AND 2),
    flushed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE FUNCTION public.cfg_document_issuer_source_statement_gate() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM public.cfg_document_issuer_source_catalogue WHERE singleton FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Document issuer source catalogue unavailable' USING ERRCODE='P0001'; END IF;
    IF EXISTS(SELECT 1 FROM cfg_document_issuer_private.source_witness
            WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user AND flushed) THEN
        RAISE EXCEPTION 'Late document issuer source mutation denied' USING ERRCODE='42501';
    END IF;
    RETURN NULL;
END $$;

CREATE FUNCTION public.cfg_document_issuer_source_witness() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE affected UUID[] := ARRAY[]::UUID[];
BEGIN
    IF TG_TABLE_SCHEMA <> 'public' OR TG_TABLE_NAME NOT IN ('tenants','tenant_brandings')
            OR TG_OP NOT IN ('INSERT','UPDATE','DELETE') THEN
        RAISE EXCEPTION 'Document issuer source witness relation denied' USING ERRCODE='42501';
    END IF;
    IF EXISTS(SELECT 1 FROM cfg_document_issuer_private.source_witness
            WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user AND flushed) THEN
        RAISE EXCEPTION 'Late document issuer source mutation denied' USING ERRCODE='42501';
    END IF;
    IF TG_TABLE_NAME='tenants' THEN
        IF TG_OP <> 'INSERT' THEN affected := array_append(affected,OLD.id); END IF;
        IF TG_OP <> 'DELETE' THEN affected := array_append(affected,NEW.id); END IF;
    ELSE
        IF TG_OP <> 'INSERT' THEN affected := array_append(affected,OLD.tenant_id); END IF;
        IF TG_OP <> 'DELETE' THEN affected := array_append(affected,NEW.tenant_id); END IF;
    END IF;
    SELECT array_agg(value) INTO affected FROM (
        SELECT DISTINCT value FROM unnest(affected) value ORDER BY value
    ) ordered_ids;
    INSERT INTO cfg_document_issuer_private.source_witness(xid,backend_pid,db_session_user,relation_name,operation,affected_tenant_ids)
        VALUES(pg_current_xact_id(),pg_backend_pid(),session_user,TG_TABLE_NAME,TG_OP,affected);
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;

CREATE FUNCTION public.cfg_document_issuer_source_flush() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE ids UUID[]; id_value UUID; witness RECORD; current_generation BIGINT;
BEGIN
    SELECT ARRAY(
        SELECT DISTINCT id FROM cfg_document_issuer_private.source_witness w
        CROSS JOIN LATERAL unnest(w.affected_tenant_ids) id
        WHERE w.xid=pg_current_xact_id() AND w.backend_pid=pg_backend_pid() AND w.db_session_user=session_user AND NOT w.flushed
        ORDER BY id
    ) INTO ids;
    IF cardinality(ids)=0 THEN RETURN NULL; END IF;
    -- Acquire the complete transaction's source-clock set in PostgreSQL UUID order.
    FOREACH id_value IN ARRAY ids LOOP
        INSERT INTO public.cfg_document_issuer_source_clock(tenant_id,generation)
            VALUES(id_value,0) ON CONFLICT (tenant_id) DO NOTHING;
        PERFORM 1 FROM public.cfg_document_issuer_source_clock WHERE tenant_id=id_value FOR UPDATE;
    END LOOP;
    FOR witness IN SELECT * FROM cfg_document_issuer_private.source_witness
            WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user AND NOT flushed
            ORDER BY ordinal LOOP
        FOREACH id_value IN ARRAY witness.affected_tenant_ids LOOP
            SELECT generation INTO STRICT current_generation FROM public.cfg_document_issuer_source_clock WHERE tenant_id=id_value;
            IF current_generation >= 9223372036854775806 THEN
                RAISE EXCEPTION 'Document issuer source generation exhausted' USING ERRCODE='P0001';
            END IF;
            UPDATE public.cfg_document_issuer_source_clock SET generation = generation + 1
                WHERE tenant_id=id_value RETURNING generation INTO current_generation;
            INSERT INTO public.cfg_document_issuer_source_change(tenant_id,generation,relation_name,operation,recorded_at)
                VALUES(id_value,current_generation,witness.relation_name,witness.operation,clock_timestamp());
        END LOOP;
    END LOOP;
    UPDATE cfg_document_issuer_private.source_witness SET flushed=TRUE
        WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user AND NOT flushed;
    RETURN NULL;
END $$;

CREATE TRIGGER cfg_document_issuer_source_statement
BEFORE INSERT OR UPDATE OR DELETE ON public.tenants
FOR EACH STATEMENT EXECUTE FUNCTION public.cfg_document_issuer_source_statement_gate();
CREATE TRIGGER cfg_document_issuer_source_row
BEFORE INSERT OR UPDATE OR DELETE ON public.tenants
FOR EACH ROW EXECUTE FUNCTION public.cfg_document_issuer_source_witness();
CREATE CONSTRAINT TRIGGER cfg_document_issuer_source_complete
AFTER INSERT OR UPDATE OR DELETE ON public.tenants DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.cfg_document_issuer_source_flush();
CREATE TRIGGER cfg_document_issuer_source_statement
BEFORE INSERT OR UPDATE OR DELETE ON public.tenant_brandings
FOR EACH STATEMENT EXECUTE FUNCTION public.cfg_document_issuer_source_statement_gate();
CREATE TRIGGER cfg_document_issuer_source_row
BEFORE INSERT OR UPDATE OR DELETE ON public.tenant_brandings
FOR EACH ROW EXECUTE FUNCTION public.cfg_document_issuer_source_witness();
CREATE CONSTRAINT TRIGGER cfg_document_issuer_source_complete
AFTER INSERT OR UPDATE OR DELETE ON public.tenant_brandings DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.cfg_document_issuer_source_flush();

ALTER TABLE public.cfg_document_issuer_source_catalogue OWNER TO cfg_document_issuer_guard;
ALTER TABLE public.cfg_document_issuer_source_clock OWNER TO cfg_document_issuer_guard;
ALTER TABLE public.cfg_document_issuer_source_change OWNER TO cfg_document_issuer_guard;
ALTER TABLE cfg_document_issuer_private.source_witness OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_source_statement_gate() OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_source_witness() OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_source_flush() OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON public.cfg_document_issuer_source_catalogue,public.cfg_document_issuer_source_clock,
    public.cfg_document_issuer_source_change,cfg_document_issuer_private.source_witness FROM PUBLIC;
REVOKE ALL ON FUNCTION public.cfg_document_issuer_source_statement_gate(),
    public.cfg_document_issuer_source_witness(),public.cfg_document_issuer_source_flush() FROM PUBLIC;

-- END 01-source-witness.sql
-- BEGIN 02-governed-records.sql
CREATE TABLE public.cfg_document_issuer_revision (
    publication_id UUID PRIMARY KEY CHECK (publication_id<>'00000000-0000-0000-0000-000000000000'),
    tenant_id UUID NOT NULL CHECK (tenant_id<>'00000000-0000-0000-0000-000000000000'),
    issuer_id UUID NOT NULL CHECK (issuer_id<>'00000000-0000-0000-0000-000000000000'),
    revision BIGINT NOT NULL CHECK (revision>0 AND revision<9223372036854775807),
    definition_json TEXT NOT NULL CHECK (octet_length(convert_to(definition_json,'UTF8')) BETWEEN 1 AND 65536),
    definition_digest TEXT NOT NULL CHECK (definition_digest ~ '^[0-9a-f]{64}$'),
    source_generation BIGINT NOT NULL CHECK (source_generation>0 AND source_generation<9223372036854775807),
    maker_issuer TEXT NOT NULL CHECK (octet_length(convert_to(maker_issuer,'UTF8')) BETWEEN 1 AND 500),
    maker_subject TEXT NOT NULL CHECK (octet_length(convert_to(maker_subject,'UTF8')) BETWEEN 1 AND 255),
    prepared_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id,issuer_id,revision),
    UNIQUE (tenant_id,issuer_id,publication_id,revision,source_generation),
    UNIQUE (tenant_id,issuer_id,publication_id,revision,definition_digest)
);

CREATE TABLE public.cfg_document_issuer_control (
    issuer_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    publication_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    generation BIGINT NOT NULL CHECK (generation>0 AND generation<9223372036854775807),
    state TEXT NOT NULL CHECK (state IN ('PREPARED','PUBLISHED','REVOKED')),
    source_generation BIGINT NOT NULL,
    FOREIGN KEY (tenant_id,issuer_id,publication_id,revision,source_generation)
        REFERENCES public.cfg_document_issuer_revision(tenant_id,issuer_id,publication_id,revision,source_generation)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE public.cfg_document_issuer_decision (
    action_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    issuer_id UUID NOT NULL,
    publication_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision>0 AND revision<9223372036854775807),
    operation TEXT NOT NULL CHECK (operation IN ('PUBLISH','REVOKE')),
    definition_digest TEXT NOT NULL CHECK (definition_digest ~ '^[0-9a-f]{64}$'),
    actor_issuer TEXT NOT NULL CHECK (octet_length(convert_to(actor_issuer,'UTF8')) BETWEEN 1 AND 500),
    actor_subject TEXT NOT NULL CHECK (octet_length(convert_to(actor_subject,'UTF8')) BETWEEN 1 AND 255),
    recorded_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id,publication_id,operation),
    FOREIGN KEY (tenant_id,issuer_id,publication_id,revision,definition_digest)
        REFERENCES public.cfg_document_issuer_revision(tenant_id,issuer_id,publication_id,revision,definition_digest)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE public.cfg_document_issuer_action (
    action_id UUID PRIMARY KEY CHECK (action_id<>'00000000-0000-0000-0000-000000000000'),
    tenant_id UUID NOT NULL CHECK (tenant_id<>'00000000-0000-0000-0000-000000000000'),
    issuer_id UUID NOT NULL CHECK (issuer_id<>'00000000-0000-0000-0000-000000000000'),
    operation TEXT NOT NULL CHECK (operation IN ('PREPARE','PUBLISH','REVOKE')),
    intent_digest TEXT NOT NULL CHECK (intent_digest ~ '^[0-9a-f]{64}$'),
    result_json TEXT NOT NULL CHECK (octet_length(convert_to(result_json,'UTF8')) BETWEEN 1 AND 16384),
    result_digest TEXT NOT NULL CHECK (result_digest ~ '^[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id,issuer_id,action_id),
    UNIQUE (tenant_id,issuer_id,action_id,operation),
    UNIQUE (tenant_id,issuer_id,action_id,operation,intent_digest)
);

ALTER TABLE public.cfg_document_issuer_decision ADD CONSTRAINT cfg_document_issuer_decision_action
    FOREIGN KEY (tenant_id,issuer_id,action_id,operation) REFERENCES public.cfg_document_issuer_action(tenant_id,issuer_id,action_id,operation)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.cfg_document_issuer_audit (
    action_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    issuer_id UUID NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('PREPARE','PUBLISH','REVOKE')),
    intent_digest TEXT NOT NULL CHECK (intent_digest ~ '^[0-9a-f]{64}$'),
    actor_issuer TEXT NOT NULL CHECK (octet_length(convert_to(actor_issuer,'UTF8')) BETWEEN 1 AND 500),
    actor_subject TEXT NOT NULL CHECK (octet_length(convert_to(actor_subject,'UTF8')) BETWEEN 1 AND 255),
    recorded_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (tenant_id,issuer_id,action_id,operation,intent_digest) REFERENCES public.cfg_document_issuer_action(tenant_id,issuer_id,action_id,operation,intent_digest)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE public.cfg_document_issuer_outbox (
    action_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    issuer_id UUID NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('PREPARED','PUBLISHED','REVOKED')),
    payload_digest TEXT NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (tenant_id,issuer_id,action_id) REFERENCES public.cfg_document_issuer_action(tenant_id,issuer_id,action_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

ALTER TABLE public.cfg_document_issuer_revision OWNER TO cfg_document_issuer_guard;
ALTER TABLE public.cfg_document_issuer_control OWNER TO cfg_document_issuer_guard;
ALTER TABLE public.cfg_document_issuer_decision OWNER TO cfg_document_issuer_guard;
ALTER TABLE public.cfg_document_issuer_action OWNER TO cfg_document_issuer_guard;
ALTER TABLE public.cfg_document_issuer_audit OWNER TO cfg_document_issuer_guard;
ALTER TABLE public.cfg_document_issuer_outbox OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON public.cfg_document_issuer_revision,public.cfg_document_issuer_control,
    public.cfg_document_issuer_decision,public.cfg_document_issuer_action,public.cfg_document_issuer_audit,
    public.cfg_document_issuer_outbox FROM PUBLIC;
ALTER TABLE public.cfg_document_issuer_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_revision FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_control ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_control FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_decision FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_action FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_audit FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_document_issuer_outbox FORCE ROW LEVEL SECURITY;

-- END 02-governed-records.sql
-- BEGIN 03-intent-codecs.sql
-- The administrator grants the new guard only the exact existing pure cfg_native validators.
CREATE FUNCTION public.cfg_document_issuer_text(p_value JSON,p_min INTEGER,p_max INTEGER) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE value TEXT; code INTEGER;
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'string' THEN PERFORM public.cfg_native_invalid(); END IF;
    value:=p_value#>>'{}';
    IF octet_length(convert_to(value,'UTF8')) NOT BETWEEN p_min AND p_max THEN PERFORM public.cfg_native_invalid(); END IF;
    IF p_min>0 THEN RETURN public.cfg_native_string(p_value,p_max); END IF;
    FOR i IN 1..char_length(value) LOOP
        code:=ascii(substr(value,i,1));
        IF code BETWEEN 0 AND 31 OR code BETWEEN 127 AND 159 OR code BETWEEN 55296 AND 57343 OR code=65533 THEN PERFORM public.cfg_native_invalid(); END IF;
    END LOOP;
    RETURN public.cfg_native_s(value);
END $$;

CREATE FUNCTION public.cfg_document_issuer_use(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE purpose TEXT; document_type TEXT; locale TEXT;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['purpose','documentType','locale']);
    purpose:=p_value->>'purpose';document_type:=p_value->>'documentType';locale:=p_value->>'locale';
    IF purpose IS DISTINCT FROM 'DEPOSIT_ACCOUNT_ENROLLMENT' OR document_type !~ '^[A-Z][A-Z0-9_]{0,63}$'
            OR locale !~ '^[A-Za-z0-9][A-Za-z0-9-]{1,63}$' THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN 'R3:'||public.cfg_document_issuer_text(p_value->'purpose',1,64)
        ||public.cfg_document_issuer_text(p_value->'documentType',1,64)||public.cfg_document_issuer_text(p_value->'locale',2,64);
END $$;

CREATE FUNCTION public.cfg_document_issuer_source(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE result TEXT; logo TEXT; color TEXT;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['tenantId','tenantVersion','brandingId','brandingVersion','sourceGeneration',
        'tenantCode','tenantName','tenantActive','brandingActive','logoUrl','primaryColor','footerText']);
    IF p_value->>'tenantActive' IS DISTINCT FROM 'true' OR p_value->>'brandingActive' IS DISTINCT FROM 'true' THEN PERFORM public.cfg_native_invalid(); END IF;
    result:='R12:'||public.cfg_native_uuid(p_value->'tenantId')||public.cfg_native_long(p_value->'tenantVersion',0,9223372036854775806)
        ||public.cfg_native_uuid(p_value->'brandingId')||public.cfg_native_long(p_value->'brandingVersion',0,9223372036854775806)
        ||public.cfg_native_long(p_value->'sourceGeneration',1,9223372036854775806)
        ||public.cfg_document_issuer_text(p_value->'tenantCode',1,100)||public.cfg_document_issuer_text(p_value->'tenantName',1,255)
        ||public.cfg_native_bool(p_value->'tenantActive')||public.cfg_native_bool(p_value->'brandingActive')
        ||public.cfg_document_issuer_text(p_value->'logoUrl',1,2000)||public.cfg_document_issuer_text(p_value->'primaryColor',1,7)
        ||public.cfg_document_issuer_text(p_value->'footerText',0,2000);
    logo:=p_value->>'logoUrl';color:=p_value->>'primaryColor';
    IF logo !~ '^https://[^/@#[:space:]]+([/?][^#[:space:]]*)?$' OR color !~ '^#[0-9A-Fa-f]{6}$' THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN result;
END $$;

CREATE FUNCTION public.cfg_document_issuer_definition(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE uses JSON; item JSON; use_tokens TEXT; prior_key TEXT; actual_key TEXT; result TEXT; from_millis BIGINT; until_millis BIGINT;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['issuerId','revision','issuerCode','displayName','contactEmail','uses','source',
        'effectiveFromEpochMillis','effectiveUntilEpochMillis']);
    IF p_value->>'issuerCode' !~ '^[A-Z][A-Z0-9_]{0,63}$' THEN PERFORM public.cfg_native_invalid(); END IF;
    uses:=p_value->'uses';
    IF json_typeof(uses) IS DISTINCT FROM 'array' OR json_array_length(uses) NOT BETWEEN 1 AND 32 THEN PERFORM public.cfg_native_invalid(); END IF;
    use_tokens:='A'||json_array_length(uses)::TEXT||':';
    FOR item IN SELECT value FROM json_array_elements(uses) LOOP
        use_tokens:=use_tokens||public.cfg_document_issuer_use(item);
        actual_key:=(item->>'purpose')||'/'||(item->>'documentType')||'/'||(item->>'locale');
        IF prior_key IS NOT NULL AND prior_key COLLATE "C">=actual_key COLLATE "C" THEN PERFORM public.cfg_native_invalid(); END IF;
        prior_key:=actual_key;
    END LOOP;
    result:='R9:'||public.cfg_native_uuid(p_value->'issuerId')||public.cfg_native_long(p_value->'revision',1,9223372036854775806)
        ||public.cfg_document_issuer_text(p_value->'issuerCode',1,64)||public.cfg_document_issuer_text(p_value->'displayName',1,255)
        ||public.cfg_document_issuer_text(p_value->'contactEmail',3,254)||use_tokens||public.cfg_document_issuer_source(p_value->'source')
        ||public.cfg_native_long(p_value->'effectiveFromEpochMillis',0,253402300799999)
        ||public.cfg_native_long(p_value->'effectiveUntilEpochMillis',0,253402300799999);
    from_millis:=(p_value->>'effectiveFromEpochMillis')::BIGINT;until_millis:=(p_value->>'effectiveUntilEpochMillis')::BIGINT;
    IF from_millis>=until_millis THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN result;
END $$;

CREATE FUNCTION public.cfg_document_issuer_definition_digest(p_value JSON) RETURNS TEXT
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
    SELECT encode(sha256(convert_to(public.cfg_native_s('FF_DOCUMENT_ISSUER_DEFINITION_V1')||public.cfg_document_issuer_definition(p_value),'UTF8')),'hex')
$$;

CREATE FUNCTION public.cfg_document_issuer_intent(p_tenant UUID,p_operation TEXT,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE result TEXT;
BEGIN
    IF p_operation='SOURCE' THEN
        PERFORM public.cfg_native_shape(p_value,ARRAY['tenantId','issuerId','publicationId','actionId','brandingId','use']);
        IF p_value->>'tenantId' IS DISTINCT FROM p_tenant::TEXT THEN PERFORM public.cfg_native_invalid(); END IF;
        result:=public.cfg_native_s('FF_DOCUMENT_ISSUER_SOURCE_REQUEST_V1')||'R6:'
            ||public.cfg_native_uuid(p_value->'tenantId')||public.cfg_native_uuid(p_value->'issuerId')
            ||public.cfg_native_uuid(p_value->'publicationId')||public.cfg_native_uuid(p_value->'actionId')
            ||public.cfg_native_uuid(p_value->'brandingId')||public.cfg_document_issuer_use(p_value->'use');
    ELSIF p_operation='PREPARE' THEN
        PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','publicationId','expectedGeneration','definition']);
        result:=public.cfg_native_s('FF_DOCUMENT_ISSUER_PREPARE_V1')||'R2:'||public.cfg_native_uuid(to_json(p_tenant::TEXT))||'R4:'
            ||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'publicationId')
            ||public.cfg_native_long(p_value->'expectedGeneration',0,9223372036854775806)||public.cfg_document_issuer_definition(p_value->'definition');
        IF p_value->'definition'->'source'->>'tenantId' IS DISTINCT FROM p_tenant::TEXT THEN PERFORM public.cfg_native_invalid(); END IF;
    ELSIF p_operation IN ('PUBLISH','REVOKE') THEN
        PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','issuerId','publicationId','revision','definitionDigest','expectedGeneration','reason']);
        IF p_value->>'definitionDigest' !~ '^[0-9a-f]{64}$' THEN PERFORM public.cfg_native_invalid(); END IF;
        result:=public.cfg_native_s('FF_DOCUMENT_ISSUER_DECISION_V1')||'R3:'||public.cfg_native_uuid(to_json(p_tenant::TEXT))
            ||public.cfg_native_s(p_operation)||'R7:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'issuerId')
            ||public.cfg_native_uuid(p_value->'publicationId')||public.cfg_native_long(p_value->'revision',1,9223372036854775806)
            ||public.cfg_document_issuer_text(p_value->'definitionDigest',64,64)||public.cfg_native_long(p_value->'expectedGeneration',0,9223372036854775806)
            ||public.cfg_document_issuer_text(p_value->'reason',1,500);
    ELSIF p_operation='CURRENT' THEN
        PERFORM public.cfg_native_shape(p_value,ARRAY['tenantId','publicationId','revision','definitionDigest','use']);
        IF p_value->>'tenantId' IS DISTINCT FROM p_tenant::TEXT OR p_value->>'definitionDigest' !~ '^[0-9a-f]{64}$' THEN PERFORM public.cfg_native_invalid(); END IF;
        result:=public.cfg_native_s('FF_DOCUMENT_ISSUER_CURRENT_REQUEST_V1')||'R5:'||public.cfg_native_uuid(p_value->'tenantId')
            ||public.cfg_native_uuid(p_value->'publicationId')||public.cfg_native_long(p_value->'revision',1,9223372036854775806)
            ||public.cfg_document_issuer_text(p_value->'definitionDigest',64,64)||public.cfg_document_issuer_use(p_value->'use');
    ELSE PERFORM public.cfg_native_invalid(); END IF;
    RETURN result;
END $$;

ALTER FUNCTION public.cfg_document_issuer_text(JSON,INTEGER,INTEGER) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_use(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_source(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_definition(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_definition_digest(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_intent(UUID,TEXT,JSON) OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON FUNCTION public.cfg_document_issuer_text(JSON,INTEGER,INTEGER),public.cfg_document_issuer_use(JSON),
    public.cfg_document_issuer_source(JSON),public.cfg_document_issuer_definition(JSON),public.cfg_document_issuer_definition_digest(JSON),
    public.cfg_document_issuer_intent(UUID,TEXT,JSON) FROM PUBLIC;

-- END 03-intent-codecs.sql
-- BEGIN 04-native-context.sql
CREATE TABLE cfg_document_issuer_private.command_context (
    xid XID8 NOT NULL,
    backend_pid INTEGER NOT NULL,
    db_session_user NAME NOT NULL,
    tenant_id UUID NOT NULL,
    issuer_id UUID NOT NULL,
    publication_id UUID NOT NULL,
    action_id UUID NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('PREPARE','PUBLISH','REVOKE')),
    original_json JSON NOT NULL,
    intent_digest TEXT NOT NULL,
    actor_issuer TEXT NOT NULL,
    actor_subject TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    until_millis BIGINT NOT NULL,
    definition_json JSON NOT NULL,
    definition_digest TEXT NOT NULL,
    expected_generation BIGINT NOT NULL,
    replay_only BOOLEAN NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (xid,backend_pid)
);
ALTER TABLE cfg_document_issuer_private.command_context OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON cfg_document_issuer_private.command_context FROM PUBLIC;

CREATE FUNCTION public.cfg_document_issuer_environment(p_read BOOLEAN) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE login_row RECORD; group_row RECORD; guard_row RECORD; expected_group TEXT;
BEGIN
    expected_group:=CASE WHEN p_read THEN 'cfg_document_issuer_read' ELSE 'cfg_document_issuer_commands' END;
    SELECT * INTO login_row FROM pg_roles WHERE rolname=session_user;
    SELECT * INTO group_row FROM pg_roles WHERE rolname=expected_group;
    SELECT * INTO guard_row FROM pg_roles WHERE rolname='cfg_document_issuer_guard';
    IF login_row.oid IS NULL OR group_row.oid IS NULL OR guard_row.oid IS NULL OR NOT login_row.rolcanlogin
            OR login_row.rolsuper OR login_row.rolbypassrls OR login_row.rolcreatedb OR login_row.rolcreaterole OR login_row.rolreplication
            OR guard_row.rolcanlogin OR guard_row.rolsuper OR guard_row.rolbypassrls OR guard_row.rolcreatedb OR guard_row.rolcreaterole OR guard_row.rolreplication OR guard_row.rolinherit
            OR group_row.rolcanlogin OR group_row.rolsuper OR group_row.rolbypassrls OR group_row.rolcreatedb OR group_row.rolcreaterole OR group_row.rolreplication OR group_row.rolinherit
            OR EXISTS(SELECT 1 FROM pg_auth_members WHERE member IN (guard_row.oid,group_row.oid))
            OR (SELECT count(*) FROM pg_auth_members WHERE member=login_row.oid)<>1
            OR NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=login_row.oid AND roleid=group_row.oid AND inherit_option AND NOT set_option AND NOT admin_option)
            OR current_setting('role',TRUE) NOT IN ('none',session_user::TEXT)
            OR has_schema_privilege(login_row.oid,'public','CREATE') OR has_schema_privilege(guard_row.oid,'public','CREATE')
            OR has_database_privilege(login_row.oid,current_database(),'CREATE,TEMPORARY')
            OR has_database_privilege(guard_row.oid,current_database(),'CREATE,TEMPORARY')
            OR NOT pg_has_role(login_row.oid,group_row.oid,'USAGE')
            OR EXISTS(SELECT 1 FROM pg_class WHERE relowner=login_row.oid)
            OR EXISTS(SELECT 1 FROM pg_proc WHERE proowner=login_row.oid)
            OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspowner=login_row.oid)
            OR current_setting('server_encoding')<>'UTF8' THEN RETURN FALSE; END IF;
    RETURN CASE WHEN p_read THEN current_setting('transaction_read_only')='on' AND current_setting('transaction_isolation')='repeatable read'
        ELSE current_setting('transaction_read_only')='off' AND current_setting('transaction_isolation')='read committed' END;
END $$;

CREATE FUNCTION public.cfg_document_issuer_actual_source(p_tenant UUID,p_branding UUID) RETURNS JSONB
LANGUAGE sql VOLATILE SET search_path=pg_catalog,pg_temp AS $$
    SELECT jsonb_build_object('tenantId',t.id::TEXT,'tenantVersion',t.version,'brandingId',b.id::TEXT,'brandingVersion',b.version,
        'sourceGeneration',s.generation,'tenantCode',t.code,'tenantName',t.name,'tenantActive',t.active,'brandingActive',b.active,
        'logoUrl',b.logo_url,'primaryColor',b.primary_color,'footerText',b.email_footer_text)
    FROM public.tenants t JOIN public.tenant_brandings b ON b.tenant_id=t.id
    JOIN public.cfg_document_issuer_source_clock s ON s.tenant_id=t.id WHERE t.id=p_tenant AND b.id=p_branding
$$;

CREATE TABLE cfg_document_issuer_private.read_key (
    singleton BOOLEAN PRIMARY KEY CHECK(singleton),
    material BYTEA NOT NULL CHECK(octet_length(material)=32)
);
ALTER TABLE cfg_document_issuer_private.read_key OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON cfg_document_issuer_private.read_key FROM PUBLIC,cfg_document_issuer_commands,cfg_document_issuer_read;
INSERT INTO cfg_document_issuer_private.read_key VALUES(TRUE,cfg_document_issuer_crypto.gen_random_bytes(32));

CREATE FUNCTION cfg_document_issuer_private.read_context() RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE encoded TEXT; signature TEXT; payload JSONB; material BYTEA; now_millis BIGINT;
BEGIN
    IF NOT public.cfg_document_issuer_environment(TRUE) THEN RETURN NULL; END IF;
    encoded:=nullif(current_setting('firefly.cfg_doc.read_payload',TRUE),'');
    signature:=nullif(current_setting('firefly.cfg_doc.read_mac',TRUE),'');
    IF encoded IS NULL OR signature IS NULL OR octet_length(encoded)>8192 OR signature !~ '^[0-9a-f]{64}$' THEN RETURN NULL; END IF;
    SELECT k.material INTO STRICT material FROM cfg_document_issuer_private.read_key k WHERE singleton;
    IF encode(cfg_document_issuer_crypto.hmac(convert_to(encoded,'UTF8'),material,'sha256'),'hex') IS DISTINCT FROM signature THEN RETURN NULL; END IF;
    payload:=encoded::JSONB;
    now_millis:=floor(extract(epoch FROM clock_timestamp())*1000)::BIGINT;
    IF payload->>'format' IS DISTINCT FROM 'FF_DOCUMENT_ISSUER_READ_CONTEXT_V1'
            OR payload->>'database' IS DISTINCT FROM current_database()
            OR payload->>'login' IS DISTINCT FROM session_user::TEXT
            OR payload->>'backend' IS DISTINCT FROM pg_backend_pid()::TEXT
            OR payload->>'xid' IS DISTINCT FROM pg_current_xact_id()::TEXT
            OR payload->>'operation' NOT IN ('CURRENT','SOURCE')
            OR (payload->>'until')::BIGINT<=now_millis
            OR (payload->>'recorded')::BIGINT>now_millis
            OR (payload->>'until')::BIGINT>(payload->>'originalUntil')::BIGINT
            OR (payload->>'until')::BIGINT>(payload->>'recorded')::BIGINT+5000 THEN RETURN NULL; END IF;
    RETURN payload;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN NULL;
END $$;
ALTER FUNCTION cfg_document_issuer_private.read_context() OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON FUNCTION cfg_document_issuer_private.read_context() FROM PUBLIC,cfg_document_issuer_commands,cfg_document_issuer_read;

CREATE FUNCTION public.cfg_document_issuer_context_current() RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE ctx cfg_document_issuer_private.command_context; now_millis BIGINT;
BEGIN
    now_millis:=floor(extract(epoch FROM clock_timestamp())*1000)::BIGINT;
    IF public.cfg_document_issuer_environment(FALSE) THEN
        SELECT * INTO ctx FROM cfg_document_issuer_private.command_context
            WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user;
        RETURN FOUND AND ctx.until_millis>now_millis;
    ELSIF public.cfg_document_issuer_environment(TRUE) THEN
        RETURN cfg_document_issuer_private.read_context() IS NOT NULL;
    END IF;
    RETURN FALSE;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN FALSE;
END $$;

CREATE FUNCTION public.cfg_document_issuer_open(p_tenant UUID,p_issuer UUID,p_publication UUID,p_action UUID,p_operation TEXT,
    p_original BYTEA,p_expected_digest TEXT,p_actor_issuer TEXT,p_actor_subject TEXT,p_until BIGINT)
RETURNS TABLE(issuer_id UUID,recorded_at TIMESTAMPTZ,db_now_millis BIGINT,until_millis BIGINT)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE body JSON; canonical TEXT; observed TIMESTAMPTZ; now_millis BIGINT; deadline BIGINT;
    definition JSON; definition_hash TEXT; actual_issuer UUID; expected_generation BIGINT; replay BOOLEAN:=FALSE;
    revision_row public.cfg_document_issuer_revision; control_row public.cfg_document_issuer_control;
    decision_row public.cfg_document_issuer_decision; action_row public.cfg_document_issuer_action; audit_row public.cfg_document_issuer_audit; read_payload TEXT; signing_material BYTEA;
BEGIN
    IF current_user<>'cfg_document_issuer_guard' OR p_operation NOT IN ('PREPARE','PUBLISH','REVOKE','CURRENT','SOURCE')
            OR NOT public.cfg_document_issuer_environment(p_operation IN ('CURRENT','SOURCE')) THEN
        RAISE EXCEPTION 'Restricted document native environment required' USING ERRCODE='42501';
    END IF;
    PERFORM public.cfg_native_uuid(to_json(p_tenant::TEXT));PERFORM public.cfg_native_uuid(to_json(p_publication::TEXT));PERFORM public.cfg_native_uuid(to_json(p_action::TEXT));
    PERFORM public.cfg_document_issuer_text(to_json(p_actor_issuer),1,500);PERFORM public.cfg_document_issuer_text(to_json(p_actor_subject),1,255);
    IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 OR p_expected_digest !~ '^[0-9a-f]{64}$'
            OR p_until IS NULL OR p_until NOT BETWEEN 1 AND 253402300799999 THEN PERFORM public.cfg_native_invalid(); END IF;
    body:=convert_from(p_original,'UTF8')::JSON;
    canonical:=public.cfg_document_issuer_intent(p_tenant,p_operation,body);
    IF encode(sha256(convert_to(canonical,'UTF8')),'hex') IS DISTINCT FROM p_expected_digest THEN
        RAISE EXCEPTION 'Exact approved document intent required' USING ERRCODE='42501';
    END IF;
    now_millis:=floor(extract(epoch FROM clock_timestamp())*1000)::BIGINT;
    IF p_until<=now_millis THEN RAISE EXCEPTION 'Original document authority expired' USING ERRCODE='42501'; END IF;
    IF p_operation='SOURCE' THEN
        IF p_issuer IS NULL OR body->>'issuerId' IS DISTINCT FROM p_issuer::TEXT
                OR body->>'publicationId' IS DISTINCT FROM p_publication::TEXT OR body->>'actionId' IS DISTINCT FROM p_action::TEXT
                OR EXISTS(SELECT 1 FROM cfg_document_issuer_private.command_context WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid()) THEN
            RAISE EXCEPTION 'Exact document source observation required' USING ERRCODE='42501';
        END IF;
        actual_issuer:=p_issuer;
        definition:=json_build_object('source',public.cfg_document_issuer_actual_source(p_tenant,(body->>'brandingId')::UUID));
        PERFORM public.cfg_document_issuer_source(definition->'source');
    ELSIF p_operation='CURRENT' THEN
        IF EXISTS(SELECT 1 FROM cfg_document_issuer_private.command_context WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid()) THEN
            RAISE EXCEPTION 'Independent read-only document root required' USING ERRCODE='42501';
        END IF;
        SELECT * INTO revision_row FROM public.cfg_document_issuer_revision r WHERE r.tenant_id=p_tenant AND r.publication_id=p_publication;
        IF NOT FOUND OR p_issuer IS NOT NULL AND p_issuer<>revision_row.issuer_id OR (body->>'publicationId')::UUID<>p_publication
                OR (body->>'revision')::BIGINT<>revision_row.revision OR body->>'definitionDigest' IS DISTINCT FROM revision_row.definition_digest THEN
            RAISE EXCEPTION 'Exact retained document publication required' USING ERRCODE='42501';
        END IF;
        actual_issuer:=revision_row.issuer_id;definition:=revision_row.definition_json::JSON;definition_hash:=public.cfg_document_issuer_definition_digest(definition);
        SELECT * INTO control_row FROM public.cfg_document_issuer_control c WHERE c.tenant_id=p_tenant AND c.issuer_id=actual_issuer;
        SELECT * INTO decision_row FROM public.cfg_document_issuer_decision d WHERE d.tenant_id=p_tenant AND d.publication_id=p_publication AND d.operation='PUBLISH';
        IF definition_hash IS DISTINCT FROM revision_row.definition_digest OR control_row.state IS DISTINCT FROM 'PUBLISHED'
                OR control_row.publication_id IS DISTINCT FROM p_publication OR control_row.revision IS DISTINCT FROM revision_row.revision
                OR control_row.source_generation IS DISTINCT FROM revision_row.source_generation
                OR decision_row.action_id IS NULL OR decision_row.issuer_id IS DISTINCT FROM actual_issuer
                OR decision_row.revision IS DISTINCT FROM revision_row.revision OR decision_row.definition_digest IS DISTINCT FROM definition_hash
                OR decision_row.actor_issuer=revision_row.maker_issuer AND decision_row.actor_subject=revision_row.maker_subject
                OR NOT EXISTS(SELECT 1 FROM json_array_elements(definition->'uses') item WHERE item::JSONB=(body->'use')::JSONB)
                OR public.cfg_document_issuer_actual_source(p_tenant,(definition->'source'->>'brandingId')::UUID) IS DISTINCT FROM (definition->'source')::JSONB THEN
            RAISE EXCEPTION 'Current approved document use unavailable' USING ERRCODE='42501';
        END IF;
    ELSE
        PERFORM public.cfg_native_uuid(to_json(p_issuer::TEXT));
        IF EXISTS(SELECT 1 FROM cfg_document_issuer_private.command_context WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid())
                OR EXISTS(SELECT 1 FROM cfg_document_issuer_private.source_witness WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid()) THEN
            RAISE EXCEPTION 'Independent document command root required' USING ERRCODE='42501';
        END IF;
        PERFORM 1 FROM public.cfg_document_issuer_source_catalogue WHERE singleton FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Document source catalogue unavailable' USING ERRCODE='42501'; END IF;
        SELECT * INTO action_row FROM public.cfg_document_issuer_action a WHERE a.action_id=p_action;
        IF FOUND THEN
            SELECT * INTO audit_row FROM public.cfg_document_issuer_audit a WHERE a.action_id=p_action;
            IF action_row.tenant_id IS DISTINCT FROM p_tenant OR action_row.issuer_id IS DISTINCT FROM p_issuer OR action_row.operation IS DISTINCT FROM p_operation
                    OR action_row.intent_digest IS DISTINCT FROM p_expected_digest OR audit_row.actor_issuer IS DISTINCT FROM p_actor_issuer
                    OR audit_row.actor_subject IS DISTINCT FROM p_actor_subject OR (action_row.result_json::JSON->>'publicationId')::UUID IS DISTINCT FROM p_publication THEN
                RAISE EXCEPTION 'Exact retained owner action required' USING ERRCODE='42501';
            END IF;
            replay:=TRUE;
        END IF;
        SELECT * INTO control_row FROM public.cfg_document_issuer_control c WHERE c.tenant_id=p_tenant AND c.issuer_id=p_issuer FOR UPDATE;
        expected_generation:=(body->>'expectedGeneration')::BIGINT;
        IF p_operation='PREPARE' THEN
            definition:=body->'definition';definition_hash:=public.cfg_document_issuer_definition_digest(definition);
            IF (body->>'actionId')::UUID<>p_action OR (body->>'publicationId')::UUID<>p_publication OR (definition->>'issuerId')::UUID<>p_issuer THEN PERFORM public.cfg_native_invalid(); END IF;
            IF NOT replay AND (control_row.issuer_id IS NULL AND (expected_generation<>0 OR (definition->>'revision')::BIGINT<>1)
                    OR control_row.issuer_id IS NOT NULL AND (control_row.generation<>expected_generation OR control_row.state NOT IN ('PUBLISHED','REVOKED') OR (definition->>'revision')::BIGINT<>control_row.revision+1)
                    OR public.cfg_document_issuer_actual_source(p_tenant,(definition->'source'->>'brandingId')::UUID) IS DISTINCT FROM (definition->'source')::JSONB) THEN
                RAISE EXCEPTION 'Fresh exact document source and control required' USING ERRCODE='42501';
            END IF;
        ELSE
            SELECT * INTO revision_row FROM public.cfg_document_issuer_revision r WHERE r.tenant_id=p_tenant AND r.publication_id=p_publication;
            IF NOT FOUND THEN RAISE EXCEPTION 'Retained document revision unavailable' USING ERRCODE='42501'; END IF;
            definition:=revision_row.definition_json::JSON;definition_hash:=public.cfg_document_issuer_definition_digest(definition);
            IF (body->>'actionId')::UUID<>p_action OR (body->>'issuerId')::UUID<>p_issuer OR (body->>'publicationId')::UUID<>p_publication
                    OR revision_row.issuer_id<>p_issuer OR (body->>'revision')::BIGINT<>revision_row.revision
                    OR body->>'definitionDigest' IS DISTINCT FROM definition_hash OR revision_row.definition_digest IS DISTINCT FROM definition_hash THEN
                RAISE EXCEPTION 'Exact document decision target required' USING ERRCODE='42501';
            END IF;
            IF NOT replay AND (control_row.publication_id IS DISTINCT FROM p_publication OR control_row.revision IS DISTINCT FROM revision_row.revision
                    OR control_row.generation IS DISTINCT FROM expected_generation OR control_row.source_generation IS DISTINCT FROM revision_row.source_generation
                    OR p_operation='PUBLISH' AND (control_row.state IS DISTINCT FROM 'PREPARED'
                        OR revision_row.maker_issuer=p_actor_issuer AND revision_row.maker_subject=p_actor_subject
                        OR public.cfg_document_issuer_actual_source(p_tenant,(definition->'source'->>'brandingId')::UUID) IS DISTINCT FROM (definition->'source')::JSONB)
                    OR p_operation='REVOKE' AND control_row.state NOT IN ('PREPARED','PUBLISHED')) THEN
                RAISE EXCEPTION 'Current exact document decision control required' USING ERRCODE='42501';
            END IF;
        END IF;
        actual_issuer:=p_issuer;
    END IF;
    observed:=clock_timestamp();now_millis:=floor(extract(epoch FROM observed)*1000)::BIGINT;
    deadline:=least(p_until,now_millis+5000);
    IF p_operation IN ('CURRENT','PUBLISH') AND NOT replay THEN
        IF now_millis<(definition->>'effectiveFromEpochMillis')::BIGINT THEN RAISE EXCEPTION 'Document effective window has not opened' USING ERRCODE='42501'; END IF;
        deadline:=least(deadline,(definition->>'effectiveUntilEpochMillis')::BIGINT);
    ELSIF p_operation='PREPARE' AND NOT replay THEN deadline:=least(deadline,(definition->>'effectiveUntilEpochMillis')::BIGINT);
    END IF;
    IF deadline<=now_millis THEN RAISE EXCEPTION 'Original document window expired' USING ERRCODE='42501'; END IF;
    IF p_operation IN ('CURRENT','SOURCE') THEN
        IF nullif(current_setting('firefly.cfg_doc.read_payload',TRUE),'') IS NOT NULL
                OR nullif(current_setting('firefly.cfg_doc.read_mac',TRUE),'') IS NOT NULL THEN
            RAISE EXCEPTION 'One document read admission per transaction required' USING ERRCODE='42501';
        END IF;
        read_payload:=jsonb_build_object('format','FF_DOCUMENT_ISSUER_READ_CONTEXT_V1',
            'database',current_database(),'login',session_user::TEXT,'backend',pg_backend_pid()::TEXT,'xid',pg_current_xact_id()::TEXT,
            'tenant',p_tenant::TEXT,'issuer',actual_issuer::TEXT,'publication',p_publication::TEXT,'action',p_action::TEXT,
            'branding',definition->'source'->>'brandingId','operation',p_operation,
            'actorIssuer',p_actor_issuer,'actorSubject',p_actor_subject,'intentDigest',p_expected_digest,
            'recorded',now_millis,'originalUntil',p_until,'until',deadline)::TEXT;
        SELECT k.material INTO STRICT signing_material FROM cfg_document_issuer_private.read_key k WHERE singleton;
        PERFORM set_config('firefly.cfg_doc.read_payload',read_payload,TRUE);
        PERFORM set_config('firefly.cfg_doc.read_mac',encode(cfg_document_issuer_crypto.hmac(convert_to(read_payload,'UTF8'),signing_material,'sha256'),'hex'),TRUE);
    ELSE
        INSERT INTO cfg_document_issuer_private.command_context(xid,backend_pid,db_session_user,tenant_id,issuer_id,publication_id,action_id,operation,
            original_json,intent_digest,actor_issuer,actor_subject,recorded_at,until_millis,definition_json,definition_digest,expected_generation,replay_only)
        VALUES(pg_current_xact_id(),pg_backend_pid(),session_user,p_tenant,actual_issuer,p_publication,p_action,p_operation,
            body,p_expected_digest,p_actor_issuer,p_actor_subject,observed,deadline,definition,definition_hash,expected_generation,replay);
    END IF;
    RETURN QUERY SELECT actual_issuer,observed,now_millis,deadline;
END $$;

ALTER FUNCTION public.cfg_document_issuer_environment(BOOLEAN) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_actual_source(UUID,UUID) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_context_current() OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_open(UUID,UUID,UUID,UUID,TEXT,BYTEA,TEXT,TEXT,TEXT,BIGINT) OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON FUNCTION public.cfg_document_issuer_environment(BOOLEAN),public.cfg_document_issuer_actual_source(UUID,UUID),
    public.cfg_document_issuer_context_current(),public.cfg_document_issuer_open(UUID,UUID,UUID,UUID,TEXT,BYTEA,TEXT,TEXT,TEXT,BIGINT) FROM PUBLIC;

-- END 04-native-context.sql
-- BEGIN 05-row-admission.sql
CREATE FUNCTION public.cfg_document_issuer_expected_receipt(p_context cfg_document_issuer_private.command_context) RETURNS JSONB
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
    SELECT jsonb_build_object('tenantId',p_context.tenant_id::TEXT,'issuerId',p_context.issuer_id::TEXT,
        'publicationId',p_context.publication_id::TEXT,'revision',(p_context.definition_json->>'revision')::BIGINT,
        'generation',p_context.expected_generation+1,'actionId',p_context.action_id::TEXT,'operation',p_context.operation,
        'state',CASE p_context.operation WHEN 'PREPARE' THEN 'PREPARED' WHEN 'PUBLISH' THEN 'PUBLISHED' ELSE 'REVOKED' END,
        'definitionDigest',p_context.definition_digest,'intentDigest',p_context.intent_digest,
        'recordedAtEpochMillis',floor(extract(epoch FROM p_context.recorded_at)*1000)::BIGINT)
$$;

CREATE FUNCTION public.cfg_document_issuer_receipt_digest(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE canonical TEXT; operation TEXT; state TEXT;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['tenantId','issuerId','publicationId','revision','generation','actionId','operation','state','definitionDigest','intentDigest','recordedAtEpochMillis']);
    operation:=p_value->>'operation';state:=p_value->>'state';
    IF operation NOT IN ('PREPARE','PUBLISH','REVOKE') OR state IS DISTINCT FROM (CASE operation WHEN 'PREPARE' THEN 'PREPARED' WHEN 'PUBLISH' THEN 'PUBLISHED' ELSE 'REVOKED' END)
            OR p_value->>'definitionDigest' !~ '^[0-9a-f]{64}$' OR p_value->>'intentDigest' !~ '^[0-9a-f]{64}$' THEN PERFORM public.cfg_native_invalid(); END IF;
    canonical:=public.cfg_native_s('FF_DOCUMENT_ISSUER_RECEIPT_V1')||'R11:'||public.cfg_native_uuid(p_value->'tenantId')||public.cfg_native_uuid(p_value->'issuerId')
        ||public.cfg_native_uuid(p_value->'publicationId')||public.cfg_native_long(p_value->'revision',1,9223372036854775806)
        ||public.cfg_native_long(p_value->'generation',1,9223372036854775806)||public.cfg_native_uuid(p_value->'actionId')
        ||public.cfg_document_issuer_text(p_value->'operation',1,32)||public.cfg_document_issuer_text(p_value->'state',1,32)
        ||public.cfg_document_issuer_text(p_value->'definitionDigest',64,64)||public.cfg_document_issuer_text(p_value->'intentDigest',64,64)
        ||public.cfg_native_long(p_value->'recordedAtEpochMillis',0,253402300799999);
    RETURN encode(sha256(convert_to(canonical,'UTF8')),'hex');
END $$;

CREATE FUNCTION public.cfg_document_issuer_write_guard() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE ctx cfg_document_issuer_private.command_context; value JSONB; expected JSONB; target_state TEXT;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN ('cfg_document_issuer_revision','cfg_document_issuer_control','cfg_document_issuer_decision','cfg_document_issuer_action','cfg_document_issuer_audit','cfg_document_issuer_outbox')
            OR TG_OP='DELETE' OR TG_OP='UPDATE' AND TG_TABLE_NAME<>'cfg_document_issuer_control'
            OR NOT public.cfg_document_issuer_environment(FALSE) OR NOT public.cfg_document_issuer_context_current() THEN
        RAISE EXCEPTION 'Exact current document write context required' USING ERRCODE='42501';
    END IF;
    SELECT * INTO STRICT ctx FROM cfg_document_issuer_private.command_context WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user;
    IF ctx.replay_only OR ctx.completed THEN RAISE EXCEPTION 'Retained or completed document context is read-only' USING ERRCODE='42501'; END IF;
    value:=to_jsonb(NEW);expected:=public.cfg_document_issuer_expected_receipt(ctx);target_state:=expected->>'state';
    IF (value->>'tenant_id')::UUID IS DISTINCT FROM ctx.tenant_id OR (value->>'issuer_id')::UUID IS DISTINCT FROM ctx.issuer_id THEN
        RAISE EXCEPTION 'Exact document tenant and issuer required' USING ERRCODE='42501';
    END IF;
    IF TG_TABLE_NAME='cfg_document_issuer_revision' THEN
        IF ctx.operation<>'PREPARE' OR TG_OP<>'INSERT' OR NEW.publication_id<>ctx.publication_id OR NEW.revision<>(ctx.definition_json->>'revision')::BIGINT
                OR public.cfg_document_issuer_definition_digest(NEW.definition_json::JSON)<>ctx.definition_digest
                OR NEW.definition_json::JSONB IS DISTINCT FROM ctx.definition_json::JSONB OR NEW.definition_digest<>ctx.definition_digest
                OR NEW.source_generation<>(ctx.definition_json->'source'->>'sourceGeneration')::BIGINT
                OR NEW.maker_issuer<>ctx.actor_issuer OR NEW.maker_subject<>ctx.actor_subject OR NEW.prepared_at<>ctx.recorded_at THEN
            RAISE EXCEPTION 'Exact immutable prepared document revision required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='cfg_document_issuer_control' THEN
        IF ctx.expected_generation>=9223372036854775806 OR NEW.publication_id<>ctx.publication_id OR NEW.revision<>(ctx.definition_json->>'revision')::BIGINT
                OR NEW.generation<>ctx.expected_generation+1 OR NEW.state<>target_state OR NEW.source_generation<>(ctx.definition_json->'source'->>'sourceGeneration')::BIGINT THEN
            RAISE EXCEPTION 'Exact document control effect required' USING ERRCODE='23514';
        END IF;
        IF TG_OP='INSERT' THEN
            IF ctx.operation<>'PREPARE' OR ctx.expected_generation<>0 OR NEW.revision<>1 THEN RAISE EXCEPTION 'Initial document control required' USING ERRCODE='23514'; END IF;
        ELSIF OLD.tenant_id<>NEW.tenant_id OR OLD.issuer_id<>NEW.issuer_id OR OLD.generation<>ctx.expected_generation
                OR ctx.operation='PREPARE' AND (OLD.state NOT IN ('PUBLISHED','REVOKED') OR OLD.revision>=9223372036854775806 OR NEW.revision<>OLD.revision+1 OR NEW.publication_id=OLD.publication_id)
                OR ctx.operation IN ('PUBLISH','REVOKE') AND (NEW.publication_id<>OLD.publication_id OR NEW.revision<>OLD.revision OR NEW.source_generation<>OLD.source_generation)
                OR ctx.operation='PUBLISH' AND OLD.state<>'PREPARED' OR ctx.operation='REVOKE' AND OLD.state NOT IN ('PREPARED','PUBLISHED') THEN
            RAISE EXCEPTION 'Exact previous document control required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='cfg_document_issuer_decision' THEN
        IF ctx.operation NOT IN ('PUBLISH','REVOKE') OR NEW.action_id<>ctx.action_id OR NEW.publication_id<>ctx.publication_id
                OR NEW.revision<>(ctx.definition_json->>'revision')::BIGINT OR NEW.operation<>ctx.operation OR NEW.definition_digest<>ctx.definition_digest
                OR NEW.actor_issuer<>ctx.actor_issuer OR NEW.actor_subject<>ctx.actor_subject OR NEW.recorded_at<>ctx.recorded_at THEN
            RAISE EXCEPTION 'Exact current document decision required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='cfg_document_issuer_action' THEN
        IF NEW.action_id<>ctx.action_id OR NEW.operation<>ctx.operation OR NEW.intent_digest<>ctx.intent_digest OR NEW.recorded_at<>ctx.recorded_at
                OR public.cfg_document_issuer_receipt_digest(NEW.result_json::JSON)<>NEW.result_digest OR NEW.result_json::JSONB IS DISTINCT FROM expected THEN
            RAISE EXCEPTION 'Exact document action receipt required' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='cfg_document_issuer_audit' THEN
        IF NEW.action_id<>ctx.action_id OR NEW.operation<>ctx.operation OR NEW.intent_digest<>ctx.intent_digest OR NEW.actor_issuer<>ctx.actor_issuer
                OR NEW.actor_subject<>ctx.actor_subject OR NEW.recorded_at<>ctx.recorded_at THEN RAISE EXCEPTION 'Exact document audit required' USING ERRCODE='23514'; END IF;
    ELSE
        IF NEW.action_id<>ctx.action_id OR NEW.event_type<>target_state OR NEW.recorded_at<>ctx.recorded_at
                OR NEW.payload_digest<>public.cfg_document_issuer_receipt_digest(expected::JSON) THEN RAISE EXCEPTION 'Exact document outbox required' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION public.cfg_document_issuer_complete() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE ctx cfg_document_issuer_private.command_context; expected JSONB; target_state TEXT;
    revision_row public.cfg_document_issuer_revision; control_row public.cfg_document_issuer_control;
    decision_row public.cfg_document_issuer_decision; action_row public.cfg_document_issuer_action;
    audit_row public.cfg_document_issuer_audit; outbox_row public.cfg_document_issuer_outbox;
BEGIN
    SELECT * INTO STRICT ctx FROM cfg_document_issuer_private.command_context WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user;
    IF ctx.completed THEN RETURN NULL; END IF;
    IF NOT public.cfg_document_issuer_context_current() THEN RAISE EXCEPTION 'Document authority expired before completion' USING ERRCODE='42501'; END IF;
    SELECT * INTO action_row FROM public.cfg_document_issuer_action a WHERE a.action_id=ctx.action_id;
    SELECT * INTO audit_row FROM public.cfg_document_issuer_audit a WHERE a.action_id=ctx.action_id;
    IF ctx.replay_only THEN
        IF action_row.tenant_id IS DISTINCT FROM ctx.tenant_id OR action_row.issuer_id IS DISTINCT FROM ctx.issuer_id OR action_row.operation IS DISTINCT FROM ctx.operation
                OR action_row.intent_digest IS DISTINCT FROM ctx.intent_digest OR audit_row.actor_issuer IS DISTINCT FROM ctx.actor_issuer OR audit_row.actor_subject IS DISTINCT FROM ctx.actor_subject THEN
            RAISE EXCEPTION 'Exact unchanged owner replay required' USING ERRCODE='23514';
        END IF;
    ELSE
        expected:=public.cfg_document_issuer_expected_receipt(ctx);target_state:=expected->>'state';
        SELECT * INTO revision_row FROM public.cfg_document_issuer_revision r WHERE r.publication_id=ctx.publication_id;
        SELECT * INTO control_row FROM public.cfg_document_issuer_control c WHERE c.tenant_id=ctx.tenant_id AND c.issuer_id=ctx.issuer_id;
        SELECT * INTO decision_row FROM public.cfg_document_issuer_decision d WHERE d.action_id=ctx.action_id;
        SELECT * INTO outbox_row FROM public.cfg_document_issuer_outbox o WHERE o.action_id=ctx.action_id;
        IF action_row.action_id IS NULL OR audit_row.action_id IS NULL OR outbox_row.action_id IS NULL OR revision_row.publication_id IS NULL
                OR control_row.publication_id IS DISTINCT FROM ctx.publication_id OR control_row.generation IS DISTINCT FROM ctx.expected_generation+1 OR control_row.state IS DISTINCT FROM target_state
                OR control_row.revision IS DISTINCT FROM (ctx.definition_json->>'revision')::BIGINT OR control_row.source_generation IS DISTINCT FROM (ctx.definition_json->'source'->>'sourceGeneration')::BIGINT
                OR revision_row.tenant_id IS DISTINCT FROM ctx.tenant_id OR revision_row.issuer_id IS DISTINCT FROM ctx.issuer_id
                OR revision_row.definition_digest IS DISTINCT FROM ctx.definition_digest OR revision_row.definition_json::JSONB IS DISTINCT FROM ctx.definition_json::JSONB
                OR action_row.result_json::JSONB IS DISTINCT FROM expected OR action_row.result_digest IS DISTINCT FROM public.cfg_document_issuer_receipt_digest(expected::JSON)
                OR audit_row.actor_issuer IS DISTINCT FROM ctx.actor_issuer OR audit_row.actor_subject IS DISTINCT FROM ctx.actor_subject
                OR outbox_row.payload_digest IS DISTINCT FROM action_row.result_digest OR outbox_row.event_type IS DISTINCT FROM target_state
                OR ctx.operation='PREPARE' AND decision_row.action_id IS NOT NULL
                OR ctx.operation IN ('PUBLISH','REVOKE') AND (decision_row.action_id IS NULL OR decision_row.operation IS DISTINCT FROM ctx.operation
                    OR decision_row.publication_id IS DISTINCT FROM ctx.publication_id OR decision_row.definition_digest IS DISTINCT FROM ctx.definition_digest)
                OR ctx.operation='PUBLISH' AND revision_row.maker_issuer=ctx.actor_issuer AND revision_row.maker_subject=ctx.actor_subject
                OR ctx.operation IN ('PREPARE','PUBLISH') AND public.cfg_document_issuer_actual_source(ctx.tenant_id,(ctx.definition_json->'source'->>'brandingId')::UUID) IS DISTINCT FROM (ctx.definition_json->'source')::JSONB THEN
            RAISE EXCEPTION 'Complete exact document lifecycle effect required' USING ERRCODE='23514';
        END IF;
    END IF;
    UPDATE cfg_document_issuer_private.command_context SET completed=TRUE WHERE xid=ctx.xid AND backend_pid=ctx.backend_pid;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER cfg_document_issuer_context_complete AFTER INSERT ON cfg_document_issuer_private.command_context
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.cfg_document_issuer_complete();
DO $triggers$
DECLARE relation_name TEXT;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['cfg_document_issuer_revision','cfg_document_issuer_control','cfg_document_issuer_decision','cfg_document_issuer_action','cfg_document_issuer_audit','cfg_document_issuer_outbox'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.cfg_document_issuer_write_guard()',relation_name||'_admission',relation_name);
    END LOOP;
END $triggers$;

CREATE OR REPLACE FUNCTION public.cfg_document_issuer_source_statement_gate() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM cfg_document_issuer_private.command_context WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid()) THEN
        RAISE EXCEPTION 'Source mutation within document command denied' USING ERRCODE='42501';
    END IF;
    PERFORM 1 FROM public.cfg_document_issuer_source_catalogue WHERE singleton FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Document source catalogue unavailable' USING ERRCODE='42501'; END IF;
    IF EXISTS(SELECT 1 FROM cfg_document_issuer_private.source_witness WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user AND flushed) THEN
        RAISE EXCEPTION 'Late document source mutation denied' USING ERRCODE='42501';
    END IF;
    RETURN NULL;
END $$;

ALTER FUNCTION public.cfg_document_issuer_expected_receipt(cfg_document_issuer_private.command_context) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_receipt_digest(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_write_guard() OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_complete() OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_source_statement_gate() OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON FUNCTION public.cfg_document_issuer_expected_receipt(cfg_document_issuer_private.command_context),public.cfg_document_issuer_receipt_digest(JSON),
    public.cfg_document_issuer_write_guard(),public.cfg_document_issuer_complete(),public.cfg_document_issuer_source_statement_gate() FROM PUBLIC;

-- END 05-row-admission.sql
-- BEGIN 06-view-and-policies.sql
CREATE FUNCTION public.cfg_document_issuer_scope_matches(p_tenant UUID,p_issuer UUID DEFAULT NULL,p_publication UUID DEFAULT NULL,p_action UUID DEFAULT NULL) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE ctx cfg_document_issuer_private.command_context; payload JSONB;
BEGIN
    IF NOT public.cfg_document_issuer_context_current() THEN RETURN FALSE; END IF;
    IF public.cfg_document_issuer_environment(FALSE) THEN
        SELECT * INTO STRICT ctx FROM cfg_document_issuer_private.command_context WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user;
        RETURN p_tenant=ctx.tenant_id AND (p_issuer IS NULL OR p_issuer=ctx.issuer_id)
            AND (p_publication IS NULL OR p_publication=ctx.publication_id) AND (p_action IS NULL OR p_action=ctx.action_id);
    END IF;
    payload:=cfg_document_issuer_private.read_context();
    RETURN coalesce(payload IS NOT NULL AND payload->>'operation'='CURRENT' AND p_action IS NULL AND p_tenant=(payload->>'tenant')::UUID
        AND (p_issuer IS NULL OR p_issuer=(payload->>'issuer')::UUID)
        AND (p_publication IS NULL OR p_publication=(payload->>'publication')::UUID),FALSE);
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR no_data_found THEN RETURN FALSE;
END $$;

CREATE FUNCTION public.cfg_document_issuer_source_visible(p_tenant UUID,p_branding UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE ctx cfg_document_issuer_private.command_context; payload JSONB;
BEGIN
    IF NOT public.cfg_document_issuer_context_current() THEN RETURN FALSE; END IF;
    IF public.cfg_document_issuer_environment(FALSE) THEN
        IF NOT public.cfg_document_issuer_scope_matches(p_tenant,NULL,NULL,NULL) THEN RETURN FALSE; END IF;
        SELECT * INTO STRICT ctx FROM cfg_document_issuer_private.command_context WHERE xid=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND db_session_user=session_user;
        RETURN p_branding=(ctx.definition_json->'source'->>'brandingId')::UUID;
    END IF;
    payload:=cfg_document_issuer_private.read_context();
    RETURN coalesce(payload IS NOT NULL AND payload->>'operation' IN ('CURRENT','SOURCE')
        AND p_tenant=(payload->>'tenant')::UUID AND p_branding=(payload->>'branding')::UUID,FALSE);
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR no_data_found THEN RETURN FALSE;
END $$;

CREATE VIEW public.cfg_document_issuer_current_source WITH (security_barrier=TRUE,security_invoker=FALSE) AS
SELECT b.id AS branding_id,t.id AS tenant_id,t.version AS tenant_version,b.version AS branding_version,s.generation AS source_generation,
    t.code AS tenant_code,t.name AS tenant_name,t.active AS tenant_active,b.active AS branding_active,
    b.logo_url,b.primary_color,b.email_footer_text AS footer_text
FROM public.tenants t JOIN public.tenant_brandings b ON b.tenant_id=t.id
JOIN public.cfg_document_issuer_source_clock s ON s.tenant_id=t.id
WHERE public.cfg_document_issuer_source_visible(t.id,b.id);
ALTER VIEW public.cfg_document_issuer_current_source OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON public.cfg_document_issuer_current_source FROM PUBLIC;

DO $policies$
DECLARE relation_name TEXT; predicate TEXT;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['cfg_document_issuer_revision','cfg_document_issuer_control','cfg_document_issuer_decision','cfg_document_issuer_action','cfg_document_issuer_audit','cfg_document_issuer_outbox'] LOOP
        EXECUTE format('CREATE POLICY %I ON public.%I FOR SELECT TO cfg_document_issuer_guard USING(TRUE)',relation_name||'_guard_lookup',relation_name);
        predicate:=CASE WHEN relation_name IN ('cfg_document_issuer_revision','cfg_document_issuer_decision')
            THEN 'public.cfg_document_issuer_scope_matches(tenant_id,issuer_id,publication_id,NULL)'
            WHEN relation_name='cfg_document_issuer_control' THEN 'public.cfg_document_issuer_scope_matches(tenant_id,issuer_id,NULL,NULL)'
            ELSE 'public.cfg_document_issuer_scope_matches(tenant_id,issuer_id,NULL,action_id)' END;
        EXECUTE format('CREATE POLICY %I ON public.%I FOR SELECT TO cfg_document_issuer_commands USING(%s)',relation_name||'_command_read',relation_name,predicate);
        EXECUTE format('CREATE POLICY %I ON public.%I FOR INSERT TO cfg_document_issuer_commands WITH CHECK(%s)',relation_name||'_command_insert',relation_name,predicate);
    END LOOP;
END $policies$;
-- A row lock also requires UPDATE visibility. The protected opener retains its exact
-- scoped lookup; this policy permits locking only in the validated command environment.
-- Changed rows remain forbidden to the guard; application updates use their own policy.
CREATE POLICY cfg_document_issuer_control_guard_lock ON public.cfg_document_issuer_control
    FOR UPDATE TO cfg_document_issuer_guard USING(public.cfg_document_issuer_environment(FALSE))
    WITH CHECK(FALSE);

CREATE POLICY cfg_document_issuer_control_command_update ON public.cfg_document_issuer_control
    FOR UPDATE TO cfg_document_issuer_commands USING(public.cfg_document_issuer_scope_matches(tenant_id,issuer_id,NULL,NULL))
    WITH CHECK(public.cfg_document_issuer_scope_matches(tenant_id,issuer_id,NULL,NULL));
CREATE POLICY cfg_document_issuer_revision_current_read ON public.cfg_document_issuer_revision
    FOR SELECT TO cfg_document_issuer_read USING(public.cfg_document_issuer_scope_matches(tenant_id,issuer_id,publication_id,NULL));
CREATE POLICY cfg_document_issuer_control_current_read ON public.cfg_document_issuer_control
    FOR SELECT TO cfg_document_issuer_read USING(public.cfg_document_issuer_scope_matches(tenant_id,issuer_id,publication_id,NULL));
CREATE POLICY cfg_document_issuer_decision_current_read ON public.cfg_document_issuer_decision
    FOR SELECT TO cfg_document_issuer_read USING(operation='PUBLISH' AND public.cfg_document_issuer_scope_matches(tenant_id,issuer_id,publication_id,NULL));

GRANT SELECT,INSERT ON public.cfg_document_issuer_revision,public.cfg_document_issuer_control,public.cfg_document_issuer_decision,
    public.cfg_document_issuer_action,public.cfg_document_issuer_audit,public.cfg_document_issuer_outbox TO cfg_document_issuer_commands;
GRANT UPDATE(publication_id,revision,generation,state,source_generation) ON public.cfg_document_issuer_control TO cfg_document_issuer_commands;
GRANT SELECT(publication_id,tenant_id,issuer_id,revision,definition_json,definition_digest,source_generation,maker_issuer,maker_subject,prepared_at)
    ON public.cfg_document_issuer_revision TO cfg_document_issuer_read;
GRANT SELECT(issuer_id,tenant_id,publication_id,revision,generation,state,source_generation) ON public.cfg_document_issuer_control TO cfg_document_issuer_read;
GRANT SELECT(action_id,tenant_id,issuer_id,publication_id,revision,operation,definition_digest,actor_issuer,actor_subject,recorded_at)
    ON public.cfg_document_issuer_decision TO cfg_document_issuer_read;
GRANT SELECT ON public.cfg_document_issuer_current_source TO cfg_document_issuer_commands,cfg_document_issuer_read;

ALTER FUNCTION public.cfg_document_issuer_scope_matches(UUID,UUID,UUID,UUID) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_source_visible(UUID,UUID) OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON FUNCTION public.cfg_document_issuer_scope_matches(UUID,UUID,UUID,UUID),public.cfg_document_issuer_source_visible(UUID,UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.cfg_document_issuer_open(UUID,UUID,UUID,UUID,TEXT,BYTEA,TEXT,TEXT,TEXT,BIGINT),public.cfg_document_issuer_context_current(),
    public.cfg_document_issuer_scope_matches(UUID,UUID,UUID,UUID),public.cfg_document_issuer_source_visible(UUID,UUID) TO cfg_document_issuer_commands,cfg_document_issuer_read;

-- END 06-view-and-policies.sql
-- BEGIN 99-close-installation.sql
DO $close$
BEGIN
    IF current_setting('firefly.cfg_doc.install.temporary_guard_create',TRUE) IS DISTINCT FROM 'true' THEN
        RAISE EXCEPTION 'Verified document migration privilege marker required' USING ERRCODE='42501';
    END IF;
    IF EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE (n.nspname='public' AND c.relname LIKE 'cfg_document_issuer_%' OR n.nspname='cfg_document_issuer_private')
            AND c.relowner<>'cfg_document_issuer_guard'::REGROLE)
            OR EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE n.nspname='public' AND p.proname LIKE 'cfg_document_issuer_%' AND p.proowner<>'cfg_document_issuer_guard'::REGROLE) THEN
        RAISE EXCEPTION 'Complete protected document ownership required' USING ERRCODE='42501';
    END IF;
    REVOKE CREATE ON SCHEMA public FROM cfg_document_issuer_guard;
    PERFORM set_config('firefly.cfg_doc.install.temporary_guard_create','closed',TRUE);
END $close$;

-- END 99-close-installation.sql

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

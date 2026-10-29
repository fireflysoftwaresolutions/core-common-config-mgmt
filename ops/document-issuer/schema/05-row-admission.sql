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

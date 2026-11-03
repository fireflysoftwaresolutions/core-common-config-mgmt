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

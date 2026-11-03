-- Opt-in forward extension of V7; an exact card document use is not card issuance or signing authority.
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
    IF NOT EXISTS(SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
            WHERE n.nspname='public' AND p.proname='cfg_document_issuer_use' AND p.proowner=guard_row.oid
            AND pg_catalog.pg_get_function_identity_arguments(p.oid)='p_value json' AND NOT p.prosecdef
            AND p.provolatile='i' AND p.proconfig=ARRAY['search_path=pg_catalog, pg_temp']) THEN
        RAISE EXCEPTION 'Established protected document issuer codec required' USING ERRCODE='42501';
    END IF;
    GRANT CREATE ON SCHEMA public TO cfg_document_issuer_guard;
    PERFORM set_config('firefly.cfg_doc.install.temporary_guard_create','true',TRUE);
END $installation$;

SET LOCAL ROLE cfg_document_issuer_guard;
CREATE OR REPLACE FUNCTION public.cfg_document_issuer_use(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE purpose TEXT; document_type TEXT; locale TEXT;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['purpose','documentType','locale']);
    purpose:=p_value->>'purpose';document_type:=p_value->>'documentType';locale:=p_value->>'locale';
    IF COALESCE(purpose,'') NOT IN ('DEPOSIT_ACCOUNT_ENROLLMENT','INSURANCE_TERM_PC_QUOTE','CARD_ENROLLMENT') OR document_type !~ '^[A-Z][A-Z0-9_]{0,63}$'
            OR locale !~ '^[A-Za-z0-9][A-Za-z0-9-]{1,63}$' THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN 'R3:'||public.cfg_document_issuer_text(p_value->'purpose',1,64)
        ||public.cfg_document_issuer_text(p_value->'documentType',1,64)||public.cfg_document_issuer_text(p_value->'locale',2,64);
END $$;
RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM cfg_document_issuer_guard;
DO $$ BEGIN
 IF pg_catalog.has_schema_privilege('cfg_document_issuer_guard','public','CREATE') THEN
  RAISE EXCEPTION 'Temporary document issuer CREATE cleanup incomplete' USING ERRCODE='42501';END IF;
END $$;

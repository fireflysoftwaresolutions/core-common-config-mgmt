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

-- Separate administrator preparation; no credentials or business approvals are created here.
-- The caller sets firefly.cfg_doc.installer to its newly allocated restricted migration role.
DO $bootstrap$
DECLARE role_name TEXT; role_row RECORD; installer RECORD; installer_name TEXT; function_value REGPROCEDURE;
BEGIN
    installer_name:=nullif(current_setting('firefly.cfg_doc.installer',TRUE),'');
    IF installer_name IS NULL OR installer_name !~ '^[a-z][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Explicit restricted document installer required' USING ERRCODE='42501';
    END IF;
    SELECT * INTO installer FROM pg_roles WHERE rolname=installer_name;
    IF NOT FOUND OR NOT installer.rolcanlogin OR installer.rolsuper OR installer.rolbypassrls OR installer.rolcreatedb
            OR installer.rolcreaterole OR installer.rolreplication
            OR has_database_privilege(installer.oid,current_database(),'CREATE,TEMPORARY') THEN
        RAISE EXCEPTION 'Restricted document installer flags and database required' USING ERRCODE='42501';
    END IF;
    FOREACH role_name IN ARRAY ARRAY['cfg_document_issuer_guard','cfg_document_issuer_commands','cfg_document_issuer_read'] LOOP
        SELECT * INTO role_row FROM pg_roles WHERE rolname=role_name;
        IF NOT FOUND THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION',role_name);
            SELECT * INTO STRICT role_row FROM pg_roles WHERE rolname=role_name;
        END IF;
        IF role_row.rolcanlogin OR role_row.rolinherit OR role_row.rolsuper OR role_row.rolbypassrls OR role_row.rolcreatedb
                OR role_row.rolcreaterole OR role_row.rolreplication
                OR EXISTS(SELECT 1 FROM pg_auth_members WHERE member=role_row.oid)
                OR has_schema_privilege(role_row.oid,'public','CREATE')
                OR has_database_privilege(role_row.oid,current_database(),'CREATE,TEMPORARY') THEN
            RAISE EXCEPTION 'Protected document issuer role contract required' USING ERRCODE='42501';
        END IF;
        IF role_name<>'cfg_document_issuer_guard' AND (
                EXISTS(SELECT 1 FROM pg_class WHERE relowner=role_row.oid)
                OR EXISTS(SELECT 1 FROM pg_proc WHERE proowner=role_row.oid)
                OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspowner=role_row.oid)) THEN
            RAISE EXCEPTION 'Document runtime groups cannot own objects' USING ERRCODE='42501';
        END IF;
    END LOOP;
    IF EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='cfg_document_issuer_private'
            AND nspowner<>'cfg_document_issuer_guard'::REGROLE) THEN
        RAISE EXCEPTION 'Document private schema ownership mismatch' USING ERRCODE='42501';
    END IF;
    CREATE SCHEMA IF NOT EXISTS cfg_document_issuer_private AUTHORIZATION cfg_document_issuer_guard;
    REVOKE ALL ON SCHEMA cfg_document_issuer_private FROM PUBLIC,cfg_document_issuer_commands,cfg_document_issuer_read;
    IF NOT EXISTS(SELECT 1 FROM pg_extension WHERE extname='pgcrypto') THEN
        IF EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='cfg_document_issuer_crypto') THEN
            RAISE EXCEPTION 'Dedicated crypto schema must be allocated with extension' USING ERRCODE='42501';
        END IF;
        CREATE SCHEMA cfg_document_issuer_crypto;
        CREATE EXTENSION pgcrypto WITH SCHEMA cfg_document_issuer_crypto;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace
            JOIN pg_roles o ON o.oid=e.extowner
            WHERE e.extname='pgcrypto' AND n.nspname='cfg_document_issuer_crypto' AND n.nspowner=e.extowner
            AND o.rolsuper)
            OR EXISTS(SELECT 1 FROM pg_proc p WHERE p.pronamespace='cfg_document_issuer_crypto'::REGNAMESPACE
                AND NOT EXISTS(SELECT 1 FROM pg_depend d JOIN pg_extension e ON e.oid=d.refobjid
                    WHERE d.classid='pg_proc'::REGCLASS AND d.objid=p.oid AND d.deptype='e' AND e.extname='pgcrypto')) THEN
        RAISE EXCEPTION 'Administrator-owned dedicated pgcrypto extension required' USING ERRCODE='42501';
    END IF;
    REVOKE ALL ON SCHEMA cfg_document_issuer_crypto FROM PUBLIC,cfg_document_issuer_guard,cfg_document_issuer_commands,cfg_document_issuer_read;
    REVOKE ALL ON ALL FUNCTIONS IN SCHEMA cfg_document_issuer_crypto FROM PUBLIC,cfg_document_issuer_commands,cfg_document_issuer_read;
    GRANT USAGE ON SCHEMA cfg_document_issuer_crypto TO cfg_document_issuer_guard;
    GRANT EXECUTE ON FUNCTION cfg_document_issuer_crypto.hmac(BYTEA,BYTEA,TEXT),cfg_document_issuer_crypto.gen_random_bytes(INTEGER) TO cfg_document_issuer_guard;
    GRANT USAGE ON SCHEMA public TO cfg_document_issuer_guard,cfg_document_issuer_commands,cfg_document_issuer_read;
    GRANT SELECT(id,code,name,active,version) ON public.tenants TO cfg_document_issuer_guard;
    GRANT SELECT(id,tenant_id,logo_url,primary_color,email_footer_text,active,version) ON public.tenant_brandings TO cfg_document_issuer_guard;
    FOREACH function_value IN ARRAY ARRAY[
        'public.cfg_native_invalid()'::REGPROCEDURE,'public.cfg_native_shape(json,text[])'::REGPROCEDURE,
        'public.cfg_native_s(text)'::REGPROCEDURE,'public.cfg_native_string(json,integer)'::REGPROCEDURE,
        'public.cfg_native_uuid(json)'::REGPROCEDURE,'public.cfg_native_long(json,bigint,bigint)'::REGPROCEDURE,
        'public.cfg_native_bool(json)'::REGPROCEDURE] LOOP
        IF NOT EXISTS(SELECT 1 FROM pg_proc WHERE oid=function_value AND proowner='cfg_native_guard'::REGROLE AND NOT prosecdef) THEN
            RAISE EXCEPTION 'Exact existing pure validator ownership required' USING ERRCODE='42501';
        END IF;
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO cfg_document_issuer_guard',function_value);
    END LOOP;
    IF EXISTS(SELECT 1 FROM pg_auth_members WHERE member=installer.oid
            AND roleid<>'cfg_document_issuer_guard'::REGROLE) THEN
        RAISE EXCEPTION 'Document installer cannot inherit other roles' USING ERRCODE='42501';
    END IF;
    EXECUTE format('GRANT cfg_document_issuer_guard TO %I WITH INHERIT TRUE, SET TRUE, ADMIN FALSE',installer_name);
    EXECUTE format('GRANT USAGE,CREATE ON SCHEMA public TO %I WITH GRANT OPTION',installer_name);
    EXECUTE format('GRANT TRIGGER ON public.tenants,public.tenant_brandings TO %I',installer_name);
END $bootstrap$;

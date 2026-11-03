DO $history$
DECLARE installer_name TEXT:=current_setting('firefly.cfg_doc.installer',TRUE);installer_oid OID;history_schema OID;history_owner OID;history_table OID;
BEGIN
    IF current_user<>session_user OR installer_name IS NULL OR installer_name!~'^[a-z][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Explicit document deployment administrator and installer required' USING ERRCODE='42501';
    END IF;
    SELECT oid INTO installer_oid FROM pg_roles WHERE rolname=installer_name AND rolcanlogin AND NOT rolsuper
        AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication;
    IF NOT FOUND THEN RAISE EXCEPTION 'Existing restricted document installer required' USING ERRCODE='42501';END IF;
    SELECT oid,nspowner INTO history_schema,history_owner FROM pg_namespace WHERE nspname='cfg_document_issuer_migrations';
    IF NOT FOUND THEN
        EXECUTE format('CREATE SCHEMA cfg_document_issuer_migrations AUTHORIZATION %I',installer_name);
        SELECT oid,nspowner INTO STRICT history_schema,history_owner FROM pg_namespace WHERE nspname='cfg_document_issuer_migrations';
    END IF;
    IF history_owner<>installer_oid OR EXISTS(SELECT 1 FROM pg_namespace n,
            LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
            WHERE n.oid=history_schema AND a.grantee<>installer_oid)
            OR EXISTS(SELECT 1 FROM pg_proc WHERE pronamespace=history_schema) THEN
        RAISE EXCEPTION 'Exact isolated deployment history schema ownership and ACL required' USING ERRCODE='42501';
    END IF;
    SELECT oid INTO history_table FROM pg_class WHERE relnamespace=history_schema AND relname='flyway_schema_history' AND relkind='r' AND relowner=installer_oid;
    IF EXISTS(SELECT 1 FROM pg_class c WHERE c.relnamespace=history_schema AND
            NOT(c.oid IS NOT DISTINCT FROM history_table OR c.relkind='i' AND c.relowner=installer_oid AND EXISTS(SELECT 1 FROM pg_index i WHERE i.indexrelid=c.oid AND i.indrelid=history_table))) THEN
        RAISE EXCEPTION 'Empty schema or exact existing document history objects required' USING ERRCODE='42501';
    END IF;
    IF EXISTS(SELECT 1 FROM pg_type t WHERE t.typnamespace=history_schema AND
            NOT(t.typtype='c' AND t.typrelid IS NOT DISTINCT FROM history_table
                OR t.typtype='b' AND t.typcategory='A' AND EXISTS(SELECT 1 FROM pg_class c WHERE c.oid=history_table AND c.reltype=t.typelem)))
            OR EXISTS(SELECT 1 FROM pg_extension WHERE extnamespace=history_schema) THEN
        RAISE EXCEPTION 'Only expected document history row types allowed' USING ERRCODE='42501';
    END IF;
    IF has_schema_privilege('cfg_document_issuer_commands',history_schema,'USAGE,CREATE')
            OR has_schema_privilege('cfg_document_issuer_read',history_schema,'USAGE,CREATE')
            OR has_schema_privilege('cfg_document_issuer_guard',history_schema,'USAGE,CREATE') THEN
        RAISE EXCEPTION 'Runtime and document guard cannot access deployment history schema' USING ERRCODE='42501';
    END IF;
END $history$;

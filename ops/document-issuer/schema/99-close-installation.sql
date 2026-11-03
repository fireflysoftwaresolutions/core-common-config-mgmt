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

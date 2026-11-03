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

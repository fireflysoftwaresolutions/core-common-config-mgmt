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

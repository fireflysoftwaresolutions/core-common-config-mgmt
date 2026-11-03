-- Optional atomic binding native extension. No default Flyway location includes this file.
BEGIN;
DO $install$
BEGIN
 IF to_regclass('public.cfg_trb_scope') IS NOT NULL OR to_regclass('public.cfg_trb_definition_anchor') IS NOT NULL OR to_regclass('public.cfg_tenant_resource_binding_definition') IS NOT NULL OR to_regclass('public.cfg_tenant_resource_binding_head') IS NOT NULL
  OR EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND left(p.proname,8)='cfg_trb_')
 THEN RAISE EXCEPTION 'Fresh exact optional binding installation required; existing objects are preserved' USING ERRCODE='55000';END IF;
 IF to_regclass('public.cfg_native_proposal') IS NULL OR to_regclass('public.cfg_native_action') IS NULL
   OR NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='cfg_native_guard' AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit)
   OR NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='cfg_native_owner' AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls)
 THEN RAISE EXCEPTION 'Qualified original native authority installation required' USING ERRCODE='55000'; END IF;
 IF NOT pg_has_role(current_user,'cfg_native_guard','SET')
  OR NOT has_schema_privilege(current_user,'public','CREATE WITH GRANT OPTION')
  OR has_schema_privilege('cfg_native_guard','public','CREATE')
  OR has_schema_privilege('cfg_native_runtime','public','CREATE')
 THEN RAISE EXCEPTION 'Separately delegated installer and revoked guard schema CREATE required' USING ERRCODE='42501';END IF;
END $install$;
ALTER TABLE public.cfg_native_proposal DROP CONSTRAINT cfg_native_proposal_target_operation_check;
ALTER TABLE public.cfg_native_proposal ADD CONSTRAINT cfg_native_proposal_target_operation_check CHECK(target_operation IN
 ('GRANT_ISSUE','GRANT_CONTROL','TENANT_TRANSITION','STATUS_TRANSITION','TENANT_RESOURCE_BINDING_ESTABLISH','TENANT_RESOURCE_BINDING_REVOKE'));
ALTER TABLE public.cfg_native_proposal_revision DROP CONSTRAINT cfg_native_proposal_revision_target_encoding_check;
ALTER TABLE public.cfg_native_proposal_revision ADD CONSTRAINT cfg_native_proposal_revision_target_encoding_check CHECK(target_encoding IN
 ('FF_TENANT_INSTITUTION_GRANT_V2','FF_TENANT_ELIGIBILITY_TRANSITION_V1','FF_STATUS_ELIGIBILITY_TRANSITION_V1','FF_INSTITUTION_GRANT_CONTROL_V2','FF_TENANT_RESOURCE_BINDING_COMMAND_V1'));

CREATE TABLE public.cfg_tenant_resource_binding_definition (
 binding_id UUID NOT NULL CHECK(binding_id<>'00000000-0000-0000-0000-000000000000'::uuid),
 version BIGINT NOT NULL CHECK(version>0 AND version<9223372036854775807),
 namespace_digest TEXT COLLATE "C" NOT NULL CHECK(namespace_digest~'^[0-9a-f]{64}$'),
 issuer TEXT COLLATE "C" NOT NULL CHECK(octet_length(issuer) BETWEEN 1 AND 500 AND issuer!~'[[:cntrl:]]'),
 platform_tenant TEXT COLLATE "C" NOT NULL CHECK(octet_length(platform_tenant) BETWEEN 1 AND 1024 AND btrim(platform_tenant)<>'' AND platform_tenant!~'[[:cntrl:]]'),
 resource_tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE RESTRICT,
 definition_digest TEXT COLLATE "C" NOT NULL CHECK(definition_digest~'^[0-9a-f]{64}$'),
 tenant_generation BIGINT NOT NULL CHECK(tenant_generation>=0 AND tenant_generation<9223372036854775807),
 status_id UUID NOT NULL REFERENCES public.tenant_statuses(id) ON DELETE RESTRICT,
 status_generation BIGINT NOT NULL CHECK(status_generation>=0 AND status_generation<9223372036854775807),
 not_after_epoch_millis BIGINT NOT NULL CHECK(not_after_epoch_millis BETWEEN 1 AND 253402300799999),
 maker_issuer TEXT COLLATE "C" NOT NULL CHECK(octet_length(maker_issuer) BETWEEN 1 AND 500),
 maker_subject TEXT COLLATE "C" NOT NULL CHECK(octet_length(maker_subject) BETWEEN 1 AND 255),
 PRIMARY KEY(binding_id,version),
 UNIQUE(binding_id,version,definition_digest,namespace_digest),
 UNIQUE(namespace_digest,version),
 CHECK(namespace_digest=encode(sha256(convert_to('FF_PLATFORM_TENANT_NAMESPACE_V1'||('S'||octet_length(convert_to(issuer,'UTF8'))::text||':'||issuer)||('S'||octet_length(convert_to(platform_tenant,'UTF8'))::text||':'||platform_tenant),'UTF8')),'hex')),
 CHECK(definition_digest=encode(sha256(convert_to('FF_TENANT_RESOURCE_BINDING_DEFINITION_V1'||('S'||octet_length(convert_to(binding_id::text,'UTF8'))::text||':'||binding_id::text)
  ||('S'||octet_length(convert_to(issuer,'UTF8'))::text||':'||issuer)||('S'||octet_length(convert_to(platform_tenant,'UTF8'))::text||':'||platform_tenant)||('S'||octet_length(convert_to(resource_tenant_id::text,'UTF8'))::text||':'||resource_tenant_id::text)||('S'||octet_length(convert_to(version::text,'UTF8'))::text||':'||version::text)
  ||('S'||octet_length(convert_to(tenant_generation::text,'UTF8'))::text||':'||tenant_generation::text)||('S'||octet_length(convert_to(status_id::text,'UTF8'))::text||':'||status_id::text)||('S'||octet_length(convert_to(status_generation::text,'UTF8'))::text||':'||status_generation::text)||('S'||octet_length(convert_to(not_after_epoch_millis::text,'UTF8'))::text||':'||not_after_epoch_millis::text),'UTF8')),'hex'))
);
CREATE TABLE public.cfg_tenant_resource_binding_head (
 namespace_digest TEXT COLLATE "C" PRIMARY KEY,
 binding_id UUID NOT NULL,
 definition_version BIGINT NOT NULL,
 definition_digest TEXT COLLATE "C" NOT NULL,
 control_version BIGINT NOT NULL CHECK(control_version>0 AND control_version<9223372036854775807),
 state TEXT NOT NULL CHECK(state IN ('ACTIVE','REVOKED')),
 FOREIGN KEY(binding_id,definition_version,definition_digest,namespace_digest)
  REFERENCES public.cfg_tenant_resource_binding_definition(binding_id,version,definition_digest,namespace_digest) ON DELETE RESTRICT
);

CREATE TABLE public.cfg_trb_definition_anchor (
 binding_id UUID NOT NULL,
 version BIGINT NOT NULL,
 namespace_digest TEXT COLLATE "C" NOT NULL,
 resource_tenant_id UUID NOT NULL,
 definition_digest TEXT COLLATE "C" NOT NULL,
 creation_action_id UUID NOT NULL,
 creation_proposal_id UUID NOT NULL,
 receipt_id UUID NOT NULL,
 audit_id UUID NOT NULL,
 event_id UUID NOT NULL,
 maker_issuer TEXT COLLATE "C" NOT NULL,
 maker_subject TEXT COLLATE "C" NOT NULL,
 original_deadline_epoch_millis BIGINT NOT NULL,
 original_intent_digest TEXT COLLATE "C" NOT NULL CHECK(original_intent_digest~'^[0-9a-f]{64}$'),
 PRIMARY KEY(binding_id,version),
 FOREIGN KEY(binding_id,version,definition_digest,namespace_digest) REFERENCES public.cfg_tenant_resource_binding_definition(binding_id,version,definition_digest,namespace_digest) ON DELETE RESTRICT,
 FOREIGN KEY(resource_tenant_id,creation_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT,
 FOREIGN KEY(resource_tenant_id,creation_proposal_id) REFERENCES public.cfg_native_proposal(tenant_id,proposal_id) ON DELETE RESTRICT,
 CHECK(receipt_id<>'00000000-0000-0000-0000-000000000000'::uuid AND audit_id<>'00000000-0000-0000-0000-000000000000'::uuid AND event_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE public.cfg_trb_scope (
 backend_pid INTEGER NOT NULL,
 xid xid8 NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT false,
 session_principal NAME NOT NULL,
 role_setting TEXT NOT NULL,
 isolation TEXT NOT NULL CHECK(isolation='read committed'),
 read_only BOOLEAN NOT NULL CHECK(NOT read_only),
 mode TEXT NOT NULL CHECK(mode IN ('LOOKUP','PREPARE','DECIDE','CONTROL')),
 tenant_id UUID NOT NULL,
 namespace_digest TEXT,
 action_id UUID,
 proposal_id UUID,
 operation TEXT NOT NULL,
 canonical_intent TEXT NOT NULL,
 original BYTEA,
 actor JSONB NOT NULL,
 intent JSONB NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL,
 until_epoch_millis BIGINT NOT NULL,
 before_head JSONB,
 completion JSONB,
 PRIMARY KEY(backend_pid,xid),
 CHECK(until_epoch_millis>floor(extract(epoch FROM observed_at)*1000)::bigint)
);

CREATE FUNCTION public.cfg_trb_namespace(p_issuer TEXT,p_tenant TEXT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
BEGIN
 PERFORM public.cfg_native_string(to_json(p_issuer),500),public.cfg_native_string(to_json(p_tenant),1024);
 IF octet_length(p_issuer)>500 OR octet_length(p_tenant)>1024 THEN PERFORM public.cfg_native_invalid(); END IF;
 RETURN encode(sha256(convert_to('FF_PLATFORM_TENANT_NAMESPACE_V1'||public.cfg_native_s(p_issuer)||public.cfg_native_s(p_tenant),'UTF8')),'hex');
END $fn$;
CREATE FUNCTION public.cfg_trb_epoch(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
BEGIN
 PERFORM public.cfg_native_shape(p_value,ARRAY['tenantGeneration','statusId','statusGeneration']);
 PERFORM public.cfg_native_long(p_value->'tenantGeneration',0,9223372036854775806),public.cfg_native_long(p_value->'statusGeneration',0,9223372036854775806);
 RETURN public.cfg_native_s(p_value->>'tenantGeneration')
  ||public.cfg_native_uuid(p_value->'statusId')||public.cfg_native_s(p_value->>'statusGeneration');
END $fn$;
CREATE FUNCTION public.cfg_trb_definition(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE ns JSON;tenant JSON;epoch TEXT;issuer TEXT;platform TEXT;
BEGIN
 PERFORM public.cfg_native_shape(p_value,ARRAY['bindingId','namespace','resourceTenant','version','eligibility','notAfterEpochMillis']);
 ns:=p_value->'namespace';tenant:=p_value->'resourceTenant';
 PERFORM public.cfg_native_shape(ns,ARRAY['issuer','platformTenant']),public.cfg_native_shape(tenant,ARRAY['value']);
 PERFORM public.cfg_native_string(ns->'issuer',500),public.cfg_native_string(ns->'platformTenant',1024);
 issuer:=ns->>'issuer';platform:=ns->>'platformTenant';
 PERFORM public.cfg_trb_namespace(issuer,platform);epoch:=public.cfg_trb_epoch(p_value->'eligibility');
 PERFORM public.cfg_native_long(p_value->'version',1,9223372036854775806),public.cfg_native_long(p_value->'notAfterEpochMillis',1,253402300799999);
 RETURN 'FF_TENANT_RESOURCE_BINDING_DEFINITION_V1'||public.cfg_native_uuid(p_value->'bindingId')
  ||public.cfg_native_s(issuer)||public.cfg_native_s(platform)||public.cfg_native_uuid(tenant->'value')
  ||public.cfg_native_s(p_value->>'version')||epoch
  ||public.cfg_native_s(p_value->>'notAfterEpochMillis');
END $fn$;
CREATE FUNCTION public.cfg_trb_command(p_value JSON) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE definition TEXT;canonical TEXT;operation TEXT;epoch TEXT;body JSONB;
BEGIN
 PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','proposalId','operation','definition','expectedCurrentEligibility','expectedControlVersion']);
 definition:=public.cfg_trb_definition(p_value->'definition');epoch:=public.cfg_trb_epoch(p_value->'expectedCurrentEligibility');
 PERFORM public.cfg_native_string(p_value->'operation',64),public.cfg_native_long(p_value->'expectedControlVersion',0,9223372036854775805);
 operation:=p_value->>'operation';
 IF operation NOT IN ('TENANT_RESOURCE_BINDING_ESTABLISH','TENANT_RESOURCE_BINDING_REVOKE') THEN PERFORM public.cfg_native_invalid(); END IF;
 canonical:='FF_TENANT_RESOURCE_BINDING_COMMAND_V1'||public.cfg_native_uuid(p_value->'actionId')
  ||public.cfg_native_uuid(p_value->'proposalId')||public.cfg_native_s(operation)
  ||public.cfg_native_s(p_value->>'expectedControlVersion')||epoch||public.cfg_native_s(definition);
 body:=p_value::jsonb;
 IF operation='TENANT_RESOURCE_BINDING_ESTABLISH' AND body->'expectedCurrentEligibility' IS DISTINCT FROM body->'definition'->'eligibility' THEN PERFORM public.cfg_native_invalid(); END IF;
 RETURN jsonb_build_object('command',body,'canonical',canonical,'intentDigest',encode(sha256(convert_to(canonical,'UTF8')),'hex'),
  'definitionCanonical',definition,'definitionDigest',encode(sha256(convert_to(definition,'UTF8')),'hex'),
  'namespaceDigest',public.cfg_trb_namespace(body->'definition'->'namespace'->>'issuer',body->'definition'->'namespace'->>'platformTenant'));
END $fn$;
CREATE FUNCTION public.cfg_trb_json(p_original BYTEA) RETURNS JSON
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
BEGIN
 IF p_original IS NULL OR octet_length(p_original) NOT BETWEEN 1 AND 65536 THEN PERFORM public.cfg_native_invalid(); END IF;
 RETURN convert_from(p_original,'UTF8')::json;
END $fn$;

CREATE FUNCTION public.cfg_trb_session() RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
 SELECT coalesce(current_user='cfg_native_guard' AND (session_user='cfg_native_runtime' OR current_setting('role')='cfg_native_runtime')
  AND NOT r.rolsuper AND NOT r.rolbypassrls AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolreplication
  AND NOT pg_has_role(session_user,'cfg_native_owner','MEMBER') AND NOT pg_has_role(session_user,'cfg_native_guard','MEMBER')
  AND NOT has_schema_privilege(session_user,'public','CREATE')
  AND EXISTS(SELECT 1 FROM pg_roles runtime WHERE runtime.rolname='cfg_native_runtime' AND NOT runtime.rolsuper AND NOT runtime.rolbypassrls
   AND NOT runtime.rolinherit AND NOT runtime.rolcreatedb AND NOT runtime.rolcreaterole)
  AND current_setting('transaction_isolation')='read committed' AND current_setting('transaction_read_only')='off',false)
 FROM pg_roles r WHERE r.rolname=session_user
$fn$;
CREATE FUNCTION public.cfg_trb_current() RETURNS public.cfg_trb_scope
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE scope public.cfg_trb_scope%ROWTYPE;
BEGIN
 SELECT * INTO scope FROM public.cfg_trb_scope s WHERE s.backend_pid=pg_backend_pid() AND s.xid=pg_current_xact_id();
 IF NOT FOUND OR scope.completed OR NOT public.cfg_trb_session() OR scope.session_principal IS DISTINCT FROM session_user
  OR scope.role_setting IS DISTINCT FROM current_setting('role') OR scope.isolation IS DISTINCT FROM current_setting('transaction_isolation')
  OR scope.read_only OR scope.until_epoch_millis<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint
 THEN RAISE EXCEPTION 'Current protected binding owning session required' USING ERRCODE='42501'; END IF;
 RETURN scope;
END $fn$;
CREATE FUNCTION public.cfg_trb_lookup_lock_current(p_tenant UUID,p_status UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE scope public.cfg_trb_scope%ROWTYPE;
BEGIN
 IF (p_tenant IS NULL)=(p_status IS NULL) THEN RETURN false;END IF;
 scope:=public.cfg_trb_current();
 IF scope.mode<>'LOOKUP' OR scope.operation<>'TENANT_RESOURCE_BINDING_LOOKUP' THEN RETURN false;END IF;
 IF p_tenant IS NOT NULL THEN RETURN p_tenant=scope.tenant_id;END IF;
 RETURN EXISTS(SELECT 1 FROM public.tenants t WHERE t.id=scope.tenant_id AND t.tenant_status_id=p_status);
EXCEPTION WHEN insufficient_privilege THEN RETURN false;
END $fn$;
CREATE FUNCTION public.cfg_trb_timeouts(p_until BIGINT) RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE remaining BIGINT:=p_until-floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
BEGIN
 IF remaining IS NULL OR remaining<1 OR remaining>30000 THEN RAISE EXCEPTION 'Finite nonrenewed binding native cap required' USING ERRCODE='42501'; END IF;
 PERFORM set_config('statement_timeout',remaining::text,true),set_config('lock_timeout',remaining::text,true);
END $fn$;
CREATE FUNCTION public.cfg_trb_enter(p_mode TEXT,p_tenant UUID,p_namespace TEXT,p_action UUID,p_proposal UUID,p_operation TEXT,
 p_canonical TEXT,p_original BYTEA,p_actor JSONB,p_intent JSONB,p_until BIGINT) RETURNS public.cfg_trb_scope
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE observed TIMESTAMPTZ:=clock_timestamp();scope public.cfg_trb_scope%ROWTYPE;now_millis BIGINT:=floor(extract(epoch FROM observed)*1000)::bigint;
BEGIN
 IF NOT public.cfg_trb_session() OR p_until IS NULL OR p_until<=now_millis OR p_until>now_millis+30000
 THEN RAISE EXCEPTION 'Finite original restricted binding admission required' USING ERRCODE='42501'; END IF;
 DELETE FROM public.cfg_trb_scope s WHERE s.backend_pid=pg_backend_pid() AND s.xid<>pg_current_xact_id() AND s.completed;
 IF EXISTS(SELECT 1 FROM public.cfg_trb_scope s WHERE s.backend_pid=pg_backend_pid() AND s.xid=pg_current_xact_id())
  OR EXISTS(SELECT 1 FROM public.cfg_trb_scope s WHERE s.backend_pid=pg_backend_pid() AND s.xid<>pg_current_xact_id() AND NOT s.completed)
 THEN RAISE EXCEPTION 'Existing active or spent binding scope cannot be reopened in this owning transaction' USING ERRCODE='42501'; END IF;
 PERFORM public.cfg_trb_timeouts(p_until);
 INSERT INTO public.cfg_trb_scope(backend_pid,xid,session_principal,role_setting,isolation,read_only,mode,tenant_id,namespace_digest,
  action_id,proposal_id,operation,canonical_intent,original,actor,intent,observed_at,until_epoch_millis)
 VALUES(pg_backend_pid(),pg_current_xact_id(),session_user,current_setting('role'),current_setting('transaction_isolation'),false,p_mode,p_tenant,p_namespace,
  p_action,p_proposal,p_operation,p_canonical,p_original,p_actor,p_intent,observed,p_until) RETURNING * INTO scope;
 RETURN scope;
END $fn$;
CREATE FUNCTION public.cfg_trb_actor(p_kind TEXT,p_issuer TEXT,p_subject TEXT,p_tenant UUID,p_employee UUID,p_client TEXT,p_staff BOOLEAN) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
BEGIN
 PERFORM public.cfg_native_string(to_json(p_issuer),500),public.cfg_native_string(to_json(p_subject),255),public.cfg_native_uuid(to_json(p_tenant::text));
 IF p_kind IS NULL OR p_kind NOT IN ('STAFF','MACHINE') OR p_staff AND p_kind<>'STAFF'
  OR p_kind='STAFF' AND p_employee IS NULL OR p_kind='MACHINE' AND(p_employee IS NOT NULL OR p_client IS NULL)
 THEN RAISE EXCEPTION 'Actual binding staff or machine admission required' USING ERRCODE='42501'; END IF;
 IF p_client IS NOT NULL THEN PERFORM public.cfg_native_string(to_json(p_client),255);END IF;
 IF p_kind='STAFF' THEN PERFORM public.cfg_native_uuid(to_json(p_employee::text));ELSE PERFORM public.cfg_native_string(to_json(p_client),255);END IF;
 RETURN jsonb_build_object('kind',p_kind,'issuer',p_issuer,'subject',p_subject,'tenantId',p_tenant,'employeeId',p_employee,'clientId',p_client);
END $fn$;
CREATE FUNCTION public.cfg_trb_legacy(p_scope public.cfg_trb_scope) RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE legacy_phase TEXT;
BEGIN
 IF p_scope.mode='PREPARE' AND p_scope.operation='TENANT_RESOURCE_BINDING_PREPARE' THEN legacy_phase:='PREPARE';
 ELSIF p_scope.mode='DECIDE' AND p_scope.operation='TENANT_RESOURCE_BINDING_SUBMIT' THEN legacy_phase:='SUBMIT';
 ELSIF p_scope.mode='DECIDE' AND p_scope.operation='TENANT_RESOURCE_BINDING_APPROVE' THEN legacy_phase:='APPROVE';
 ELSIF p_scope.mode='DECIDE' AND p_scope.operation='TENANT_RESOURCE_BINDING_REJECT' THEN legacy_phase:='REJECT';
 ELSIF p_scope.mode='DECIDE' AND p_scope.operation='TENANT_RESOURCE_BINDING_WITHDRAW' THEN legacy_phase:='WITHDRAW';
 ELSIF p_scope.mode='CONTROL' AND p_scope.operation='TENANT_RESOURCE_BINDING_ESTABLISH' THEN legacy_phase:='TENANT_RESOURCE_BINDING_ESTABLISH';
 ELSIF p_scope.mode='CONTROL' AND p_scope.operation='TENANT_RESOURCE_BINDING_REVOKE' THEN legacy_phase:='TENANT_RESOURCE_BINDING_REVOKE';
 ELSE RAISE EXCEPTION 'Exact binding legacy phase required' USING ERRCODE='42501';
 END IF;
 IF p_scope.mode='LOOKUP' OR p_scope.xid<>pg_current_xact_id() OR p_scope.backend_pid<>pg_backend_pid() THEN RAISE EXCEPTION 'Actual UUID control-plane scope required' USING ERRCODE='42501';END IF;
 PERFORM set_config('firefly.cfg.xid',p_scope.xid::text,true),set_config('firefly.cfg.tenant',p_scope.tenant_id::text,true),
  set_config('firefly.cfg.institution','',true),set_config('firefly.cfg.operation',legacy_phase,true),
  set_config('firefly.cfg.resource_type',CASE WHEN p_scope.mode='CONTROL' THEN 'TENANT_RESOURCE_BINDING' ELSE 'TENANT_RESOURCE_BINDING_PROPOSAL' END,true),
  set_config('firefly.cfg.resource',CASE WHEN p_scope.mode='CONTROL' THEN p_scope.intent->'command'->'definition'->>'bindingId' ELSE p_scope.proposal_id::text END,true),
  set_config('firefly.cfg.action',p_scope.action_id::text,true),set_config('firefly.cfg.intent',encode(sha256(convert_to(p_scope.canonical_intent,'UTF8')),'hex'),true),
  set_config('firefly.cfg.actor_kind',p_scope.actor->>'kind',true),set_config('firefly.cfg.actor_issuer',p_scope.actor->>'issuer',true),
  set_config('firefly.cfg.actor_subject',p_scope.actor->>'subject',true),set_config('firefly.cfg.actor_tenant',p_scope.actor->>'tenantId',true),
  set_config('firefly.cfg.employee',coalesce(p_scope.actor->>'employeeId',''),true),set_config('firefly.cfg.client',coalesce(p_scope.actor->>'clientId',''),true),
  set_config('firefly.cfg.platform_recovery','true',true),set_config('firefly.cfg.authority_until',p_scope.until_epoch_millis::text,true),set_config('firefly.cfg.command_until',p_scope.until_epoch_millis::text,true);
END $fn$;

CREATE FUNCTION public.cfg_trb_lock_eligibility(p_tenants UUID[]) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE scope public.cfg_trb_scope%ROWTYPE;key UUID;candidate UUID;source public.tenants%ROWTYPE;tenant public.cfg_native_tenant_eligibility%ROWTYPE;
 status public.cfg_native_status_eligibility%ROWTYPE;status_source public.tenant_statuses%ROWTYPE;answer JSONB:='{}'::jsonb;statuses UUID[];
BEGIN
 scope:=public.cfg_trb_current();
 IF p_tenants IS NULL OR cardinality(p_tenants) NOT BETWEEN 1 AND 2 OR array_position(p_tenants,NULL) IS NOT NULL
  OR array_position(p_tenants,'00000000-0000-0000-0000-000000000000'::uuid) IS NOT NULL THEN PERFORM public.cfg_native_invalid(); END IF;
 SELECT array_agg(DISTINCT t.tenant_status_id ORDER BY t.tenant_status_id) INTO statuses FROM public.tenants t WHERE t.id=ANY(p_tenants);
 IF statuses IS NULL THEN RAISE EXCEPTION 'Existing governed tenant required' USING ERRCODE='55000';END IF;
 FOREACH key IN ARRAY statuses LOOP
  PERFORM 1 FROM public.tenant_statuses s WHERE s.id=key FOR SHARE;
  PERFORM 1 FROM public.cfg_native_status_eligibility s WHERE s.status_id=key FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Existing native status eligibility required; no seed is issued' USING ERRCODE='55000';END IF;
  PERFORM public.cfg_trb_current();
 END LOOP;
 FOR key IN SELECT DISTINCT value FROM unnest(p_tenants) AS requested(value) ORDER BY value LOOP
  SELECT * INTO source FROM public.tenants t WHERE t.id=key FOR SHARE;
  IF NOT FOUND OR NOT(source.tenant_status_id=ANY(statuses)) THEN RAISE EXCEPTION 'Tenant status changed; exact owning transaction must restart' USING ERRCODE='40001';END IF;
  SELECT * INTO tenant FROM public.cfg_native_tenant_eligibility t WHERE t.tenant_id=key FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Existing native tenant eligibility required; no seed is issued' USING ERRCODE='55000';END IF;
  SELECT * INTO status FROM public.cfg_native_status_eligibility s WHERE s.status_id=source.tenant_status_id;
  SELECT * INTO status_source FROM public.tenant_statuses s WHERE s.id=source.tenant_status_id;
  IF status.status_id IS NULL OR tenant.current_status_id IS DISTINCT FROM source.tenant_status_id
   OR tenant.enabled IS DISTINCT FROM(source.active IS TRUE) OR status.enabled IS DISTINCT FROM(status_source.active IS TRUE)
  THEN RAISE EXCEPTION 'Exact live tenant/status eligibility proof required' USING ERRCODE='23514';END IF;
  answer:=answer||jsonb_build_object(key::text,jsonb_build_object('tenantGeneration',tenant.generation,'statusId',status.status_id,
   'statusGeneration',status.generation,'eligibleNow',tenant.enabled AND status.enabled AND status_source.code='ACTIVE'));
  PERFORM public.cfg_trb_current();
 END LOOP;
 RETURN answer;
END $fn$;
CREATE FUNCTION public.cfg_trb_head(p_namespace TEXT,p_lock BOOLEAN DEFAULT false) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE value public.cfg_tenant_resource_binding_head%ROWTYPE;definition public.cfg_tenant_resource_binding_definition%ROWTYPE;
BEGIN
 IF p_lock THEN SELECT * INTO value FROM public.cfg_tenant_resource_binding_head h WHERE h.namespace_digest=p_namespace FOR UPDATE;
 ELSE SELECT * INTO value FROM public.cfg_tenant_resource_binding_head h WHERE h.namespace_digest=p_namespace;END IF;
 IF NOT FOUND THEN RETURN NULL;END IF;
 SELECT * INTO definition FROM public.cfg_tenant_resource_binding_definition d WHERE d.binding_id=value.binding_id AND d.version=value.definition_version;
 IF NOT FOUND OR definition.namespace_digest IS DISTINCT FROM p_namespace OR definition.definition_digest IS DISTINCT FROM value.definition_digest
 THEN RAISE EXCEPTION 'Exact immutable binding head required' USING ERRCODE='23514';END IF;
 RETURN to_jsonb(value)||jsonb_build_object('resource_tenant_id',definition.resource_tenant_id,'issuer',definition.issuer,'platform_tenant',definition.platform_tenant,
  'tenant_generation',definition.tenant_generation,'status_id',definition.status_id,'status_generation',definition.status_generation,'not_after_epoch_millis',definition.not_after_epoch_millis);
END $fn$;
CREATE FUNCTION public.cfg_trb_window(p_scope public.cfg_trb_scope) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE reference JSONB;resource JSONB;version BIGINT;state TEXT;definition JSONB;
BEGIN
 PERFORM public.cfg_trb_current();
 IF p_scope.mode='LOOKUP' THEN
  reference:=p_scope.intent->'reference';resource:=p_scope.intent->'resourceTenant';version:=(p_scope.intent->>'controlVersion')::bigint;state:=p_scope.intent->>'state';
 ELSE
  definition:=p_scope.intent->'command'->'definition';reference:=jsonb_build_object('bindingId',definition->>'bindingId','version',(definition->>'version')::bigint,'definitionDigest',p_scope.intent->>'definitionDigest');
  resource:=definition->'resourceTenant';version:=(p_scope.intent->'command'->>'expectedControlVersion')::bigint+1;
  state:=CASE WHEN p_scope.operation='TENANT_RESOURCE_BINDING_ESTABLISH' THEN 'ACTIVE' ELSE 'REVOKED' END;
 END IF;
 RETURN jsonb_build_object('namespaceDigest',p_scope.namespace_digest,'resourceTenant',resource,'reference',reference,
  'currentEligibility',p_scope.intent->'currentEligibility','controlVersion',version,'state',state,'eligibleNow',(p_scope.intent->>'eligibleNow')::boolean,
  'observedAtEpochMillis',floor(extract(epoch FROM p_scope.observed_at)*1000)::bigint,'validUntilEpochMillis',p_scope.until_epoch_millis,
  'effectiveSessionPrincipal',CASE WHEN p_scope.role_setting='none' THEN p_scope.session_principal::text ELSE p_scope.role_setting END);
END $fn$;
CREATE FUNCTION public.cfg_trb_control_current(p_namespace TEXT,p_binding UUID,p_version BIGINT,p_digest TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE scope public.cfg_trb_scope%ROWTYPE;definition JSONB;
BEGIN
 scope:=public.cfg_trb_current();IF scope.mode<>'CONTROL' OR scope.namespace_digest IS DISTINCT FROM p_namespace THEN RETURN false;END IF;
 definition:=scope.intent->'command'->'definition';
 RETURN coalesce((definition->>'bindingId')::uuid=p_binding AND
  ((definition->>'version')::bigint=p_version AND scope.intent->>'definitionDigest'=p_digest
   OR (scope.before_head->>'binding_id')::uuid=p_binding AND (scope.before_head->>'definition_version')::bigint=p_version AND scope.before_head->>'definition_digest'=p_digest),false);
EXCEPTION WHEN insufficient_privilege THEN RETURN false;
END $fn$;
CREATE FUNCTION public.cfg_trb_lookup_current(p_namespace TEXT,p_issuer TEXT,p_platform TEXT,p_tenant UUID,p_binding UUID,p_version BIGINT,p_digest TEXT,
 p_tenant_generation BIGINT,p_status UUID,p_status_generation BIGINT,p_control BIGINT,p_state TEXT,p_not_after BIGINT) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE scope public.cfg_trb_scope%ROWTYPE;reference JSONB;epoch JSONB;
BEGIN
 scope:=public.cfg_trb_current();reference:=scope.intent->'reference';epoch:=scope.intent->'currentEligibility';
 RETURN coalesce(scope.mode='LOOKUP' AND scope.namespace_digest=p_namespace AND scope.actor->>'issuer'=p_issuer AND scope.actor->>'tenantId'=p_platform
  AND scope.tenant_id=p_tenant AND (reference->>'bindingId')::uuid=p_binding AND (reference->>'version')::bigint=p_version AND reference->>'definitionDigest'=p_digest
  AND (epoch->>'tenantGeneration')::bigint=p_tenant_generation AND (epoch->>'statusId')::uuid=p_status AND (epoch->>'statusGeneration')::bigint=p_status_generation
  AND (scope.intent->>'controlVersion')::bigint=p_control AND p_state='ACTIVE' AND (scope.intent->>'eligibleNow')::boolean
  AND scope.until_epoch_millis<=p_not_after,false);
EXCEPTION WHEN insufficient_privilege THEN RETURN false;
END $fn$;
CREATE FUNCTION public.cfg_trb_open_lookup(p_issuer TEXT,p_subject TEXT,p_platform TEXT,p_until BIGINT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE ns TEXT;head JSONB;locked JSONB;live JSONB;epoch JSONB;scope public.cfg_trb_scope%ROWTYPE;until_millis BIGINT;
BEGIN
 IF NOT public.cfg_trb_session() THEN RAISE EXCEPTION 'Restricted binding owning session required' USING ERRCODE='42501';END IF;
 PERFORM public.cfg_trb_timeouts(p_until);ns:=public.cfg_trb_namespace(p_issuer,p_platform);PERFORM public.cfg_native_string(to_json(p_subject),255);
 head:=public.cfg_trb_head(ns);IF head IS NULL THEN RAISE EXCEPTION 'Established exact tenant resource binding required' USING ERRCODE='55000';END IF;
 until_millis:=least(p_until,(head->>'not_after_epoch_millis')::bigint);
 scope:=public.cfg_trb_enter('LOOKUP',(head->>'resource_tenant_id')::uuid,ns,NULL,NULL,'TENANT_RESOURCE_BINDING_LOOKUP',ns,NULL,
  jsonb_build_object('issuer',p_issuer,'subject',p_subject,'tenantId',p_platform),'{}'::jsonb,until_millis);
 live:=public.cfg_trb_lock_eligibility(ARRAY[scope.tenant_id])->scope.tenant_id::text;
 PERFORM 1 FROM public.cfg_tenant_resource_binding_head h WHERE h.namespace_digest=ns FOR SHARE;locked:=public.cfg_trb_head(ns);
 IF locked IS DISTINCT FROM head OR head->>'issuer' IS DISTINCT FROM p_issuer OR head->>'platform_tenant' IS DISTINCT FROM p_platform
  OR head->>'state'<>'ACTIVE' OR (live->>'eligibleNow')::boolean IS DISTINCT FROM true
  OR (head->>'tenant_generation')::bigint IS DISTINCT FROM (live->>'tenantGeneration')::bigint
  OR (head->>'status_id')::uuid IS DISTINCT FROM (live->>'statusId')::uuid OR (head->>'status_generation')::bigint IS DISTINCT FROM (live->>'statusGeneration')::bigint
 THEN RAISE EXCEPTION 'Current exact binding and eligibility epoch required' USING ERRCODE='55000';END IF;
 epoch:=live-'eligibleNow';
 UPDATE public.cfg_trb_scope SET before_head=head,intent=jsonb_build_object('reference',jsonb_build_object('bindingId',head->>'binding_id','version',(head->>'definition_version')::bigint,'definitionDigest',head->>'definition_digest'),
  'resourceTenant',jsonb_build_object('value',scope.tenant_id),'currentEligibility',epoch,'eligibleNow',true,'controlVersion',(head->>'control_version')::bigint,'state','ACTIVE')
 WHERE backend_pid=pg_backend_pid() AND xid=pg_current_xact_id() RETURNING * INTO scope;
 PERFORM public.cfg_trb_anchor(ns,(head->>'binding_id')::uuid,(head->>'definition_version')::bigint,head->>'definition_digest');
 RETURN public.cfg_trb_window(scope);
END $fn$;

CREATE FUNCTION public.cfg_trb_control_result(p_action public.cfg_native_action) RETURNS JSONB
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
BEGIN
 IF p_action.action_id IS NULL OR p_action.operation NOT IN ('TENANT_RESOURCE_BINDING_ESTABLISH','TENANT_RESOURCE_BINDING_REVOKE') OR p_action.result_kind<>'APPLIED'
 THEN RAISE EXCEPTION 'Original binding effect receipt required' USING ERRCODE='23514';END IF;
 RETURN jsonb_build_object('actionId',p_action.action_id,'intentDigest',p_action.intent_digest,'reference',p_action.result->'reference',
  'controlVersion',(p_action.result->>'controlVersion')::bigint,'state',p_action.result->>'state','receiptId',p_action.receipt_id,'auditId',p_action.audit_id,'eventId',p_action.event_id);
END $fn$;
CREATE FUNCTION public.cfg_trb_governance_result(p_action public.cfg_native_action,p_until BIGINT) RETURNS JSONB
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
BEGIN
 IF p_action.action_id IS NULL OR p_action.operation NOT IN ('TENANT_RESOURCE_BINDING_PREPARE','TENANT_RESOURCE_BINDING_SUBMIT','TENANT_RESOURCE_BINDING_APPROVE','TENANT_RESOURCE_BINDING_REJECT','TENANT_RESOURCE_BINDING_WITHDRAW')
  OR p_action.result_kind NOT IN ('PREPARED','DECIDED') THEN RAISE EXCEPTION 'Original binding proposal receipt required' USING ERRCODE='23514';END IF;
 RETURN jsonb_build_object('actionId',p_action.action_id,'intentDigest',p_action.intent_digest,'proposal',p_action.result,
  'nativeUntilEpochMillis',least(p_until,p_action.deadline_epoch_millis),'receiptId',p_action.receipt_id,'auditId',p_action.audit_id,'eventId',p_action.event_id);
END $fn$;
CREATE FUNCTION public.cfg_trb_existing(p_scope public.cfg_trb_scope) RETURNS public.cfg_native_action
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE action public.cfg_native_action%ROWTYPE;receipt public.cfg_native_receipt%ROWTYPE;audit public.cfg_native_audit%ROWTYPE;event public.cfg_native_outbox%ROWTYPE;until_millis BIGINT;
BEGIN
 SELECT * INTO action FROM public.cfg_native_action a WHERE a.tenant_id=p_scope.tenant_id AND a.action_id=p_scope.action_id;
 IF NOT FOUND THEN RETURN action;END IF;
 SELECT * INTO receipt FROM public.cfg_native_receipt r WHERE r.tenant_id=action.tenant_id AND r.action_id=action.action_id;
 SELECT * INTO audit FROM public.cfg_native_audit a WHERE a.tenant_id=action.tenant_id AND a.action_id=action.action_id;
 SELECT * INTO event FROM public.cfg_native_outbox e WHERE e.tenant_id=action.tenant_id AND e.action_id=action.action_id;
 IF action.canonical_intent IS DISTINCT FROM p_scope.canonical_intent OR action.operation IS DISTINCT FROM p_scope.operation
  OR action.proposal_id IS DISTINCT FROM p_scope.proposal_id OR action.proposal_revision IS DISTINCT FROM 1::bigint
  OR receipt.receipt_id IS DISTINCT FROM action.receipt_id OR audit.audit_id IS DISTINCT FROM action.audit_id OR event.event_id IS DISTINCT FROM action.event_id
  OR receipt.payload IS DISTINCT FROM audit.payload OR receipt.payload IS DISTINCT FROM event.payload
  OR receipt.payload IS DISTINCT FROM receipt.canonical_payload::jsonb OR receipt.payload->>'actionId' IS DISTINCT FROM action.action_id::text
  OR receipt.payload->>'intentDigest' IS DISTINCT FROM action.intent_digest OR receipt.payload->'result' IS DISTINCT FROM action.result
  OR decode(receipt.payload->>'originalBase64','base64') IS DISTINCT FROM action.original_request
 THEN RAISE EXCEPTION 'Exact original action receipt audit outbox required' USING ERRCODE='23514';END IF;
 IF p_scope.mode='CONTROL' AND public.cfg_trb_command(public.cfg_trb_json(action.original_request))->>'canonical' IS DISTINCT FROM action.canonical_intent THEN RAISE EXCEPTION 'Original complete binding command required' USING ERRCODE='23514';END IF;
 until_millis:=least(p_scope.until_epoch_millis,action.deadline_epoch_millis);
 IF until_millis<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RAISE EXCEPTION 'Original native receipt window expired; no replacement is issued' USING ERRCODE='42501';END IF;
 UPDATE public.cfg_trb_scope SET until_epoch_millis=until_millis WHERE backend_pid=p_scope.backend_pid AND xid=p_scope.xid;
 PERFORM public.cfg_trb_timeouts(until_millis);
 RETURN action;
END $fn$;
CREATE FUNCTION public.cfg_trb_target(p_scope public.cfg_trb_scope,p_execution BOOLEAN) RETURNS public.cfg_native_proposal
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;
 decision public.cfg_native_decision%ROWTYPE;command JSONB:=p_scope.intent->'command';epoch JSONB;
BEGIN
 SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_scope.tenant_id AND p.proposal_id=p_scope.proposal_id;
 SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=p_scope.tenant_id AND r.proposal_id=p_scope.proposal_id AND r.revision=1;
 SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=p_scope.tenant_id AND c.proposal_id=p_scope.proposal_id FOR UPDATE;
 epoch:=command->'expectedCurrentEligibility';
 IF proposal.proposal_id IS NULL OR revision.proposal_id IS NULL OR control.proposal_id IS NULL
  OR proposal.resource_id IS DISTINCT FROM(command->'definition'->>'bindingId')::uuid OR proposal.target_operation IS DISTINCT FROM command->>'operation'
  OR proposal.target_action_id IS DISTINCT FROM(command->>'actionId')::uuid OR revision.target_encoding<>'FF_TENANT_RESOURCE_BINDING_COMMAND_V1'
  OR revision.canonical_target IS DISTINCT FROM p_scope.intent->>'canonical' OR revision.target_digest IS DISTINCT FROM p_scope.intent->>'intentDigest'
  OR revision.tenant_generation IS DISTINCT FROM(epoch->>'tenantGeneration')::bigint OR revision.status_id IS DISTINCT FROM(epoch->>'statusId')::uuid
  OR revision.status_generation IS DISTINCT FROM(epoch->>'statusGeneration')::bigint OR revision.not_after_epoch_millis<=floor(extract(epoch FROM clock_timestamp())*1000)::bigint
 THEN RAISE EXCEPTION 'Retained exact binding proposal target and epoch required' USING ERRCODE='55000';END IF;
 IF p_execution THEN
  SELECT * INTO decision FROM public.cfg_native_decision d WHERE d.tenant_id=proposal.tenant_id AND d.decision_id=control.decision_id;
  IF control.state NOT IN ('APPROVED','EXECUTION_ADMITTED') OR control.state='EXECUTION_ADMITTED' AND control.execution_action_id IS DISTINCT FROM p_scope.action_id
   OR decision.operation IS DISTINCT FROM 'approve' OR decision.proposal_id IS DISTINCT FROM proposal.proposal_id OR decision.revision IS DISTINCT FROM 1::bigint
   OR decision.target_digest IS DISTINCT FROM revision.target_digest OR decision.employee_id=proposal.maker_employee_id
   OR decision.actor_issuer=proposal.maker_issuer AND decision.actor_subject=proposal.maker_subject
  THEN RAISE EXCEPTION 'Actual independent approved binding target required' USING ERRCODE='42501';END IF;
 END IF;
 UPDATE public.cfg_trb_scope SET until_epoch_millis=least(until_epoch_millis,revision.not_after_epoch_millis)
  WHERE backend_pid=p_scope.backend_pid AND xid=p_scope.xid;
 PERFORM public.cfg_trb_current();RETURN proposal;
END $fn$;

CREATE FUNCTION public.cfg_trb_bind_current(p_scope public.cfg_trb_scope,p_target JSONB,p_replay BOOLEAN) RETURNS public.cfg_trb_scope
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE candidate JSONB;head JSONB;live JSONB;epoch JSONB;command JSONB:=p_target->'command';definition JSONB:=command->'definition';
 expected BIGINT:=(command->>'expectedControlVersion')::bigint;tenant UUID:=p_scope.tenant_id;tenants UUID[];until_millis BIGINT;
BEGIN
 candidate:=public.cfg_trb_head(p_scope.namespace_digest);tenants:=ARRAY[tenant];
 IF candidate IS NOT NULL AND (candidate->>'resource_tenant_id')::uuid<>tenant THEN tenants:=tenants||(candidate->>'resource_tenant_id')::uuid;END IF;
 live:=public.cfg_trb_lock_eligibility(tenants)->tenant::text;epoch:=live-'eligibleNow';
 PERFORM 1 FROM public.cfg_tenant_resource_binding_head h WHERE h.namespace_digest=p_scope.namespace_digest FOR UPDATE;
 head:=public.cfg_trb_head(p_scope.namespace_digest);
 IF head IS DISTINCT FROM candidate THEN RAISE EXCEPTION 'Binding namespace changed while acquiring its exact plan' USING ERRCODE='40001';END IF;
 IF epoch IS DISTINCT FROM command->'expectedCurrentEligibility' OR command->>'operation'='TENANT_RESOURCE_BINDING_ESTABLISH' AND(live->>'eligibleNow')::boolean IS DISTINCT FROM true
 THEN RAISE EXCEPTION 'Exact current binding target eligibility required' USING ERRCODE='55000';END IF;
 IF p_replay THEN
  IF head IS NULL OR (head->>'control_version')::bigint IS DISTINCT FROM expected+1 OR (head->>'definition_version')::bigint IS DISTINCT FROM(definition->>'version')::bigint
   OR head->>'definition_digest' IS DISTINCT FROM p_target->>'definitionDigest' OR head->>'state' IS DISTINCT FROM(CASE WHEN command->>'operation'='TENANT_RESOURCE_BINDING_ESTABLISH' THEN 'ACTIVE' ELSE 'REVOKED' END)
  THEN RAISE EXCEPTION 'Historical head recovery is not a current duplicate admission' USING ERRCODE='55000';END IF;
 ELSIF expected=0 THEN
  IF head IS NOT NULL OR (definition->>'version')::bigint<>1 OR command->>'operation'<>'TENANT_RESOURCE_BINDING_ESTABLISH'
  THEN RAISE EXCEPTION 'Exact first binding establishment required' USING ERRCODE='55000';END IF;
 ELSE
  IF head IS NULL OR (head->>'control_version')::bigint IS DISTINCT FROM expected THEN RAISE EXCEPTION 'Exact expected binding head required' USING ERRCODE='55000';END IF;
  IF command->>'operation'='TENANT_RESOURCE_BINDING_REVOKE' THEN
   IF head->>'state'<>'ACTIVE' OR (head->>'definition_version')::bigint IS DISTINCT FROM(definition->>'version')::bigint
    OR head->>'definition_digest' IS DISTINCT FROM p_target->>'definitionDigest' THEN RAISE EXCEPTION 'Exact active immutable binding required for revocation' USING ERRCODE='55000';END IF;
  ELSIF (definition->>'version')::bigint IS DISTINCT FROM(head->>'definition_version')::bigint+1 THEN RAISE EXCEPTION 'Exact binding successor definition required' USING ERRCODE='55000';END IF;
 END IF;
 IF head IS NOT NULL AND(head->>'issuer' IS DISTINCT FROM definition->'namespace'->>'issuer' OR head->>'platform_tenant' IS DISTINCT FROM definition->'namespace'->>'platformTenant'
  OR (head->>'binding_id')::uuid IS DISTINCT FROM(definition->>'bindingId')::uuid) THEN RAISE EXCEPTION 'Exact retained binding namespace identity required' USING ERRCODE='23514';END IF;
 until_millis:=p_scope.until_epoch_millis;
 IF command->>'operation'='TENANT_RESOURCE_BINDING_ESTABLISH' THEN until_millis:=least(until_millis,(definition->>'notAfterEpochMillis')::bigint);END IF;
 UPDATE public.cfg_trb_scope SET before_head=head,intent=p_target||jsonb_build_object('currentEligibility',epoch,'eligibleNow',(live->>'eligibleNow')::boolean,'replay',p_replay),until_epoch_millis=until_millis
 WHERE backend_pid=p_scope.backend_pid AND xid=p_scope.xid RETURNING * INTO p_scope;
 PERFORM public.cfg_trb_current(),public.cfg_trb_timeouts(until_millis);RETURN p_scope;
END $fn$;

CREATE FUNCTION public.cfg_trb_preparation(p_value JSON) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE target JSONB;canonical TEXT;action TEXT;until_millis TEXT;
BEGIN
 PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','target','notAfterEpochMillis']);target:=public.cfg_trb_command(p_value->'target');
 PERFORM public.cfg_native_uuid(p_value->'actionId'),public.cfg_native_long(p_value->'notAfterEpochMillis',1,253402300799999);
 action:=p_value->>'actionId';until_millis:=p_value->>'notAfterEpochMillis';
 IF action=target->'command'->>'actionId' THEN PERFORM public.cfg_native_invalid();END IF;
 canonical:='FF_TENANT_RESOURCE_BINDING_PREPARE_V1'||public.cfg_native_s(action)||public.cfg_native_s(until_millis)||public.cfg_native_s(target->>'canonical');
 RETURN target||jsonb_build_object('prepareActionId',action,'proposalNotAfter',until_millis::bigint,'prepareCanonical',canonical);
END $fn$;
CREATE FUNCTION public.cfg_trb_decision(p_value JSON) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE canonical TEXT;operation TEXT;tenant JSON;digest TEXT;
BEGIN
 PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','resourceTenant','proposalId','operation','expectedControlVersion','targetDigest','notAfterEpochMillis']);
 tenant:=p_value->'resourceTenant';PERFORM public.cfg_native_shape(tenant,ARRAY['value']);
 PERFORM public.cfg_native_string(p_value->'operation',32),public.cfg_native_string(p_value->'targetDigest',64),
  public.cfg_native_long(p_value->'expectedControlVersion',0,9223372036854775805),public.cfg_native_long(p_value->'notAfterEpochMillis',1,253402300799999);
 operation:=p_value->>'operation';digest:=p_value->>'targetDigest';
 IF operation NOT IN ('SUBMIT','APPROVE','REJECT','WITHDRAW') OR digest!~'^[0-9a-f]{64}$' THEN PERFORM public.cfg_native_invalid();END IF;
 canonical:='FF_TENANT_RESOURCE_BINDING_DECISION_V1'||public.cfg_native_uuid(p_value->'actionId')
  ||public.cfg_native_uuid(tenant->'value')||public.cfg_native_uuid(p_value->'proposalId')||public.cfg_native_s(operation)
  ||public.cfg_native_s(p_value->>'expectedControlVersion')||public.cfg_native_s(digest)
  ||public.cfg_native_s(p_value->>'notAfterEpochMillis');
 RETURN jsonb_build_object('decision',p_value::jsonb,'canonical',canonical);
END $fn$;

CREATE FUNCTION public.cfg_trb_prepare(p_original BYTEA,p_kind TEXT,p_issuer TEXT,p_subject TEXT,p_actor_tenant UUID,p_employee UUID,p_client TEXT,p_until BIGINT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
<<binding_prepare>>
DECLARE body JSON;target JSONB;command JSONB;definition JSONB;epoch JSONB;scope public.cfg_trb_scope%ROWTYPE;actor JSONB;
 tenant UUID;proposal UUID;action_id UUID;not_after BIGINT;now_millis BIGINT;existing public.cfg_native_action%ROWTYPE;result JSONB;completion JSONB;
BEGIN
 IF NOT public.cfg_trb_session() THEN RAISE EXCEPTION 'Restricted binding owning session required' USING ERRCODE='42501';END IF;
 PERFORM public.cfg_trb_timeouts(p_until);body:=public.cfg_trb_json(p_original);target:=public.cfg_trb_preparation(body);command:=target->'command';definition:=command->'definition';epoch:=command->'expectedCurrentEligibility';
 actor:=public.cfg_trb_actor(p_kind,p_issuer,p_subject,p_actor_tenant,p_employee,p_client,true);
 tenant:=(definition->'resourceTenant'->>'value')::uuid;proposal:=(command->>'proposalId')::uuid;action_id:=(target->>'prepareActionId')::uuid;not_after:=(target->>'proposalNotAfter')::bigint;
 now_millis:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
 IF not_after<=now_millis OR not_after>now_millis+600000 THEN RAISE EXCEPTION 'Finite original binding proposal deadline required' USING ERRCODE='42501';END IF;
 scope:=public.cfg_trb_enter('PREPARE',tenant,target->>'namespaceDigest',action_id,proposal,'TENANT_RESOURCE_BINDING_PREPARE',target->>'prepareCanonical',p_original,actor,target,least(p_until,not_after));
 PERFORM public.cfg_trb_legacy(scope);scope:=public.cfg_trb_bind_current(scope,target,false);PERFORM public.cfg_trb_legacy(scope),public.cfg_native_action_gate(tenant,action_id);existing:=public.cfg_trb_existing(scope);
 IF existing.action_id IS NOT NULL THEN
  completion:=public.cfg_trb_governance_result(existing,scope.until_epoch_millis);
  UPDATE public.cfg_trb_scope SET completion=binding_prepare.completion WHERE backend_pid=pg_backend_pid() AND xid=pg_current_xact_id();RETURN completion;
 END IF;
 INSERT INTO public.cfg_native_proposal(tenant_id,proposal_id,resource_id,target_operation,target_action_id,maker_issuer,maker_subject,maker_employee_id,prepare_action_id)
 VALUES(tenant,proposal,(definition->>'bindingId')::uuid,command->>'operation',(command->>'actionId')::uuid,p_issuer,p_subject,p_employee,binding_prepare.action_id);
 INSERT INTO public.cfg_native_proposal_revision(tenant_id,proposal_id,revision,target_encoding,original_prepare,original_target,canonical_target,target_digest,not_after_epoch_millis,tenant_generation,status_id,status_generation)
 VALUES(tenant,proposal,1,'FF_TENANT_RESOURCE_BINDING_COMMAND_V1',p_original,convert_to((body->'target')::text,'UTF8'),target->>'canonical',target->>'intentDigest',not_after,(epoch->>'tenantGeneration')::bigint,(epoch->>'statusId')::uuid,(epoch->>'statusGeneration')::bigint);
 INSERT INTO public.cfg_native_proposal_control(tenant_id,proposal_id,state,control_version)VALUES(tenant,proposal,'DRAFT',0);
 result:=jsonb_build_object('proposalId',proposal,'targetDigest',target->>'intentDigest','state','DRAFT','controlVersion',0,'notAfterEpochMillis',not_after);
 PERFORM public.cfg_native_finish_action(tenant,action_id,'TENANT_RESOURCE_BINDING_PREPARE',proposal,proposal,1,p_original,scope.canonical_intent,scope.until_epoch_millis,'PREPARED',result);
 SELECT * INTO existing FROM public.cfg_native_action a WHERE a.tenant_id=tenant AND a.action_id=binding_prepare.action_id;
 completion:=public.cfg_trb_governance_result(existing,scope.until_epoch_millis);
 UPDATE public.cfg_trb_scope SET completion=binding_prepare.completion WHERE backend_pid=pg_backend_pid() AND xid=pg_current_xact_id();RETURN completion;
END $fn$;

CREATE FUNCTION public.cfg_trb_decide(p_original BYTEA,p_kind TEXT,p_issuer TEXT,p_subject TEXT,p_actor_tenant UUID,p_employee UUID,p_client TEXT,p_until BIGINT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
<<binding_decide>>
DECLARE parsed JSONB;body JSONB;target JSONB;scope public.cfg_trb_scope%ROWTYPE;actor JSONB;tenant UUID;proposal UUID;action_id UUID;
 revision public.cfg_native_proposal_revision%ROWTYPE;proposal_row public.cfg_native_proposal%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;existing public.cfg_native_action%ROWTYPE;
 decision_id UUID;operation TEXT;next_state TEXT;maker BOOLEAN;result JSONB;completion JSONB;
BEGIN
 IF NOT public.cfg_trb_session() THEN RAISE EXCEPTION 'Restricted binding owning session required' USING ERRCODE='42501';END IF;
 PERFORM public.cfg_trb_timeouts(p_until);parsed:=public.cfg_trb_decision(public.cfg_trb_json(p_original));body:=parsed->'decision';
 actor:=public.cfg_trb_actor(p_kind,p_issuer,p_subject,p_actor_tenant,p_employee,p_client,true);
 tenant:=(body->'resourceTenant'->>'value')::uuid;proposal:=(body->>'proposalId')::uuid;action_id:=(body->>'actionId')::uuid;operation:=body->>'operation';
 scope:=public.cfg_trb_enter('DECIDE',tenant,NULL,action_id,proposal,'TENANT_RESOURCE_BINDING_'||operation,parsed->>'canonical',p_original,actor,'{}'::jsonb,least(p_until,(body->>'notAfterEpochMillis')::bigint));PERFORM public.cfg_trb_legacy(scope);
 SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=tenant AND r.proposal_id=proposal AND r.revision=1;
 IF NOT FOUND OR revision.target_encoding<>'FF_TENANT_RESOURCE_BINDING_COMMAND_V1' OR revision.target_digest IS DISTINCT FROM body->>'targetDigest'
  OR revision.not_after_epoch_millis IS DISTINCT FROM(body->>'notAfterEpochMillis')::bigint THEN RAISE EXCEPTION 'Exact retained binding proposal decision required' USING ERRCODE='55000';END IF;
 target:=public.cfg_trb_command(public.cfg_trb_json(revision.original_target));
 UPDATE public.cfg_trb_scope SET namespace_digest=target->>'namespaceDigest',intent=target WHERE backend_pid=pg_backend_pid() AND xid=pg_current_xact_id() RETURNING * INTO scope;
 scope:=public.cfg_trb_bind_current(scope,target,false);proposal_row:=public.cfg_trb_target(scope,false);scope:=public.cfg_trb_current();PERFORM public.cfg_trb_legacy(scope),public.cfg_native_action_gate(tenant,action_id);existing:=public.cfg_trb_existing(scope);
 IF existing.action_id IS NOT NULL THEN
  completion:=public.cfg_trb_governance_result(existing,scope.until_epoch_millis);
  UPDATE public.cfg_trb_scope SET completion=binding_decide.completion WHERE backend_pid=pg_backend_pid() AND xid=pg_current_xact_id();RETURN completion;
 END IF;
 SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=tenant AND c.proposal_id=proposal FOR UPDATE;
 IF control.control_version IS DISTINCT FROM(body->>'expectedControlVersion')::bigint THEN RAISE EXCEPTION 'Exact current proposal control version required' USING ERRCODE='55000';END IF;
 maker:=p_employee=proposal_row.maker_employee_id AND p_issuer=proposal_row.maker_issuer AND p_subject=proposal_row.maker_subject;
 IF operation IN ('SUBMIT','WITHDRAW') THEN
  IF NOT maker THEN RAISE EXCEPTION 'Actual original binding proposal maker required' USING ERRCODE='42501';END IF;
  IF operation='SUBMIT' AND control.state='DRAFT' THEN next_state:='SUBMITTED';
  ELSIF operation='WITHDRAW' AND control.state IN ('DRAFT','SUBMITTED','APPROVED') THEN next_state:='WITHDRAWN';
  ELSE RAISE EXCEPTION 'Current binding proposal state required' USING ERRCODE='55000';END IF;
 ELSE
  IF p_employee=proposal_row.maker_employee_id OR p_issuer=proposal_row.maker_issuer AND p_subject=proposal_row.maker_subject
  THEN RAISE EXCEPTION 'Actual independent verified binding checker required' USING ERRCODE='42501';END IF;
  IF control.state<>'SUBMITTED' THEN RAISE EXCEPTION 'Submitted binding target required' USING ERRCODE='55000';END IF;
  next_state:=CASE WHEN operation='APPROVE' THEN 'APPROVED' ELSE 'REJECTED' END;
 END IF;
 decision_id:=gen_random_uuid();
 INSERT INTO public.cfg_native_decision(tenant_id,decision_id,action_id,proposal_id,revision,operation,target_digest,actor_issuer,actor_subject,employee_id,original_decision,canonical_decision,decision_digest)
 VALUES(tenant,decision_id,action_id,proposal,1,lower(operation),revision.target_digest,p_issuer,p_subject,p_employee,p_original,scope.canonical_intent,encode(sha256(convert_to(scope.canonical_intent,'UTF8')),'hex'));
 UPDATE public.cfg_native_proposal_control SET state=next_state,control_version=control_version+1,decision_id=binding_decide.decision_id WHERE tenant_id=tenant AND proposal_id=proposal;
 result:=jsonb_build_object('proposalId',proposal,'targetDigest',revision.target_digest,'state',next_state,'controlVersion',control.control_version+1,'notAfterEpochMillis',revision.not_after_epoch_millis);
 PERFORM public.cfg_native_finish_action(tenant,action_id,scope.operation,proposal,proposal,1,p_original,scope.canonical_intent,scope.until_epoch_millis,'DECIDED',result);
 SELECT * INTO existing FROM public.cfg_native_action a WHERE a.tenant_id=tenant AND a.action_id=binding_decide.action_id;
 completion:=public.cfg_trb_governance_result(existing,scope.until_epoch_millis);
 UPDATE public.cfg_trb_scope SET completion=binding_decide.completion WHERE backend_pid=pg_backend_pid() AND xid=pg_current_xact_id();RETURN completion;
END $fn$;

CREATE FUNCTION public.cfg_trb_open_control(p_original BYTEA,p_kind TEXT,p_issuer TEXT,p_subject TEXT,p_actor_tenant UUID,p_employee UUID,p_client TEXT,p_until BIGINT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
<<binding_open>>
DECLARE target JSONB;command JSONB;definition JSONB;scope public.cfg_trb_scope%ROWTYPE;actor JSONB;tenant UUID;proposal_id UUID;action_id UUID;replay BOOLEAN;
 proposal public.cfg_native_proposal%ROWTYPE;revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;existing public.cfg_native_action%ROWTYPE;completion JSONB;
BEGIN
 IF NOT public.cfg_trb_session() THEN RAISE EXCEPTION 'Restricted binding owning session required' USING ERRCODE='42501';END IF;
 PERFORM public.cfg_trb_timeouts(p_until);target:=public.cfg_trb_command(public.cfg_trb_json(p_original));command:=target->'command';definition:=command->'definition';
 actor:=public.cfg_trb_actor(p_kind,p_issuer,p_subject,p_actor_tenant,p_employee,p_client,false);
 tenant:=(definition->'resourceTenant'->>'value')::uuid;proposal_id:=(command->>'proposalId')::uuid;action_id:=(command->>'actionId')::uuid;
 scope:=public.cfg_trb_enter('CONTROL',tenant,target->>'namespaceDigest',action_id,proposal_id,command->>'operation',target->>'canonical',p_original,actor,target,p_until);PERFORM public.cfg_trb_legacy(scope);
 SELECT EXISTS(SELECT 1 FROM public.cfg_native_action a WHERE a.tenant_id=tenant AND a.action_id=binding_open.action_id) INTO replay;
 scope:=public.cfg_trb_bind_current(scope,target,replay);proposal:=public.cfg_trb_target(scope,true);scope:=public.cfg_trb_current();PERFORM public.cfg_trb_legacy(scope),public.cfg_native_action_gate(tenant,action_id);
 SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=tenant AND r.proposal_id=binding_open.proposal_id AND r.revision=1;
 SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=tenant AND c.proposal_id=binding_open.proposal_id;
 existing:=public.cfg_trb_existing(scope);
 IF replay OR scope.operation='TENANT_RESOURCE_BINDING_REVOKE' THEN PERFORM public.cfg_trb_anchor(scope.namespace_digest,(definition->>'bindingId')::uuid,(definition->>'version')::bigint,target->>'definitionDigest');END IF;
 IF replay THEN
  IF existing.action_id IS NULL OR control.state<>'EXECUTION_ADMITTED' OR control.execution_action_id IS DISTINCT FROM action_id
  THEN RAISE EXCEPTION 'Original execution receipt and anchor required; no replacement is issued' USING ERRCODE='23514';END IF;
  completion:=public.cfg_trb_control_result(existing);
 ELSE
  IF existing.action_id IS NOT NULL OR control.state<>'APPROVED' OR control.execution_action_id IS NOT NULL
  THEN RAISE EXCEPTION 'Exact unexecuted approved binding target required' USING ERRCODE='23514';END IF;
 END IF;
 UPDATE public.cfg_trb_scope SET original=revision.original_target,completion=binding_open.completion
 WHERE backend_pid=pg_backend_pid() AND xid=pg_current_xact_id() RETURNING * INTO scope;
 PERFORM public.cfg_trb_legacy(scope);
 RETURN jsonb_build_object('window',public.cfg_trb_window(scope),'originalMakerIssuer',proposal.maker_issuer,'originalMakerSubject',proposal.maker_subject,'replay',completion);
END $fn$;
CREATE FUNCTION public.cfg_trb_definition_json(p_value public.cfg_tenant_resource_binding_definition) RETURNS JSONB
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,public,pg_temp AS $fn$
 SELECT jsonb_build_object('bindingId',(p_value).binding_id,'namespace',jsonb_build_object('issuer',(p_value).issuer,'platformTenant',(p_value).platform_tenant),
  'resourceTenant',jsonb_build_object('value',(p_value).resource_tenant_id),'version',(p_value).version,
  'eligibility',jsonb_build_object('tenantGeneration',(p_value).tenant_generation,'statusId',(p_value).status_id,'statusGeneration',(p_value).status_generation),'notAfterEpochMillis',(p_value).not_after_epoch_millis)
$fn$;
CREATE FUNCTION public.cfg_trb_check_effect(p_scope public.cfg_trb_scope,p_require_anchor BOOLEAN DEFAULT true) RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE command JSONB:=p_scope.intent->'command';definition JSONB:=command->'definition';actual public.cfg_tenant_resource_binding_definition%ROWTYPE;
 head public.cfg_tenant_resource_binding_head%ROWTYPE;proposal public.cfg_native_proposal%ROWTYPE;live JSONB;expected_state TEXT;tenants UUID[];
BEGIN
 PERFORM public.cfg_trb_current();tenants:=ARRAY[p_scope.tenant_id];
 IF p_scope.before_head IS NOT NULL AND(p_scope.before_head->>'resource_tenant_id')::uuid<>p_scope.tenant_id THEN tenants:=tenants||(p_scope.before_head->>'resource_tenant_id')::uuid;END IF;
 live:=public.cfg_trb_lock_eligibility(tenants)->p_scope.tenant_id::text;
 IF live-'eligibleNow' IS DISTINCT FROM p_scope.intent->'currentEligibility' OR (live->>'eligibleNow')::boolean IS DISTINCT FROM(p_scope.intent->>'eligibleNow')::boolean
 THEN RAISE EXCEPTION 'Original current eligibility changed before native completion' USING ERRCODE='55000';END IF;
 SELECT * INTO actual FROM public.cfg_tenant_resource_binding_definition d WHERE d.binding_id=(definition->>'bindingId')::uuid AND d.version=(definition->>'version')::bigint;
 SELECT * INTO head FROM public.cfg_tenant_resource_binding_head h WHERE h.namespace_digest=p_scope.namespace_digest;
 SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_scope.tenant_id AND p.proposal_id=p_scope.proposal_id;
 expected_state:=CASE WHEN p_scope.operation='TENANT_RESOURCE_BINDING_ESTABLISH' THEN 'ACTIVE' ELSE 'REVOKED' END;
 IF actual.binding_id IS NULL OR head.namespace_digest IS NULL OR public.cfg_trb_definition_json(actual) IS DISTINCT FROM definition
  OR actual.namespace_digest IS DISTINCT FROM p_scope.namespace_digest OR actual.definition_digest IS DISTINCT FROM p_scope.intent->>'definitionDigest'
  OR p_scope.operation='TENANT_RESOURCE_BINDING_ESTABLISH' AND(actual.maker_issuer IS DISTINCT FROM proposal.maker_issuer OR actual.maker_subject IS DISTINCT FROM proposal.maker_subject)
  OR head.binding_id IS DISTINCT FROM actual.binding_id OR head.definition_version IS DISTINCT FROM actual.version OR head.definition_digest IS DISTINCT FROM actual.definition_digest
  OR head.control_version IS DISTINCT FROM(command->>'expectedControlVersion')::bigint+1 OR head.state IS DISTINCT FROM expected_state
 THEN RAISE EXCEPTION 'Complete exact mapped immutable binding and head effect required' USING ERRCODE='23514';END IF;
 IF p_require_anchor THEN PERFORM public.cfg_trb_anchor(p_scope.namespace_digest,actual.binding_id,actual.version,actual.definition_digest);END IF;
END $fn$;
CREATE FUNCTION public.cfg_trb_finish_control(p_original BYTEA) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
<<binding_finish>>
DECLARE scope public.cfg_trb_scope%ROWTYPE;target JSONB;command JSONB;proposal public.cfg_native_proposal%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;
 action public.cfg_native_action%ROWTYPE;result JSONB;completion JSONB;state TEXT;
BEGIN
 scope:=public.cfg_trb_current();target:=public.cfg_trb_command(public.cfg_trb_json(p_original));command:=target->'command';
 IF scope.mode<>'CONTROL' OR scope.canonical_intent IS DISTINCT FROM target->>'canonical' OR scope.intent->>'definitionDigest' IS DISTINCT FROM target->>'definitionDigest'
 THEN RAISE EXCEPTION 'Original owning binding control scope required' USING ERRCODE='42501';END IF;
 proposal:=public.cfg_trb_target(scope,true);scope:=public.cfg_trb_current();PERFORM public.cfg_trb_legacy(scope),public.cfg_trb_check_effect(scope,scope.operation<>'TENANT_RESOURCE_BINDING_ESTABLISH' OR(scope.intent->>'replay')::boolean);
 action:=public.cfg_trb_existing(scope);
 IF action.action_id IS NOT NULL THEN
  completion:=public.cfg_trb_control_result(action);
 ELSE
  IF(scope.intent->>'replay')::boolean THEN RAISE EXCEPTION 'Original receipt cannot be replaced' USING ERRCODE='23514';END IF;
  SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=scope.tenant_id AND c.proposal_id=scope.proposal_id FOR UPDATE;
  IF control.state<>'APPROVED' OR control.execution_action_id IS NOT NULL THEN RAISE EXCEPTION 'Unexecuted original binding target required' USING ERRCODE='23514';END IF;
  UPDATE public.cfg_native_proposal_control SET state='EXECUTION_ADMITTED',control_version=control.control_version+1,execution_action_id=scope.action_id WHERE tenant_id=scope.tenant_id AND proposal_id=scope.proposal_id;
  state:=CASE WHEN scope.operation='TENANT_RESOURCE_BINDING_ESTABLISH' THEN 'ACTIVE' ELSE 'REVOKED' END;
  result:=jsonb_build_object('reference',jsonb_build_object('bindingId',command->'definition'->>'bindingId','version',(command->'definition'->>'version')::bigint,'definitionDigest',scope.intent->>'definitionDigest'),
   'controlVersion',(command->>'expectedControlVersion')::bigint+1,'state',state);
  PERFORM public.cfg_native_finish_action(scope.tenant_id,scope.action_id,scope.operation,(command->'definition'->>'bindingId')::uuid,scope.proposal_id,1,
   scope.original,scope.canonical_intent,scope.until_epoch_millis,'APPLIED',result);
  SELECT * INTO action FROM public.cfg_native_action a WHERE a.tenant_id=scope.tenant_id AND a.action_id=scope.action_id;
  IF scope.operation='TENANT_RESOURCE_BINDING_ESTABLISH' THEN PERFORM public.cfg_trb_capture_anchor(scope,action);END IF;
  completion:=public.cfg_trb_control_result(action);
 END IF;
 UPDATE public.cfg_trb_scope SET completion=binding_finish.completion WHERE backend_pid=pg_backend_pid() AND xid=pg_current_xact_id();
 PERFORM public.cfg_trb_current();RETURN completion;
END $fn$;

CREATE FUNCTION public.cfg_trb_anchor(p_namespace TEXT,p_binding UUID,p_version BIGINT,p_digest TEXT) RETURNS public.cfg_trb_definition_anchor
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE scope public.cfg_trb_scope%ROWTYPE;anchor public.cfg_trb_definition_anchor%ROWTYPE;definition public.cfg_tenant_resource_binding_definition%ROWTYPE;reference JSONB;
BEGIN
 scope:=public.cfg_trb_current();
 IF scope.mode='LOOKUP' THEN reference:=scope.intent->'reference';ELSE reference:=jsonb_build_object('bindingId',scope.intent->'command'->'definition'->>'bindingId',
  'version',(scope.intent->'command'->'definition'->>'version')::bigint,'definitionDigest',scope.intent->>'definitionDigest');END IF;
 IF scope.namespace_digest IS DISTINCT FROM p_namespace OR(reference->>'bindingId')::uuid IS DISTINCT FROM p_binding
  OR(reference->>'version')::bigint IS DISTINCT FROM p_version OR reference->>'definitionDigest' IS DISTINCT FROM p_digest
 THEN RAISE EXCEPTION 'Exact binding origin reference required' USING ERRCODE='42501';END IF;
 SELECT * INTO anchor FROM public.cfg_trb_definition_anchor a WHERE a.binding_id=p_binding AND a.version=p_version;
 SELECT * INTO definition FROM public.cfg_tenant_resource_binding_definition d WHERE d.binding_id=p_binding AND d.version=p_version;
 IF anchor.binding_id IS NULL OR definition.binding_id IS NULL OR anchor.namespace_digest IS DISTINCT FROM p_namespace OR anchor.definition_digest IS DISTINCT FROM p_digest
  OR anchor.resource_tenant_id IS DISTINCT FROM scope.tenant_id OR definition.resource_tenant_id IS DISTINCT FROM anchor.resource_tenant_id
  OR definition.namespace_digest IS DISTINCT FROM anchor.namespace_digest OR definition.definition_digest IS DISTINCT FROM anchor.definition_digest
  OR definition.maker_issuer IS DISTINCT FROM anchor.maker_issuer OR definition.maker_subject IS DISTINCT FROM anchor.maker_subject
 THEN RAISE EXCEPTION 'Original native binding creation anchor required; no origin is synthesized' USING ERRCODE='23514';END IF;
 RETURN anchor;
END $fn$;
CREATE FUNCTION public.cfg_trb_capture_anchor(p_scope public.cfg_trb_scope,p_action public.cfg_native_action) RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE definition JSONB:=p_scope.intent->'command'->'definition';proposal public.cfg_native_proposal%ROWTYPE;receipt public.cfg_native_receipt%ROWTYPE;audit public.cfg_native_audit%ROWTYPE;event public.cfg_native_outbox%ROWTYPE;
BEGIN
 PERFORM public.cfg_trb_current();
 SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=p_scope.tenant_id AND p.proposal_id=p_scope.proposal_id;
 SELECT * INTO receipt FROM public.cfg_native_receipt r WHERE r.tenant_id=p_scope.tenant_id AND r.action_id=p_scope.action_id;
 SELECT * INTO audit FROM public.cfg_native_audit a WHERE a.tenant_id=p_scope.tenant_id AND a.action_id=p_scope.action_id;
 SELECT * INTO event FROM public.cfg_native_outbox e WHERE e.tenant_id=p_scope.tenant_id AND e.action_id=p_scope.action_id;
 IF p_scope.mode<>'CONTROL' OR p_scope.operation<>'TENANT_RESOURCE_BINDING_ESTABLISH' OR p_action.operation IS DISTINCT FROM p_scope.operation
  OR p_action.action_id IS DISTINCT FROM p_scope.action_id OR p_action.proposal_id IS DISTINCT FROM p_scope.proposal_id OR p_action.result_kind<>'APPLIED'
  OR p_action.canonical_intent IS DISTINCT FROM p_scope.canonical_intent OR p_action.original_request IS DISTINCT FROM p_scope.original
  OR receipt.receipt_id IS DISTINCT FROM p_action.receipt_id OR audit.audit_id IS DISTINCT FROM p_action.audit_id OR event.event_id IS DISTINCT FROM p_action.event_id
  OR receipt.payload IS DISTINCT FROM audit.payload OR receipt.payload IS DISTINCT FROM event.payload OR receipt.payload->'result' IS DISTINCT FROM p_action.result
 THEN RAISE EXCEPTION 'Actual complete first binding creation required before anchor capture' USING ERRCODE='23514';END IF;
 INSERT INTO public.cfg_trb_definition_anchor(binding_id,version,namespace_digest,resource_tenant_id,definition_digest,creation_action_id,creation_proposal_id,receipt_id,audit_id,event_id,
  maker_issuer,maker_subject,original_deadline_epoch_millis,original_intent_digest)
 VALUES((definition->>'bindingId')::uuid,(definition->>'version')::bigint,p_scope.namespace_digest,p_scope.tenant_id,p_scope.intent->>'definitionDigest',p_action.action_id,p_scope.proposal_id,
  p_action.receipt_id,p_action.audit_id,p_action.event_id,proposal.maker_issuer,proposal.maker_subject,p_action.deadline_epoch_millis,p_action.intent_digest);
END $fn$;

CREATE FUNCTION public.cfg_trb_complete_scope() RETURNS TRIGGER
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $fn$
DECLARE scope public.cfg_trb_scope%ROWTYPE;action public.cfg_native_action%ROWTYPE;proposal public.cfg_native_proposal%ROWTYPE;
 revision public.cfg_native_proposal_revision%ROWTYPE;control public.cfg_native_proposal_control%ROWTYPE;decision public.cfg_native_decision%ROWTYPE;
 live JSONB;head JSONB;expected JSONB;canonical TEXT;command JSONB;resource UUID;
BEGIN
 scope:=public.cfg_trb_current();
 IF scope.backend_pid IS DISTINCT FROM NEW.backend_pid OR scope.xid IS DISTINCT FROM NEW.xid THEN RAISE EXCEPTION 'Exact final owning binding scope required' USING ERRCODE='42501';END IF;
 IF scope.mode='LOOKUP' THEN
  head:=public.cfg_trb_head(scope.namespace_digest);live:=public.cfg_trb_lock_eligibility(ARRAY[scope.tenant_id])->scope.tenant_id::text;
  IF head IS DISTINCT FROM scope.before_head OR live-'eligibleNow' IS DISTINCT FROM scope.intent->'currentEligibility'
   OR(live->>'eligibleNow')::boolean IS DISTINCT FROM true THEN RAISE EXCEPTION 'Current binding observation expired or changed before commit' USING ERRCODE='55000';END IF;
  PERFORM public.cfg_trb_anchor(scope.namespace_digest,(scope.intent->'reference'->>'bindingId')::uuid,(scope.intent->'reference'->>'version')::bigint,scope.intent->'reference'->>'definitionDigest');
 ELSE
  PERFORM public.cfg_trb_legacy(scope);action:=public.cfg_trb_existing(scope);scope:=public.cfg_trb_current();command:=scope.intent->'command';
  IF action.action_id IS NULL OR scope.completion IS NULL OR action.original_request IS DISTINCT FROM scope.original
   OR action.canonical_intent IS DISTINCT FROM scope.canonical_intent OR action.proposal_id IS DISTINCT FROM scope.proposal_id OR action.proposal_revision IS DISTINCT FROM 1::bigint
  THEN RAISE EXCEPTION 'Complete original binding action receipt required before commit' USING ERRCODE='23514';END IF;
  IF scope.mode='CONTROL' THEN
   proposal:=public.cfg_trb_target(scope,true);scope:=public.cfg_trb_current();PERFORM public.cfg_trb_check_effect(scope,true);
   SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=scope.tenant_id AND c.proposal_id=scope.proposal_id;
   expected:=public.cfg_trb_control_result(action);resource:=(command->'definition'->>'bindingId')::uuid;
   IF control.state<>'EXECUTION_ADMITTED' OR control.execution_action_id IS DISTINCT FROM action.action_id THEN RAISE EXCEPTION 'Exact completed binding execution anchor required' USING ERRCODE='23514';END IF;
  ELSE
   SELECT * INTO proposal FROM public.cfg_native_proposal p WHERE p.tenant_id=scope.tenant_id AND p.proposal_id=scope.proposal_id;
   SELECT * INTO revision FROM public.cfg_native_proposal_revision r WHERE r.tenant_id=scope.tenant_id AND r.proposal_id=scope.proposal_id AND r.revision=1;
   SELECT * INTO control FROM public.cfg_native_proposal_control c WHERE c.tenant_id=scope.tenant_id AND c.proposal_id=scope.proposal_id;
   head:=public.cfg_trb_head(scope.namespace_digest);live:=public.cfg_trb_lock_eligibility(ARRAY[scope.tenant_id])->scope.tenant_id::text;
   IF proposal.proposal_id IS NULL OR revision.proposal_id IS NULL OR control.proposal_id IS NULL OR head IS DISTINCT FROM scope.before_head
    OR live-'eligibleNow' IS DISTINCT FROM scope.intent->'currentEligibility' OR(live->>'eligibleNow')::boolean IS DISTINCT FROM(scope.intent->>'eligibleNow')::boolean
    OR revision.target_encoding<>'FF_TENANT_RESOURCE_BINDING_COMMAND_V1' OR revision.canonical_target IS DISTINCT FROM scope.intent->>'canonical'
    OR revision.target_digest IS DISTINCT FROM scope.intent->>'intentDigest' OR proposal.target_operation IS DISTINCT FROM command->>'operation'
    OR control.state IS DISTINCT FROM action.result->>'state' OR control.control_version IS DISTINCT FROM(action.result->>'controlVersion')::bigint
    OR action.result->>'targetDigest' IS DISTINCT FROM revision.target_digest OR(action.result->>'notAfterEpochMillis')::bigint IS DISTINCT FROM revision.not_after_epoch_millis
   THEN RAISE EXCEPTION 'Complete exact current binding proposal effect required' USING ERRCODE='23514';END IF;
   IF scope.mode='PREPARE' THEN
    IF proposal.maker_employee_id IS DISTINCT FROM action.actor_employee_id OR proposal.maker_issuer IS DISTINCT FROM action.actor_issuer
     OR proposal.maker_subject IS DISTINCT FROM action.actor_subject OR control.state<>'DRAFT' OR control.control_version<>0
    THEN RAISE EXCEPTION 'Original actual binding maker required' USING ERRCODE='23514';END IF;
    canonical:=public.cfg_trb_preparation(public.cfg_trb_json(action.original_request))->>'prepareCanonical';
   ELSE
    SELECT * INTO decision FROM public.cfg_native_decision d WHERE d.tenant_id=scope.tenant_id AND d.action_id=scope.action_id;
    IF decision.decision_id IS NULL OR decision.decision_id IS DISTINCT FROM control.decision_id OR decision.proposal_id IS DISTINCT FROM scope.proposal_id
     OR decision.actor_issuer IS DISTINCT FROM action.actor_issuer OR decision.actor_subject IS DISTINCT FROM action.actor_subject OR decision.employee_id IS DISTINCT FROM action.actor_employee_id
     OR decision.target_digest IS DISTINCT FROM revision.target_digest OR decision.decision_digest IS DISTINCT FROM action.intent_digest
     OR decision.operation IS DISTINCT FROM lower(replace(scope.operation,'TENANT_RESOURCE_BINDING_',''))
    THEN RAISE EXCEPTION 'Original actual binding decision required' USING ERRCODE='23514';END IF;
    IF decision.operation IN ('submit','withdraw') THEN
     IF decision.employee_id IS DISTINCT FROM proposal.maker_employee_id OR decision.actor_issuer IS DISTINCT FROM proposal.maker_issuer OR decision.actor_subject IS DISTINCT FROM proposal.maker_subject
     THEN RAISE EXCEPTION 'Original maker-only decision required' USING ERRCODE='23514';END IF;
    ELSIF decision.employee_id=proposal.maker_employee_id OR decision.actor_issuer=proposal.maker_issuer AND decision.actor_subject=proposal.maker_subject
    THEN RAISE EXCEPTION 'Original independent checker required' USING ERRCODE='23514';END IF;
    canonical:=public.cfg_trb_decision(public.cfg_trb_json(action.original_request))->>'canonical';
   END IF;
   IF canonical IS DISTINCT FROM action.canonical_intent THEN RAISE EXCEPTION 'Full original proposal command binding required' USING ERRCODE='23514';END IF;
   expected:=public.cfg_trb_governance_result(action,scope.until_epoch_millis);resource:=scope.proposal_id;
  END IF;
  IF scope.completion IS DISTINCT FROM expected OR action.resource_id IS DISTINCT FROM resource THEN RAISE EXCEPTION 'Native completion differs from retained exact action' USING ERRCODE='23514';END IF;
 END IF;
 PERFORM public.cfg_trb_current();
 UPDATE public.cfg_trb_scope s SET completed=true WHERE s.backend_pid=scope.backend_pid AND s.xid=scope.xid;
 RETURN NULL;
END $fn$;
CREATE CONSTRAINT TRIGGER cfg_trb_scope_complete AFTER INSERT ON public.cfg_trb_scope
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.cfg_trb_complete_scope();

ALTER TABLE public.cfg_tenant_resource_binding_definition OWNER TO cfg_native_owner;
ALTER TABLE public.cfg_tenant_resource_binding_head OWNER TO cfg_native_owner;
ALTER TABLE public.cfg_tenant_resource_binding_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_tenant_resource_binding_definition FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_tenant_resource_binding_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_tenant_resource_binding_head FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.cfg_tenant_resource_binding_definition,public.cfg_tenant_resource_binding_head FROM PUBLIC;
CREATE POLICY cfg_trb_definition_guard ON public.cfg_tenant_resource_binding_definition FOR SELECT TO cfg_native_guard USING(true);
CREATE POLICY cfg_trb_head_guard ON public.cfg_tenant_resource_binding_head FOR ALL TO cfg_native_guard USING(true) WITH CHECK(true);
CREATE POLICY cfg_trb_definition_runtime_read ON public.cfg_tenant_resource_binding_definition FOR SELECT TO cfg_native_runtime
 USING(public.cfg_trb_control_current(namespace_digest,binding_id,version,definition_digest));
CREATE POLICY cfg_trb_definition_runtime_insert ON public.cfg_tenant_resource_binding_definition FOR INSERT TO cfg_native_runtime
 WITH CHECK(public.cfg_trb_control_current(namespace_digest,binding_id,version,definition_digest));
CREATE POLICY cfg_trb_head_runtime ON public.cfg_tenant_resource_binding_head FOR ALL TO cfg_native_runtime
 USING(public.cfg_trb_control_current(namespace_digest,binding_id,definition_version,definition_digest))
 WITH CHECK(public.cfg_trb_control_current(namespace_digest,binding_id,definition_version,definition_digest));
CREATE TRIGGER cfg_trb_definition_immutable BEFORE UPDATE OR DELETE ON public.cfg_tenant_resource_binding_definition
 FOR EACH ROW EXECUTE FUNCTION public.cfg_native_immutable_guard();
GRANT SELECT ON public.cfg_tenant_resource_binding_definition,public.cfg_tenant_resource_binding_head TO cfg_native_guard;
GRANT SELECT,INSERT ON public.cfg_tenant_resource_binding_definition TO cfg_native_runtime;
GRANT SELECT,INSERT,UPDATE ON public.cfg_tenant_resource_binding_head TO cfg_native_runtime;


GRANT UPDATE ON public.cfg_tenant_resource_binding_head TO cfg_native_guard;
ALTER TABLE public.cfg_trb_scope OWNER TO cfg_native_owner;
ALTER TABLE public.cfg_trb_definition_anchor OWNER TO cfg_native_owner;
ALTER TABLE public.cfg_trb_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_trb_scope FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_trb_definition_anchor ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cfg_trb_definition_anchor FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.cfg_trb_scope,public.cfg_trb_definition_anchor FROM PUBLIC,cfg_native_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.cfg_trb_scope TO cfg_native_guard;
GRANT SELECT,INSERT ON public.cfg_trb_definition_anchor TO cfg_native_guard;
CREATE POLICY cfg_trb_scope_guard ON public.cfg_trb_scope FOR ALL TO cfg_native_guard USING(true) WITH CHECK(true);
CREATE POLICY cfg_trb_anchor_guard ON public.cfg_trb_definition_anchor FOR ALL TO cfg_native_guard USING(true) WITH CHECK(true);
-- Locking SELECT uses UPDATE USING; real writes still require the original legacy WITH CHECK.
CREATE POLICY cfg_trb_tenant_eligibility_lock ON public.cfg_native_tenant_eligibility FOR UPDATE TO cfg_native_guard
 USING(public.cfg_trb_lookup_lock_current(tenant_id,NULL::uuid)) WITH CHECK(false);
CREATE POLICY cfg_trb_status_eligibility_lock ON public.cfg_native_status_eligibility FOR UPDATE TO cfg_native_guard
 USING(public.cfg_trb_lookup_lock_current(NULL::uuid,status_id)) WITH CHECK(false);
CREATE TRIGGER cfg_trb_anchor_immutable BEFORE UPDATE OR DELETE ON public.cfg_trb_definition_anchor
 FOR EACH ROW EXECUTE FUNCTION public.cfg_native_immutable_guard();

CREATE VIEW public.cfg_tenant_resource_binding_current WITH (security_barrier=true) AS
 SELECT h.namespace_digest,h.binding_id,h.definition_version,h.definition_digest,h.control_version,h.state,
  d.issuer,d.platform_tenant,d.resource_tenant_id,d.tenant_generation,d.status_id,d.status_generation,d.not_after_epoch_millis,
  t.generation AS live_tenant_generation,t.current_status_id AS live_status_id,s.generation AS live_status_generation,
  (t.enabled AND s.enabled AND source.active IS TRUE AND status.active IS TRUE AND status.code='ACTIVE' AND source.tenant_status_id=t.current_status_id) AS eligible_now
 FROM public.cfg_tenant_resource_binding_head h
 JOIN public.cfg_tenant_resource_binding_definition d ON d.binding_id=h.binding_id AND d.version=h.definition_version
  AND d.namespace_digest=h.namespace_digest AND d.definition_digest=h.definition_digest
 JOIN public.cfg_native_tenant_eligibility t ON t.tenant_id=d.resource_tenant_id
 JOIN public.cfg_native_status_eligibility s ON s.status_id=t.current_status_id
 JOIN public.tenants source ON source.id=t.tenant_id
 JOIN public.tenant_statuses status ON status.id=s.status_id
 WHERE public.cfg_trb_lookup_current(h.namespace_digest,d.issuer,d.platform_tenant,d.resource_tenant_id,h.binding_id,h.definition_version,
  h.definition_digest,t.generation,t.current_status_id,s.generation,h.control_version,h.state,d.not_after_epoch_millis);
GRANT CREATE ON SCHEMA public TO cfg_native_guard;
ALTER VIEW public.cfg_tenant_resource_binding_current OWNER TO cfg_native_guard;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
REVOKE ALL ON public.cfg_tenant_resource_binding_current FROM PUBLIC;
GRANT SELECT ON public.cfg_tenant_resource_binding_current TO cfg_native_runtime;

GRANT CREATE ON SCHEMA public TO cfg_native_guard;
DO $acl$
DECLARE function_value RECORD;
BEGIN
 FOR function_value IN SELECT p.oid::regprocedure AS identity FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public' AND left(p.proname,8)='cfg_trb_' LOOP
  EXECUTE format('ALTER FUNCTION %s OWNER TO cfg_native_guard',function_value.identity);
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,cfg_native_runtime',function_value.identity);
 END LOOP;
END $acl$;
REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;
GRANT EXECUTE ON FUNCTION public.cfg_trb_open_lookup(TEXT,TEXT,TEXT,BIGINT),
 public.cfg_trb_prepare(BYTEA,TEXT,TEXT,TEXT,UUID,UUID,TEXT,BIGINT),public.cfg_trb_decide(BYTEA,TEXT,TEXT,TEXT,UUID,UUID,TEXT,BIGINT),
 public.cfg_trb_open_control(BYTEA,TEXT,TEXT,TEXT,UUID,UUID,TEXT,BIGINT),public.cfg_trb_finish_control(BYTEA),
 public.cfg_trb_control_current(TEXT,UUID,BIGINT,TEXT),public.cfg_trb_lookup_current(TEXT,TEXT,TEXT,UUID,UUID,BIGINT,TEXT,BIGINT,UUID,BIGINT,BIGINT,TEXT,BIGINT)
 TO cfg_native_runtime;
DO $attest$
DECLARE guard OID;owner OID;runtime OID;item RECORD;allowed BOOLEAN;
BEGIN
 SELECT oid INTO guard FROM pg_roles WHERE rolname='cfg_native_guard' AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit;
 SELECT oid INTO owner FROM pg_roles WHERE rolname='cfg_native_owner' AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls;
 SELECT oid INTO runtime FROM pg_roles WHERE rolname='cfg_native_runtime' AND NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit AND NOT rolcreatedb AND NOT rolcreaterole;
 IF has_schema_privilege('cfg_native_guard','public','CREATE') OR has_schema_privilege('cfg_native_runtime','public','CREATE')
  THEN RAISE EXCEPTION 'Temporary installer schema CREATE must be revoked' USING ERRCODE='55000';END IF;
 IF guard IS NULL OR owner IS NULL OR runtime IS NULL OR pg_has_role(runtime,guard,'MEMBER') OR pg_has_role(runtime,owner,'MEMBER') THEN RAISE EXCEPTION 'Exact original narrow authority roles required' USING ERRCODE='55000';END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_proc p WHERE p.oid='public.cfg_trb_lookup_lock_current(uuid,uuid)'::regprocedure
  AND p.proowner=guard AND p.prosecdef AND NOT has_function_privilege(runtime,p.oid,'EXECUTE'))
 THEN RAISE EXCEPTION 'Private current lookup row-lock predicate required' USING ERRCODE='55000';END IF;
 FOR item IN SELECT * FROM(VALUES
  ('cfg_native_tenant_eligibility','cfg_trb_tenant_eligibility_lock','cfg_native_tenant_membership_lookup',
   'cfg_native_context_current(tenant_id)','cfg_trb_lookup_lock_current(tenant_id,NULL::uuid)'),
  ('cfg_native_status_eligibility','cfg_trb_status_eligibility_lock','cfg_native_status_membership_lookup',
   'cfg_native_context_current()','cfg_trb_lookup_lock_current(NULL::uuid,status_id)')
 ) expected(table_name,lock_policy,read_policy,legacy_expression,lock_expression) LOOP
  IF(SELECT count(*) FROM pg_policy WHERE polrelid=to_regclass('public.'||item.table_name))<>3
   OR NOT EXISTS(SELECT 1 FROM pg_policy policy WHERE policy.polrelid=to_regclass('public.'||item.table_name)
    AND policy.polname=item.lock_policy AND policy.polcmd='w' AND policy.polroles=ARRAY[guard] AND policy.polpermissive
    AND regexp_replace(replace(pg_get_expr(policy.polqual,policy.polrelid),'public.',''),'[[:space:]]','','g')=item.lock_expression
    AND pg_get_expr(policy.polwithcheck,policy.polrelid)='false'
    AND EXISTS(SELECT 1 FROM pg_depend dependency WHERE dependency.classid='pg_policy'::regclass AND dependency.objid=policy.oid
     AND dependency.refclassid='pg_proc'::regclass AND dependency.refobjid='public.cfg_trb_lookup_lock_current(uuid,uuid)'::regprocedure))
   OR NOT EXISTS(SELECT 1 FROM pg_policy policy WHERE policy.polrelid=to_regclass('public.'||item.table_name)
    AND policy.polname=item.read_policy AND policy.polcmd='r' AND policy.polroles=ARRAY[guard] AND policy.polpermissive
    AND pg_get_expr(policy.polqual,policy.polrelid)='true' AND policy.polwithcheck IS NULL)
   OR NOT EXISTS(SELECT 1 FROM pg_policy policy WHERE policy.polrelid=to_regclass('public.'||item.table_name)
    AND policy.polname=item.table_name||'_scope' AND policy.polcmd='*' AND policy.polroles=ARRAY[guard] AND policy.polpermissive
    AND regexp_replace(replace(pg_get_expr(policy.polqual,policy.polrelid),'public.',''),'[[:space:]]','','g')
     IN(item.legacy_expression,CASE WHEN item.table_name='cfg_native_status_eligibility' THEN 'cfg_native_context_current(NULL::uuid)' ELSE item.legacy_expression END)
    AND regexp_replace(replace(pg_get_expr(policy.polwithcheck,policy.polrelid),'public.',''),'[[:space:]]','','g')
     IN(item.legacy_expression,CASE WHEN item.table_name='cfg_native_status_eligibility' THEN 'cfg_native_context_current(NULL::uuid)' ELSE item.legacy_expression END))
  THEN RAISE EXCEPTION 'Exact legacy eligibility policies and lock-only lookup extension required' USING ERRCODE='55000';END IF;
 END LOOP;
 FOR item IN SELECT c.oid,c.relname,c.relowner,c.relrowsecurity,c.relforcerowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relname IN('cfg_trb_scope','cfg_trb_definition_anchor','cfg_tenant_resource_binding_definition','cfg_tenant_resource_binding_head') LOOP
  IF item.relowner<>owner OR NOT item.relrowsecurity OR NOT item.relforcerowsecurity
   OR EXISTS(SELECT 1 FROM aclexplode(coalesce((SELECT relacl FROM pg_class WHERE oid=item.oid),acldefault('r',owner))) a WHERE a.grantee NOT IN(owner,guard,runtime) OR a.grantee=runtime AND a.is_grantable)
  THEN RAISE EXCEPTION 'Protected binding table owner and FORCE RLS required' USING ERRCODE='55000';END IF;
 END LOOP;
 IF(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN('cfg_trb_scope','cfg_trb_definition_anchor','cfg_tenant_resource_binding_definition','cfg_tenant_resource_binding_head') AND c.relkind='r')<>4
  OR has_table_privilege(runtime,'public.cfg_trb_scope','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
  OR has_table_privilege(runtime,'public.cfg_trb_definition_anchor','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
  OR has_table_privilege(runtime,'public.cfg_tenant_resource_binding_definition','UPDATE,DELETE,TRUNCATE')
  OR has_table_privilege(runtime,'public.cfg_tenant_resource_binding_head','DELETE,TRUNCATE')
 THEN RAISE EXCEPTION 'Narrow binding persistence and private origin privileges required' USING ERRCODE='55000';END IF;
 FOR item IN SELECT p.oid,p.proname,p.proowner,p.proconfig,p.prosecdef,p.proacl FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public' AND left(p.proname,8)='cfg_trb_' LOOP
  allowed:=item.proname IN('cfg_trb_open_lookup','cfg_trb_prepare','cfg_trb_decide','cfg_trb_open_control','cfg_trb_finish_control','cfg_trb_control_current','cfg_trb_lookup_current');
  IF item.proowner<>guard OR NOT(item.proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp'])
   OR EXISTS(SELECT 1 FROM aclexplode(coalesce(item.proacl,acldefault('f',guard))) a WHERE a.grantee<>guard AND(a.grantee<>runtime OR NOT allowed OR a.is_grantable))
   OR has_function_privilege(runtime,item.oid,'EXECUTE') IS DISTINCT FROM allowed
   OR(allowed OR item.proname IN('cfg_trb_current','cfg_trb_complete_scope')) AND NOT item.prosecdef
  THEN RAISE EXCEPTION 'Exact restricted binding function owner/ACL/body boundary required' USING ERRCODE='55000';END IF;
 END LOOP;
 IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='public.cfg_trb_scope'::regclass AND tgname='cfg_trb_scope_complete' AND tgdeferrable AND tginitdeferred AND tgenabled='O')
  OR NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='public.cfg_trb_definition_anchor'::regclass AND tgname='cfg_trb_anchor_immutable' AND tgenabled='O')
  OR NOT EXISTS(SELECT 1 FROM pg_class WHERE oid='public.cfg_tenant_resource_binding_current'::regclass AND relowner=guard AND reloptions @> ARRAY['security_barrier=true'])
 THEN RAISE EXCEPTION 'Complete binding lifecycle and protected current view required' USING ERRCODE='55000';END IF;
END $attest$;
COMMIT;

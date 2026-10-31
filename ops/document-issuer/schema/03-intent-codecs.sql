-- The administrator grants the new guard only the exact existing pure cfg_native validators.
CREATE FUNCTION public.cfg_document_issuer_text(p_value JSON,p_min INTEGER,p_max INTEGER) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE value TEXT; code INTEGER;
BEGIN
    IF p_value IS NULL OR json_typeof(p_value) IS DISTINCT FROM 'string' THEN PERFORM public.cfg_native_invalid(); END IF;
    value:=p_value#>>'{}';
    IF octet_length(convert_to(value,'UTF8')) NOT BETWEEN p_min AND p_max THEN PERFORM public.cfg_native_invalid(); END IF;
    IF p_min>0 THEN RETURN public.cfg_native_string(p_value,p_max); END IF;
    FOR i IN 1..char_length(value) LOOP
        code:=ascii(substr(value,i,1));
        IF code BETWEEN 0 AND 31 OR code BETWEEN 127 AND 159 OR code BETWEEN 55296 AND 57343 OR code=65533 THEN PERFORM public.cfg_native_invalid(); END IF;
    END LOOP;
    RETURN public.cfg_native_s(value);
END $$;

CREATE FUNCTION public.cfg_document_issuer_use(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE purpose TEXT; document_type TEXT; locale TEXT;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['purpose','documentType','locale']);
    purpose:=p_value->>'purpose';document_type:=p_value->>'documentType';locale:=p_value->>'locale';
    IF purpose IS DISTINCT FROM 'DEPOSIT_ACCOUNT_ENROLLMENT' OR document_type !~ '^[A-Z][A-Z0-9_]{0,63}$'
            OR locale !~ '^[A-Za-z0-9][A-Za-z0-9-]{1,63}$' THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN 'R3:'||public.cfg_document_issuer_text(p_value->'purpose',1,64)
        ||public.cfg_document_issuer_text(p_value->'documentType',1,64)||public.cfg_document_issuer_text(p_value->'locale',2,64);
END $$;

CREATE FUNCTION public.cfg_document_issuer_source(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE result TEXT; logo TEXT; color TEXT;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['tenantId','tenantVersion','brandingId','brandingVersion','sourceGeneration',
        'tenantCode','tenantName','tenantActive','brandingActive','logoUrl','primaryColor','footerText']);
    IF p_value->>'tenantActive' IS DISTINCT FROM 'true' OR p_value->>'brandingActive' IS DISTINCT FROM 'true' THEN PERFORM public.cfg_native_invalid(); END IF;
    result:='R12:'||public.cfg_native_uuid(p_value->'tenantId')||public.cfg_native_long(p_value->'tenantVersion',0,9223372036854775806)
        ||public.cfg_native_uuid(p_value->'brandingId')||public.cfg_native_long(p_value->'brandingVersion',0,9223372036854775806)
        ||public.cfg_native_long(p_value->'sourceGeneration',1,9223372036854775806)
        ||public.cfg_document_issuer_text(p_value->'tenantCode',1,100)||public.cfg_document_issuer_text(p_value->'tenantName',1,255)
        ||public.cfg_native_bool(p_value->'tenantActive')||public.cfg_native_bool(p_value->'brandingActive')
        ||public.cfg_document_issuer_text(p_value->'logoUrl',1,2000)||public.cfg_document_issuer_text(p_value->'primaryColor',1,7)
        ||public.cfg_document_issuer_text(p_value->'footerText',0,2000);
    logo:=p_value->>'logoUrl';color:=p_value->>'primaryColor';
    IF logo !~ '^https://[^/@#[:space:]]+([/?][^#[:space:]]*)?$' OR color !~ '^#[0-9A-Fa-f]{6}$' THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN result;
END $$;

CREATE FUNCTION public.cfg_document_issuer_definition(p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE uses JSON; item JSON; use_tokens TEXT; prior_key TEXT; actual_key TEXT; result TEXT; from_millis BIGINT; until_millis BIGINT;
BEGIN
    PERFORM public.cfg_native_shape(p_value,ARRAY['issuerId','revision','issuerCode','displayName','contactEmail','uses','source',
        'effectiveFromEpochMillis','effectiveUntilEpochMillis']);
    IF p_value->>'issuerCode' !~ '^[A-Z][A-Z0-9_]{0,63}$' THEN PERFORM public.cfg_native_invalid(); END IF;
    uses:=p_value->'uses';
    IF json_typeof(uses) IS DISTINCT FROM 'array' OR json_array_length(uses) NOT BETWEEN 1 AND 32 THEN PERFORM public.cfg_native_invalid(); END IF;
    use_tokens:='A'||json_array_length(uses)::TEXT||':';
    FOR item IN SELECT value FROM json_array_elements(uses) LOOP
        use_tokens:=use_tokens||public.cfg_document_issuer_use(item);
        actual_key:=(item->>'purpose')||'/'||(item->>'documentType')||'/'||(item->>'locale');
        IF prior_key IS NOT NULL AND prior_key COLLATE "C">=actual_key COLLATE "C" THEN PERFORM public.cfg_native_invalid(); END IF;
        prior_key:=actual_key;
    END LOOP;
    result:='R9:'||public.cfg_native_uuid(p_value->'issuerId')||public.cfg_native_long(p_value->'revision',1,9223372036854775806)
        ||public.cfg_document_issuer_text(p_value->'issuerCode',1,64)||public.cfg_document_issuer_text(p_value->'displayName',1,255)
        ||public.cfg_document_issuer_text(p_value->'contactEmail',3,254)||use_tokens||public.cfg_document_issuer_source(p_value->'source')
        ||public.cfg_native_long(p_value->'effectiveFromEpochMillis',0,253402300799999)
        ||public.cfg_native_long(p_value->'effectiveUntilEpochMillis',0,253402300799999);
    from_millis:=(p_value->>'effectiveFromEpochMillis')::BIGINT;until_millis:=(p_value->>'effectiveUntilEpochMillis')::BIGINT;
    IF from_millis>=until_millis THEN PERFORM public.cfg_native_invalid(); END IF;
    RETURN result;
END $$;

CREATE FUNCTION public.cfg_document_issuer_definition_digest(p_value JSON) RETURNS TEXT
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
    SELECT encode(sha256(convert_to(public.cfg_native_s('FF_DOCUMENT_ISSUER_DEFINITION_V1')||public.cfg_document_issuer_definition(p_value),'UTF8')),'hex')
$$;

CREATE FUNCTION public.cfg_document_issuer_intent(p_tenant UUID,p_operation TEXT,p_value JSON) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE result TEXT;
BEGIN
    IF p_operation='SOURCE' THEN
        PERFORM public.cfg_native_shape(p_value,ARRAY['tenantId','issuerId','publicationId','actionId','brandingId','use']);
        IF p_value->>'tenantId' IS DISTINCT FROM p_tenant::TEXT THEN PERFORM public.cfg_native_invalid(); END IF;
        result:=public.cfg_native_s('FF_DOCUMENT_ISSUER_SOURCE_REQUEST_V1')||'R6:'
            ||public.cfg_native_uuid(p_value->'tenantId')||public.cfg_native_uuid(p_value->'issuerId')
            ||public.cfg_native_uuid(p_value->'publicationId')||public.cfg_native_uuid(p_value->'actionId')
            ||public.cfg_native_uuid(p_value->'brandingId')||public.cfg_document_issuer_use(p_value->'use');
    ELSIF p_operation='PREPARE' THEN
        PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','publicationId','expectedGeneration','definition']);
        result:=public.cfg_native_s('FF_DOCUMENT_ISSUER_PREPARE_V1')||'R2:'||public.cfg_native_uuid(to_json(p_tenant::TEXT))||'R4:'
            ||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'publicationId')
            ||public.cfg_native_long(p_value->'expectedGeneration',0,9223372036854775806)||public.cfg_document_issuer_definition(p_value->'definition');
        IF p_value->'definition'->'source'->>'tenantId' IS DISTINCT FROM p_tenant::TEXT THEN PERFORM public.cfg_native_invalid(); END IF;
    ELSIF p_operation IN ('PUBLISH','REVOKE') THEN
        PERFORM public.cfg_native_shape(p_value,ARRAY['actionId','issuerId','publicationId','revision','definitionDigest','expectedGeneration','reason']);
        IF p_value->>'definitionDigest' !~ '^[0-9a-f]{64}$' THEN PERFORM public.cfg_native_invalid(); END IF;
        result:=public.cfg_native_s('FF_DOCUMENT_ISSUER_DECISION_V1')||'R3:'||public.cfg_native_uuid(to_json(p_tenant::TEXT))
            ||public.cfg_native_s(p_operation)||'R7:'||public.cfg_native_uuid(p_value->'actionId')||public.cfg_native_uuid(p_value->'issuerId')
            ||public.cfg_native_uuid(p_value->'publicationId')||public.cfg_native_long(p_value->'revision',1,9223372036854775806)
            ||public.cfg_document_issuer_text(p_value->'definitionDigest',64,64)||public.cfg_native_long(p_value->'expectedGeneration',0,9223372036854775806)
            ||public.cfg_document_issuer_text(p_value->'reason',1,500);
    ELSIF p_operation='CURRENT' THEN
        PERFORM public.cfg_native_shape(p_value,ARRAY['tenantId','publicationId','revision','definitionDigest','use']);
        IF p_value->>'tenantId' IS DISTINCT FROM p_tenant::TEXT OR p_value->>'definitionDigest' !~ '^[0-9a-f]{64}$' THEN PERFORM public.cfg_native_invalid(); END IF;
        result:=public.cfg_native_s('FF_DOCUMENT_ISSUER_CURRENT_REQUEST_V1')||'R5:'||public.cfg_native_uuid(p_value->'tenantId')
            ||public.cfg_native_uuid(p_value->'publicationId')||public.cfg_native_long(p_value->'revision',1,9223372036854775806)
            ||public.cfg_document_issuer_text(p_value->'definitionDigest',64,64)||public.cfg_document_issuer_use(p_value->'use');
    ELSE PERFORM public.cfg_native_invalid(); END IF;
    RETURN result;
END $$;

ALTER FUNCTION public.cfg_document_issuer_text(JSON,INTEGER,INTEGER) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_use(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_source(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_definition(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_definition_digest(JSON) OWNER TO cfg_document_issuer_guard;
ALTER FUNCTION public.cfg_document_issuer_intent(UUID,TEXT,JSON) OWNER TO cfg_document_issuer_guard;
REVOKE ALL ON FUNCTION public.cfg_document_issuer_text(JSON,INTEGER,INTEGER),public.cfg_document_issuer_use(JSON),
    public.cfg_document_issuer_source(JSON),public.cfg_document_issuer_definition(JSON),public.cfg_document_issuer_definition_digest(JSON),
    public.cfg_document_issuer_intent(UUID,TEXT,JSON) FROM PUBLIC;

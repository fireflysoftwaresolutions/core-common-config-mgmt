/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIssuerApiContractTest {
    static final String TENANT="10000000-0000-4000-8000-000000000001";
    static final PublicationReference REFERENCE=new PublicationReference(TENANT,"10000000-0000-4000-8000-000000000002",1,"a".repeat(64),new Use(Purpose.DEPOSIT_ACCOUNT_ENROLLMENT,"CONTRACT","en-US"));
    static PublicationReference decode(String tenant,byte[] body)throws Exception{
        var method=DocumentIssuerCodec.class.getMethod("decodePublicationReference",String.class,byte[].class);
        try{return (PublicationReference)method.invoke(null,tenant,body);}catch(InvocationTargetException error){throw (RuntimeException)error.getCause();}
    }
    static byte[] original(){return DocumentIssuerCodec.publicationReferenceJson(REFERENCE).getBytes(StandardCharsets.UTF_8);}
    @Test void currentBodyRoundTripPreservesAllFiveFields()throws Exception{assertEquals(REFERENCE,decode(TENANT,original()));}
    @Test void currentBodyTenantMustMatchPath()throws Exception{assertThrows(IllegalArgumentException.class,()->decode("10000000-0000-4000-8000-000000000003",original()));}
    @Test void currentBodyRejectsDuplicateTrailingUnknownAndWrongType()throws Exception{
        String body=new String(original(),StandardCharsets.UTF_8);
        for(String invalid:List.of(body+"{}",body.replaceFirst("\\{","{\"revision\":1,"),body.replaceFirst("\\{","{\"extra\":true,"),body.replace("\"revision\":1","\"revision\":\"1\""))){assertThrows(IllegalArgumentException.class,()->decode(TENANT,invalid.getBytes(StandardCharsets.UTF_8)));}
    }
    @Test void currentBodyRejectsMalformedUtf8AndOversize(){assertThrows(IllegalArgumentException.class,()->decode(TENANT,new byte[]{(byte)0xff}));assertThrows(IllegalArgumentException.class,()->decode(TENANT,new byte[4097]));}
}

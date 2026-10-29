/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class DocumentIssuerCodec {
    private DocumentIssuerCodec() {}
    public record Intent<T>(String originalJson, T request, String canonicalIntent, String intentDigest) {
        @Override public String toString() { return "DocumentIssuerIntent"; }
    }
    public static Intent<SourceRequest> source(String tenantId,byte[] bytes){
        AuthorityWire.uuid(tenantId);var original=AuthorityWire.original(bytes,4096);
        var request=AuthorityWire.record(AuthorityWire.parse(original),SourceRequest.class,Set.of());
        for(var id:new String[]{request.tenantId(),request.issuerId(),request.publicationId(),request.actionId(),request.brandingId()})AuthorityWire.uuid(id);
        useKey(request.use());if(!tenantId.equals(request.tenantId()))throw bad();
        var canonical=AuthorityWire.token("FF_DOCUMENT_ISSUER_SOURCE_REQUEST_V1")+AuthorityWire.token(request);
        return new Intent<>(original,request,canonical,AuthorityWire.sha(canonical));
    }
    public static Intent<Prepare> prepare(String tenantId, byte[] bytes) {
        AuthorityWire.uuid(tenantId);
        var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),Prepare.class,Set.of());
        AuthorityWire.uuid(request.actionId());AuthorityWire.uuid(request.publicationId());
        generation(request.expectedGeneration());validate(request.definition());
        if(!tenantId.equals(request.definition().source().tenantId()))throw bad();
        var canonical=AuthorityWire.token("FF_DOCUMENT_ISSUER_PREPARE_V1")+AuthorityWire.recordToken(tenantId,request);
        return new Intent<>(original,request,canonical,AuthorityWire.sha(canonical));
    }
    public static Intent<Decision> decision(String tenantId, Operation operation, byte[] bytes) {
        AuthorityWire.uuid(tenantId);
        if(operation!=Operation.PUBLISH&&operation!=Operation.REVOKE)throw bad();
        var original=AuthorityWire.original(bytes,65536);
        var request=AuthorityWire.record(AuthorityWire.parse(original),Decision.class,Set.of());
        AuthorityWire.uuid(request.actionId());AuthorityWire.uuid(request.issuerId());AuthorityWire.uuid(request.publicationId());
        positive(request.revision());generation(request.expectedGeneration());AuthorityWire.digest(request.definitionDigest());
        text(request.reason(),1,500);
        var canonical=AuthorityWire.token("FF_DOCUMENT_ISSUER_DECISION_V1")+AuthorityWire.recordToken(tenantId,operation,request);
        return new Intent<>(original,request,canonical,AuthorityWire.sha(canonical));
    }
    public static String definitionDigest(Definition definition) {
        validate(definition);
        return AuthorityWire.sha(AuthorityWire.token("FF_DOCUMENT_ISSUER_DEFINITION_V1")+AuthorityWire.token(definition));
    }
    public static String definitionJson(Definition definition){
        validate(definition);
        try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(definition);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw bad();}
    }
    public static Definition decodeDefinition(byte[] bytes){
        var definition=AuthorityWire.record(AuthorityWire.parse(AuthorityWire.original(bytes,65536)),Definition.class,Set.of());
        validate(definition);return definition;
    }
    public static String receiptJson(Receipt receipt){
        validateReceipt(receipt);
        try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(receipt);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw bad();}
    }
    public static Receipt decodeReceipt(byte[] bytes){
        var receipt=AuthorityWire.record(AuthorityWire.parse(AuthorityWire.original(bytes,16384)),Receipt.class,Set.of());
        validateReceipt(receipt);return receipt;
    }
    public static String receiptDigest(Receipt receipt){
        validateReceipt(receipt);return AuthorityWire.sha(AuthorityWire.token("FF_DOCUMENT_ISSUER_RECEIPT_V1")+AuthorityWire.token(receipt));
    }
    public static String referenceJson(Reference reference){
        validateReference(reference);
        try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(reference);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw bad();}
    }
    public static String referenceDigest(Reference reference){
        validateReference(reference);return AuthorityWire.sha(AuthorityWire.token("FF_DOCUMENT_ISSUER_CURRENT_V1")+AuthorityWire.token(reference));
    }
    public static PublicationReference decodePublicationReference(String tenantId,byte[] bytes){
        AuthorityWire.uuid(tenantId);
        var reference=AuthorityWire.record(AuthorityWire.parse(AuthorityWire.original(bytes,4096)),PublicationReference.class,Set.of());
        validatePublicationReference(reference);if(!tenantId.equals(reference.tenantId()))throw bad();return reference;
    }
    public static String publicationReferenceJson(PublicationReference reference){
        validatePublicationReference(reference);
        try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(reference);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw bad();}
    }
    public static String publicationReferenceDigest(PublicationReference reference){
        validatePublicationReference(reference);return AuthorityWire.sha(AuthorityWire.token("FF_DOCUMENT_ISSUER_CURRENT_REQUEST_V1")+AuthorityWire.token(reference));
    }
    private static void validatePublicationReference(PublicationReference reference){
        if(reference==null)throw bad();AuthorityWire.uuid(reference.tenantId());AuthorityWire.uuid(reference.publicationId());
        positive(reference.revision());AuthorityWire.digest(reference.definitionDigest());useKey(reference.use());
    }
    private static void validateReference(Reference reference){
        if(reference==null)throw bad();AuthorityWire.uuid(reference.tenantId());AuthorityWire.uuid(reference.issuerId());AuthorityWire.uuid(reference.publicationId());
        positive(reference.revision());AuthorityWire.digest(reference.definitionDigest());useKey(reference.use());
    }
    private static void validateReceipt(Receipt receipt){
        if(receipt==null||receipt.operation()==null||receipt.state()==null)throw bad();
        for(var id:java.util.List.of(receipt.tenantId(),receipt.issuerId(),receipt.publicationId(),receipt.actionId()))AuthorityWire.uuid(id);
        positive(receipt.revision());positive(receipt.generation());AuthorityWire.digest(receipt.definitionDigest());AuthorityWire.digest(receipt.intentDigest());
        if(receipt.recordedAtEpochMillis()<0||receipt.recordedAtEpochMillis()>253402300799999L)throw bad();
        boolean matches=switch(receipt.operation()){
            case PREPARE->receipt.state()==State.PREPARED;
            case PUBLISH->receipt.state()==State.PUBLISHED;
            case REVOKE->receipt.state()==State.REVOKED;
            default->false;
        };
        if(!matches)throw bad();
    }
    public static void validate(Definition definition) {
        if(definition==null||definition.source()==null||definition.uses()==null)throw bad();
        AuthorityWire.uuid(definition.issuerId());positive(definition.revision());
        if(definition.issuerCode()==null||!definition.issuerCode().matches("[A-Z][A-Z0-9_]{0,63}"))throw bad();
        text(definition.displayName(),1,255);text(definition.contactEmail(),3,254);
        if(!definition.contactEmail().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw bad();
        if(definition.effectiveFromEpochMillis()<0||definition.effectiveFromEpochMillis()>=definition.effectiveUntilEpochMillis()
                ||definition.effectiveUntilEpochMillis()>253402300799999L)throw bad();
        validateSource(definition.source());
        if(definition.uses().isEmpty()||definition.uses().size()>32)throw bad();
        String prior=null;
        for(var use:definition.uses()) {
            var key=useKey(use);
            if(prior!=null&&prior.compareTo(key)>=0)throw bad();prior=key;
        }
    }
    public static void validateSource(SourceSnapshot source){
        if(source==null)throw bad();AuthorityWire.uuid(source.tenantId());AuthorityWire.uuid(source.brandingId());
        generation(source.tenantVersion());generation(source.brandingVersion());positive(source.sourceGeneration());
        if(!source.tenantActive()||!source.brandingActive())throw bad();
        text(source.tenantCode(),1,100);text(source.tenantName(),1,255);text(source.footerText(),0,2000);
        text(source.logoUrl(),1,2000);
        try {var uri=URI.create(source.logoUrl());if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw bad();}catch(IllegalArgumentException e){throw bad();}
        if(source.primaryColor()==null||!source.primaryColor().matches("#[0-9A-Fa-f]{6}"))throw bad();
    }
    static String useKey(Use use){
        if(use==null||use.purpose()!=Purpose.DEPOSIT_ACCOUNT_ENROLLMENT||use.documentType()==null||!use.documentType().matches("[A-Z][A-Z0-9_]{0,63}"))throw bad();
        text(use.locale(),2,64);var locale=Locale.forLanguageTag(use.locale());
        if(locale.getLanguage().isEmpty()||"und".equals(locale.toLanguageTag())||!locale.toLanguageTag().equals(use.locale()))throw bad();
        return use.purpose().name()+"/"+use.documentType()+"/"+use.locale();
    }
    static void generation(long value){if(value<0||value==Long.MAX_VALUE)throw bad();}
    static void positive(long value){if(value<1||value==Long.MAX_VALUE)throw bad();}
    static void text(String value,int min,int max){AuthorityWire.scalar(value);int size=value.getBytes(StandardCharsets.UTF_8).length;if(size<min||size>max||min>0&&value.isBlank())throw bad();}
    private static IllegalArgumentException bad(){return new IllegalArgumentException("Invalid document issuer request");}
}

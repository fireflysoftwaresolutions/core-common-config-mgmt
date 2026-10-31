package io.fireflysoftware.common.config.web.security;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityPackagedHttpIT extends NativeAuthorityVerifiedHttpIT {
    @Test void metadataRoleTenantScopeAndCurrentWithdrawalAreEnforced() throws Exception {
        assertNotNull(System.getProperty("cfg.authority.packaged.jar"));var reader=metadataToken("metadata-reader",List.of("METADATA_READER"),platform);var nativeOperator=token("machine",null);var target="/api/v1/tenants/"+tenant;var status="/api/v1/tenant-statuses/"+statusId;
        for(var path:List.of(target,status)){assertEquals(403,call("GET",path,nativeOperator,null).statusCode());assertEquals(401,call("GET",path,null,null).statusCode());var response=call("GET",path,reader,null);assertEquals(200,response.statusCode(),response.body());}
        assertEquals(403,call("POST","/api/v1/api-process-mappings",nativeOperator,"{}").statusCode());assertEquals(403,call("GET","/api/v1/tenants/"+platform,reader,null).statusCode());assertEquals(403,call("GET",target,metadataToken("metadata-reader",List.of("METADATA_READER"),tenant),null).statusCode());assertEquals(403,call("GET","/api/v1/api-process-mappings/vanilla",reader,null).statusCode());
        try{metadataRead=false;policyData();assertEquals(403,call("GET",target,reader,null).statusCode());}finally{metadataRead=true;policyData();}assertEquals(200,call("GET",target,reader,null).statusCode());
    }
    @Test void scopedMetadataWriterUpdatesOrdinaryNameAndCurrentWithdrawalBlocksReplay() throws Exception {
        var path="/api/v1/tenants/"+tenant;var reader=metadataToken("metadata-reader",List.of("METADATA_READER"),platform);var writer=metadataToken("metadata-writer",List.of("METADATA_WRITER"),platform);var body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(call("GET",path,reader,null).body());body.put("name","Fictional metadata writer update");
        assertEquals(403,call("PUT",path,reader,body.toString()).statusCode());assertEquals(403,call("PUT","/api/v1/tenants/"+platform,writer,body.toString()).statusCode());var updated=call("PUT",path,writer,body.toString());assertEquals(200,updated.statusCode(),updated.body());
        try{metadataWrite=false;policyData();assertEquals(403,call("PUT",path,writer,body.toString()).statusCode());}finally{metadataWrite=true;policyData();}assertEquals("Fictional metadata writer update",JSON.readTree(call("GET",path,reader,null).body()).get("name").textValue());
    }
    @Test void explicitMetadataAdminCrudRequiresCurrentWritePermission() throws Exception {
        var base="/api/v1/api-process-mappings";var admin=metadataToken("metadata-admin",List.of("METADATA_ADMIN"),platform);var reader=metadataToken("metadata-reader",List.of("METADATA_READER"),platform);var body=JSON.createObjectNode().put("tenantId",tenant).put("operationId","metadata-proof-"+java.util.UUID.randomUUID()).put("processId","fictional-original-process").put("parameters","{\"fictional\":1}").put("priority",0).put("isActive",true);assertEquals(403,call("POST",base,reader,body.toString()).statusCode());var created=call("POST",base,admin,body.toString());assertEquals(201,created.statusCode(),created.body());var path=base+"/"+JSON.readTree(created.body()).get("id").textValue();body.put("processId","fictional-updated-process");body.put("parameters","{\"fictional\":2}");var updated=call("PUT",path,admin,body.toString());assertEquals(200,updated.statusCode(),updated.body());assertEquals(2,JSON.readTree(JSON.readTree(updated.body()).get("parameters").textValue()).get("fictional").intValue());body.putNull("parameters");var cleared=call("PUT",path,admin,body.toString());assertEquals(200,cleared.statusCode(),cleared.body());assertTrue(JSON.readTree(cleared.body()).get("parameters").isNull());
        try{metadataWrite=false;policyData();assertEquals(403,call("PUT",path,admin,body.toString()).statusCode());assertEquals(403,call("DELETE",path,admin,null).statusCode());}finally{metadataWrite=true;policyData();}assertEquals(204,call("DELETE",path,admin,null).statusCode());
    }
    @Test void explicitCurrentMetadataAdminReadsOriginalVanillaMappings() throws Exception {
        var admin=metadataToken("metadata-admin",List.of("METADATA_ADMIN"),platform);var response=call("GET","/api/v1/api-process-mappings/vanilla",admin,null);assertEquals(200,response.statusCode(),response.body());assertFalse(response.body().isBlank());
    }
}

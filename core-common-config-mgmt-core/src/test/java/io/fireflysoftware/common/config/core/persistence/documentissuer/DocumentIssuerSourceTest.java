package io.fireflysoftware.common.config.core.persistence.documentissuer;

import io.fireflysoftware.common.config.core.services.documentissuer.impl.DocumentIssuerServiceImpl;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIssuerSourceTest {
    static byte[] body(){var p=DocumentIssuerServiceTest.request();var s=p.definition().source();return ("{\"tenantId\":\""+s.tenantId()+"\",\"issuerId\":\""+p.definition().issuerId()+"\",\"publicationId\":\""+p.publicationId()+"\",\"actionId\":\""+p.actionId()+"\",\"brandingId\":\""+s.brandingId()+"\",\"use\":{\"purpose\":\"DEPOSIT_ACCOUNT_ENROLLMENT\",\"documentType\":\"CONTRACT\",\"locale\":\"en-US\"}}").getBytes(StandardCharsets.UTF_8);}
    static Mono<?> observe(DocumentIssuerServiceImpl service,String tenant,byte[] bytes)throws Exception{return (Mono<?>)service.getClass().getMethod("observeSource",String.class,byte[].class).invoke(service,tenant,bytes);}
    @Test void actualMappedSourceObservationUsesOnlyDedicatedReadOnlyRoot()throws Exception{
        var f=new DocumentIssuerTransactionTest();var a=f.authority();var c=new DocumentIssuerTransactionTest.Driver();var r=new DocumentIssuerTransactionTest.Driver();r.sourceData=DocumentIssuerServiceTest.sourceRow();
        var service=new DocumentIssuerServiceImpl(a,new DocumentIssuerTransaction(c,a,f.clock,f.nanos),new DocumentIssuerTransaction(r,a,f.clock,f.nanos));var observed=observe(service,DocumentIssuerTransactionTest.TENANT,body()).block();
        assertNotNull(observed);assertEquals(DocumentIssuerServiceTest.request().definition().source(),observed.getClass().getMethod("source").invoke(observed));
        assertEquals(0,c.creates);assertEquals(1,r.creates);assertEquals(1,r.commits);assertEquals(1,r.closes);assertTrue(r.sql.contains("SET TRANSACTION READ ONLY"));
        assertTrue(r.sql.stream().noneMatch(sql->sql.startsWith("INSERT ")||sql.startsWith("UPDATE ")||sql.contains("cfg_document_issuer_revision")||sql.contains("cfg_document_issuer_control")));
        assertTrue((long)observed.getClass().getMethod("effectiveUntilEpochMillis").invoke(observed)<=(long)observed.getClass().getMethod("nativeUntilEpochMillis").invoke(observed));
    }
    @Test void missingActualSourceRefusesAndRollsBack()throws Exception{
        var f=new DocumentIssuerTransactionTest();var a=f.authority();var c=new DocumentIssuerTransactionTest.Driver();var r=new DocumentIssuerTransactionTest.Driver();var service=new DocumentIssuerServiceImpl(a,new DocumentIssuerTransaction(c,a,f.clock,f.nanos),new DocumentIssuerTransaction(r,a,f.clock,f.nanos));
        var result=observe(service,DocumentIssuerTransactionTest.TENANT,body());assertThrows(RuntimeException.class,result::block);assertEquals(0,c.creates);assertEquals(0,r.commits);assertEquals(1,r.rollbacks);assertEquals(1,r.closes);
    }
    @Test void sourceHasNoCommandPoolFallback()throws Exception{
        var f=new DocumentIssuerTransactionTest();var a=f.authority();var c=new DocumentIssuerTransactionTest.Driver();var service=new DocumentIssuerServiceImpl(a,new DocumentIssuerTransaction(c,a,f.clock,f.nanos));var result=observe(service,DocumentIssuerTransactionTest.TENANT,body());assertThrows(IllegalStateException.class,result::block);assertEquals(0,c.creates);
    }
    @Test void malformedSourceSelectionCannotAcquire()throws Exception{
        var f=new DocumentIssuerTransactionTest();var a=f.authority();var c=new DocumentIssuerTransactionTest.Driver();var r=new DocumentIssuerTransactionTest.Driver();var service=new DocumentIssuerServiceImpl(a,new DocumentIssuerTransaction(c,a,f.clock,f.nanos),new DocumentIssuerTransaction(r,a,f.clock,f.nanos));var result=observe(service,DocumentIssuerTransactionTest.TENANT,"{}".getBytes(StandardCharsets.UTF_8));assertThrows(IllegalArgumentException.class,result::block);assertEquals(0,c.creates);assertEquals(0,r.creates);
    }
}

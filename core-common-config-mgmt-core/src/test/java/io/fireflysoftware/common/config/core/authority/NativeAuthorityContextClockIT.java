package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityContextClockIT {
    @BeforeAll static void requireLease() throws Exception {InstitutionAuthorityNativeIT.requireExplicitOwnedNativeLease();}
    static Connection runtime() throws SQLException {return DriverManager.getConnection(InstitutionAuthorityNativeIT.required("CFG_NATIVE_JDBC_URL"),"cfg_native_runtime",InstitutionAuthorityNativeIT.required("CFG_NATIVE_RUNTIME_PASSWORD"));}
    static boolean establish(Connection c,Long until,Long deadline) throws SQLException {
        try(var q=c.prepareStatement("select public.cfg_native_establish_context(?::uuid,null,'HISTORY','PROPOSAL',?::uuid,?::uuid,?,'STAFF','https://clock-probe.example.test','clock-probe',?::uuid,?::uuid,null,true,?,?)")) {
            q.setString(1,UUID.randomUUID().toString());q.setString(2,UUID.randomUUID().toString());q.setString(3,UUID.randomUUID().toString());q.setString(4,"a".repeat(64));q.setString(5,UUID.randomUUID().toString());q.setString(6,UUID.randomUUID().toString());q.setObject(7,until,Types.BIGINT);q.setObject(8,deadline,Types.BIGINT);
            try(var r=q.executeQuery()){assertTrue(r.next());return r.getBoolean(1);}
        }
    }
    @Test void futureApplicationHorizonIsBoundedByFinalDatabaseClock() throws Exception {
        try(var c=runtime()) {
            c.setAutoCommit(false);
            try {
                assertTrue(establish(c,253402300799999L,253402300799999L));
                try(var q=c.createStatement();var r=q.executeQuery("select current_setting('firefly.cfg.authority_until')::bigint,current_setting('firefly.cfg.command_until')::bigint,floor(extract(epoch from clock_timestamp())*1000)::bigint")) {
                    assertTrue(r.next());var authority=r.getLong(1);var command=r.getLong(2);var databaseNow=r.getLong(3);
                    assertTrue(authority>databaseNow);assertTrue(authority<=databaseNow+30000);assertEquals(authority,command);
                }
            } finally {c.rollback();}
        }
    }
    @Test void retainedShorterHorizonIsNeverExtended() throws Exception {
        try(var c=runtime()) {
            c.setAutoCommit(false);try {var supplied=System.currentTimeMillis()+10000;assertTrue(establish(c,supplied,supplied));
                try(var q=c.createStatement();var r=q.executeQuery("select current_setting('firefly.cfg.authority_until')::bigint,current_setting('firefly.cfg.command_until')::bigint")){assertTrue(r.next());assertEquals(supplied,r.getLong(1));assertEquals(supplied,r.getLong(2));}
            } finally {c.rollback();}
        }
    }
    @Test void nullHorizonsAndInvalidMaximumDeadlineCannotUseLeastNullSemantics() throws Exception {
        for(var horizons:new Long[][]{{null,253402300799999L},{253402300799999L,null},{253402300799999L,Long.MAX_VALUE}})try(var c=runtime()) {
            c.setAutoCommit(false);try {assertEquals("42501",assertThrows(SQLException.class,()->establish(c,horizons[0],horizons[1])).getSQLState());}finally{c.rollback();}
        }
    }
    @Test void expiredAdmissionAndCommandDeadlineRemainDenied() throws Exception {
        for(boolean expiredAuthority:new boolean[]{true,false})try(var c=runtime()) {
            c.setAutoCommit(false);try {var now=System.currentTimeMillis();assertEquals("42501",assertThrows(SQLException.class,()->establish(c,expiredAuthority?now-1000:now+10000,expiredAuthority?now+10000:now-1000)).getSQLState());}finally{c.rollback();}
        }
    }
}

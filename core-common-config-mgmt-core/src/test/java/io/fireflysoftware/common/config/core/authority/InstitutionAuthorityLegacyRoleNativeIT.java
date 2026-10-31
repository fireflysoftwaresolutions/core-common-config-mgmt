package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.*;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityLegacyRoleNativeIT {
    @BeforeAll static void lease() throws Exception {TenantEligibilityNativeIT.lease();}
    @Test void runtimePrivilegesMatchExactBaselineOperationsAndExcludeNativeMutation() throws Exception {
        var tables=List.of("tenants","tenant_statuses","tenant_brandings","provider_types","provider_statuses","providers","provider_parameters","provider_value_mappings","provider_tenants","feature_flags","tenant_settings","webhook_configs","environment_configs","channel_configs","channel_config_parameters","api_process_mappings","configuration_audits");
        try(var c=TenantEligibilityNativeIT.runtime();var privilege=c.prepareStatement("select has_table_privilege(current_user,?::regclass,?)")) {
            for(var table:tables)for(var operation:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER")) {
                privilege.setString(1,"public."+table);privilege.setString(2,operation);try(var rows=privilege.executeQuery()){assertTrue(rows.next());boolean permitted=List.of("SELECT","INSERT","UPDATE","DELETE").contains(operation) && (!table.equals("configuration_audits") || List.of("SELECT","INSERT").contains(operation));assertEquals(permitted,rows.getBoolean(1),table+":"+operation);}
            }
            try(var query=c.prepareStatement("select relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind='r' and c.relname like 'cfg_native_%'")){var rows=query.executeQuery();int count=0;while(rows.next()){count++;for(var operation:List.of("INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER")){privilege.setString(1,"public."+rows.getString(1));privilege.setString(2,operation);try(var grant=privilege.executeQuery()){assertTrue(grant.next());assertFalse(grant.getBoolean(1),rows.getString(1)+":"+operation);}}}assertEquals(18,count);}
            for(var sql:List.of("update public.configuration_audits set id=id where false","delete from public.configuration_audits where false","update public.cfg_native_grant_control set state=state where false"))try(var statement=c.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->statement.executeUpdate(sql)).getSQLState());}
            try(var query=c.prepareStatement("select has_schema_privilege(current_user,'public','CREATE'),pg_has_role(current_user,'cfg_native_owner','MEMBER'),pg_has_role(current_user,'cfg_native_guard','MEMBER')")){var row=query.executeQuery();assertTrue(row.next());for(int index=1;index<=3;index++)assertFalse(row.getBoolean(index));}
        }
    }
}

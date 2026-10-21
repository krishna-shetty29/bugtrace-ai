package com.bugtrace.backend;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_REGISTRATION_PG_TEST",matches="true")
class BenchmarkRegistrationPostgresTest {
    @Test void registersReplaysAndReloadsVerifiedReleaseAcrossServiceInstances()throws Exception {
        String schema="benchmark_test_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("DB_URL"),user=System.getenv("POSTGRES_USER"),password=System.getenv("POSTGRES_PASSWORD");assertNotNull(url,"Load DB_URL privately");
        try(var conn=DriverManager.getConnection(url,user,password);var statement=conn.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("SET search_path TO "+schema);ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/migration/V18__add_benchmark_case_registrations.sql"));
                var ds=new DriverManagerDataSource(url,user,password);var properties=new java.util.Properties();properties.setProperty("currentSchema",schema);ds.setConnectionProperties(properties);
                var jdbc=new JdbcTemplate(ds);String repo=System.getProperty("user.home")+"/bbp",commit="6c4f8856bb7e4d49e953a8b086d594d2f505838d";
                var service=new BenchmarkRegistrationService(jdbc,true,repo,commit);
                var first=service.register(new BenchmarkRegistrationService.Create("case-001"));UUID id=(UUID)first.get("registrationId");
                assertEquals(true,first.get("verified"));assertEquals(false,first.get("executionSupported"));assertEquals(id,service.register(new BenchmarkRegistrationService.Create("case-001")).get("registrationId"));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM benchmark_case_registrations",Integer.class));
                assertEquals(true,new BenchmarkRegistrationService(new JdbcTemplate(ds),true,repo,commit).verify(id).get("verified"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.register(new BenchmarkRegistrationService.Create("../bbh")));
                jdbc.update("UPDATE benchmark_case_registrations SET registration='{}'::jsonb WHERE id=?",id);
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.verify(id));
            }finally{statement.execute("SET search_path TO public");statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}

package com.bugtrace.backend;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_BROWSER_PG_TEST",matches="true")
class BenchmarkBrowserPostgresTest {
    @TempDir Path temp;
    @AfterEach void writableCleanup()throws Exception {try(var walk=Files.walk(temp)){for(Path file:walk.filter(Files::isRegularFile).toList())if(Files.getFileStore(file).supportsFileAttributeView("dos"))Files.setAttribute(file,"dos:readonly",false);}}
    @Test void createsFreshCaseReportsIdempotentlyAndReloadsVerifiedImports()throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL")),user=System.getenv("POSTGRES_USER"),password=System.getenv("POSTGRES_PASSWORD");
        if(!url.startsWith("jdbc:postgresql://localhost:")&&!url.startsWith("jdbc:postgresql://127.0.0.1:"))throw new IllegalArgumentException("Use local PostgreSQL for the test");
        String schema="benchmark_browser_test_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("SET search_path TO "+schema);
                try(var stream=new ClassPathResource("db/migration/V2__create_initial_tables.sql").getInputStream()){statement.execute(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
                for(String name:List.of("V3__add_snapshot_import_metadata.sql","V18__add_benchmark_case_registrations.sql","V19__add_benchmark_snapshot_imports.sql","V22__add_benchmark_browser_requests.sql"))ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+name));
                var source=new DriverManagerDataSource(url,user,password);var props=new Properties();props.setProperty("currentSchema",schema);source.setConnectionProperties(props);
                var jdbc=new JdbcTemplate(source);var manager=new DataSourceTransactionManager(source);
                String repository=System.getenv().getOrDefault("BENCHMARK_TEST_REPOSITORY",Path.of(System.getProperty("user.home"),"bbp").toString());
                var registrations=new BenchmarkRegistrationService(jdbc,true,repository,"6c4f8856bb7e4d49e953a8b086d594d2f505838d");
                var snapshots=new BenchmarkSnapshotService(jdbc,manager,registrations,temp.resolve("workspace").toString());var investigations=new InvestigationService(jdbc);
                var browser=new BenchmarkBrowserService(jdbc,manager,registrations,snapshots,investigations);
                assertEquals(6,((List<?>)browser.catalog().get("cases")).size());
                UUID request=UUID.randomUUID();var input=new BenchmarkBrowserService.Create(request,"case-001");var first=browser.create(input);UUID id=(UUID)first.get("investigationId");
                assertEquals(false,first.get("imported"));assertEquals(true,first.get("executionSupported"));assertEquals(false,first.get("liveSmokeReady"));
                assertEquals(id,browser.create(input).get("investigationId"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM investigations",Integer.class));
                assertThrows(Exception.class,()->browser.create(new BenchmarkBrowserService.Create(request,"case-002")));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM investigations",Integer.class));
                var fresh=browser.create(new BenchmarkBrowserService.Create(UUID.randomUUID(),"case-001"));assertNotEquals(id,fresh.get("investigationId"));assertNotEquals(first.get("snapshotId"),fresh.get("snapshotId"));
                var captured=browser.capture(id);assertEquals(true,captured.get("imported"));assertEquals(true,((Map<?,?>)captured.get("verification")).get("verified"));
                assertEquals(captured.get("snapshotId"),browser.capture(id).get("snapshotId"));assertEquals(2,browser.recent().size());
                var restarted=new BenchmarkBrowserService(jdbc,manager,registrations,snapshots,investigations);assertEquals(captured.get("snapshotId"),restarted.detail(id).get("snapshotId"));
                assertThrows(Exception.class,()->browser.detail(UUID.randomUUID()));
                UUID other=(UUID)registrations.register(new BenchmarkRegistrationService.Create("case-002")).get("registrationId");
                jdbc.update("UPDATE benchmark_browser_requests SET registration_id=? WHERE request_id=?",other,request);
                assertThrows(Exception.class,()->browser.detail(id));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM executions",Integer.class));
            }finally{statement.execute("SET search_path TO public");statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}

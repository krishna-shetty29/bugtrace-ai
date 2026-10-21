package com.bugtrace.backend;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_SNAPSHOT_PG_TEST",matches="true")
class BenchmarkSnapshotPostgresTest {
    @TempDir Path temp;
    @AfterEach void writableCleanup()throws Exception {try(var walk=Files.walk(temp)){for(Path file:walk.filter(Files::isRegularFile).toList())if(Files.getFileStore(file).supportsFileAttributeView("dos"))Files.setAttribute(file,"dos:readonly",false);}}
    @Test void importsReplaysReloadsAndRejectsCrossCaseWithoutRelaxingOriginalAllowlist()throws Exception {
        String schema="benchmark_import_test_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("DB_URL"),user=System.getenv("POSTGRES_USER"),password=System.getenv("POSTGRES_PASSWORD");assertNotNull(url);
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("SET search_path TO "+schema);
                // V2 contains a dollar-quoted PL/pgSQL function. Send the complete
                // script to PostgreSQL; ScriptUtils's default semicolon splitter
                // does not understand the function's internal statement separators.
                try(var stream=new ClassPathResource("db/migration/V2__create_initial_tables.sql").getInputStream()) {
                    statement.execute(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                }
                for(String file:List.of("V3__add_snapshot_import_metadata.sql","V18__add_benchmark_case_registrations.sql","V19__add_benchmark_snapshot_imports.sql"))ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
                var data=new DriverManagerDataSource(url,user,password);var properties=new Properties();properties.setProperty("currentSchema",schema);data.setConnectionProperties(properties);
                var jdbc=new JdbcTemplate(data);var manager=new DataSourceTransactionManager(data);
                String repo=Path.of(System.getProperty("user.home"),"bbp").toString(),commit="6c4f8856bb7e4d49e953a8b086d594d2f505838d";
                var registrations=new BenchmarkRegistrationService(jdbc,true,repo,commit);
                UUID first=(UUID)registrations.register(new BenchmarkRegistrationService.Create("case-001")).get("registrationId"),second=(UUID)registrations.register(new BenchmarkRegistrationService.Create("case-002")).get("registrationId");
                var investigations=new InvestigationService(jdbc);var project=investigations.createProject("Import test","Temporary schema only");
                var investigation=investigations.createInvestigation(project.id(),repo,commit,"Import case 001","Report","Expected","Not executed");
                var service=new BenchmarkSnapshotService(jdbc,manager,registrations,temp.resolve("workspace").toString());
                var imported=service.capture(first,new BenchmarkSnapshotService.Import(investigation.id()));assertEquals(true,imported.get("verified"));assertEquals(false,imported.get("executionSupported"));
                assertEquals(imported.get("snapshotId"),service.capture(first,new BenchmarkSnapshotService.Import(investigation.id())).get("snapshotId"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.capture(second,new BenchmarkSnapshotService.Import(investigation.id())));
                var restarted=new BenchmarkSnapshotService(new JdbcTemplate(data),manager,registrations,temp.resolve("workspace").toString());assertEquals(true,restarted.verify(first,investigation.id()).get("verified"));
                var oldImporter=new SnapshotImportService(jdbc,new TransactionTemplate(manager),Path.of(System.getProperty("user.home"),"bugtrace-ai").toString(),temp.resolve("workspace").toString());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->oldImporter.importSnapshot(investigation.id(),repo,commit,"cases/case-001"));
                assertEquals(true,oldImporter.verifySnapshot(investigation.id()).get("verified"));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM benchmark_snapshot_imports",Integer.class));
            }finally{statement.execute("SET search_path TO public");statement.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}

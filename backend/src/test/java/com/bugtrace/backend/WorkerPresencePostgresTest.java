package com.bugtrace.backend;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_WORKER_PRESENCE_PG_TEST",matches="true")
class WorkerPresencePostgresTest {
 @Test void bindsPresenceToCredentialUsesServerClockAndDoesNotRenewLease() throws Exception {
  String url=Objects.requireNonNull(System.getenv("DB_URL"));
  if(!url.startsWith("jdbc:postgresql://127.0.0.1:")&&!url.startsWith("jdbc:postgresql://localhost:"))throw new IllegalArgumentException("Local test database only");
  String user=Objects.requireNonNull(System.getenv("POSTGRES_USER")),password=Objects.requireNonNull(System.getenv("POSTGRES_PASSWORD"));
  String schema="presence_test_"+UUID.randomUUID().toString().replace("-","");
  try(Connection admin=DriverManager.getConnection(url,user,password);Statement ddl=admin.createStatement()) {
   ddl.execute("CREATE SCHEMA "+schema);
   try {
    var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);
    var jdbc=new JdbcTemplate(ds);
    jdbc.execute("CREATE TABLE projects(id UUID PRIMARY KEY)");
    jdbc.execute("CREATE TABLE repository_snapshots(id UUID PRIMARY KEY,project_id UUID NOT NULL,content_hash TEXT)");
    jdbc.execute("CREATE TABLE team_credentials(id UUID PRIMARY KEY,project_id UUID,kind TEXT,worker_id TEXT,snapshot_id UUID,revoked BOOLEAN,expires_at TIMESTAMPTZ)");
    jdbc.execute("CREATE TABLE execution_jobs(attempt_execution_id UUID PRIMARY KEY,snapshot_id UUID,worker_id TEXT,queue_state TEXT,lease_until TIMESTAMPTZ)");
    jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V25__add_worker_presence.sql")));
    UUID project=UUID.randomUUID(),other=UUID.randomUUID(),snapshot=UUID.randomUUID(),credential=UUID.randomUUID(),execution=UUID.randomUUID();String hash="a".repeat(64);
    jdbc.update("INSERT INTO projects VALUES (?),(?)",project,other);
    jdbc.update("INSERT INTO repository_snapshots VALUES (?,?,?)",snapshot,project,hash);
    jdbc.update("INSERT INTO team_credentials VALUES (?,?,'WORKER','laptop-worker-01',?,FALSE,clock_timestamp()+INTERVAL '1 day')",credential,project,snapshot);
    jdbc.update("INSERT INTO execution_jobs VALUES (?,?,'laptop-worker-01','RUNNING',clock_timestamp()+INTERVAL '1 minute')",execution,snapshot);
    var service=new WorkerPresenceService(jdbc,new DataSourceTransactionManager(ds));
    var worker=new TeamAccessContext.Actor(credential,project,"Test worker","WORKER",null,snapshot,"laptop-worker-01");
    TeamAccessContext.set(worker,UUID.randomUUID());
    var idle=new WorkerPresenceService.Contact("laptop-worker-01",snapshot,hash,"BASE_VERIFIED",null);
    assertNotNull(service.publish(idle).get("acceptedAt"));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM worker_presence WHERE last_contact_at BETWEEN clock_timestamp()-INTERVAL '5 seconds' AND clock_timestamp()",Integer.class));
    Object lease=jdbc.queryForObject("SELECT lease_until FROM execution_jobs",Object.class);
    service.publish(new WorkerPresenceService.Contact("laptop-worker-01",snapshot,hash,"BASE_VERIFIED",execution));
    assertEquals(lease,jdbc.queryForObject("SELECT lease_until FROM execution_jobs",Object.class));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(new WorkerPresenceService.Contact("foreign",snapshot,hash,"BASE_VERIFIED",null)));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(new WorkerPresenceService.Contact("laptop-worker-01",snapshot,"b".repeat(64),"BASE_VERIFIED",null)));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(new WorkerPresenceService.Contact("laptop-worker-01",snapshot,hash,"BASE_VERIFIED",UUID.randomUUID())));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,service::list);
    var human=new TeamAccessContext.Actor(UUID.randomUUID(),project,"Viewer","HUMAN","VIEWER",null,null);
    TeamAccessContext.set(human,UUID.randomUUID());assertEquals("BUSY",service.list().getFirst().get("availability"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(idle));
    TeamAccessContext.set(new TeamAccessContext.Actor(UUID.randomUUID(),other,"Other","HUMAN","OWNER",null,null),UUID.randomUUID());assertTrue(service.list().isEmpty());
    TeamAccessContext.set(human,UUID.randomUUID());
    jdbc.update("UPDATE worker_presence SET last_contact_at=clock_timestamp()-INTERVAL '61 seconds'");assertEquals("OFFLINE",service.list().getFirst().get("availability"));
    jdbc.update("UPDATE team_credentials SET revoked=TRUE");assertEquals("REVOKED",service.list().getFirst().get("availability"));
    Object previous=jdbc.queryForObject("SELECT last_contact_at FROM worker_presence",Object.class);
    TeamAccessContext.set(worker,UUID.randomUUID());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(idle));assertEquals(previous,jdbc.queryForObject("SELECT last_contact_at FROM worker_presence",Object.class));
    jdbc.update("UPDATE team_credentials SET revoked=FALSE,expires_at=clock_timestamp()-INTERVAL '1 second'");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(idle));
    TeamAccessContext.clear();assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.publish(idle));assertThrows(org.springframework.web.server.ResponseStatusException.class,service::list);
   }finally{TeamAccessContext.clear();ddl.execute("DROP SCHEMA "+schema+" CASCADE");}
  }
 }
}

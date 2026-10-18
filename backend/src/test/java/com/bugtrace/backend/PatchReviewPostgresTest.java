package com.bugtrace.backend;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_PATCH_REVIEW_PG_TEST",matches="true")
class PatchReviewPostgresTest {
    @Test void migrationPersistsDecisionsAndRejectsDuplicates()throws Exception {
        String schema="review_test_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("DB_URL");String user=System.getenv("POSTGRES_USER"),password=System.getenv("POSTGRES_PASSWORD");
        assertNotNull(url,"Set DB_URL");
        try(Connection conn=DriverManager.getConnection(url,user,password)) {
            try(var sql=conn.createStatement()) {
                sql.execute("CREATE SCHEMA "+schema);
                try {
                    sql.execute("SET search_path TO "+schema);
                    sql.execute("CREATE TABLE investigations(id uuid PRIMARY KEY)");sql.execute("CREATE TABLE repository_snapshots(id uuid PRIMARY KEY)");sql.execute("CREATE TABLE diagnosis_reports(id uuid PRIMARY KEY)");
                    ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/migration/V17__add_developer_patch_reviews.sql"));
                    UUID review=UUID.randomUUID(),investigation=UUID.randomUUID(),snapshot=UUID.randomUUID(),diagnosis=UUID.randomUUID(),patch=UUID.randomUUID(),verification=UUID.randomUUID();
                    sql.execute("INSERT INTO investigations VALUES ('"+investigation+"')");sql.execute("INSERT INTO repository_snapshots VALUES ('"+snapshot+"')");sql.execute("INSERT INTO diagnosis_reports VALUES ('"+diagnosis+"')");
                    String insert="INSERT INTO developer_patch_reviews(id,investigation_id,snapshot_id,diagnosis_id,patch_id,verification_id,bundle_hash,payload,payload_hash) VALUES ('"+review+"','"+investigation+"','"+snapshot+"','"+diagnosis+"','"+patch+"','"+verification+"','"+"a".repeat(64)+"','{}','"+"b".repeat(64)+"')";
                    sql.execute(insert);
                    assertThrows(SQLException.class,()->sql.execute(insert.replace(review.toString(),UUID.randomUUID().toString())));
                    sql.execute("UPDATE developer_patch_reviews SET state='APPROVED',reviewed_at=clock_timestamp(),decision_reason='Reviewed known exception',suite_exception_accepted=true WHERE id='"+review+"'");
                    sql.execute("INSERT INTO developer_patch_review_events(review_id,action,details) VALUES ('"+review+"','APPROVED','{\"automaticApplication\":false}')");
                    try(var rows=sql.executeQuery("SELECT state,suite_exception_accepted FROM developer_patch_reviews WHERE id='"+review+"'")){assertTrue(rows.next());assertEquals("APPROVED",rows.getString(1));assertTrue(rows.getBoolean(2));}
                    try(Connection other=DriverManager.getConnection(url,user,password);var check=other.createStatement();var rows=check.executeQuery("SELECT count(*) FROM "+schema+".developer_patch_review_events")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                } finally {sql.execute("SET search_path TO public");sql.execute("DROP SCHEMA "+schema+" CASCADE");}
            }
        }
    }
}

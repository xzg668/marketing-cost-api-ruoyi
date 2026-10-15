package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class V307OaQueueColumnsMigrationIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("oa_queue_cleanup").withUsername("root").withPassword("root123");

  @BeforeAll static void start() {
    MYSQL.start();
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/V261__oa_integration_inbox.sql"), "/tmp/base.sql");
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/V307__remove_obsolete_oa_queue_columns.sql"), "/tmp/cleanup.sql");
  }

  @AfterAll static void stop() { MYSQL.stop(); }

  @BeforeEach void createHistoricalSchema() throws Exception {
    try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE oa_queue_cleanup");
      statement.execute("CREATE DATABASE oa_queue_cleanup CHARACTER SET utf8mb4");
    }
    assertThat(script("/tmp/base.sql").getExitCode()).isZero();
    execute("""
        INSERT INTO lp_oa_integration_message
          (source_system,environment,direction,request_id,interface_type,schema_version,
           occurred_at,raw_payload,payload_hash,allowed_business_units,status,result_json)
        VALUES ('WEAVER','TEST','INBOUND','REQUEST-1','QUOTE_REQUEST',3,
                '2026-10-09','{"product":"P1"}',REPEAT('a',64),'COMMERCIAL','PROCESSED','{"code":"0"}')
        """);
    execute("""
        INSERT INTO lp_oa_integration_attempt(message_id,attempt_no,mapping_version,stage,status)
        VALUES (1,1,'i01','BUSINESS','PROCESSED')
        """);
  }

  @Test void removesOnlyUnusedColumnsAndPreservesMessagesReceiptsAndIdempotency() throws Exception {
    String before = value("SELECT CONCAT_WS('|',id,request_id,raw_payload,payload_hash,status,result_json,received_at) FROM lp_oa_integration_message");
    ExecResult result = script("/tmp/cleanup.sql");
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    assertThat(script("/tmp/cleanup.sql").getExitCode()).isZero();
    assertThat(obsoleteColumns()).isEqualTo("0");
    assertThat(value("SELECT CONCAT_WS('|',id,request_id,raw_payload,payload_hash,status,result_json,received_at) FROM lp_oa_integration_message")).isEqualTo(before);
    assertThat(value("SELECT COUNT(*) FROM lp_oa_integration_attempt WHERE message_id=1")).isEqualTo("1");
    assertThat(value("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lp_oa_integration_message' AND index_name='idx_oa_message_poll'")).isEqualTo("0");
    assertThat(value("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lp_oa_integration_message' AND index_name='uk_oa_message_request'")).isEqualTo("4");
  }

  @ParameterizedTest
  @ValueSource(strings = {"lease_token='unfinished-lease'", "lease_until=NOW(3)", "next_attempt_at=TIMESTAMPADD(SECOND,60,received_at)"})
  void refusesToDiscardExistingLeaseOrRetryHistory(String assignment) throws Exception {
    execute("UPDATE lp_oa_integration_message SET " + assignment);
    ExecResult result = script("/tmp/cleanup.sql");
    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("inspect and archive existing OA lease or retry data");
    assertThat(obsoleteColumns()).isEqualTo("3");
    assertThat(value("SELECT COUNT(*) FROM lp_oa_integration_message")).isEqualTo("1");
  }

  private String obsoleteColumns() throws Exception {
    return value("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_oa_integration_message' AND column_name IN ('lease_token','lease_until','next_attempt_at')");
  }

  private static ExecResult script(String path) throws Exception {
    return MYSQL.execInContainer("sh", "-c", "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot oa_queue_cleanup < \"$1\"", "migration", path);
  }

  private static void execute(String sql) throws Exception {
    try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String value(String sql) throws Exception {
    try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }
}

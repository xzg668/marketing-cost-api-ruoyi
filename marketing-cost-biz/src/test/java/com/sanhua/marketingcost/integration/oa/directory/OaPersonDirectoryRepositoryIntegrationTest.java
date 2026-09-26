package com.sanhua.marketingcost.integration.oa.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

@Tag("integration")
class OaPersonDirectoryRepositoryIntegrationTest {

  @Test
  void replacesOnlyCompleteSnapshotsAndSearchesCsvFields() throws Exception {
    try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")) {
      mysql.start();
      MysqlDataSource dataSource = new MysqlDataSource();
      dataSource.setURL(mysql.getJdbcUrl());
      dataSource.setUser(mysql.getUsername());
      dataSource.setPassword(mysql.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      createIdentityTables(jdbc);
      try (Connection connection = dataSource.getConnection()) {
        ScriptUtils.executeSqlScript(connection,
            new ClassPathResource("db/V289__oa_person_directory.sql"));
      }
      jdbc.update("""
          INSERT INTO sys_role(role_id,role_key,status,del_flag)
          VALUES(12,'technical_collaborator','0','0')
          """);

      OaPersonDirectoryProperties properties = new OaPersonDirectoryProperties();
      OaPersonDirectoryRepository repository = new OaPersonDirectoryRepository(
          jdbc, new BCryptPasswordEncoder(), properties);
      TransactionTemplate transaction = new TransactionTemplate(
          new DataSourceTransactionManager(dataSource));

      transaction.executeWithoutResult(status -> repository.replace(snapshot(person(
          "U1", "E001", "张三", "商用制冷业务单元/商用四通阀事业部",
          "集团/商用制冷业务单元/商用四通阀事业部/制造部"))));

      assertThat(repository.search("E001", 50)).singleElement().satisfies(option -> {
        assertThat(option.name()).isEqualTo("张三");
        assertThat(option.employeeNo()).isEqualTo("E001");
        assertThat(option.targetDepartment()).contains("商用四通阀事业部");
        assertThat(option.department()).endsWith("制造部");
      });
      assertThat(repository.search("制造部", 50)).hasSize(1);
      assertThat(repository.search("商用四通", 50)).hasSize(1);

      transaction.executeWithoutResult(status -> repository.replace(snapshot(person(
          "U2", "E002", "李四", "商用制冷业务单元/技术中心",
          "集团/商用制冷业务单元/技术中心/研发部"))));
      String states = jdbc.query("""
          SELECT CONCAT(employee_no,':',active_flag) FROM lp_oa_person_directory
          ORDER BY employee_no
          """, (ResultSetExtractor<String>) rows -> {
        StringBuilder result = new StringBuilder();
        while (rows.next()) {
          if (!result.isEmpty()) result.append('|');
          result.append(rows.getString(1));
        }
        return result.toString();
      });
      assertThat(states).isEqualTo("E001:0|E002:1");
      assertThat(jdbc.queryForObject(
          "SELECT status FROM sys_user WHERE user_name='oa_E001'", String.class)).isEqualTo("1");

      OaDirectoryPerson oversized = person(
          "U4", "E004", "王五", "商用制冷业务单元/技术中心",
          "集团/商用制冷业务单元/技术中心/" + "超".repeat(2100));
      assertThatThrownBy(() -> transaction.executeWithoutResult(status -> repository.replace(
          new OaDirectorySnapshot(10, 2, List.of(
              person("U3", "E003", "赵六", "商用制冷业务单元/板换事业部",
                  "集团/商用制冷业务单元/板换事业部"), oversized), List.of()))))
          .isInstanceOf(OaPersonDirectoryException.class)
          .hasMessageContaining("实际部门");
      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM lp_oa_person_directory WHERE active_flag=1", Integer.class))
          .isEqualTo(1);
      assertThat(repository.search("E002", 50)).hasSize(1);
      assertThat(repository.search("E003", 50)).isEmpty();

      transaction.executeWithoutResult(status -> repository.replace(snapshot(person(
          "U1", "E001", "张三", "商用制冷业务单元/商用四通阀事业部",
          "集团/商用制冷业务单元/商用四通阀事业部/制造部"))));
      assertThat(jdbc.queryForObject(
          "SELECT status FROM sys_user WHERE user_name='oa_E001'", String.class)).isEqualTo("0");
      assertThat(repository.search("E001", 50)).hasSize(1);
    }
  }

  private static void createIdentityTables(JdbcTemplate jdbc) {
    jdbc.execute("""
        CREATE TABLE sys_user(
          user_id BIGINT AUTO_INCREMENT PRIMARY KEY,user_name VARCHAR(64) NOT NULL UNIQUE,
          password VARCHAR(256) NOT NULL,nick_name VARCHAR(64),business_unit_type VARCHAR(20),
          sex CHAR(1),avatar VARCHAR(500),status CHAR(1),del_flag CHAR(1),
          create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME,
          remark VARCHAR(500)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    jdbc.execute("""
        CREATE TABLE sys_role(
          role_id BIGINT PRIMARY KEY,role_key VARCHAR(100),status CHAR(1),del_flag CHAR(1))
        ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    jdbc.execute("""
        CREATE TABLE sys_user_role(
          user_id BIGINT NOT NULL,role_id BIGINT NOT NULL,PRIMARY KEY(user_id,role_id))
        ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
  }

  private static OaDirectorySnapshot snapshot(OaDirectoryPerson person) {
    return new OaDirectorySnapshot(10, 20, List.of(person), List.of());
  }

  private static OaDirectoryPerson person(
      String oaUserId,
      String employeeNo,
      String name,
      String targetDepartment,
      String actualDepartment) {
    return new OaDirectoryPerson(
        oaUserId, employeeNo, name, "技术员", "P1", "normal",
        List.of(targetDepartment), List.of(actualDepartment), List.of("D1"),
        List.of("T1"), List.of("下级部门"));
  }
}

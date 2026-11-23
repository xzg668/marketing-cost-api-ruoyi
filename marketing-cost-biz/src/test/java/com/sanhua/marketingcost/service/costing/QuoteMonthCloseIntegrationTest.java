package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuoteMonthCloseLocks;
import com.sanhua.marketingcost.service.reprice.MonthlyQuotationControl;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.MySQLContainer;

@Tag("integration")
class QuoteMonthCloseIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
      .withDatabaseName("quote_close_test").withStartupTimeout(Duration.ofMinutes(3));
  private static DriverManagerDataSource database;
  private static JdbcTemplate jdbc;
  private static MonthlyQuotationControl control;
  private static final AtomicReference<String> ACTIVE = new AtomicReference<>("2026-11");

  @BeforeAll
  static void start() {
    MYSQL.start();
    database = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(database);
    control = new MonthlyQuotationControl(database);
    jdbc.execute("""
        CREATE TABLE oa_form(id BIGINT PRIMARY KEY,oa_no VARCHAR(128) COLLATE utf8mb4_unicode_ci,customer VARCHAR(255),
          deleted INT,business_unit_type VARCHAR(32),accounting_period_month CHAR(7))
        """);
    jdbc.execute("""
        CREATE TABLE oa_form_item(id BIGINT PRIMARY KEY,oa_form_id BIGINT,seq INT,material_no VARCHAR(128),
          product_name VARCHAR(255),sunl_model VARCHAR(255),spec TEXT,deleted INT,business_unit_type VARCHAR(32))
        """);
    jdbc.execute("""
        CREATE TABLE lp_quote_cost_run_version(id BIGINT PRIMARY KEY,oa_form_item_id BIGINT,
          oa_no VARCHAR(128) COLLATE utf8mb4_0900_ai_ci,version_no VARCHAR(48),
          final_quote_amount DECIMAL(20,6),total_cost DECIMAL(20,6),
          confirmed_at DATETIME,trial_finished_at DATETIME,created_at DATETIME,business_unit_type VARCHAR(32),
          pricing_month CHAR(7),reprice_no VARCHAR(64),status VARCHAR(24))
        """);
    jdbc.execute("CREATE TABLE lp_quote_costing_workspace(oa_form_item_id BIGINT,period_month CHAR(7))");
    jdbc.execute("CREATE TABLE lp_cost_run_task(business_unit_type VARCHAR(32),scene VARCHAR(24),pricing_month CHAR(7),status VARCHAR(24))");
    jdbc.execute("""
        CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY, menu_name VARCHAR(64), parent_id BIGINT,
          order_num INT, path VARCHAR(255), component VARCHAR(255), is_frame INT, is_cache CHAR(1),
          menu_type CHAR(1), visible CHAR(1), status CHAR(1), perms VARCHAR(128), icon VARCHAR(32),
          create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME,
          remark VARCHAR(255), business_unit_type VARCHAR(32))
        """);
    jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(64),status CHAR(1),del_flag CHAR(1))");
    jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
    jdbc.update("INSERT INTO sys_menu(menu_id,parent_id,order_num,path,component) VALUES(40197,40161,3,'/cost/monthly-reprice','pages:MonthlyRepricePage')");
    jdbc.update("INSERT INTO sys_role VALUES(1,'admin','0','0'),(2,'bu_director','0','0')");
    new ResourceDatabasePopulator(new ClassPathResource("db/V392__quote_month_close.sql")).execute(database);
    new ResourceDatabasePopulator(new ClassPathResource("db/V393__quote_month_close_revocation.sql")).execute(database);
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void seed() {
    for (String table : List.of("lp_quote_month_close_item", "lp_quote_month_close", "lp_quote_cost_run_version",
        "oa_form_item", "oa_form", "lp_cost_run_task", "lp_quote_costing_workspace")) {
      jdbc.update("DELETE FROM " + table);
    }
    ACTIVE.set("2026-11");
    login("COMMERCIAL");
    jdbc.update("""
        INSERT INTO oa_form VALUES
          (1,'008','客户A',0,'COMMERCIAL','2026-10'),
          (2,'009','客户B',0,'COMMERCIAL','2026-11'),
          (3,'010','客户C',0,'HOUSEHOLD','2026-11'),
          (4,'011','没有保存成本的客户',0,'COMMERCIAL','2026-11')
        """);
    jdbc.update("""
        INSERT INTO oa_form_item VALUES
          (11,1,1,'200001','阀','M-1','S',0,'COMMERCIAL'),
          (12,1,2,'200001','阀','M-1','S',0,'COMMERCIAL'),
          (13,2,1,'200001','阀','M-1','S',0,'COMMERCIAL'),
          (14,2,2,'200002','阀','M-2','S',0,'COMMERCIAL'),
          (15,3,1,'200001','阀','M-1','S',0,'HOUSEHOLD'),
          (16,4,1,'200001','阀','M-1','S',0,'COMMERCIAL')
        """);
    save(101, 11, "008", "2026-11", 100, null, "HISTORY");
    save(102, 11, "008", "2026-11", 103, null, "SUCCESS");
    save(103, 11, "008", "2026-11", 900, null, "RUNNING");
    save(104, 11, "008", "2026-11", 850, "RP-1", "SUCCESS");
    save(105, 12, "008", "2026-11", 0, null, "CONFIRMED");
    save(106, 13, "009", "2026-11", 108, null, "SUCCESS");
    save(107, 13, "009", "2026-10", 75, null, "SUCCESS");
    save(108, 13, "009", "2026-11", 999, null, "VOIDED");
  }

  @AfterEach
  void logout() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void locksLatestOrdinaryVersionForEachOaLineAndKeepsSavedIdentity() {
    var service = service("ENABLED");
    var preview = service.view(null, true);
    assertThat(preview.canConfirm()).isTrue();
    assertThat(preview.orderCount()).isEqualTo(2);
    assertThat(preview.readyCount()).isEqualTo(3);
    assertThat(preview.rows()).extracting(QuoteMonthCloseService.Row::versionId)
        .containsExactly(102L, 105L, 106L);
    assertThat(preview.canRevoke()).isFalse();
    assertThat(service.history()).isEmpty();
    var receipt = service.confirm("2026-11", "director");
    assertThat(service.confirm("2026-11", "admin")).isEqualTo(receipt);
    assertThat(service.orderStatus("008").lockedVersions()).isEqualTo(Map.of(11L, 102L, 12L, 105L));
    var locks = new QuoteMonthCloseLocks(jdbc, "ENABLED");
    assertThatThrownBy(() -> locks.requireOpen("COMMERCIAL", "2026-11", 11L))
        .hasMessage(QuoteMonthCloseLocks.CLOSED_MESSAGE);
    locks.requireOpen("COMMERCIAL", "2026-11", 14L);
    locks.requireOpen("COMMERCIAL", "2026-12", 11L);
    locks.requireOpen("HOUSEHOLD", "2026-11", 11L);
    jdbc.update("UPDATE oa_form SET customer='改名之后' WHERE id=1");
    save(112, 11, "008", "2026-12", 123, "RP-2", "SUCCESS");
    ACTIVE.set("2026-12");
    assertThat(service.view("2026-11", true).rows()).extracting(QuoteMonthCloseService.Row::customer)
        .containsExactly("客户A", "客户A", "客户B");
    assertThat(service.view("2026-11", true).rows().getFirst().amount()).isEqualByComparingTo("103");
    assertThat(service.view(null, true).readyCount()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version", Integer.class)).isEqualTo(9);
  }

  @Test
  void revocationReleasesAllLocksAndKeepsOldCloseAndCostsForAudit() {
    var service = service("ENABLED");
    var first = service.confirm("2026-11", "admin");
    assertThat(service.view(null, true).canRevoke()).isTrue();
    assertThat(service.view(null, false).canRevoke()).isFalse();
    assertThatThrownBy(() -> service("PREVIEW").revoke(first.id(), "2026-11", "预览环境", "admin"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.revoke(first.id(), "2026-11", " ", "director"))
        .hasMessageContaining("撤销原因");
    ACTIVE.set("2026-12");
    assertThatThrownBy(() -> service.revoke(first.id(), "2026-11", "误操作", "director"))
        .hasMessageContaining("2026-12");
    ACTIVE.set("2026-11");
    var revoked = service.revoke(first.id(), "2026-11", "确认试验", "director");
    assertThat(revoked.revokedBy()).isEqualTo("director");
    assertThat(revoked.revokeReason()).isEqualTo("确认试验");
    assertThat(revoked.closedBy()).isEqualTo("admin");
    assertThat(service.orderStatus("008").lockedVersions()).isEmpty();
    assertThat(service.history()).isEmpty();
    assertThat(service.view(null, true).canConfirm()).isTrue();
    save(121, 11, "008", "2026-11", 111, null, "SUCCESS");
    var current = service.confirm("2026-11", "director");
    assertThat(current.id()).isNotEqualTo(first.id());
    assertThat(service.revoke(first.id(), "2026-11", "旧请求重复提交", "admin")).isEqualTo(revoked);
    assertThat(service.orderStatus("008").lockedVersions().get(11L)).isEqualTo(121L);
    assertThat(jdbc.queryForObject("""
        SELECT cost_version_id FROM lp_quote_month_close_item WHERE close_id=? AND oa_form_item_id=11
        """, Long.class, first.id())).isEqualTo(102L);
  }

  @Test
  void refusesOldMonthAndNonCommercialAndPreviewWrites() {
    var preview = service("PREVIEW");
    assertThat(preview.view(null, true).canConfirm()).isFalse();
    assertThat(preview.view(null, true).readyCount()).isEqualTo(3);
    assertThatThrownBy(() -> preview.confirm("2026-11", "admin")).isInstanceOf(AccessDeniedException.class);
    var enabled = service("ENABLED");
    assertThatThrownBy(() -> enabled.confirm("2026-05", "admin")).hasMessageContaining("2026-11");
    ACTIVE.set("2026-12");
    assertThatThrownBy(() -> enabled.confirm("2026-11", "admin")).hasMessageContaining("2026-12");
    login("HOUSEHOLD");
    assertThatThrownBy(() -> enabled.view(null, true)).isInstanceOf(AccessDeniedException.class);
    login("COMMERCIAL");
    assertThat(enabled.history()).isEmpty();
  }

  @Test
  void refusesWhileQueueOrSynchronousProductIsRunning() throws Exception {
    var service = service("ENABLED");
    jdbc.update("INSERT INTO lp_cost_run_task VALUES('COMMERCIAL','QUOTE','2026-11','PENDING')");
    assertThatThrownBy(() -> service.confirm("2026-11", "admin")).hasMessageContaining("正在处理");
    jdbc.update("UPDATE lp_cost_run_task SET status='WAITING_INPUT'");
    try (var lease = database.getConnection(); var statement = lease.createStatement()) {
      statement.executeQuery("""
          SELECT GET_LOCK(CONCAT('quote-item:', LEFT(SHA2(CONCAT(DATABASE(),':11:2026-11'),256),48)),0)
          """).close();
      assertThatThrownBy(() -> service.confirm("2026-11", "admin")).hasMessageContaining("正在处理");
    }
    assertThat(service.confirm("2026-11", "admin").periodMonth()).isEqualTo("2026-11");
  }

  @Test
  void waitsForPublicationFenceAndSeesFinalVersion() throws Exception {
    var started = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var service = service("ENABLED");
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var publisher = threads.submit(() -> control.serialized("COMMERCIAL", () -> {
        started.countDown();
        try {
          if (!finish.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test publication timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        save(115, 11, "008", "2026-11", 109, null, "SUCCESS");
        return true;
      }));
      assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
      var closer = threads.submit(() -> {
        login("COMMERCIAL");
        try { return service.confirm("2026-11", "director"); }
        finally { SecurityContextHolder.clearContext(); }
      });
      finish.countDown();
      assertThat(publisher.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(closer.get(15, TimeUnit.SECONDS).closedBy()).isEqualTo("director");
    }
    assertThat(service.orderStatus("008").lockedVersions().get(11L)).isEqualTo(115L);
  }

  private QuoteMonthCloseService service(String mode) {
    return new QuoteMonthCloseService(jdbc, unit -> ACTIVE.get(), new QuoteMonthCloseLocks(jdbc, mode),
        control, new DataSourceTransactionManager(database));
  }

  private static void save(long id, long item, String oa, String month, int amount,
      String reprice, String status) {
    jdbc.update("""
        INSERT INTO lp_quote_cost_run_version VALUES(?,?,?,CONCAT('V',?),?,?,NULL,NULL,
          '2026-11-05 12:00:00','COMMERCIAL',?,?,?)
        """, id, item, oa, id, amount, amount, month, reprice, status);
  }

  private static void login(String unit) {
    var auth = new UsernamePasswordAuthenticationToken("director", null, List.of());
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, unit));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}

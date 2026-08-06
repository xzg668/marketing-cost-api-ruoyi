package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomConfirmResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomExclusionSummaryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomNodeResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomVariantInput;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeCommand;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeKey;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeResult;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomConfirmationCandidate;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@DisplayName("QEB-12 单产品确认真实MySQL一致事务")
class QuoteEffectiveBomConfirmRealMysqlIntegrationTest {

  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(
              DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql"))
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          .withCommand(
              "--sql-mode=NO_ENGINE_SUBSTITUTION",
              "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4",
              "--collation-server=utf8mb4_0900_ai_ci");

  private static JdbcTemplate jdbc;
  private static JdbcFlow flow;
  private static QuoteEffectiveBomConfirmationService service;

  @BeforeAll
  static void setUpDatabase() throws Exception {
    MYSQL.start();
    MysqlDataSource source = new MysqlDataSource();
    source.setUrl(MYSQL.getJdbcUrl());
    source.setUser(MYSQL.getUsername());
    source.setPassword(MYSQL.getPassword());
    jdbc = new JdbcTemplate(source);
    createSchema(source);
    flow = new JdbcFlow(jdbc);
    QuoteEffectiveBomConfirmationServiceImpl target =
        new QuoteEffectiveBomConfirmationServiceImpl(
            flow, flow, flow, flow, () -> 9527L);
    service = transactionalProxy(target, new DataSourceTransactionManager(source));
  }

  @AfterAll
  static void tearDownDatabase() {
    MYSQL.stop();
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM qeb12_confirmation");
    jdbc.update("DELETE FROM qeb12_costing_row");
    jdbc.update("DELETE FROM qeb12_status");
    jdbc.update("DELETE FROM qeb12_effective_node");
    jdbc.update("DELETE FROM qeb12_monthly");
    jdbc.update(
        "INSERT INTO qeb12_monthly(id,freeze_status,build_batch_id) VALUES(1,'DRAFT',NULL)");
    flow.resetFailures();
  }

  @Test
  @DisplayName("首次确认后最终节点、状态、结算行和确认记录批次完全一致")
  void firstConfirmationKeepsOneBuildBatchEverywhere() {
    QuoteEffectiveBomConfirmResponse result = service.confirm("OA-1", 10L, null);

    assertThat(result.buildBatchId()).isEqualTo("qeb_BUILD_1");
    assertThat(strings("SELECT DISTINCT build_batch_id FROM qeb12_effective_node"))
        .containsExactly("qeb_BUILD_1");
    assertThat(strings("SELECT DISTINCT build_batch_id FROM qeb12_status"))
        .containsExactly("qeb_BUILD_1");
    assertThat(strings("SELECT DISTINCT build_batch_id FROM qeb12_costing_row"))
        .containsExactly("qeb_BUILD_1");
    assertThat(strings("SELECT DISTINCT build_batch_id FROM qeb12_confirmation"))
        .containsExactly("qeb_BUILD_1");
  }

  @Test
  @DisplayName("后续OA复用同一最终树但生成自己的结算行和确认")
  void laterOaReusesTreeAndOwnsItsCostingRows() {
    service.confirm("OA-1", 10L, null);
    QuoteEffectiveBomConfirmResponse reused = service.confirm("OA-2", 20L, null);

    assertThat(reused.reusedMonthlyFreeze()).isTrue();
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb12_effective_node")).isEqualTo(2);
    assertThat(strings("SELECT DISTINCT oa_item_id FROM qeb12_costing_row ORDER BY oa_item_id"))
        .containsExactly("10", "20");
    assertThat(strings("SELECT DISTINCT build_batch_id FROM qeb12_costing_row"))
        .containsExactly("qeb_BUILD_1");
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb12_confirmation")).isEqualTo(2);
  }

  @Test
  @DisplayName("确认保存失败时冻结、节点、状态和结算行全部回滚")
  void confirmationFailureRollsBackTheWholeTransaction() {
    flow.failConfirmation.set(true);

    assertThatThrownBy(() -> service.confirm("OA-1", 10L, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟确认失败");

    assertDraftAndEmpty();
  }

  @Test
  @DisplayName("第2步生成失败时冻结和最终节点也回滚")
  void costingFailureRollsBackFreezeAndNodes() {
    flow.failCosting.set(true);

    assertThatThrownBy(() -> service.confirm("OA-1", 10L, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟第2步失败");

    assertDraftAndEmpty();
  }

  private void assertDraftAndEmpty() {
    assertThat(strings("SELECT freeze_status FROM qeb12_monthly WHERE id=1"))
        .containsExactly("DRAFT");
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb12_effective_node")).isZero();
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb12_status")).isZero();
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb12_costing_row")).isZero();
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb12_confirmation")).isZero();
  }

  private static QuoteEffectiveBomConfirmationService transactionalProxy(
      QuoteEffectiveBomConfirmationServiceImpl target,
      DataSourceTransactionManager transactionManager) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.addAdvice(
        new TransactionInterceptor(
            transactionManager, new AnnotationTransactionAttributeSource()));
    return (QuoteEffectiveBomConfirmationService) factory.getProxy();
  }

  private static void createSchema(DataSource source) throws Exception {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE qeb12_monthly(id BIGINT PRIMARY KEY, freeze_status VARCHAR(16) NOT NULL, build_batch_id VARCHAR(64) NULL) ENGINE=InnoDB");
      statement.execute(
          "CREATE TABLE qeb12_effective_node(id BIGINT AUTO_INCREMENT PRIMARY KEY, build_batch_id VARCHAR(64) NOT NULL, material_code VARCHAR(64) NOT NULL) ENGINE=InnoDB");
      statement.execute(
          "CREATE TABLE qeb12_status(oa_item_id BIGINT PRIMARY KEY, build_batch_id VARCHAR(64) NOT NULL) ENGINE=InnoDB");
      statement.execute(
          "CREATE TABLE qeb12_costing_row(id BIGINT AUTO_INCREMENT PRIMARY KEY, oa_item_id BIGINT NOT NULL, build_batch_id VARCHAR(64) NOT NULL, material_code VARCHAR(64) NOT NULL) ENGINE=InnoDB");
      statement.execute(
          "CREATE TABLE qeb12_confirmation(id BIGINT AUTO_INCREMENT PRIMARY KEY, oa_item_id BIGINT NOT NULL, build_batch_id VARCHAR(64) NOT NULL) ENGINE=InnoDB");
    }
  }

  private int scalarInt(String sql) {
    Integer value = jdbc.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }

  private List<String> strings(String sql) {
    return jdbc.query(sql, (row, index) -> row.getString(1));
  }

  private static final class JdbcFlow
      implements QuoteEffectiveBomApplicationService,
          QuoteBomMonthlyFreezeService,
          QuoteProductBomCostingBuildService,
          QuoteBomConfirmationService {

    private final JdbcTemplate jdbc;
    private final AtomicBoolean failCosting = new AtomicBoolean();
    private final AtomicBoolean failConfirmation = new AtomicBoolean();

    private JdbcFlow(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    private void resetFailures() {
      failCosting.set(false);
      failConfirmation.set(false);
    }

    @Override
    public QuoteEffectiveBomConfirmationCandidate prepareConfirmation(
        String oaNo, Long oaFormItemId) {
      String status = jdbc.queryForObject(
          "SELECT freeze_status FROM qeb12_monthly WHERE id=1", String.class);
      boolean frozen = "FROZEN".equals(status);
      String build =
          frozen
              ? jdbc.queryForObject(
                  "SELECT build_batch_id FROM qeb12_monthly WHERE id=1", String.class)
              : null;
      return new QuoteEffectiveBomConfirmationCandidate(
          response(oaNo, oaFormItemId, frozen, build),
          new QuoteBomMonthlyFreezeKey(
              "2026-08", "P", "CUSTOMER-A", "BOX", "210"),
          Map.of(),
          frozen
              ? null
              : new EffectiveBomVariantInput(
                  "2026-08", "RAW-1", "210", "P", "BOX", Map.of(), null));
    }

    @Override
    public QuoteBomMonthlyFreezeResult freeze(QuoteBomMonthlyFreezeCommand command) {
      String status = jdbc.queryForObject(
          "SELECT freeze_status FROM qeb12_monthly WHERE id=1 FOR UPDATE", String.class);
      boolean reused = "FROZEN".equals(status);
      String build = "qeb_BUILD_1";
      if (!reused) {
        jdbc.update(
            "INSERT INTO qeb12_effective_node(build_batch_id,material_code) VALUES(?,?),(?,?)",
            build, "P", build, "T");
        jdbc.update(
            "UPDATE qeb12_monthly SET freeze_status='FROZEN',build_batch_id=? WHERE id=1",
            build);
      }
      jdbc.update(
          "INSERT INTO qeb12_status(oa_item_id,build_batch_id) VALUES(?,?) ON DUPLICATE KEY UPDATE build_batch_id=VALUES(build_batch_id)",
          command.oaFormItemId(), build);
      return new QuoteBomMonthlyFreezeResult(
          1L, build, "HASH", reused, reused, LocalDateTime.now());
    }

    @Override
    public QuoteBomCostingBuildResponse buildFromEffectiveBom(
        Long oaFormItemId, String buildBatchId) {
      jdbc.update("DELETE FROM qeb12_costing_row WHERE oa_item_id=?", oaFormItemId);
      jdbc.update(
          "INSERT INTO qeb12_costing_row(oa_item_id,build_batch_id,material_code) SELECT ?,build_batch_id,material_code FROM qeb12_effective_node WHERE build_batch_id=?",
          oaFormItemId, buildBatchId);
      if (failCosting.getAndSet(false)) {
        throw new IllegalStateException("模拟第2步失败");
      }
      return new QuoteBomCostingBuildResponse(
          1L, null, oaFormItemId, oa(oaFormItemId), "P", "NON_BARE", "2026-08",
          buildBatchId, 2, 2, 0, Map.of(), List.of(), LocalDateTime.now());
    }

    @Override
    public boolean hasActiveConfirmation(
        String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
      Integer count = jdbc.queryForObject(
          "SELECT COUNT(*) FROM qeb12_confirmation WHERE oa_item_id=?",
          Integer.class, oaFormItemId);
      return count != null && count > 0;
    }

    @Override
    public QuoteBomConfirmResponse confirmEffective(
        String oaNo,
        Long oaFormItemId,
        String buildBatchId,
        int replaceCount,
        QuoteBomConfirmRequest request) {
      if (!hasActiveConfirmation(oaNo, oaFormItemId, "P", "2026-08")) {
        jdbc.update(
            "INSERT INTO qeb12_confirmation(oa_item_id,build_batch_id) VALUES(?,?)",
            oaFormItemId, buildBatchId);
      }
      if (failConfirmation.getAndSet(false)) {
        throw new IllegalStateException("模拟确认失败");
      }
      QuoteBomConfirmResponse response = new QuoteBomConfirmResponse();
      response.setOaNo(oaNo);
      response.setOaFormItemId(oaFormItemId);
      response.setTopProductCode("P");
      response.setPeriodMonth("2026-08");
      response.setRowCount(2);
      response.setCostingBuildBatchId(buildBatchId);
      return response;
    }

    @Override
    public QuoteEffectiveBomResponse getEffectiveBom(String oaNo, Long oaFormItemId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuoteEffectiveBomResponse rebuildPreview(String oaNo, Long oaFormItemId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuoteEffectiveBomResponse previewAlternative(
        String oaNo,
        Long oaFormItemId,
        String periodMonth,
        String alternativeGroupKey,
        String selectedMaterialCode) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuoteBomCostingBuildResponse buildByOaFormItem(Long oaFormItemId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuoteBomCostingBuildResponse buildByOaFormItem(
        Long oaFormItemId, String periodMonth) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuoteBomCostingBuildResponse buildByOaFormItem(
        Long oaFormItemId, String periodMonth, java.time.LocalDate quoteDate) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuoteBomCostingBuildResponse buildByTask(Long taskId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuoteBomConfirmResponse confirm(
        String oaNo, Long oaFormItemId, QuoteBomConfirmRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuoteBomConfirmResponse cancelConfirm(
        String oaNo,
        Long oaFormItemId,
        QuoteBomCancelConfirmRequest request) {
      throw new UnsupportedOperationException();
    }

    private static QuoteEffectiveBomResponse response(
        String oaNo, Long itemId, boolean frozen, String build) {
      return new QuoteEffectiveBomResponse(
          frozen ? (itemId == 10L ? "FROZEN" : "REUSED") : "DRAFT",
          oaNo,
          itemId,
          "2026-08",
          "P",
          "CUSTOMER-A",
          "OA_HEADER",
          "BOX",
          "210",
          "COMMERCIAL",
          1L,
          "RAW-1",
          build,
          frozen ? "HASH" : null,
          10L,
          List.of(
              new QuoteEffectiveBomNodeResponse(
                  "ROOT", null, 0, 1, "/P/", "P", "产品", null,
                  BigDecimal.ONE, BigDecimal.ONE, "制造件", "MANUFACTURE", "U9",
                  null, null, null, null, null, null, null, null, null, null,
                  "U9", "RAW-1", 1L, "/P/")),
          List.of(),
          new QuoteEffectiveBomExclusionSummaryResponse(true, 0, Map.of()),
          List.of(),
          List.of());
    }

    private static String oa(Long itemId) {
      return itemId == 20L ? "OA-2" : "OA-1";
    }
  }
}

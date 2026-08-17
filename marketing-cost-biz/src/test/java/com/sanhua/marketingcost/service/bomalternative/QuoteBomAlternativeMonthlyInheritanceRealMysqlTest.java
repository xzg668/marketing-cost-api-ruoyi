package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeKey;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeRepository;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeRepositoryImpl;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
@DisplayName("QEB-10 标准替代月度继承真实MySQL并发")
class QuoteBomAlternativeMonthlyInheritanceRealMysqlTest {

  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(
              DockerImageName.parse("mysql:8.4")
                  .asCompatibleSubstituteFor("mysql"))
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          .withCommand(
              "--sql-mode=NO_ENGINE_SUBSTITUTION",
              "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4",
              "--collation-server=utf8mb4_0900_ai_ci");

  private static JdbcTemplate jdbcTemplate;
  private static QuoteBomAlternativeMonthlyInheritanceService service;

  @BeforeAll
  static void setUpDatabase() throws Exception {
    MYSQL.start();
    MysqlDataSource dataSource = new MysqlDataSource();
    dataSource.setUrl(MYSQL.getJdbcUrl());
    dataSource.setUser(MYSQL.getUsername());
    dataSource.setPassword(MYSQL.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    createSchema(dataSource);

    QuoteBomMonthlyFreezeRepository monthlyRepository =
        new QuoteBomMonthlyFreezeRepositoryImpl(jdbcTemplate);
    JdbcSelectionRepository selectionRepository =
        new JdbcSelectionRepository(jdbcTemplate);
    QuoteBomAlternativeMonthlyInheritanceServiceImpl target =
        new QuoteBomAlternativeMonthlyInheritanceServiceImpl(
            monthlyRepository,
            new JdbcEffectiveRepository(jdbcTemplate),
            selectionRepository,
            Clock.fixed(Instant.parse("2026-08-04T03:00:00Z"), ZoneOffset.UTC));
    service =
        transactionalProxy(target, new DataSourceTransactionManager(dataSource));
  }

  @AfterAll
  static void tearDownDatabase() {
    MYSQL.stop();
  }

  @BeforeEach
  void resetAndSeed() {
    jdbcTemplate.update("DELETE FROM lp_quote_bom_confirmation");
    jdbcTemplate.update("DELETE FROM lp_quote_bom_alternative_selection");
    jdbcTemplate.update("DELETE FROM lp_quote_effective_bom_node");
    jdbcTemplate.update("DELETE FROM lp_quote_bom_monthly_snapshot");
    jdbcTemplate.update(
        """
        INSERT INTO lp_quote_bom_monthly_snapshot
          (id,product_code,price_org_code,customer_code,package_method,
           cost_period_month,sync_status,sync_at,source_oa_form_item_id,
           bom_batch_id,active_flag,freeze_status,effective_build_batch_id,
           effective_variant_hash,frozen_at,frozen_by,created_at,updated_at)
        VALUES
          (10,'P','210','CUSTOMER-A','BOX','2026-08','SUCCESS',
           '2026-08-04 10:00:00',100,'RAW-1',1,'FROZEN','EFFECTIVE-T',
           REPEAT('a',64),'2026-08-04 10:00:00',9527,
           '2026-08-04 10:00:00','2026-08-04 10:00:00')
        """);
    jdbcTemplate.update(
        """
        INSERT INTO lp_quote_bom_confirmation
          (confirm_no,oa_no,oa_form_item_id,top_product_code,period_month,
           confirm_status,costing_build_batch_id)
        VALUES
          ('CONFIRM-SOURCE','OA-FIRST',100,'P','2026-08','CONFIRMED','EFFECTIVE-T')
        """);
    jdbcTemplate.update(
        """
        INSERT INTO lp_quote_bom_alternative_selection
          (id,selection_no,oa_no,oa_form_item_id,top_product_code,period_month,
           price_org_code,alternative_group_key,parent_path,parent_material_code,
           child_seq,process_seq,bom_purpose,bom_version,standard_material_code,
           selected_material_code,selected_child_type,selection_source,
           selection_version,selection_status,current_slot,candidate_snapshot_json,
           source_import_batch_id,source_build_batch_id,selected_by,selected_at,
           business_unit_type,created_at,updated_at)
        VALUES
          (81,'SEL-SOURCE','OA-FIRST',100,'P','2026-08','210','GROUP-1',
           '/P/','P',10,'010','主制造','V1','S','T','ALTERNATIVE',
           'MANUAL_ALTERNATIVE',1,'ACTIVE',1,
           JSON_OBJECT('candidates',JSON_ARRAY()),'IMPORT-1','RAW-BUILD-1',
           'finance','2026-08-01 09:00:00','COMMERCIAL',
           '2026-08-01 09:00:00','2026-08-01 09:00:00')
        """);
    jdbcTemplate.update(
        """
        INSERT INTO lp_quote_effective_bom_node
          (id,build_batch_id,top_product_code,cost_period_month,price_org_code,
           node_path,material_code,alternative_group_key,alternative_child_type,
           alternative_selection_id,node_level,sort_seq)
        VALUES
          (91,'EFFECTIVE-T','P','2026-08','210','/P/T/','T','GROUP-1',
           'ALTERNATIVE',81,1,1)
        """);
  }

  @Test
  @DisplayName("20个并发读取同一冻结场景只写一个继承版本")
  void twentyConcurrentRequestsCreateOneInheritedCurrentSelection()
      throws Exception {
    QuoteBomMonthlyFreezeKey key =
        new QuoteBomMonthlyFreezeKey(
            "2026-08", "P", "CUSTOMER-A", "BOX", "210");
    QuoteBomAlternativeSelectionScope target =
        new QuoteBomAlternativeSelectionScope(
            "OA-NEW", 200L, "P", "2026-08", "210", "COMMERCIAL");
    int concurrency = 20;
    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    CountDownLatch ready = new CountDownLatch(concurrency);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<QuoteBomAlternativeMonthlyInheritanceResult>> futures =
        new ArrayList<>();
    try {
      for (int index = 0; index < concurrency; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return service.inheritIfFrozen(key, target);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<QuoteBomAlternativeMonthlyInheritanceResult> results =
          new ArrayList<>();
      for (Future<QuoteBomAlternativeMonthlyInheritanceResult> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }

      assertThat(results).allMatch(QuoteBomAlternativeMonthlyInheritanceResult::frozen);
      assertThat(results.stream().filter(QuoteBomAlternativeMonthlyInheritanceResult::inherited))
          .hasSize(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM lp_quote_bom_alternative_selection "
                      + "WHERE oa_form_item_id=200",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT selection_source FROM lp_quote_bom_alternative_selection "
                      + "WHERE oa_form_item_id=200",
                  String.class))
          .isEqualTo(QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT inherited_monthly_snapshot_id "
                      + "FROM lp_quote_bom_alternative_selection WHERE oa_form_item_id=200",
                  Long.class))
          .isEqualTo(10L);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT selection_source FROM lp_quote_bom_alternative_selection WHERE id=81",
                  String.class))
          .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static QuoteBomAlternativeMonthlyInheritanceService transactionalProxy(
      QuoteBomAlternativeMonthlyInheritanceServiceImpl target,
      DataSourceTransactionManager transactionManager) {
    TransactionInterceptor interceptor =
        new TransactionInterceptor(
            transactionManager, new AnnotationTransactionAttributeSource());
    ProxyFactory factory = new ProxyFactory(target);
    factory.addAdvice(interceptor);
    return (QuoteBomAlternativeMonthlyInheritanceService) factory.getProxy();
  }

  private static void createSchema(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE lp_quote_bom_monthly_snapshot (
            id BIGINT NOT NULL PRIMARY KEY,
            product_code VARCHAR(64) NOT NULL,
            price_org_code VARCHAR(32) NOT NULL,
            customer_code VARCHAR(128) NOT NULL DEFAULT '',
            package_method VARCHAR(128) NOT NULL DEFAULT '',
            cost_period_month CHAR(7) NOT NULL,
            sync_status VARCHAR(16) NOT NULL,
            sync_at DATETIME NOT NULL,
            source_oa_form_item_id BIGINT NULL,
            bom_batch_id VARCHAR(128) NULL,
            active_flag TINYINT NOT NULL,
            freeze_status VARCHAR(16) NOT NULL,
            effective_build_batch_id VARCHAR(64) NULL,
            effective_variant_hash CHAR(64) NULL,
            frozen_at DATETIME NULL,
            frozen_by BIGINT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            KEY idx_monthly_key (
              cost_period_month,product_code,customer_code,package_method,
              price_org_code,active_flag)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
      statement.execute(
          """
          CREATE TABLE lp_quote_bom_confirmation (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            confirm_no VARCHAR(64) NOT NULL,
            oa_no VARCHAR(64) NOT NULL,
            oa_form_item_id BIGINT NOT NULL,
            top_product_code VARCHAR(64) NOT NULL,
            period_month VARCHAR(7) NOT NULL,
            confirm_status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED',
            costing_build_batch_id VARCHAR(64) NULL,
            UNIQUE KEY uk_quote_bom_confirm_no (confirm_no),
            KEY idx_quote_bom_confirm_item (
              oa_no,oa_form_item_id,top_product_code,period_month)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
      statement.execute(
          """
          CREATE TABLE lp_quote_bom_alternative_selection (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            selection_no VARCHAR(64) NOT NULL,
            oa_no VARCHAR(64) NOT NULL,
            oa_form_item_id BIGINT NOT NULL,
            top_product_code VARCHAR(64) NOT NULL,
            period_month CHAR(7) NOT NULL,
            price_org_code VARCHAR(32) NOT NULL,
            alternative_group_key CHAR(64) NOT NULL,
            parent_path VARCHAR(2000) NULL,
            parent_material_code VARCHAR(64) NOT NULL,
            parent_material_name VARCHAR(255) NULL,
            child_seq INT NULL,
            process_seq VARCHAR(32) NULL,
            bom_purpose VARCHAR(32) NULL,
            bom_version VARCHAR(64) NULL,
            source_effective_from DATE NULL,
            source_effective_to DATE NULL,
            standard_material_code VARCHAR(64) NOT NULL,
            selected_material_code VARCHAR(64) NOT NULL,
            selected_child_type VARCHAR(16) NOT NULL,
            selection_source VARCHAR(32) NOT NULL,
            selection_version INT NOT NULL,
            selection_status VARCHAR(16) NOT NULL,
            current_slot TINYINT NULL,
            candidate_snapshot_json JSON NULL,
            source_import_batch_id VARCHAR(128) NULL,
            source_build_batch_id VARCHAR(128) NULL,
            selected_by VARCHAR(64) NULL,
            selected_at DATETIME NULL,
            selection_remark VARCHAR(1000) NULL,
            inherited_monthly_snapshot_id BIGINT NULL,
            business_unit_type VARCHAR(32) NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            UNIQUE KEY uk_selection_no (selection_no),
            UNIQUE KEY uk_current (
              oa_no,oa_form_item_id,top_product_code,period_month,
              alternative_group_key,current_slot),
            UNIQUE KEY uk_version (
              oa_no,oa_form_item_id,top_product_code,period_month,
              alternative_group_key,selection_version)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
      statement.execute(
          """
          CREATE TABLE lp_quote_effective_bom_node (
            id BIGINT NOT NULL PRIMARY KEY,
            build_batch_id VARCHAR(64) NOT NULL,
            top_product_code VARCHAR(64) NOT NULL,
            cost_period_month CHAR(7) NOT NULL,
            price_org_code VARCHAR(32) NOT NULL,
            node_path VARCHAR(2000) NOT NULL,
            material_code VARCHAR(64) NOT NULL,
            alternative_group_key CHAR(64) NULL,
            alternative_child_type VARCHAR(16) NULL,
            alternative_selection_id BIGINT NULL,
            node_level INT NOT NULL,
            sort_seq INT NOT NULL
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }
  }

  private static final class JdbcEffectiveRepository
      implements QuoteEffectiveBomRepository {

    private final JdbcTemplate jdbc;

    private JdbcEffectiveRepository(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public List<QuoteEffectiveBomNode> findNodesByBuildBatchId(String buildBatchId) {
      return jdbc.query(
          "SELECT * FROM lp_quote_effective_bom_node WHERE build_batch_id=?",
          (result, rowNum) -> {
            QuoteEffectiveBomNode node = new QuoteEffectiveBomNode();
            node.setId(result.getLong("id"));
            node.setBuildBatchId(result.getString("build_batch_id"));
            node.setTopProductCode(result.getString("top_product_code"));
            node.setCostPeriodMonth(result.getString("cost_period_month"));
            node.setPriceOrgCode(result.getString("price_org_code"));
            node.setNodePath(result.getString("node_path"));
            node.setMaterialCode(result.getString("material_code"));
            node.setAlternativeGroupKey(result.getString("alternative_group_key"));
            node.setAlternativeChildType(result.getString("alternative_child_type"));
            node.setAlternativeSelectionId(nullableLong(result, "alternative_selection_id"));
            node.setNodeLevel(result.getInt("node_level"));
            node.setSortSeq(result.getInt("sort_seq"));
            return node;
          },
          buildBatchId);
    }

    @Override
    public List<String> findBuildBatchIdsByVariantHash(String variantHash) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean existsBuildBatchId(String buildBatchId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertAll(List<QuoteEffectiveBomNode> nodes) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class JdbcSelectionRepository
      implements QuoteBomAlternativeSelectionRepository {

    private final JdbcTemplate jdbc;

    private JdbcSelectionRepository(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public QuoteBomAlternativeSelection findCurrent(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return first(queryScope(scope, groupKey, false, false));
    }

    @Override
    public QuoteBomAlternativeSelection findCurrentForUpdate(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return first(queryScope(scope, groupKey, true, false));
    }

    @Override
    public List<QuoteBomAlternativeSelection> findCurrentsForUpdate(
        QuoteBomAlternativeSelectionScope scope) {
      return jdbc.query(
          "SELECT * FROM lp_quote_bom_alternative_selection "
              + "WHERE oa_no=? AND oa_form_item_id=? AND top_product_code=? "
              + "AND period_month=? AND price_org_code=? AND business_unit_type=? "
              + "AND selection_status='ACTIVE' AND current_slot=1 "
              + "ORDER BY alternative_group_key FOR UPDATE",
          this::map,
          scope.oaNo(),
          scope.oaFormItemId(),
          scope.topProductCode(),
          scope.periodMonth(),
          scope.priceOrgCode(),
          scope.businessUnitType());
    }

    @Override
    public QuoteBomAlternativeSelection findLatest(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return first(queryScope(scope, groupKey, false, true));
    }

    @Override
    public List<QuoteBomAlternativeSelection> findHistory(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return jdbc.query(
          baseScopeSql()
              + " AND alternative_group_key=? ORDER BY selection_version",
          this::map,
          scopeArgs(scope, groupKey));
    }

    @Override
    public List<QuoteBomAlternativeSelection> findByIds(Collection<Long> ids) {
      List<QuoteBomAlternativeSelection> rows = new ArrayList<>();
      if (ids == null) {
        return rows;
      }
      for (Long id : ids) {
        rows.addAll(
            jdbc.query(
                "SELECT * FROM lp_quote_bom_alternative_selection WHERE id=?",
                this::map,
                id));
      }
      return rows;
    }

    @Override
    public void insert(QuoteBomAlternativeSelection row) {
      jdbc.update(
          """
          INSERT INTO lp_quote_bom_alternative_selection
            (selection_no,oa_no,oa_form_item_id,top_product_code,period_month,
             price_org_code,alternative_group_key,parent_path,parent_material_code,
             parent_material_name,child_seq,process_seq,bom_purpose,bom_version,
             source_effective_from,source_effective_to,standard_material_code,
             selected_material_code,selected_child_type,selection_source,
             selection_version,selection_status,current_slot,candidate_snapshot_json,
             source_import_batch_id,source_build_batch_id,selected_by,selected_at,
             selection_remark,inherited_monthly_snapshot_id,business_unit_type,
             created_at,updated_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          row.getSelectionNo(),
          row.getOaNo(),
          row.getOaFormItemId(),
          row.getTopProductCode(),
          row.getPeriodMonth(),
          row.getPriceOrgCode(),
          row.getAlternativeGroupKey(),
          row.getParentPath(),
          row.getParentMaterialCode(),
          row.getParentMaterialName(),
          row.getChildSeq(),
          row.getProcessSeq(),
          row.getBomPurpose(),
          row.getBomVersion(),
          row.getSourceEffectiveFrom(),
          row.getSourceEffectiveTo(),
          row.getStandardMaterialCode(),
          row.getSelectedMaterialCode(),
          row.getSelectedChildType(),
          row.getSelectionSource(),
          row.getSelectionVersion(),
          row.getSelectionStatus(),
          row.getCurrentSlot(),
          row.getCandidateSnapshotJson(),
          row.getSourceImportBatchId(),
          row.getSourceBuildBatchId(),
          row.getSelectedBy(),
          row.getSelectedAt(),
          row.getSelectionRemark(),
          row.getInheritedMonthlySnapshotId(),
          row.getBusinessUnitType(),
          row.getCreatedAt(),
          row.getUpdatedAt());
      row.setId(
          jdbc.queryForObject(
              "SELECT id FROM lp_quote_bom_alternative_selection WHERE selection_no=?",
              Long.class,
              row.getSelectionNo()));
    }

    @Override
    public boolean transitionCurrent(
        Long id,
        Integer expectedVersion,
        String targetStatus,
        LocalDateTime updatedAt) {
      return jdbc.update(
              "UPDATE lp_quote_bom_alternative_selection "
                  + "SET selection_status=?,current_slot=NULL,updated_at=? "
                  + "WHERE id=? AND selection_version=? "
                  + "AND selection_status='ACTIVE' AND current_slot=1",
              targetStatus,
              updatedAt,
              id,
              expectedVersion)
          == 1;
    }

    @Override
    public boolean refreshSource(
        Long id,
        Integer expectedVersion,
        String sourceImportBatchId,
        String sourceBuildBatchId,
        LocalDateTime updatedAt) {
      return jdbc.update(
              "UPDATE lp_quote_bom_alternative_selection "
                  + "SET source_import_batch_id=?,source_build_batch_id=?,updated_at=? "
                  + "WHERE id=? AND selection_version=? AND current_slot=1",
              sourceImportBatchId,
              sourceBuildBatchId,
              updatedAt,
              id,
              expectedVersion)
          == 1;
    }

    private List<QuoteBomAlternativeSelection> queryScope(
        QuoteBomAlternativeSelectionScope scope,
        String groupKey,
        boolean lock,
        boolean latest) {
      String suffix =
          latest
              ? " ORDER BY selection_version DESC LIMIT 1"
              : " AND selection_status='ACTIVE' AND current_slot=1 LIMIT 1"
                  + (lock ? " FOR UPDATE" : "");
      return jdbc.query(
          baseScopeSql() + " AND alternative_group_key=?" + suffix,
          this::map,
          scopeArgs(scope, groupKey));
    }

    private String baseScopeSql() {
      return "SELECT * FROM lp_quote_bom_alternative_selection "
          + "WHERE oa_no=? AND oa_form_item_id=? AND top_product_code=? "
          + "AND period_month=? AND price_org_code=? AND business_unit_type=?";
    }

    private Object[] scopeArgs(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return new Object[] {
        scope.oaNo(),
        scope.oaFormItemId(),
        scope.topProductCode(),
        scope.periodMonth(),
        scope.priceOrgCode(),
        scope.businessUnitType(),
        groupKey
      };
    }

    private QuoteBomAlternativeSelection map(ResultSet result, int rowNum)
        throws SQLException {
      QuoteBomAlternativeSelection row = new QuoteBomAlternativeSelection();
      row.setId(result.getLong("id"));
      row.setSelectionNo(result.getString("selection_no"));
      row.setOaNo(result.getString("oa_no"));
      row.setOaFormItemId(result.getLong("oa_form_item_id"));
      row.setTopProductCode(result.getString("top_product_code"));
      row.setPeriodMonth(result.getString("period_month"));
      row.setPriceOrgCode(result.getString("price_org_code"));
      row.setAlternativeGroupKey(result.getString("alternative_group_key"));
      row.setParentPath(result.getString("parent_path"));
      row.setParentMaterialCode(result.getString("parent_material_code"));
      row.setParentMaterialName(result.getString("parent_material_name"));
      row.setChildSeq(nullableInteger(result, "child_seq"));
      row.setProcessSeq(result.getString("process_seq"));
      row.setBomPurpose(result.getString("bom_purpose"));
      row.setBomVersion(result.getString("bom_version"));
      row.setStandardMaterialCode(result.getString("standard_material_code"));
      row.setSelectedMaterialCode(result.getString("selected_material_code"));
      row.setSelectedChildType(result.getString("selected_child_type"));
      row.setSelectionSource(result.getString("selection_source"));
      row.setSelectionVersion(result.getInt("selection_version"));
      row.setSelectionStatus(result.getString("selection_status"));
      row.setCurrentSlot(nullableInteger(result, "current_slot"));
      row.setCandidateSnapshotJson(result.getString("candidate_snapshot_json"));
      row.setSourceImportBatchId(result.getString("source_import_batch_id"));
      row.setSourceBuildBatchId(result.getString("source_build_batch_id"));
      row.setSelectedBy(result.getString("selected_by"));
      row.setSelectedAt(result.getObject("selected_at", LocalDateTime.class));
      row.setSelectionRemark(result.getString("selection_remark"));
      row.setInheritedMonthlySnapshotId(
          nullableLong(result, "inherited_monthly_snapshot_id"));
      row.setBusinessUnitType(result.getString("business_unit_type"));
      row.setCreatedAt(result.getObject("created_at", LocalDateTime.class));
      row.setUpdatedAt(result.getObject("updated_at", LocalDateTime.class));
      return row;
    }

    private QuoteBomAlternativeSelection first(
        List<QuoteBomAlternativeSelection> rows) {
      return rows.isEmpty() ? null : rows.getFirst();
    }
  }

  private static Long nullableLong(ResultSet result, String column)
      throws SQLException {
    long value = result.getLong(column);
    return result.wasNull() ? null : value;
  }

  private static Integer nullableInteger(ResultSet result, String column)
      throws SQLException {
    int value = result.getInt(column);
    return result.wasNull() ? null : value;
  }
}

package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceGapItemMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceMonthMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import com.sanhua.marketingcost.service.impl.LinkedPriceEnsureServiceImpl;
import com.sanhua.marketingcost.service.impl.PriceLinkedCalcServiceImpl;
import com.sanhua.marketingcost.service.impl.PricePrepareCurrentStateServiceImpl;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import com.sanhua.marketingcost.service.pricing.PriceMonthSelection;
import com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelector;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/** 独立 MySQL 执行真实向前迁移和生产 Mapper，不使用模拟 SQL 或业务库。 */
@Tag("integration")
class OriginalPriceVersionContractIntegrationTest {
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("prc_contract")
          .withUsername("root")
          .withPassword("test-only-password");
  private static SqlSessionFactory sessions;

  @BeforeAll
  static void start() {
    MYSQL.start();
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/prc01_pre_migration_schema.sql"), "/tmp/base.sql");
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V308__original_price_version_contract.sql"),
        "/tmp/migration.sql");
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V314__price_preparation_input_scope.sql"),
        "/tmp/scope.sql");
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V315__original_price_month_lookup_indexes.sql"),
        "/tmp/month-indexes.sql");
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(
        new Environment(
            "prc-contract",
            new JdbcTransactionFactory(),
            new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword())));
    for (Class<?> mapper :
        List.of(
            FactorMonthlyPriceMapper.class,
            FactorRowRefMapper.class,
            PriceLinkedItemMapper.class,
            PriceRangeFactorRuleMapper.class,
            PriceRangeItemMapper.class,
            PriceLinkedCalcItemMapper.class,
            MaterialPriceMonthMapper.class)) {
      config.addMapper(mapper);
    }
    sessions = new MybatisSqlSessionFactoryBuilder().build(config);
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE prc_contract");
      statement.execute("CREATE DATABASE prc_contract CHARACTER SET utf8mb4");
    }
    assertSuccess(script("/tmp/base.sql"));
    execute(
        """
INSERT INTO lp_factor_identity(id,business_unit_type,factor_seq_no,factor_name,short_name,price_source)
VALUES (191,'COMMERCIAL','1','长江铜','1#Cu','平均价'),
       (193,'COMMERCIAL','2','SMM铜','1#cu','平均价'),
       (197,'COMMERCIAL','3','期货铜','Cu','平均价'),
       (208,'COMMERCIAL','4','月均铜','CU','月均价'),
       (200,'COMMERCIAL','5','紫铜','Pcu','平均价'),
       (250,'COMMERCIAL','6','304规格一','304/2B/1219','平均价'),
       (251,'COMMERCIAL','7','304规格二','304/2B/1500','平均价'),
       (300,'HOUSEHOLD','1','家用铜','Cu','平均价'),
       (301,'COMMERCIAL','8','现货铜','Cu','现货价')
""");
    execute(
        """
        INSERT INTO lp_factor_upload_batch(id,batch_no,price_month,business_unit_type,status)
        VALUES (1,'LEGACY-1','2026-10','COMMERCIAL','SUCCESS')
        """);
    execute(
        """
INSERT INTO lp_factor_monthly_price(id,factor_identity_id,price_month,price,source_upload_batch_id)
VALUES (10,191,'2026-10',109.114,1)
""");
    execute(
        """
INSERT INTO lp_factor_row_ref(id,factor_upload_batch_id,source_sheet_name,source_row_number,
  factor_identity_id,factor_monthly_price_id,price,original_price)
VALUES (11,1,'旧因素表',3,191,10,109.114,109.114)
""");
    execute(
        """
        INSERT INTO lp_price_range_factor_rule(id,business_unit_type,material_code,factor_code,
          import_batch_no,effective_from,created_at,updated_at)
        VALUES (12,'COMMERCIAL','OLD-M','CU','OLD-R','2026-09-15','2026-09-01','2026-09-01')
        """);
    execute(
        """
INSERT INTO lp_price_range_item(id,material_code,range_low,range_high,range_basis,price_excl_tax,created_at,updated_at)
VALUES (13,'OLD-QTY',0,100,'QTY',1.234567,NOW(),NOW())
""");
    execute(
        """
INSERT INTO lp_price_linked_calc_item(id,oa_no,item_code,part_unit_price,trace_json,created_at,updated_at)
VALUES (14,'OLD-OA','OLD-M',1.234567,'{"result":"1.234567","variables":{"Cu":"109.114"}}',NOW(),NOW())
""");
    execute(
        """
INSERT INTO lp_price_linked_item(id,pricing_month,material_code,formula_expr,business_unit_type,effective_from,created_at,updated_at)
VALUES (15,'2026-09','OLD-M','Cu / 1.13','COMMERCIAL','2026-09-15',NOW(),NOW())
""");
    assertSuccess(script("/tmp/migration.sql"));
    // 当前生产实体还包含后续V314字段；历史V308的幂等与数据断言继续保留。
    execute("CREATE TABLE lp_price_prepare_batch (id BIGINT PRIMARY KEY, prepare_no VARCHAR(64))");
    assertSuccess(script("/tmp/scope.sql"));
    assertSuccess(script("/tmp/month-indexes.sql"));
  }

  @Test
  void v314RerunPreservesResultsAndSeparatesOrganizationAndTechnicalSource() throws Exception {
    execute(
        "INSERT INTO lp_price_prepare_batch(id,prepare_no,source_revision) VALUES"
            + " (1,'PREP','saved-revision')");
    String insert =
        "INSERT INTO lp_price_linked_calc_item(oa_no,item_code,business_unit_type,calc_scene,"
            + "pricing_month,factor_source,price_as_of_time,price_org_code,source_kind,part_unit_price,created_at,updated_at)"
            + " VALUES ('ORG-OA','M1','COMMERCIAL','QUOTE','2026-10','OA_LOCKED','2026-10-01"
            + " 12:00:00',%s,%s,1,NOW(),NOW())";
    execute(insert.formatted("'210'", "'PUBLIC'"));
    execute(insert.formatted("'220'", "'PUBLIC'"));
    execute(insert.formatted("'210'", "'TECH_SUPPLEMENTAL'"));
    assertThatThrownBy(() -> execute(insert.formatted("'210'", "'PUBLIC'")))
        .hasMessageContaining("Duplicate");
    assertSuccess(script("/tmp/scope.sql"));
    assertSuccess(script("/tmp/scope.sql"));
    try (var session = sessions.openSession(true)) {
      var rows =
          session
              .getMapper(PriceLinkedCalcItemMapper.class)
              .selectList(
                  com.baomidou.mybatisplus.core.toolkit.Wrappers.<PriceLinkedCalcItem>lambdaQuery()
                      .eq(PriceLinkedCalcItem::getOaNo, "ORG-OA"));
      assertThat(rows).hasSize(3);
      assertThat(rows)
          .allSatisfy(row -> assertThat(row.getPartUnitPrice()).isEqualByComparingTo("1"));
    }
  }

  @Test
  void optimisticRevisionProtectsRowsWithoutAnInputFingerprint() throws Exception {
    execute(
        "INSERT INTO lp_price_linked_calc_item(id,oa_no,item_code,part_unit_price,"
            + "business_unit_type,calc_scene,pricing_month,factor_source,price_org_code,created_at,updated_at)"
            + " VALUES"
            + " (900,'CAS-OA','M1',10,'COMMERCIAL','QUOTE','2026-10','OA_LOCKED','210',NOW(),NOW())");
    try (var older = sessions.openSession(true);
        var newer = sessions.openSession(true)) {
      var oldMapper = older.getMapper(PriceLinkedCalcItemMapper.class);
      var newMapper = newer.getMapper(PriceLinkedCalcItemMapper.class);
      var oldRow = oldMapper.selectById(900L);
      var newRow = newMapper.selectById(900L);
      assertThat(oldRow.getCalcFingerprint()).isNull();
      var newUpdate = new PriceLinkedCalcItem();
      newUpdate.setPartUnitPrice(new BigDecimal("30"));
      newUpdate.setResultRevision(1L);
      newUpdate.setUpdatedAt(LocalDateTime.now());
      assertThat(
              newMapper.update(
                  newUpdate,
                  com.baomidou.mybatisplus.core.toolkit.Wrappers.<PriceLinkedCalcItem>lambdaUpdate()
                      .eq(PriceLinkedCalcItem::getId, 900L)
                      .eq(PriceLinkedCalcItem::getResultRevision, newRow.getResultRevision())))
          .isOne();
      var oldUpdate = new PriceLinkedCalcItem();
      oldUpdate.setPartUnitPrice(new BigDecimal("20"));
      oldUpdate.setResultRevision(1L);
      oldUpdate.setUpdatedAt(LocalDateTime.now());
      assertThat(
              oldMapper.update(
                  oldUpdate,
                  com.baomidou.mybatisplus.core.toolkit.Wrappers.<PriceLinkedCalcItem>lambdaUpdate()
                      .eq(PriceLinkedCalcItem::getId, 900L)
                      .eq(PriceLinkedCalcItem::getResultRevision, oldRow.getResultRevision())))
          .isZero();
      older.clearCache();
      assertThat(oldMapper.selectById(900L).getPartUnitPrice()).isEqualByComparingTo("30");
    }
    assertSuccess(script("/tmp/scope.sql"));
    try (var session = sessions.openSession(true)) {
      assertThat(
              session
                  .getMapper(PriceLinkedCalcItemMapper.class)
                  .selectById(900L)
                  .getResultRevision())
          .isEqualTo(1L);
    }
  }

  @Test
  void preservesLegacyPricesIdsProvenanceQuantityRulesAndHistoricalResultsOnRerun()
      throws Exception {
    assertSuccess(script("/tmp/migration.sql"));
    try (SqlSession session = sessions.openSession(true)) {
      FactorMonthlyPrice old = session.getMapper(FactorMonthlyPriceMapper.class).selectById(10);
      assertThat(old.getFactorIdentityId()).isEqualTo(191);
      assertThat(old.getPrice()).isEqualByComparingTo("109.114");
      assertThat(old.getSchemeCode()).isEqualTo("LEGACY");
      assertThat(old.getPriceOrgCode()).isEmpty();
      assertThat(old.getUnit()).isNull();
      assertThat(old.getPublishedAt()).isNull();
      FactorRowRef source = session.getMapper(FactorRowRefMapper.class).selectById(11);
      assertThat(source.getFactorMonthlyPriceId()).isEqualTo(10);
      assertThat(source.getOriginalPrice()).isEqualByComparingTo("109.114");
      assertThat(source.getSourceColumnNumber()).isZero();
      PriceRangeItem quantity = session.getMapper(PriceRangeItemMapper.class).selectById(13);
      assertThat(quantity.getRangeBasis()).isEqualTo("QTY");
      assertThat(quantity.getPriceExclTax()).isEqualByComparingTo("1.234567");
      assertThat(quantity.getLowerInclusive()).isOne();
      assertThat(quantity.getUpperInclusive()).isOne();
      PriceRangeFactorRule rule =
          session.getMapper(PriceRangeFactorRuleMapper.class).selectById(12);
      assertThat(rule.getPricingMonth()).isNull();
      assertThat(rule.getRuleStatus()).isEqualTo("LEGACY");
      assertThat(rule.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-09-01T00:00:00"));
      assertThat(rule.getExtrapolationJson()).isNull();
      PriceLinkedCalcItem result =
          session.getMapper(PriceLinkedCalcItemMapper.class).selectById(14);
      assertThat(result.getPartUnitPrice()).isEqualByComparingTo("1.234567");
      assertThat(result.getTraceJson()).contains("109.114");
      assertThat(result.getInputSnapshotJson()).isNull();
      assertThat(session.getMapper(PriceLinkedItemMapper.class).selectById(15).getFormulaExpr())
          .isEqualTo("Cu / 1.13");
    }
    assertThat(scalar("SELECT COUNT(*) FROM lp_factor_identity")).isEqualTo("9");
    assertThat(scalar("SELECT COUNT(*) FROM lp_factor_monthly_price")).isEqualTo("1");
  }

  @Test
  void mapsOnlyConfirmedCopperAliasesWithoutMergingOtherSourcesOrSpecifications() throws Exception {
    assertThat(
            scalar(
                "SELECT GROUP_CONCAT(CONCAT(id,':',canonical_factor_identity_id) ORDER BY id) FROM"
                    + " lp_factor_identity WHERE canonical_factor_identity_id IS NOT NULL"))
        .isEqualTo("191:191,193:191,197:191,208:191,300:300");
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM lp_factor_identity WHERE id IN (200,250,251,301) AND"
                    + " canonical_factor_identity_id IS NULL AND canonical_factor_key IS NULL"))
        .isEqualTo("4");
  }

  @Test
  void refusesConflictingExistingCopperMappingsInsteadOfOverwritingThem() throws Exception {
    execute("UPDATE lp_factor_identity SET canonical_factor_identity_id=193 WHERE id=197");
    ExecResult result = script("/tmp/migration.sql");
    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("conflicting Cu canonical mapping");
    assertThat(scalar("SELECT canonical_factor_identity_id FROM lp_factor_identity WHERE id=197"))
        .isEqualTo("193");
  }

  @Test
  void selectsSchemesOrganizationsConditionsAndPublishedRevisionsWithoutMixingInputs()
      throws Exception {
    publication(100, "COMMERCIAL", "210", "2026-10", 1, "PUBLISHED");
    price(100, 191, "210", "2026-10", "CU_90", Condition.GENERAL, 1, "90");
    price(100, 191, "210", "2026-10", "CU_100", Condition.GENERAL, 1, "100");
    price(100, 200, "210", "2026-10", "CU_90", Condition.GENERAL, 1, "115.979");
    publication(101, "COMMERCIAL", "210", "2026-10", 2, "PUBLISHED");
    price(101, 191, "210", "2026-10", "CU_90", Condition.GENERAL, 2, "91");
    price(101, 191, "210", "2026-10", "CU_100", Condition.GENERAL, 2, "101");
    publication(102, "COMMERCIAL", "210", "2026-10", 3, "DRAFT");
    price(102, 191, "210", "2026-10", "CU_90", Condition.GENERAL, 3, "999");
    publication(103, "COMMERCIAL", "220", "2026-10", 1, "PUBLISHED");
    price(103, 191, "220", "2026-10", "BOARD", Condition.GENERAL, 1, "92");
    price(103, 250, "220", "2026-10", "BOARD", Condition.NON_TENDER, 1, "20");
    price(103, 250, "220", "2026-10", "BOARD", Condition.TENDER, 1, "15");
    publication(104, "HOUSEHOLD", "210", "2026-10", 1, "PUBLISHED");
    price(104, 300, "210", "2026-10", "CU_90", Condition.GENERAL, 1, "85");
    assertPrice(scope("210", "CU_90", Condition.GENERAL), null, "91");
    assertPrice(scope("210", "CU_100", Condition.GENERAL), null, "101");
    assertPrice(scope("220", "BOARD", Condition.GENERAL), null, "92");
    assertPrice(scope("220", "BOARD", Condition.NON_TENDER), null, "20");
    assertPrice(scope("220", "BOARD", Condition.TENDER), null, "15");
    assertPrice(
        new FactorPriceScope("HOUSEHOLD", "210", "2026-10", "CU_90", Condition.GENERAL),
        null,
        "85");
    try (SqlSession session = sessions.openSession(true)) {
      var mapper = session.getMapper(FactorMonthlyPriceMapper.class);
      var historical = mapper.findPublishedScheme(scope("210", "CU_90", Condition.GENERAL), 1);
      assertThat(historical)
          .extracting(FactorMonthlyPrice::getFactorIdentityId)
          .containsExactly(191L, 200L);
      assertThat(historical.getFirst().getPrice()).isEqualByComparingTo("90");
      assertThat(historical.getLast().getPrice()).isEqualByComparingTo("115.979");
      // 整个方案固定一个发布批次；修订2未包含Pcu时不能偷拿修订1拼接。
      assertThat(mapper.findPublishedScheme(scope("210", "CU_90", Condition.GENERAL), null))
          .hasSize(1);
      assertThat(mapper.findPublishedScheme(scope("210", "CU_90", Condition.GENERAL), 3)).isEmpty();
      assertThat(
              mapper.findPublishedScheme(
                  new FactorPriceScope("COMMERCIAL", "210", "2026-09", "CU_90", Condition.GENERAL),
                  null))
          .isEmpty();
      assertThat(mapper.findActiveByIdentityAndMonth(191L, "2026-10").getId()).isEqualTo(10L);
    }
  }

  @Test
  void rejectsDuplicateVersionsButKeepsEveryPreviousRevision() throws Exception {
    publication(100, "COMMERCIAL", "210", "2026-10", 1, "PUBLISHED");
    price(100, 191, "210", "2026-10", "CU_90", Condition.GENERAL, 1, "90");
    assertThatThrownBy(() -> publication(101, "COMMERCIAL", "210", "2026-10", 1, "PUBLISHED"))
        .isInstanceOf(SQLIntegrityConstraintViolationException.class);
    assertThatThrownBy(() -> price(100, 191, "210", "2026-10", "CU_90", Condition.GENERAL, 1, "99"))
        .isInstanceOf(SQLIntegrityConstraintViolationException.class);
    publication(102, "COMMERCIAL", "210", "2026-10", 2, "PUBLISHED");
    price(102, 191, "210", "2026-10", "CU_90", Condition.GENERAL, 2, "99");
    assertPrice(scope("210", "CU_90", Condition.GENERAL), 1, "90");
    assertPrice(scope("210", "CU_90", Condition.GENERAL), null, "99");
  }

  @Test
  void retainsRawValuesFormatsUnitsAndMultipleSchemeReferencesToTheSameFallbackCell()
      throws Exception {
    publication(100, "COMMERCIAL", "210", "2026-10", 1, "PUBLISHED");
    price(100, 191, "210", "2026-10", "A", Condition.GENERAL, 1, "21.6362912421045");
    price(100, 191, "210", "2026-10", "B", Condition.GENERAL, 1, "21.6362912421045");
    try (SqlSession session = sessions.openSession(true)) {
      var monthly =
          session
              .getMapper(FactorMonthlyPriceMapper.class)
              .findPublishedScheme(scope("210", "A", Condition.GENERAL), null)
              .getFirst();
      assertThat(monthly.getPrice()).isEqualByComparingTo("21.6362912421045");
      var mapper = session.getMapper(FactorRowRefMapper.class);
      for (String scheme : List.of("A", "B")) {
        var schemePrice =
            session
                .getMapper(FactorMonthlyPriceMapper.class)
                .findPublishedScheme(scope("210", scheme, Condition.GENERAL), null)
                .getFirst();
        FactorRowRef ref = new FactorRowRef();
        ref.setFactorUploadBatchId(100L);
        ref.setSourceSheetName("财务因素");
        ref.setSourceRowNumber(3);
        ref.setSourceColumnNumber(5);
        ref.setSourceCellRef("E3");
        ref.setSchemeCode(scheme);
        ref.setConditionCode("GENERAL");
        ref.setFactorIdentityId(191L);
        ref.setFactorMonthlyPriceId(schemePrice.getId());
        ref.setSourceValueText("21.6362912421045");
        ref.setOriginalPrice(new BigDecimal("21.6362912421045"));
        ref.setPrice(monthly.getPrice());
        ref.setSourceNumberFormat("0.000");
        ref.setSourceFormulaExpr("AVERAGE(C1:C2)");
        ref.setUnit("元/公斤");
        ref.setSourceUnit("元/公斤");
        ref.setConversionBasis("CURRENT_WORKBOOK_PREVIOUS_MONTH_AVERAGE");
        assertThat(mapper.insert(ref)).isOne();
        FactorRowRef saved = mapper.selectById(ref.getId());
        assertThat(saved.getOriginalPrice()).isEqualByComparingTo("21.6362912421045");
        assertThat(saved.getSourceNumberFormat()).isEqualTo("0.000");
        assertThat(saved.getSourceCellRef()).isEqualTo("E3");
        assertThat(saved.getSourceValueText()).isEqualTo("21.6362912421045");
        assertThat(saved.getConversionBasis()).isEqualTo("CURRENT_WORKBOOK_PREVIOUS_MONTH_AVERAGE");
      }
    }
    assertThat(scalar("SELECT COUNT(*) FROM lp_factor_row_ref WHERE factor_upload_batch_id=100"))
        .isEqualTo("2");
  }

  @Test
  void selectsBothRuleKindsBySupplierSpecificationUnitMonthAndRevision() {
    var a = key("210", "SUP-A", "SPEC", "只");
    var b = key("210", "SUP-B", "SPEC", "只");
    var otherOrg = key("220", "SUP-A", "SPEC", "只");
    var otherSpec = key("210", "SUP-A", "OTHER", "只");
    var otherUnit = key("210", "SUP-A", "SPEC", "米");
    try (SqlSession session = sessions.openSession(true)) {
      for (var key : List.of(a, b, otherOrg, otherSpec, otherUnit)) {
        linkedRule(session, key, "2026-10", 1, "PUBLISHED");
        rangeRule(session, key, "2026-10", 1, "PUBLISHED");
      }
      linkedRule(session, a, "2026-10", 2, "PUBLISHED");
      rangeRule(session, a, "2026-10", 2, "PUBLISHED");
      linkedRule(session, a, "2026-10", 3, "DRAFT");
      rangeRule(session, a, "2026-10", 3, "DRAFT");
      linkedRule(session, a, "2026-11", 1, "PUBLISHED");
      rangeRule(session, a, "2026-11", 1, "PUBLISHED");
      for (var key : List.of(a, b, otherOrg, otherSpec, otherUnit)) {
        var linked =
            session.getMapper(PriceLinkedItemMapper.class).findPublishedVersion(key, "2026-10");
        var range =
            session
                .getMapper(PriceRangeFactorRuleMapper.class)
                .findPublishedVersion(key, "2026-10");
        assertThat(linked.getRuleRevision()).isEqualTo(key.equals(a) ? 2 : 1);
        assertThat(range.getVersionNo()).isEqualTo(key.equals(a) ? 2 : 1);
        assertThat(linked.getSupplierCode()).isEqualTo(key.supplierCode());
        assertThat(range.getSupplierCode()).isEqualTo(key.supplierCode());
        assertThat(linked.getExpressionVersion()).isEqualTo("ORIGINAL_V1");
        assertThat(linked.getFormulaExpr()).isEqualTo("ROUND(Cu + 2, 4) / 1.13");
      }
      assertThat(session.getMapper(PriceLinkedItemMapper.class).findPublishedVersion(a, "2026-09"))
          .isNull();
      assertThat(
              session
                  .getMapper(PriceRangeFactorRuleMapper.class)
                  .findPublishedVersion(a, "2026-09"))
          .isNull();
    }
  }

  @Test
  void storesDistinctBoundarySemanticsAndCompleteFrozenInputs() {
    try (SqlSession session = sessions.openSession(true)) {
      var tiers = session.getMapper(PriceRangeItemMapper.class);
      var tier = tiers.selectById(13);
      tier.setRangeBasis("FACTOR");
      tier.setRangeLow(new BigDecimal("89001"));
      tier.setRangeHigh(new BigDecimal("90000"));
      tier.setLowerInclusive(1);
      tier.setUpperInclusive(0);
      tier.setPriceExclTax(new BigDecimal("2.1234567890123456"));
      tier.setSourceCellRef("G19");
      tiers.updateById(tier);
      assertThat(tiers.selectById(13).getUpperInclusive()).isZero();
      assertThat(tiers.selectById(13).getPriceExclTax()).isEqualByComparingTo("2.1234567890123456");
      var results = session.getMapper(PriceLinkedCalcItemMapper.class);
      var frozen = results.selectById(14);
      frozen.setCalculatorVersion("ORIGINAL_V1");
      frozen.setInputSnapshotJson(
          """
{"schemaVersion":1,"requestMonth":"2026-10","rule":{"id":15,"revision":1,"month":"2026-09"},
 "factorPublication":{"batchId":100,"revision":1,"month":"2026-10","scheme":"CU_90"},
 "factors":[{"identityId":191,"monthlyPriceId":10,"value":"90","unit":"CNY/KG","taxIncluded":true}],
 "supplierCode":"SUP-A","customerCu":null,"sus304":null,"sus316l":null,"tender":false}
""");
      frozen.setCalcFingerprint("f".repeat(64));
      results.updateById(frozen);
      var loaded = results.selectById(14);
      assertThat(loaded.getCalculatorVersion()).isEqualTo("ORIGINAL_V1");
      assertThat(loaded.getInputSnapshotJson()).contains("monthlyPriceId", "CNY/KG", "CU_90");
      assertThat(loaded.getCalcFingerprint()).isEqualTo("f".repeat(64));
      assertThat(loaded.getTraceJson()).contains("109.114");
    }
  }

  @Test
  void partialMonthIsScopeWideAcrossBothRuleKindsAndNeverBackfillsMissingMaterial()
      throws Exception {
    var a = new MaterialPriceRuleKey("COMMERCIAL", "210", "A", "SUP", "供应商", "", "只");
    var b = new MaterialPriceRuleKey("COMMERCIAL", "210", "B", "SUP", "供应商", "", "只");
    try (var session = sessions.openSession(true)) {
      var selector =
          new MaterialPriceMonthSelector(session.getMapper(MaterialPriceMonthMapper.class));
      linkedRule(session, a, "2026-10", 1, "PUBLISHED");
      linkedRule(session, b, "2026-10", 1, "PUBLISHED");
      rangeRule(session, b, "2026-10", 1, "PUBLISHED");
      assertThat(selector.select("COMMERCIAL", "210", "2026-11"))
          .isEqualTo(new PriceMonthSelection("2026-11", "2026-10"));
      // 11月只导入A的区间，B的联动和区间都不得分别回退10月。
      rangeRule(session, a, "2026-11", 1, "PUBLISHED");
      var partial = selector.select("COMMERCIAL", "210", "2026-11");
      assertThat(partial.sourceMonth()).isEqualTo("2026-11");
      assertThat(
              session
                  .getMapper(PriceLinkedItemMapper.class)
                  .findPublishedVersion(b, partial.sourceMonth()))
          .isNull();
      assertThat(
              session
                  .getMapper(PriceRangeFactorRuleMapper.class)
                  .findPublishedVersion(b, partial.sourceMonth()))
          .isNull();
      assertThat(selector.hasRange("COMMERCIAL", "210", partial.sourceMonth(), "A")).isTrue();
      assertThat(selector.hasRange("COMMERCIAL", "210", partial.sourceMonth(), "B")).isFalse();
      // 更晚上传的旧月和未来月均不能改变11月；历史请求依然读10月。
      linkedRule(session, b, "2026-09", 5, "PUBLISHED");
      linkedRule(session, b, "2026-12", 1, "PUBLISHED");
      assertThat(selector.select("COMMERCIAL", "210", "2026-11")).isEqualTo(partial);
      assertThat(selector.select("COMMERCIAL", "210", "2026-10").sourceMonth())
          .isEqualTo("2026-10");
      assertThat(selector.select("COMMERCIAL", "210", "2026-08")).isNull();
      assertThat(session.getMapper(PriceLinkedItemMapper.class).findPublishedVersion(b, "2026-10"))
          .isNotNull();
      linkedRule(session, b, "2026-11", 1, "PUBLISHED");
      assertThat(
              session
                  .getMapper(PriceLinkedItemMapper.class)
                  .findPublishedVersion(b, partial.sourceMonth()))
          .isNotNull();
    }
    assertThat(scalar("SELECT part_unit_price FROM lp_price_linked_calc_item WHERE id=14"))
        .startsWith("1.234567");
  }

  @Test
  void monthSelectionIsolatedFromOtherOrganizationsBusinessUnitsDraftRejectedAndSupplementalRules()
      throws Exception {
    try (var session = sessions.openSession(true)) {
      var selector =
          new MaterialPriceMonthSelector(session.getMapper(MaterialPriceMonthMapper.class));
      var commercial = key("210", "SUP", "SPEC", "只");
      linkedRule(session, commercial, "2026-10", 1, "PUBLISHED");
      linkedRule(session, commercial, "2026-11", 1, "DRAFT");
      rangeRule(session, commercial, "2026-11", 1, "REJECTED");
      linkedRule(session, key("220", "SUP", "SPEC", "只"), "2026-11", 1, "PUBLISHED");
      rangeRule(
          session,
          new MaterialPriceRuleKey("HOUSEHOLD", "210", "M-001", "SUP", "供应商", "SPEC", "只"),
          "2026-11",
          1,
          "PUBLISHED");
      assertThat(selector.select("COMMERCIAL", "210", "2026-11").sourceMonth())
          .isEqualTo("2026-10");
      assertThat(selector.select("COMMERCIAL", "220", "2026-11").sourceMonth())
          .isEqualTo("2026-11");
      assertThat(selector.select("HOUSEHOLD", "210", "2026-11").sourceMonth()).isEqualTo("2026-11");
      assertThat(selector.select("HOUSEHOLD", "220", "2026-11")).isNull();
      linkedRule(session, commercial, "2026-11", 2, "PUBLISHED");
      execute(
          "UPDATE lp_price_linked_item SET source_kind='TECH_SUPPLEMENTAL' WHERE"
              + " pricing_month='2026-11' AND rule_revision=2");
      session.clearCache();
      assertThat(selector.select("COMMERCIAL", "210", "2026-11").sourceMonth())
          .isEqualTo("2026-10");
      linkedRule(session, commercial, "2026-11", 3, "PUBLISHED");
      execute(
          "UPDATE lp_price_linked_item SET deleted=1 WHERE pricing_month='2026-11' AND"
              + " rule_revision=3");
      session.clearCache();
      assertThat(selector.select("COMMERCIAL", "210", "2026-11").sourceMonth())
          .isEqualTo("2026-10");
    }
  }

  @Test
  void deletingAnObsoletePrepareBatchPreservesSharedLinkedEvidenceInRealMysql() throws Exception {
    execute(
        "CREATE TABLE lp_price_prepare_item (id BIGINT PRIMARY KEY,prepare_no"
            + " VARCHAR(64),result_ref_type VARCHAR(32),result_ref_id BIGINT)");
    execute(
        "INSERT INTO lp_price_prepare_item VALUES"
            + " (1,'OLD','LINKED_PRICE',14),(2,'NEW','LINKED_PRICE',14),(3,'OLD','LINKED_PRICE',16)");
    execute(
        "INSERT INTO"
            + " lp_price_linked_calc_item(id,oa_no,item_code,part_unit_price,created_at,updated_at)"
            + " VALUES (16,'OLD-OA','OTHER',3.3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    try (var session = sessions.openSession(true)) {
      var batches = Mockito.mock(PricePrepareBatchMapper.class);
      var items = Mockito.mock(PricePrepareItemMapper.class);
      var workspace = Mockito.mock(QuoteCostingWorkspaceService.class);
      var old = new PricePrepareBatch();
      old.setPrepareNo("OLD");
      old.setStatus("PARTIAL");
      old.setOaFormItemId(1L);
      old.setPeriodMonth("2026-10");
      Mockito.when(batches.selectOne(ArgumentMatchers.any())).thenReturn(old);
      Mockito.when(workspace.find(1L, "2026-10")).thenReturn(java.util.Optional.empty());
      var shared = new PricePrepareItem();
      shared.setResultRefType("LINKED_PRICE");
      shared.setResultRefId(14L);
      var orphan = new PricePrepareItem();
      orphan.setResultRefType("LINKED_PRICE");
      orphan.setResultRefId(16L);
      Mockito.when(items.selectList(ArgumentMatchers.any())).thenReturn(List.of(shared, orphan));
      var cleaner =
          new PricePrepareCurrentStateServiceImpl(
              batches,
              items,
              Mockito.mock(PricePrepareGapMapper.class),
              Mockito.mock(QuoteCostRunVersionMapper.class),
              Mockito.mock(PackageComponentPriceMapper.class),
              Mockito.mock(PackageComponentPriceDetailMapper.class),
              Mockito.mock(MakePartPriceCalcRowMapper.class),
              Mockito.mock(MakePartPriceGapItemMapper.class),
              session.getMapper(PriceLinkedCalcItemMapper.class),
              workspace,
              Mockito.mock(QuoteCostRunVersionInvalidationService.class));
      assertThat(cleaner.discardPromotedFailedAttempt("OLD")).isTrue();
      assertThat(session.getMapper(PriceLinkedCalcItemMapper.class).selectById(14)).isNotNull();
      assertThat(session.getMapper(PriceLinkedCalcItemMapper.class).selectById(16)).isNull();
      execute("DELETE FROM lp_price_prepare_item WHERE prepare_no='NEW'");
      session.clearCache();
      assertThat(cleaner.discardPromotedFailedAttempt("OLD")).isTrue();
      assertThat(session.getMapper(PriceLinkedCalcItemMapper.class).selectById(14)).isNull();
    }
  }

  @Test
  void aMissingMaterialClearsOldPriceSourceAndTraceInTheDatabaseWithoutRunningTheCalculator()
      throws Exception {
    execute("UPDATE lp_price_linked_item SET org_code='210' WHERE id=15");
    execute(
        """
UPDATE lp_price_linked_calc_item SET business_unit_type='COMMERCIAL',calc_scene='QUOTE',
  pricing_month='2026-10',price_as_of_time='2026-10-01 12:00:00',factor_source='OA_LOCKED',
  calc_status='OK',source_price_record_id=15,price_org_code='210',part_amount=9.9 WHERE id=14
""");
    try (var session = sessions.openSession(true)) {
      linkedRule(
          session,
          new MaterialPriceRuleKey("COMMERCIAL", "210", "OTHER-M", "SUP", "供应商", "", "只"),
          "2026-10",
          1,
          "PUBLISHED");
      var calculator = Mockito.mock(PriceLinkedCalcServiceImpl.class);
      var ensure =
          new LinkedPriceEnsureServiceImpl(
              session.getMapper(PriceLinkedCalcItemMapper.class),
              session.getMapper(PriceLinkedItemMapper.class),
              Mockito.mock(BomCostingRowMapper.class),
              Mockito.mock(OaFormMapper.class),
              calculator,
              new SupplierPreferredPriceSelector(
                  Mockito.mock(SupplierSupplyRatioResolveService.class)),
              new MaterialPriceMonthSelector(session.getMapper(MaterialPriceMonthMapper.class)),
              Mockito.mock(com.sanhua.marketingcost.service.impl.OriginalLinkedPriceRuntime.class), org.mockito.Mockito.mock(com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService.class), org.mockito.Mockito.mock(com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceSelectionService.class), org.mockito.Mockito.mock(com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService.class));
      var request =
          LinkedPriceEnsureRequest.quote(
              "OLD-OA", "COMMERCIAL", "2026-10", java.util.Set.of("OLD-M"));
      request.setPriceOrgCode("210");
      request.setPriceAsOfTime(LocalDateTime.of(2026, 10, 1, 12, 0));
      assertThat(ensure.ensure(request).getFailedCount()).isOne();
      var failed = session.getMapper(PriceLinkedCalcItemMapper.class).selectById(14);
      assertThat(failed.getCalcStatus()).isEqualTo("FAILED");
      assertThat(failed.getFailureCode()).isEqualTo("MATERIAL_MONTH_PRICE_MISSING");
      assertThat(failed.getPartUnitPrice()).isNull();
      assertThat(failed.getPartAmount()).isNull();
      assertThat(failed.getSourcePriceRecordId()).isNull();
      assertThat(failed.getTraceJson()).isNull();
      Mockito.verifyNoInteractions(calculator);
    }
  }

  private static FactorPriceScope scope(String org, String scheme, Condition condition) {
    return new FactorPriceScope("COMMERCIAL", org, "2026-10", scheme, condition);
  }

  private static MaterialPriceRuleKey key(String org, String supplier, String spec, String unit) {
    return new MaterialPriceRuleKey("COMMERCIAL", org, "M-001", supplier, "供应商", spec, unit);
  }

  private static void assertPrice(FactorPriceScope scope, Integer revision, String expected) {
    try (SqlSession session = sessions.openSession(true)) {
      var prices =
          session.getMapper(FactorMonthlyPriceMapper.class).findPublishedScheme(scope, revision);
      assertThat(prices).hasSize(1);
      assertThat(prices.getFirst().getPrice()).isEqualByComparingTo(expected);
    }
  }

  private static void publication(
      long id, String bu, String org, String month, int revision, String status) throws Exception {
    execute(
        """
        INSERT INTO lp_factor_upload_batch(id,batch_no,import_type,price_month,business_unit_type,
          price_org_code,publication_revision,status,default_scheme_code,published_at)
        VALUES (?,?,'FINANCE_BASE',?,?,?,?,?,'CU_90',CURRENT_TIMESTAMP(3))
        """,
        id,
        "B-" + id,
        month,
        bu,
        org,
        revision,
        status);
  }

  private static void price(
      long batch,
      long identity,
      String org,
      String month,
      String scheme,
      Condition condition,
      int revision,
      String amount)
      throws Exception {
    execute(
        """
INSERT INTO lp_factor_monthly_price(factor_identity_id,price_org_code,price_month,scheme_code,
  condition_code,revision_no,price,source_upload_batch_id,status,published_at,unit)
VALUES (?,?,?,?,?,?,?,?,'PUBLISHED',CURRENT_TIMESTAMP(3),'CNY/KG')
""",
        identity,
        org,
        month,
        scheme,
        condition.name(),
        revision,
        new BigDecimal(amount),
        batch);
  }

  private static void linkedRule(
      SqlSession session, MaterialPriceRuleKey key, String month, int revision, String status) {
    var rule = new PriceLinkedItem();
    rule.setBusinessUnitType(key.businessUnitType());
    rule.setOrgCode(key.orgCode());
    rule.setMaterialCode(key.materialCode());
    rule.setSupplierCode(key.supplierCode());
    rule.setSpecModel(key.specification());
    rule.setUnit(key.unit());
    rule.setRuleIdentityKey(key.identityKey());
    rule.setPricingMonth(month);
    rule.setRuleRevision(revision);
    rule.setRuleStatus(status);
    rule.setExpressionVersion("ORIGINAL_V1");
    rule.setEffectiveFrom(LocalDate.parse("2020-01-01"));
    rule.setCreatedAt(LocalDateTime.now());
    rule.setUpdatedAt(LocalDateTime.now());
    rule.setFormulaExpr("ROUND(Cu + 2, 4) / 1.13");
    assertThat(session.getMapper(PriceLinkedItemMapper.class).insert(rule)).isOne();
  }

  private static void rangeRule(
      SqlSession session, MaterialPriceRuleKey key, String month, int revision, String status) {
    var rule = new PriceRangeFactorRule();
    rule.setBusinessUnitType(key.businessUnitType());
    rule.setOrgCode(key.orgCode());
    rule.setMaterialCode(key.materialCode());
    rule.setSupplierCode(key.supplierCode());
    rule.setSpecModel(key.specification());
    rule.setPriceUnit(key.unit());
    rule.setRuleIdentityKey(key.identityKey());
    rule.setPricingMonth(month);
    rule.setVersionNo(revision);
    rule.setRuleStatus(status);
    rule.setFactorCode("CU");
    rule.setFactorUnit("CNY/TON");
    rule.setImportBatchNo("R-" + month + "-" + revision);
    rule.setEffectiveFrom(LocalDate.parse("2020-01-01"));
    rule.setCreatedAt(LocalDateTime.now());
    rule.setUpdatedAt(LocalDateTime.now());
    assertThat(session.getMapper(PriceRangeFactorRuleMapper.class).insert(rule)).isOne();
  }

  private static ExecResult script(String path) throws Exception {
    return MYSQL.execInContainer(
        "sh",
        "-c",
        "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot prc_contract < \"$1\"",
        "migration",
        path);
  }

  private static void assertSuccess(ExecResult result) {
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }

  private static void execute(String sql, Object... params) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
      statement.execute();
    }
  }

  private static String scalar(String sql) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }
}

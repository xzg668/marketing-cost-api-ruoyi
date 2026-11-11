package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.dto.CostRunFeeQuery.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.costing.CostRunFeeSources;
import java.math.BigDecimal;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real SQL applicability/ordering/precision comparisons on isolated synthetic fee tables. */
@Tag("integration")
class CostRunFeeSourcesIntegrationTest {
  static JdbcTemplate jdbc;
  static SqlSessionTemplate session;
  static CostRunFeeSources reader;

  @BeforeAll static void start() throws Exception {
    CostRunManufacturingSourcesIntegrationTest.start();
    jdbc = CostRunManufacturingSourcesIntegrationTest.jdbc;
    // 父级夹具已建工资查询用的最小空料品表，本夹具需要完整字段的独立合成表。
    jdbc.execute("DROP TABLE lp_material_master_raw");
    for (var type : List.of(DepartmentFundRate.class, ManufactureRate.class, ThreeExpenseRate.class,
        OtherExpenseRate.class, ProductProperty.class, ProductPropertyRule.class, QualityLossRate.class,
        ThreeExpenseDimensionMapping.class, MaterialMasterRaw.class)) createSyntheticTable(type);
    jdbc.execute("CREATE TABLE lp_cost_business_rule(id BIGINT PRIMARY KEY AUTO_INCREMENT,rule_code VARCHAR(100),"
        + "business_unit_type VARCHAR(40),enabled INT,effective_from VARCHAR(7),effective_to VARCHAR(7),decimal_value DECIMAL(24,12))");
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    for (var mapper : List.of(CostRunFeeSourceMapper.class, DepartmentFundRateMapper.class,
        ManufactureRateMapper.class, ThreeExpenseRateMapper.class, OtherExpenseRateMapper.class,
        ProductPropertyMapper.class, ProductPropertyRuleMapper.class, QualityLossRateMapper.class,
        ThreeExpenseDimensionMappingMapper.class, CostBusinessRuleMapper.class, MaterialMasterRawMapper.class)) config.addMapper(mapper);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(jdbc.getDataSource()); factory.setConfiguration(config);
    session = new SqlSessionTemplate(factory.getObject());
    reader = new CostRunFeeSources(session.getMapper(CostRunFeeSourceMapper.class));
  }

  @AfterAll static void stop() { CostRunManufacturingSourcesIntegrationTest.stop(); }

  @Test void materialFactsStayWithinOrganizationAndActiveRowsWithExactSourceFields() {
    String code = code();
    insert("lp_material_master_raw", Map.of("material_code", code, "organization_code", "COMMERCIAL",
        "active_flag", 1, "production_division", "商用", "material_model", "MODEL-A", "product_attr", "标准品",
        "bare_code", "BARE-A", "main_category_code", "10", "source_batch_no", "BATCH-1"));
    insert("lp_material_master_raw", Map.of("material_code", code, "organization_code", "PLATE",
        "active_flag", 1, "production_division", "板换", "product_attr", "非标品"));
    insert("lp_material_master_raw", Map.of("material_code", code, "organization_code", "COMMERCIAL",
        "active_flag", 0, "production_division", "过期版本"));
    var queries = List.of(new Material(code, "COMMERCIAL"), new Material(code, "PLATE"),
        new Material(code.toLowerCase(java.util.Locale.ROOT), "COMMERCIAL"), new Material(code, "MISSING"));
    var original = session.getMapper(MaterialMasterRawMapper.class);
    var expected = queries.stream().map(q -> original.selectByLatestBatchAndCodes(List.of(q.materialCode()), null, q.organizationCode())).toList();
    assertThat(reader.readMaterial(queries)).usingRecursiveComparison().isEqualTo(expected);
    assertThat(expected.getFirst()).hasSize(1);
    assertThat(expected.get(1).getFirst().getProductAttr()).isEqualTo("非标品");
    assertThat(expected.getLast()).isEmpty();
  }

  @Test void batchNetLossResolutionMatchesOriginalIncludingFallbackAndAmbiguity() {
    String product = code(), bare = code(), model = code();
    insert("lp_material_master_raw", Map.of("material_code", product, "organization_code", "COMMERCIAL",
        "active_flag", 1, "main_category_code", "10", "bare_code", bare, "material_model", model));
    insert("lp_material_master_raw", Map.of("material_code", bare, "organization_code", "COMMERCIAL",
        "active_flag", 1, "main_category_code", "11", "material_model", model + "-bare"));
    insert("lp_quality_loss_rate", Map.of("rate_year", 2026, "bare_product_code", bare,
        "business_unit_type", "COMMERCIAL", "loss_rate", "0.023456789"));
    var query = new com.sanhua.marketingcost.service.NetLossRateQuery(
        session.getMapper(MaterialMasterRawMapper.class), session.getMapper(QualityLossRateMapper.class));
    var requests = List.of(new com.sanhua.marketingcost.service.NetLossRateQuery.Lookup(product, "wrong-model", "COMMERCIAL", 2026, "COMMERCIAL"),
        new com.sanhua.marketingcost.service.NetLossRateQuery.Lookup("missing-code", model, "COMMERCIAL", 2026, "COMMERCIAL"),
        new com.sanhua.marketingcost.service.NetLossRateQuery.Lookup(bare, null, "COMMERCIAL", 2026, "COMMERCIAL"),
        new com.sanhua.marketingcost.service.NetLossRateQuery.Lookup(product, null, "COMMERCIAL", 2025, "COMMERCIAL"),
        new com.sanhua.marketingcost.service.NetLossRateQuery.Lookup(product, model, "PLATE", 2026, "COMMERCIAL"));
    var expected = requests.stream().map(q -> query.lookup(q.code(), q.model(), q.organization(), q.year(), q.businessUnit())).toList();
    assertThat(query.lookupBatch(requests, reader)).isEqualTo(expected);
    assertThat(expected.get(0).rate()).isEqualByComparingTo("0.023456789");
    assertThat(expected.get(1).status()).isEqualTo("AVAILABLE");
    assertThat(expected.get(3).reasonCode()).isEqualTo("NET_LOSS_RATE_MISSING");
    insert("lp_material_master_raw", Map.of("material_code", code(), "organization_code", "COMMERCIAL",
        "active_flag", 1, "main_category_code", "10", "material_model", model));
    assertThat(query.lookupBatch(List.of(requests.get(1)), reader).getFirst())
        .isEqualTo(query.lookup("missing-code", model, "COMMERCIAL", 2026, "COMMERCIAL"));
    assertThat(query.lookupBatch(List.of(requests.get(1)), reader).getFirst().reasonCode()).isEqualTo("NET_LOSS_PRODUCT_AMBIGUOUS");
    int initial = statements();
    assertThat(query.lookupBatch(Collections.nCopies(64, requests.getFirst()), reader)).allMatch(expected.getFirst()::equals);
    assertThat(statements() - initial).isEqualTo(2);
    initial = statements();
    assertThat(query.lookupBatch(List.of(), reader)).isEmpty();
    assertThatThrownBy(() -> query.lookupBatch(Collections.nCopies(65, requests.getFirst()), reader)).isInstanceOf(IllegalArgumentException.class);
    assertThat(statements()).isEqualTo(initial);
  }

  @Test void modelLookupKeepsAmbiguityLimitAndDoesNotIncludeComponentsOrOtherOrganizations() {
    String model = code();
    for (int i = 0; i < 3; i++) insert("lp_material_master_raw", Map.of("material_code", "P" + i + model,
        "organization_code", "COMMERCIAL", "active_flag", 1, "main_category_code", i == 0 ? "11" : "10",
        "material_model", " " + model + " "));
    insert("lp_material_master_raw", Map.of("material_code", "A" + model, "organization_code", "COMMERCIAL",
        "active_flag", 1, "main_category_code", "20", "material_model", model));
    insert("lp_material_master_raw", Map.of("material_code", "B" + model, "organization_code", "PLATE",
        "active_flag", 1, "main_category_code", "10", "material_model", model));
    var queries = List.of(new NetLossModel(model, "COMMERCIAL"), new NetLossModel(model, "PLATE"),
        new NetLossModel(model + "-missing", "COMMERCIAL"));
    var original = session.getMapper(MaterialMasterRawMapper.class);
    var expected = queries.stream().map(q -> original.selectNetLossProducts("MODEL", q.model(), true, q.organizationCode(), 2)).toList();
    assertThat(reader.readNetLossModel(queries)).usingRecursiveComparison().isEqualTo(expected);
    assertThat(expected.get(0)).hasSize(2); assertThat(expected.get(1)).hasSize(1); assertThat(expected.get(2)).isEmpty();
  }

  @Test void netLossBatchPreservesMissingBareInvalidRatesAndDuplicateConfigurationFailures() {
    String missingBare = code(), invalid = code();
    insert("lp_material_master_raw", Map.of("material_code", missingBare, "organization_code", "COMMERCIAL",
        "active_flag", 1, "main_category_code", "10"));
    insert("lp_material_master_raw", Map.of("material_code", invalid, "organization_code", "COMMERCIAL",
        "active_flag", 1, "main_category_code", "11"));
    insert("lp_quality_loss_rate", Map.of("rate_year", 2026, "bare_product_code", invalid,
        "business_unit_type", "COMMERCIAL", "loss_rate", "-0.01"));
    var query = new com.sanhua.marketingcost.service.NetLossRateQuery(
        session.getMapper(MaterialMasterRawMapper.class), session.getMapper(QualityLossRateMapper.class));
    var requests = List.of(new com.sanhua.marketingcost.service.NetLossRateQuery.Lookup(missingBare, null, "COMMERCIAL", 2026, "COMMERCIAL"),
        new com.sanhua.marketingcost.service.NetLossRateQuery.Lookup(invalid, null, "COMMERCIAL", 2026, "COMMERCIAL"));
    var result = query.lookupBatch(requests, reader);
    assertThat(result).isEqualTo(requests.stream().map(q -> query.lookup(q.code(), q.model(), q.organization(), q.year(), q.businessUnit())).toList());
    assertThat(result.getFirst().reasonCode()).isEqualTo("NET_LOSS_BARE_MISSING");
    assertThat(result.getLast().status()).isEqualTo("ERROR");
    assertThat(result.getLast().reasonCode()).isEqualTo("NET_LOSS_RATE_INVALID");
    insert("lp_quality_loss_rate", Map.of("rate_year", 2026, "bare_product_code", invalid,
        "business_unit_type", "COMMERCIAL", "loss_rate", "0.02"));
    assertThatThrownBy(() -> query.lookupBatch(requests, reader)).isInstanceOf(org.apache.ibatis.exceptions.TooManyResultsException.class);
  }

  @Test void departmentUsesLatestExactScopeAndKeepsZeroMissingAndRateModesDistinct() {
    String division = code();
    insert("lp_department_fund_rate", Map.of("rate_year", 2026, "business_division", division,
        "expense_subject", "水电费用", "business_unit_type", "COMMERCIAL", "quote_ratio", "0.1"));
    insert("lp_department_fund_rate", Map.of("rate_year", 2026, "business_division", division,
        "expense_subject", "水电费用", "business_unit_type", "COMMERCIAL", "quote_ratio", "0",
        "manhour_rate", "0.406900", "uplift_ratio", "1.1", "rate_calculation_mode", "FINAL_QUOTE"));
    insert("lp_department_fund_rate", Map.of("rate_year", 2025, "business_division", division,
        "expense_subject", "水电费用", "business_unit_type", "COMMERCIAL", "quote_ratio", "0.5"));
    insert("lp_department_fund_rate", Map.of("rate_year", 2026, "business_division", division,
        "expense_subject", "水电费用", "business_unit_type", "HOUSEHOLD", "quote_ratio", "0.8"));
    var queries = List.of(new Department(2026, division, "水电费用", " COMMERCIAL "),
        new Department(2025, division, "水电费用", "COMMERCIAL"),
        new Department(2026, division, "水电费用", "HOUSEHOLD"),
        new Department(2026, division, "缺失科目", "COMMERCIAL"),
        new Department(2026, division, "水电费用", " "));
    var original = session.getMapper(DepartmentFundRateMapper.class);
    var expected = queries.stream().map(q -> original.selectList(lambdaQuery(DepartmentFundRate.class)
        .eq(DepartmentFundRate::getRateYear, q.year()).eq(DepartmentFundRate::getBusinessDivision, q.division())
        .eq(DepartmentFundRate::getExpenseSubject, q.subject())
        .eq(q.businessUnitType() != null, DepartmentFundRate::getBusinessUnitType, q.businessUnitType())
        .orderByDesc(DepartmentFundRate::getId).last("LIMIT 1"))).toList();
    var result = reader.readDepartment(queries);
    assertThat(result).usingRecursiveComparison().isEqualTo(expected);
    assertThat(result.get(0).getFirst().getQuoteRatio()).isZero();
    assertThat(result.get(0).getFirst().getRateCalculationMode()).isEqualTo("FINAL_QUOTE");
    assertThat(result.get(3)).isEmpty();
  }

  @Test void manufactureRetainsEveryMatchLevelAndDoesNotChooseAProductsPriority() {
    String key = code();
    var queries = new ArrayList<Manufacture>();
    for (String level : List.of("MATERIAL_CODE", "MATERIAL_MODEL", "DIVISION_PRODUCT_NAME", "DIVISION_CATEGORY_PREFIX", "DIVISION")) {
      insert("lp_manufacture_rate", Map.of("rate_year", 2026, "match_level", level,
          "match_key", key, "business_unit_type", "COMMERCIAL", "fee_rate", "0.123456789"));
      queries.add(new Manufacture(2026, level, key, "COMMERCIAL"));
    }
    queries.add(new Manufacture(2026, "DIVISION", key, "HOUSEHOLD"));
    queries.add(new Manufacture(2025, "DIVISION", key, "COMMERCIAL"));
    var original = session.getMapper(ManufactureRateMapper.class);
    var expected = queries.stream().map(q -> original.selectList(lambdaQuery(ManufactureRate.class)
        .eq(ManufactureRate::getRateYear, q.year()).eq(ManufactureRate::getMatchLevel, q.matchLevel())
        .eq(ManufactureRate::getMatchKey, q.matchKey()).eq(ManufactureRate::getBusinessUnitType, q.businessUnitType())
        .orderByDesc(ManufactureRate::getId).last("LIMIT 1"))).toList();
    assertThat(reader.readManufacture(queries)).usingRecursiveComparison().isEqualTo(expected);
    assertThat(expected.subList(0, 5)).allSatisfy(rows -> assertThat(rows.getFirst().getFeeRate()).isEqualByComparingTo("0.123456789"));
    assertThat(expected.subList(5, 7)).allSatisfy(rows -> assertThat(rows).isEmpty());
  }

  @Test void threeExpenseSharesCandidateFactsWhilePreservingEachDepartmentAndOffice() {
    String category = code();
    for (String office : List.of("华东事务所-直销", "华东事务所-代销", "")) {
      insert("lp_three_expense_rate", Map.of("period_year", 2026, "product_category", category,
          "product_line", "板换", "business_unit_type", "COMMERCIAL", "applicant_office", office,
          "applicant_department", office.isEmpty() ? "板换原锦直销" : "营销部", "management_expense_rate", "0.01",
          "sales_expense_rate", "0.02", "finance_expense_rate", "0.03", "oem_expense_rate", "0.99"));
    }
    var query = new ThreeExpense(2026, category, "板换", "COMMERCIAL");
    var original = session.getMapper(ThreeExpenseRateMapper.class).selectList(lambdaQuery(ThreeExpenseRate.class)
        .eq(ThreeExpenseRate::getPeriodYear, 2026).eq(ThreeExpenseRate::getProductCategory, category)
        .eq(ThreeExpenseRate::getProductLine, "板换").eq(ThreeExpenseRate::getBusinessUnitType, "COMMERCIAL")
        .orderByDesc(ThreeExpenseRate::getId));
    int before = statements();
    var result = reader.readThreeExpense(Collections.nCopies(64, query));
    assertThat(statements() - before).isEqualTo(1);
    assertThat(result).allSatisfy(rows -> assertThat(rows).usingRecursiveComparison().isEqualTo(original));
    result.get(0).getFirst().setManagementExpenseRate(BigDecimal.TEN);
    assertThat(result.get(1)).usingRecursiveComparison().isEqualTo(original);
    assertThat(reader.readThreeExpense(List.of(new ThreeExpense(2026, category, "其他线", "COMMERCIAL"))).getFirst()).isEmpty();
  }

  @Test void propertyAndPropertyRulesPreserveRawValuesAndSeparateBusinessYears() {
    String code = code(), attr = code();
    insert("lp_product_property", Map.of("property_year", 2025, "product_code", code,
        "business_unit_type", "COMMERCIAL", "product_attr", "旧属性"));
    insert("lp_product_property", Map.of("property_year", 2026, "product_code", code,
        "business_unit_type", "COMMERCIAL", "product_attr", attr));
    insert("lp_product_property_rule", Map.of("property_year", 2026, "product_attr", attr,
        "business_unit_type", "COMMERCIAL", "uplift_rate", "0.05"));
    var queries = List.of(new Property(2026, code, "COMMERCIAL"), new Property(2025, code, "COMMERCIAL"),
        new Property(2026, code, "HOUSEHOLD"), new Property(null, code, null));
    var mapper = session.getMapper(ProductPropertyMapper.class);
    var expected = queries.stream().map(q -> mapper.selectList(lambdaQuery(ProductProperty.class)
        .eq(q.year() != null, ProductProperty::getPropertyYear, q.year())
        .eq(q.businessUnitType() != null, ProductProperty::getBusinessUnitType, q.businessUnitType())
        .eq(ProductProperty::getProductCode, q.productCode()).orderByDesc(ProductProperty::getId).last("LIMIT 1"))).toList();
    assertThat(reader.readProperty(queries)).usingRecursiveComparison().isEqualTo(expected);
    var rule = reader.readPropertyRule(List.of(new PropertyRule(2026, attr, "COMMERCIAL"))).getFirst();
    assertThat(rule).usingRecursiveComparison().isEqualTo(session.getMapper(ProductPropertyRuleMapper.class)
        .selectList(lambdaQuery(ProductPropertyRule.class).eq(ProductPropertyRule::getPropertyYear, 2026)
            .eq(ProductPropertyRule::getProductAttr, attr).eq(ProductPropertyRule::getBusinessUnitType, "COMMERCIAL")));
    assertThat(rule.getFirst().getUpliftRate()).isEqualByComparingTo("0.05");
    assertThat(expected.getFirst().getFirst().getCoefficient()).isNull();
  }

  @Test void qualityLossKeepsAmbiguousAndInvalidPublicRowsForOriginalValidation() {
    String bare = code();
    insert("lp_quality_loss_rate", Map.of("rate_year", 2026, "bare_product_code", bare,
        "business_unit_type", "COMMERCIAL", "loss_rate", "0.02"));
    insert("lp_quality_loss_rate", Map.of("rate_year", 2026, "bare_product_code", bare,
        "business_unit_type", "COMMERCIAL", "loss_rate", "-0.01"));
    var rows = reader.readQualityLoss(List.of(new QualityLoss(2026, bare, "COMMERCIAL"),
        new QualityLoss(2025, bare, "COMMERCIAL"))).getFirst();
    assertThat(rows).hasSize(2);
    assertThat(rows).usingRecursiveComparison().isEqualTo(session.getMapper(QualityLossRateMapper.class)
        .selectList(lambdaQuery(QualityLossRate.class).eq(QualityLossRate::getRateYear, 2026)
            .eq(QualityLossRate::getBareProductCode, bare).eq(QualityLossRate::getBusinessUnitType, "COMMERCIAL")
            .orderByAsc(QualityLossRate::getId)));
    assertThat(rows.get(1).getLossRate()).isNegative();
  }

  @Test void dimensionMappingUsesEnabledPriorityThenLatestIdWithAllContextDimensions() {
    String value = code();
    for (int priority : List.of(8, 1, 1)) {
      insert("lp_three_expense_dimension_mapping", Map.of("dimension_type", "DEPARTMENT", "source_system", "OA",
          "source_value", value, "business_unit_type", "COMMERCIAL", "enabled", 1, "priority", priority,
          "standard_value", "目标" + priority));
    }
    insert("lp_three_expense_dimension_mapping", Map.of("dimension_type", "DEPARTMENT", "source_system", "OA",
        "source_value", value, "business_unit_type", "COMMERCIAL", "enabled", 0, "priority", 0));
    var query = new Dimension("DEPARTMENT", "OA", value, "COMMERCIAL");
    var original = session.getMapper(ThreeExpenseDimensionMappingMapper.class).selectList(lambdaQuery(ThreeExpenseDimensionMapping.class)
        .eq(ThreeExpenseDimensionMapping::getDimensionType, "DEPARTMENT").eq(ThreeExpenseDimensionMapping::getSourceSystem, "OA")
        .eq(ThreeExpenseDimensionMapping::getSourceValue, value).eq(ThreeExpenseDimensionMapping::getBusinessUnitType, "COMMERCIAL")
        .eq(ThreeExpenseDimensionMapping::getEnabled, 1).orderByAsc(ThreeExpenseDimensionMapping::getPriority)
        .orderByDesc(ThreeExpenseDimensionMapping::getId).last("LIMIT 1"));
    assertThat(reader.readDimension(List.of(query)).getFirst()).usingRecursiveComparison().isEqualTo(original);
    assertThat(original.getFirst().getPriority()).isEqualTo(1);
    assertThat(reader.readDimension(List.of(new Dimension("DEPARTMENT", "U9", value, "COMMERCIAL"))).getFirst()).isEmpty();
  }

  @Test void effectiveRuleKeepsTenantPriorityDatesDisabledAndNullConfigurationBehavior() {
    String code = code();
    insert("lp_cost_business_rule", Map.of("rule_code", code, "business_unit_type", "*", "enabled", 1,
        "effective_from", "2026-01", "decimal_value", "1.05"));
    insert("lp_cost_business_rule", Map.of("rule_code", code, "business_unit_type", "COMMERCIAL", "enabled", 1,
        "effective_from", "2026-10", "effective_to", "2026-11", "decimal_value", "1.07"));
    insert("lp_cost_business_rule", Map.of("rule_code", code, "business_unit_type", "COMMERCIAL", "enabled", 0,
        "effective_from", "2026-11", "decimal_value", "9"));
    var queries = List.of(new Rule(code, "2026-09", "COMMERCIAL"), new Rule(code, "2026-10", "COMMERCIAL"),
        new Rule(code, "2026-12", "COMMERCIAL"), new Rule(code, "2026-10", "HOUSEHOLD"),
        new Rule(code, "2025-01", "COMMERCIAL"));
    var result = reader.readRule(queries);
    for (int i = 0; i < queries.size(); i++) {
      var q = queries.get(i);
      var expected = session.getMapper(CostBusinessRuleMapper.class).selectEffectiveDecimal(q.ruleCode(), q.pricingMonth(), q.businessUnitType());
      assertThat(result.get(i).isEmpty() ? null : result.get(i).getFirst().decimalValue()).isEqualTo(expected);
    }
    insert("lp_cost_business_rule", Map.of("rule_code", code, "business_unit_type", "COMMERCIAL", "enabled", 1, "effective_from", "2026-11"));
    var nullRule = reader.readRule(List.of(new Rule(code, "2026-11", "COMMERCIAL"))).getFirst();
    assertThat(nullRule).hasSize(1); assertThat(nullRule.getFirst().decimalValue()).isNull();
  }

  @Test void boundedChunksReadActualProductsOnlyAndNewCallsSeeCorrections() {
    var queries = new ArrayList<OtherExpense>();
    for (int i = 0; i < 129; i++) {
      String code = code();
      insert("lp_other_expense_rate", Map.of("material_code", code, "expense_type", "已配置费用", "expense_amount", "0.123456789"));
      queries.add(new OtherExpense(code));
    }
    queries.add(queries.getFirst()); queries.add(new OtherExpense(code()));
    var before = jdbc.queryForList("SELECT * FROM lp_other_expense_rate ORDER BY id");
    int initial = statements();
    var result = reader.readOtherExpense(queries);
    assertThat(statements() - initial).isEqualTo(3);
    for (int i = 0; i < queries.size(); i++) {
      var expected = session.getMapper(OtherExpenseRateMapper.class).selectList(lambdaQuery(OtherExpenseRate.class)
          .eq(OtherExpenseRate::getMaterialCode, queries.get(i).productCode()).orderByAsc(OtherExpenseRate::getId));
      assertThat(result.get(i)).usingRecursiveComparison().isEqualTo(expected);
    }
    assertThat(result.getLast()).isEmpty();
    assertThat(jdbc.queryForList("SELECT * FROM lp_other_expense_rate ORDER BY id")).isEqualTo(before);
    result.getFirst().getFirst().setExpenseAmount(BigDecimal.TEN);
    assertThat(result.get(129).getFirst().getExpenseAmount()).isEqualByComparingTo("0.123456789");
    jdbc.update("UPDATE lp_other_expense_rate SET expense_amount=7 WHERE material_code=?", queries.getFirst().productCode());
    assertThat(reader.readOtherExpense(List.of(queries.getFirst())).getFirst().getFirst().getExpenseAmount()).isEqualByComparingTo("7");
    initial = statements();
    assertThat(reader.readOtherExpense(List.of())).isEmpty();
    assertThatThrownBy(() -> reader.readOtherExpense(Collections.nCopies(4097, queries.getFirst()))).isInstanceOf(IllegalArgumentException.class);
    assertThat(statements()).isEqualTo(initial);
    System.out.println("REPRICE_FEE_SQL distinct=130 consumers=131 chunks=3");
  }

  private static int statements() { return CostRunManufacturingSourcesIntegrationTest.statements.get(); }
  private static String code() { return "FEE-" + UUID.randomUUID(); }
  static void insert(String table, Map<String, ?> values) {
    var keys = new ArrayList<>(values.keySet());
    jdbc.update("INSERT INTO " + table + "(" + String.join(",", keys) + ") VALUES("
        + String.join(",", Collections.nCopies(keys.size(), "?")) + ")", keys.stream().map(values::get).toArray());
  }

  // Deliberately synthetic read fixture: no claim of migration/constraint acceptance is made here.
  private static void createSyntheticTable(Class<?> entity) {
    var columns = new ArrayList<String>();
    for (var field : entity.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      var annotation = field.getAnnotation(TableField.class);
      if (annotation != null && !annotation.exist()) continue;
      String name = annotation != null && !annotation.value().isBlank() ? annotation.value()
          : field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
      String type = field.getName().equals("id") ? "BIGINT PRIMARY KEY AUTO_INCREMENT"
          : field.getType() == BigDecimal.class ? "DECIMAL(24,12)"
          : field.getType() == Integer.class ? "INT" : field.getType() == Long.class ? "BIGINT"
          : field.getType() == LocalDateTime.class ? "DATETIME" : "VARCHAR(120)";
      columns.add("`" + name + "` " + type);
    }
    jdbc.execute("CREATE TABLE " + entity.getAnnotation(TableName.class).value() + "(" + String.join(",", columns) + ")");
  }
}

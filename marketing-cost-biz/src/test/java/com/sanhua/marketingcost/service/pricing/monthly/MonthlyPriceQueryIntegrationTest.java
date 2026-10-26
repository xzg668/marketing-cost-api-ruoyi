package com.sanhua.marketingcost.service.pricing.monthly;

import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyCostVersionIntegrationTest.*;
import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceQuery.*;
import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceQueryMapper;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.PreparationBinding;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.Ordinary;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;

@Tag("integration")
class MonthlyPriceQueryIntegrationTest {
  static MonthlyPriceQueryService query;
  static com.baomidou.mybatisplus.core.MybatisConfiguration config;

  @BeforeAll
  static void setup() throws Exception {
    database();
    new ResourceDatabasePopulator(
            new ClassPathResource("monthly-pricing/jjb-scope-schema.sql"),
            new ClassPathResource("db/V345__monthly_price_reference_lookup_index.sql"))
        .execute(jdbc.getDataSource());
    jdbc.update(
        "INSERT INTO oa_form(id,oa_no,business_unit_type) VALUES(1,'MPP05-LINKED','COMMERCIAL')");
    jdbc.update(
        "INSERT INTO oa_form_item(id,oa_form_id,material_no,business_unit_type,deleted)"
            + " VALUES(1,1,'TOP','COMMERCIAL',0),(2,1,'TOP2','COMMERCIAL',0)");
    config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.addMapper(MonthlyPriceQueryMapper.class);
    var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
    factory.setDataSource(jdbc.getDataSource());
    factory.setConfiguration(config);
    query =
        new MonthlyPriceQueryService(
            new org.mybatis.spring.SqlSessionTemplate(factory.getObject())
                .getMapper(MonthlyPriceQueryMapper.class),
            codec);
  }

  @AfterAll
  static void shutdown() {
    stop();
  }

  static Filter filter(String month, String code, String org, int page, int size) {
    return new Filter(month, code, org, null, null, null, null, null, null, page, size);
  }

  static long householdId(Fixture f) {
    return f.plan().selections().values().stream()
        .filter(s -> s.fact().householdRate() != null)
        .findFirst()
        .orElseThrow()
        .monthlyPriceId();
  }

  @Test
  void pageFiltersCountsOrderingAndSharedOrganizationsUseOnlyTwoQueries() throws Exception {
    var f = fixture("2", "100000", false);
    saveCompleted(f);
    String month = f.version().getPricingMonth();
    prepared.set(0);
    var one = query.list("COMMERCIAL", filter(month, null, null, 1, 1));
    assertThat(prepared.get()).isEqualTo(2);
    var two = query.list("COMMERCIAL", filter(month, null, null, 2, 1));
    assertThat(one.total()).isEqualTo(2);
    assertThat(two.rows()).hasSize(1);
    assertThat(one.rows().getFirst().id()).isNotEqualTo(two.rows().getFirst().id());
    assertThat(query.list("HOUSEHOLD", filter(month, null, null, 1, 20)).total()).isZero();
    assertThat(query.list("COMMERCIAL", filter(month, null, "220", 1, 20)).rows())
        .hasSize(1)
        .allMatch(r -> r.pricingType().equals("HOUSEHOLD_INQUIRY"));
    var exact =
        query.list("COMMERCIAL", filter(month, one.rows().getFirst().materialCode(), null, 1, 20));
    assertThat(exact.rows()).hasSize(1);
    assertThat(
            query
                .list(
                    "COMMERCIAL",
                    filter(
                        month,
                        one.rows().getFirst().materialCode().toUpperCase(Locale.ROOT),
                        null,
                        1,
                        20))
                .rows())
        .isEmpty();
    assertThat(query.list("COMMERCIAL", filter(month, null, null, 3, 1)).rows()).isEmpty();
    var typed =
        new Filter(month, null, null, "NORMAL", "SPEC", "件", "CNY", "EXCLUDED", null, 1, 20);
    assertThat(query.list("COMMERCIAL", typed).rows())
        .hasSize(1)
        .allMatch(r -> r.householdRate() == null);
    assertThat(
            query
                .list(
                    "COMMERCIAL",
                    new Filter(month, null, null, null, null, null, null, null, "S1", 1, 20))
                .rows())
        .isEmpty();
  }

  @Test
  void savedSnapshotRemainsReadableAfterSourcesChangeWithoutWritesOrFinancialLeakage()
      throws Exception {
    var f = fixture("2", "100000", false);
    saveCompleted(f);
    long id = householdId(f);
    var before = jdbc.queryForList("SELECT * FROM lp_material_monthly_price ORDER BY id");
    var detail = query.detail("COMMERCIAL", id);
    household(detail.price().materialCode(), "77.000000");
    assertThat(query.detail("COMMERCIAL", id)).isEqualTo(detail);
    assertThat(detail.price().price()).isEqualTo("20.9853");
    assertThat(detail.price().householdRate()).isEqualTo("0.085");
    assertThat(detail.savedEvidence().toString())
        .doesNotContain("financialReference", "fileKey", "cells");
    for (int i = 0; i < 3; i++) {
      query.list("COMMERCIAL", filter(f.version().getPricingMonth(), null, null, 1, 20));
      query.references("COMMERCIAL", id, 1, 20);
    }
    assertThat(jdbc.queryForList("SELECT * FROM lp_material_monthly_price ORDER BY id"))
        .isEqualTo(before);
  }

  @Test
  void reverseReferencesAreDistinctAcrossProductsAndKeepTheirOwnSavedAmounts() throws Exception {
    var f = fixture("2", "100000", false);
    saveCompleted(f);
    var v =
        versionService.createRunning(
            "MPP05-LINKED",
            2L,
            "TOP2",
            f.version().getPricingMonth(),
            f.version().getPricingMonth(),
            null,
            "COMMERCIAL");
    v.setOaPricePrepareNo("MPP11-OA-" + v.getId());
    v.setFinancePricePrepareNo("MPP11-FIN-" + v.getId());
    versionMapper.updateById(v);
    var bindings = new LinkedHashMap<String, PreparationBinding>();
    for (var use : f.plan().uses()) {
      var selected = f.plan().selections().get(use.request());
      long financial =
          prepare(
              v,
              use.request(),
              v.getFinancePricePrepareNo(),
              use.path(),
              use.cumulativeQuantity(),
              selected.fact().price());
      long customer =
          prepare(
              v,
              use.request(),
              v.getOaPricePrepareNo(),
              use.path(),
              use.cumulativeQuantity(),
              selected.fact().price());
      for (long id : List.of(financial, customer)) {
        var input = prepareMapper.selectById(id);
        input.setTopProductCode("TOP2");
        prepareMapper.updateById(input);
      }
      bindings.put(use.path(), new PreparationBinding(financial, customer));
    }
    var o = f.ordinary();
    var second =
        new Ordinary(
            o.materials(),
            o.nonBomMaterial(),
            o.directLabor(),
            o.indirectLabor(),
            o.lossRate(),
            o.manufactureRate(),
            o.coefficient(),
            o.managementRate(),
            d(".3"),
            o.financeRate(),
            o.otherIndependentFees());
    costing.calculateAndSave("COMMERCIAL", v.getId(), f.plan(), second, null, f.copper(), bindings);
    finishSaved(v.getId());
    prepared.set(0);
    var refs = query.references("COMMERCIAL", householdId(f), 1, 1);
    assertThat(prepared.get()).isEqualTo(3);
    assertThat(refs.total()).isEqualTo(2);
    assertThat(refs.rows().getFirst().productCode()).isEqualTo("TOP2");
    assertThat(refs.rows().getFirst().consumerOrganizations()).isEqualTo("210");
    var older = query.references("COMMERCIAL", householdId(f), 2, 1).rows().getFirst();
    assertThat(older.productCode()).isEqualTo("TOP");
    assertThat(older.totalCost()).isNotEqualTo(refs.rows().getFirst().totalCost());
  }

  @Test
  void identityScopeAndInvalidPaginationFailClosed() throws Exception {
    var f = fixture("1", "100000", false);
    saveCompleted(f);
    long id = householdId(f);
    assertThatThrownBy(() -> query.detail("HOUSEHOLD", id))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> query.references("HOUSEHOLD", id, 1, 20))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> query.detail(null, id)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> query.detail("COMMERCIAL", Long.MAX_VALUE))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> query.references("COMMERCIAL", id, 0, 20))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void baseMaterialReferenceSurvivesAnAggregatedParentWithoutADirectMonthlyForeignKey()
      throws Exception {
    var f = fixture("1", "100000", false);
    var o = f.ordinary();
    var original = o.materials().getFirst();
    var aggregate =
        new com.sanhua.marketingcost.service.costing.MonthlyProductCost.MaterialLine(
            "root",
            "SELF_MADE_PARENT",
            original.quantity(),
            original.monthlyPriceIds(),
            original.financeAmount(),
            original.customerAmount(),
            null);
    var explanation =
        new com.sanhua.marketingcost.service.costing.MonthlyProductCost.MaterialLine(
            original.path(),
            original.materialCode(),
            original.quantity(),
            original.monthlyPriceIds(),
            original.financeAmount(),
            original.customerAmount(),
            "root");
    var input =
        new Ordinary(
            List.of(aggregate, explanation),
            o.nonBomMaterial(),
            o.directLabor(),
            o.indirectLabor(),
            o.lossRate(),
            o.manufactureRate(),
            o.coefficient(),
            o.managementRate(),
            o.salesRate(),
            o.financeRate(),
            o.otherIndependentFees());
    var normal =
        f.plan().uses().stream()
            .filter(u -> u.path().equals(original.path()))
            .findFirst()
            .orElseThrow();
    var plan =
        new MonthlyAdoptionPlan(
            Map.of(normal.request(), f.plan().selections().get(normal.request())),
            List.of(normal),
            Set.of());
    var parent =
        request(
            "SELF_MADE_PARENT",
            "COMMERCIAL",
            f.version().getPricingMonth(),
            "210",
            NonCuConditions.empty());
    long fin =
        prepare(
            f.version(),
            parent,
            f.version().getFinancePricePrepareNo(),
            "root",
            original.quantity(),
            original.financeAmount());
    long customer =
        prepare(
            f.version(),
            parent,
            f.version().getOaPricePrepareNo(),
            "root",
            original.quantity(),
            original.customerAmount());
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var selected = plan.selections().get(normal.request());
    var pair = MonthlyScenarioTestSupport.create(null).calculate(plan.selections(), f.copper())
        .get(selected.monthlyPriceId());
    var captured = MonthlyPreparationPrice.of(new QuotationPriceSession.Value(normal.request(),
        selected, pair.financialPrice(), pair.customerPrice()), normal.path(), normal.cumulativeQuantity());
    for (long id : List.of(fin, customer)) {
      var prepared = prepareMapper.selectById(id);
      prepared.setResultRefType("PACKAGE_PRICE");
      var snapshot = json.createObjectNode();
      snapshot.put("calculatorVersion", "PACKAGE_PRICE_V1");
      snapshot.put("materialCode", parent.family().materialCode());
      snapshot.put("businessUnitType", "COMMERCIAL"); snapshot.put("priceOrgCode", "210");
      snapshot.put("periodMonth", f.version().getPricingMonth());
      snapshot.put("sourceRawHierarchyId", 1); snapshot.put("sourcePath", "root");
      snapshot.put("parentBaseQty", 1);
      var component = snapshot.putArray("components").addObject();
      component.put("sourceHierarchyId", 2); component.put("sourcePath", normal.path());
      component.put("materialCode", normal.request().family().materialCode());
      component.put("priceSource", MonthlyPreparationPrice.KIND); component.put("priceType", "LINKED");
      component.put("quantity", 1); component.put("parentBaseQty", 1);
      component.put("unitPrice", prepared.getUnitPrice()); component.put("amount", prepared.getUnitPrice());
      component.set("inputs", json.readTree(codec.encode(captured)));
      prepared.setInputSnapshotJson(json.writeValueAsString(snapshot));
      prepared.setInputSnapshotJson(new MonthlyPreparationEvidence(codec).attach(prepared,
          MonthlyPreparationInputs.capture(plan, "root")));
      prepareMapper.updateById(prepared);
    }
    var bindings = Map.of("root", new PreparationBinding(fin, customer));
    costing.calculateAndSave(
        "COMMERCIAL", f.version().getId(), plan, input, null, f.copper(), bindings);
    finishSaved(f.version().getId());
    long monthlyId = original.monthlyPriceIds().getFirst();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND"
                    + " monthly_price_id=?",
                Integer.class,
                f.version().getId(),
                monthlyId))
        .isZero();
    assertThat(query.references("COMMERCIAL", monthlyId, 1, 20).rows())
        .extracting(Reference::versionId)
        .containsExactly(f.version().getId());
  }

  @Test
  void failedOrUnownedVersionsCannotAppearInReverseReferences() throws Exception {
    var f = fixture("1", "100000", false);
    saveCompleted(f);
    long id = householdId(f);
    jdbc.update(
        "UPDATE lp_quote_cost_run_version SET status='FAILED' WHERE id=?", f.version().getId());
    assertThat(query.references("COMMERCIAL", id, 1, 20).rows()).isEmpty();
    jdbc.update(
        "UPDATE lp_quote_cost_run_version SET status='STALE',oa_form_item_id=999 WHERE id=?",
        f.version().getId());
    assertThat(query.references("COMMERCIAL", id, 1, 20).rows()).isEmpty();
  }

  @Test
  void actualBoundQueriesUseLedgerAndMonthIndexes() throws Exception {
    var f = fixture("1", "100000", false);
    saveCompleted(f);
    for (String method : List.of("count", "list", "referenceCount", "references")) {
      Map<String, Object> params =
          new HashMap<>(
              Map.of(
                  "bu",
                  "COMMERCIAL",
                  "month",
                  f.version().getPricingMonth(),
                  "id",
                  householdId(f),
                  "size",
                  20,
                  "offset",
                  0L,
                  "q",
                  filter(f.version().getPricingMonth(), null, null, 1, 20)));
      var statement =
          config.getMappedStatement(MonthlyPriceQueryMapper.class.getName() + "." + method);
      var sql = statement.getBoundSql(params);
      var meta = config.newMetaObject(params);
      var values =
          sql.getParameterMappings().stream().map(p -> meta.getValue(p.getProperty())).toArray();
      var explain = jdbc.queryForList("EXPLAIN FORMAT=TRADITIONAL " + sql.getSql(), values);
      String key =
          method.startsWith("reference")
              ? "idx_quote_cost_monthly_lookup"
              : "idx_monthly_price_query";
      assertThat(explain).anyMatch(row -> key.equals(row.get("key")));
    }
  }
}

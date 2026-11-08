package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.sanhua.marketingcost.service.reprice.CostRunFeeSourcesIntegrationTest.insert;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.service.impl.*;
import java.math.BigDecimal;
import java.time.Year;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;

/** Actual original fee algorithm consumes the prepared database facts; no cost/approval writes. */
@Tag("integration")
class CostRunPublicInputPreparationIntegrationTest {
  static SqlSessionTemplate sql;
  static CostRunCostItemServiceImpl costing;
  static CostRunPublicInputPreparation preparation;
  static EffectiveTechnicalDataQueryService technical;
  static OaFormMapper forms;
  static OaFormItemMapper items;
  static int year;

  @BeforeAll static void start() throws Exception {
    CostRunFeeSourcesIntegrationTest.start(); sql = CostRunFeeSourcesIntegrationTest.session;
    year = Year.now(com.sanhua.marketingcost.util.CostPricingPeriodUtils.BUSINESS_ZONE).getValue();
    var cache = new CostRunCacheLookupServiceImpl(mapper(ManufactureRateMapper.class), mapper(ThreeExpenseRateMapper.class),
        mapper(DepartmentFundRateMapper.class), mapper(OtherExpenseRateMapper.class), mapper(ProductPropertyMapper.class),
        mapper(ProductPropertyRuleMapper.class), mapper(ThreeExpenseDimensionMappingMapper.class));
    forms = mock(OaFormMapper.class); items = mock(OaFormItemMapper.class);
    costing = new CostRunCostItemServiceImpl(mock(CostRunCostItemMapper.class), forms, items,
        CostRunManufacturingSourcesIntegrationTest.salary, mapper(DepartmentFundRateMapper.class),
        CostRunManufacturingSourcesIntegrationTest.auxiliary, mock(CostRunPartItemMapper.class),
        mapper(QualityLossRateMapper.class), mapper(ManufactureRateMapper.class), mapper(ThreeExpenseRateMapper.class),
        mapper(OtherExpenseRateMapper.class), mock(MaterialMasterMapper.class), mapper(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class), cache,
        new CostBusinessRuleProviderImpl(mapper(CostBusinessRuleMapper.class), unit -> year + "-10"));
    technical = mock(EffectiveTechnicalDataQueryService.class);
    preparation = new CostRunPublicInputPreparation(costing, CostRunFeeSourcesIntegrationTest.reader,
        CostRunManufacturingSourcesIntegrationTest.reader, technical,
        new NetLossRateQuery(mapper(MaterialMasterRawMapper.class), mapper(QualityLossRateMapper.class)), unit -> year + "-10");
  }
  @AfterAll static void stop() { CostRunFeeSourcesIntegrationTest.stop(); }
  @BeforeEach void resetProvider() { reset(technical); clearInvocations(forms, items); }

  @Test void differentCustomersKeepTheirOwnFeesWhileOriginalAlgorithmReadsNoLivePublicSources() {
    var fixture = fixture(); var requests = requests(fixture, 16);
    int before = statements();
    var original = requests.stream().map(r -> calculate(r.context())).toList();
    int serialSql = statements() - before;
    var databaseBefore = sourceRows();
    before = statements();
    var prepared = preparation.prepare(requests);
    int batchSql = statements() - before;
    before = statements();
    var actual = new ArrayList<List<CostRunCostItemDto>>();
    for (int i = 0; i < requests.size(); i++) {
      var context = requests.get(i).context(); context.setPreparedInputs(prepared.get(i));
      actual.add(calculate(context));
    }
    assertThat(statements() - before).as("prepared original fee calculation must not reread sources").isZero();
    assertThat(actual).usingRecursiveComparison().isEqualTo(original);
    assertThat(amount(actual.get(0), "TOTAL")).isNotNull().isPositive();
    assertThat(amount(actual.get(0), "MGMT_EXP")).isNotEqualByComparingTo(amount(actual.get(1), "MGMT_EXP"));
    assertThat(batchSql).isPositive().isLessThan(serialSql / 4);
    assertThat(sourceRows()).isEqualTo(databaseBefore);
    for (var r : requests) verify(technical).resolve(r.context().getOaFormItemId(), year + "-10");
    verifyNoInteractions(forms, items);
    System.out.println("REPRICE_PUBLIC_INPUT_SQL serial=" + serialSql + " prepared=" + batchSql + " calculation=0 consumers=16");
  }

  @Test void sourceSnapshotIsStableButANewPreparationSeesPublicCorrections() {
    var fixture = fixture(); var request = requests(fixture, 1).getFirst();
    var original = calculate(request.context());
    var prepared = preparation.prepare(List.of(request)).getFirst();
    CostRunFeeSourcesIntegrationTest.jdbc.update("UPDATE cms_cost_source_effective SET amount_yuan=9 WHERE parent_code=? AND source_type='SALARY_DIRECT'", fixture.product());
    request.context().setPreparedInputs(prepared);
    assertThat(calculate(request.context())).usingRecursiveComparison().isEqualTo(original);
    request.context().setPreparedInputs(null);
    var refreshed = preparation.prepare(List.of(request)).getFirst(); request.context().setPreparedInputs(refreshed);
    assertThat(amount(calculate(request.context()), "DIRECT_LABOR")).isEqualByComparingTo("9");
    var changed = prepared.fees().require(new CostRunFeeQuery.Material(fixture.product(), "COMMERCIAL"), MaterialMasterRaw.class);
    changed.getFirst().setProductionDivision("caller-mutated");
    assertThat(prepared.fees().require(new CostRunFeeQuery.Material(fixture.product(), "COMMERCIAL"), MaterialMasterRaw.class)
        .getFirst().getProductionDivision()).isEqualTo(fixture.division());
  }

  @Test void anotherConsumerOrChangedSelectedOaIsRejectedBeforeAnySourceRead() {
    var fixture = fixture(); var requests = requests(fixture, 2);
    var prepared = preparation.prepare(requests);
    requests.get(1).context().setPreparedInputs(prepared.getFirst());
    int before = statements();
    assertThatThrownBy(() -> calculate(requests.get(1).context())).hasMessageContaining("重新批量解析");
    assertThat(statements()).isEqualTo(before);
    var context = requests.getFirst().context(); context.setPreparedInputs(prepared.getFirst());
    var form = context.getQuotationInput().form(); form.setApplicantDept("another-department");
    context.setQuotationInput(new CostRunQuotationInput(form, context.getQuotationInput().item()));
    assertThatThrownBy(() -> calculate(context)).hasMessageContaining("重新批量解析");
    assertThat(statements()).isEqualTo(before);
  }

  @Test void freshRetrievalTimeIsAllowedButApprovalRevisionOrBatchChangeRequiresRepreparation() {
    var request = requests(fixture(), 1).getFirst(); var context = request.context();
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var approved = new EffectiveTechnicalDataInput(1L, 2L, 1, year + "-10", "APPROVED", "revision-one",
        java.time.LocalDateTime.of(year, 10, 1, 0, 0), false, false, false, null, null, null,
        List.of(), List.of(), List.of(), null, null);
    when(technical.resolve(context.getOaFormItemId(), year + "-10")).thenReturn(approved);
    var prepared = preparation.prepare(List.of(request)).getFirst(); context.setPreparedInputs(prepared);
    var original = calculate(context);
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(approved);
    node.put("retrievedAt", year + "-10-02T00:00:00");
    context.setEffectiveTechnicalData(json.convertValue(node, EffectiveTechnicalDataInput.class));
    assertThat(calculate(context)).usingRecursiveComparison().isEqualTo(original);
    int before = statements();
    node.put("versionId", 3L); context.setEffectiveTechnicalData(json.convertValue(node, EffectiveTechnicalDataInput.class));
    assertThatThrownBy(() -> calculate(context)).hasMessageContaining("重新批量解析");
    context.setEffectiveTechnicalData(approved); context.setRepriceNo("MRP1130-another");
    assertThatThrownBy(() -> calculate(context)).hasMessageContaining("重新批量解析");
    assertThat(statements()).isEqualTo(before);
  }

  @Test void approvalFailureIsNotReplacedByAnotherCustomersPublicInputs() {
    var fixture = fixture(); var requests = requests(fixture, 2);
    when(technical.resolve(requests.get(1).context().getOaFormItemId(), year + "-10"))
        .thenThrow(new IllegalStateException("本客户补录未审批"));
    int before = statements();
    assertThatThrownBy(() -> preparation.prepare(requests)).hasMessageContaining("本客户补录未审批");
    assertThat(statements()).isEqualTo(before);
    assertThat(requests).allSatisfy(r -> assertThat(r.context().getPreparedInputs()).isNull());
  }

  @Test void selectedBomScopeIsAppliedIndividuallyBeforePublicSourcesAreShared() {
    var requests = requests(fixture(), 2);
    var activeItem = new ThreadLocal<Long>();
    when(technical.resolve(anyLong(), anyString())).thenAnswer(invocation -> {
      assertThat(activeItem.get()).as("approval must read this consumer's selected BOM")
          .isEqualTo(invocation.getArgument(0, Long.class));
      return null;
    });
    var prepared = preparation.prepare(requests, (request, query) -> {
      assertThat(activeItem.get()).isNull();
      activeItem.set(request.context().getOaFormItemId());
      try { return query.get(); } finally { activeItem.remove(); }
    });
    assertThat(prepared).hasSize(2);
    assertThat(activeItem.get()).isNull();
    for (var request : requests) verify(technical).resolve(request.context().getOaFormItemId(), year + "-10");
    doThrow(new IllegalStateException("this customer's approval is incomplete"))
        .when(technical).resolve(requests.getLast().context().getOaFormItemId(), year + "-10");
    assertThatThrownBy(() -> preparation.prepare(requests, (request, query) -> {
      activeItem.set(request.context().getOaFormItemId());
      try { return query.get(); } finally { activeItem.remove(); }
    })).hasMessageContaining("approval is incomplete");
    assertThat(activeItem.get()).isNull();
  }

  @Test void emptyAndOversizedBatchesDoNotReadOrResolveApproval() {
    int before = statements();
    assertThat(preparation.prepare(List.of())).isEmpty();
    var form = new OaForm(); form.setId(1L); form.setOaNo("BOUND"); form.setBusinessUnitType("COMMERCIAL");
    var item = new OaFormItem(); item.setId(2L); item.setOaFormId(1L); item.setMaterialNo("P");
    var context = CostRunContext.quote("BOUND", 2L, "P", null, null, "COMMERCIAL", year + "-10", "K");
    context.setQuotationInput(new CostRunQuotationInput(form, item)); context.setMaterialOrganizationCode("COMMERCIAL");
    var request = new CostRunPublicInputPreparation.Request(context, Set.of("P"));
    assertThatThrownBy(() -> preparation.prepare(Collections.nCopies(65, request))).isInstanceOf(IllegalArgumentException.class);
    assertThat(statements()).isEqualTo(before); verifyNoInteractions(technical);
  }

  private record Fixture(String product, String division) {}
  private static Fixture fixture() {
    String product = "BATCH-" + UUID.randomUUID(), division = "DIV-" + UUID.randomUUID();
    insert("lp_material_master_raw", Map.of("material_code", product, "organization_code", "COMMERCIAL", "active_flag", 1,
        "main_category_code", "11", "production_division", division, "material_model", "M1", "material_name", "测试成品", "product_attr", product));
    insert("lp_product_property_rule", Map.of("property_year", year, "product_attr", product, "business_unit_type", "COMMERCIAL", "uplift_rate", "0.05"));
    insert("lp_quality_loss_rate", Map.of("rate_year", year, "bare_product_code", product, "business_unit_type", "COMMERCIAL", "loss_rate", "0.01"));
    insert("lp_manufacture_rate", Map.of("rate_year", year, "match_level", "DIVISION", "match_key", division, "business_unit_type", "COMMERCIAL", "fee_rate", "0.15"));
    for (String subject : List.of("大修费用", "工装零星费用", "水电费用")) insert("lp_department_fund_rate", Map.of(
        "rate_year", year, "business_division", division, "expense_subject", subject, "business_unit_type", "COMMERCIAL",
        "quote_ratio", "0.015432", "manhour_rate", "0.4069", "rate_calculation_mode", "FINAL_QUOTE"));
    for (int department = 0; department < 2; department++) insert("lp_three_expense_rate", Map.of("period_year", year,
        "product_category", "商用直销产品", "product_line", "国内产线", "business_unit_type", "COMMERCIAL",
        "applicant_department", product + department + "-直销", "management_expense_rate", department == 0 ? "0.02" : "0.03",
        "sales_expense_rate", "0.01", "finance_expense_rate", "0.01"));
    insert("lp_other_expense_rate", Map.of("material_code", product, "expense_type", "检测费", "expense_amount", "0.123456"));
    for (String type : List.of("SALARY_DIRECT", "SALARY_INDIRECT", "AUX_SUBJECT")) {
      insert("cms_cost_source_effective", Map.of("cost_year", year, "source_type", type, "parent_code", product,
          "period", year + "-09", "subject_code", type.equals("AUX_SUBJECT") ? "0201" : "0301", "subject_name", "测试辅料",
          "source_table", "fixture", "source_row_ids", "[1]", "amount_yuan", type.equals("SALARY_DIRECT") ? "4.123456" : "0.234567",
          "business_unit_type", "COMMERCIAL"));
    }
    return new Fixture(product, division);
  }
  private static List<CostRunPublicInputPreparation.Request> requests(Fixture fixture, int count) {
    var result = new ArrayList<CostRunPublicInputPreparation.Request>();
    for (int i = 0; i < count; i++) {
      var form = new OaForm(); form.setId(100L + i); form.setOaNo("OA-" + fixture.product() + i);
      form.setProcessCode("FI-SC-006"); form.setBusinessUnitType("COMMERCIAL"); form.setSourceBusinessDivision(fixture.division());
      form.setApplicantDept(fixture.product() + i % 2); form.setAccountingPeriodMonth(year + "-10"); form.setCustomer("C" + i);
      var item = new OaFormItem(); item.setId(1000L + i); item.setOaFormId(form.getId()); item.setMaterialNo(fixture.product());
      item.setBusinessUnitType("COMMERCIAL"); item.setSunlModel("M1");
      var context = CostRunContext.monthlyReprice(year + "-10", "MRP1130-batch", "COMMERCIAL", form.getOaNo(), item.getId(),
          fixture.product(), null, form.getCustomer(), "object-" + i);
      context.setMaterialOrganizationCode("COMMERCIAL"); context.setPriceOrgCode("210");
      context.setQuotationInput(new CostRunQuotationInput(form, item));
      result.add(new CostRunPublicInputPreparation.Request(context, Set.of(fixture.product())));
    }
    return result;
  }
  private static List<CostRunCostItemDto> calculate(CostRunContext context) {
    return costing.listByMaterialCodes(context.getOaNo(), context.getProductCode(), Set.of(context.getProductCode()), context, List.of(), false, p -> {});
  }
  private static BigDecimal amount(List<CostRunCostItemDto> rows, String code) {
    return rows.stream().filter(r -> code.equals(r.getCostCode())).findFirst().orElseThrow().getAmount();
  }
  private static int statements() { return CostRunManufacturingSourcesIntegrationTest.statements.get(); }
  private static <T> T mapper(Class<T> type) { return sql.getMapper(type); }
  private static Map<String, List<Map<String, Object>>> sourceRows() {
    var result = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table : List.of("cms_cost_source_effective", "lp_material_master_raw", "lp_department_fund_rate", "lp_manufacture_rate",
        "lp_three_expense_rate", "lp_other_expense_rate", "lp_product_property", "lp_product_property_rule", "lp_quality_loss_rate", "lp_cost_business_rule")) {
      result.put(table, CostRunFeeSourcesIntegrationTest.jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    }
    return result;
  }
}

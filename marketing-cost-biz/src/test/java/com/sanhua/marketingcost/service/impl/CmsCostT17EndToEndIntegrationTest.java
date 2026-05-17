package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.CmsCostImportRequest;
import com.sanhua.marketingcost.dto.CmsCostImportResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.dto.CmsPlanCostExcelRow;
import com.sanhua.marketingcost.dto.CmsProductSubjectCostExcelRow;
import com.sanhua.marketingcost.dto.CmsSubjectSettingExcelRow;
import com.sanhua.marketingcost.dto.CmsWorkshopLaborExcelRow;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsCostImportService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("T17 CMS 成本来源端到端回归")
class CmsCostT17EndToEndIntegrationTest extends BomMapperTestBase {
  private static final Path PLAN_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/产品计划成本汇总-空值-2026-05-13-09-43.xlsx");
  private static final Path WORKSHOP_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/产品车间料工费汇总_商用导出20260512150458.xlsx");
  private static final Path SUBJECT_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/产品科目成本汇总_商用导出20260512150308.xlsx");
  private static final Path SUBJECT_SETTING_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/科目设置导出20260514103958.xlsx");
  private static final String PRODUCT_CODE = "1079900000536";
  private static final String BUSINESS_UNIT_TYPE = "COMMERCIAL";
  private static final String IMPORTED_BY_PREFIX = "t17-e2e-";

  static {
    try {
      runScriptViaMysqlCli("/db/V53__material_master_raw_staging.sql", "V53_T17");
      runScriptViaMysqlCli("/db/V56__cost_run_cost_item_add_remark.sql", "V56_T17");
      runScriptViaMysqlCli("/db/V59__quote_ingest_schema.sql", "V59_T17");
      runScriptViaMysqlCli("/db/V64__cms_cost_source_schema.sql", "V64_T17");
      runScriptViaMysqlCli("/db/V66__cms_subject_setting_source.sql", "V66_T17");
    } catch (Exception e) {
      throw new IllegalStateException("T17 初始化迁移失败: " + e.getMessage(), e);
    }
  }

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  private final String importedBy = IMPORTED_BY_PREFIX + suffix;
  private final String refreshImportedBy = IMPORTED_BY_PREFIX + "refresh-" + suffix;
  private final String blockedParentCode = "T17_BLOCK_" + suffix;
  private final String blockedImportedBy = IMPORTED_BY_PREFIX + "blocked-" + suffix;
  private final String refSourceCode = "T17_REF_SRC_" + suffix;
  private final String refActualCode = "T17_REF_ACT_" + suffix;
  private final String refOaNo = "OA-T17-REF-" + suffix;
  private final String refImportedBy = IMPORTED_BY_PREFIX + "ref-" + suffix;

  @Autowired private CmsCostImportService importService;
  @Autowired private CmsSalaryCostSourceEffectiveService salaryEffectiveService;
  @Autowired private CmsAuxSubjectSourceEffectiveService auxEffectiveService;
  @Autowired private CmsCostSourceEffectiveMapper effectiveMapper;
  @Autowired private CmsCostSourceEffectiveLogMapper effectiveLogMapper;
  @Autowired private CostRunCostItemServiceImpl costItemService;

  @BeforeEach
  void setUp() throws Exception {
    cleanT17Rows();
  }

  @AfterEach
  void tearDown() throws Exception {
    cleanT17Rows();
  }

  @Test
  @DisplayName("真实样例 Excel：导入原始池、生成公共生效来源，并用公共来源进入核算辅料项")
  void importsRealSamplesAndGeneratesEffectiveSourcesForGoldenProduct() throws Exception {
    assumeTrue(
        Files.exists(PLAN_SAMPLE)
            && Files.exists(WORKSHOP_SAMPLE)
            && Files.exists(SUBJECT_SAMPLE)
            && Files.exists(SUBJECT_SETTING_SAMPLE));

    CmsCostImportResponse response = importSamples(importedBy);

    assertThat(response.getStatus()).isEqualTo("IMPORTED");
    assertThat(response.getPlanRowCount()).isPositive();
    assertThat(response.getWorkshopRowCount()).isPositive();
    assertThat(response.getSubjectRowCount()).isPositive();
    assertThat(response.getSalaryInsertCount()).isZero();
    assertThat(response.getAuxInsertCount()).isZero();
    assertThat(countSql("SELECT COUNT(*) FROM cms_plan_cost_raw WHERE import_batch_id = " + response.getImportBatchId()))
        .isEqualTo(response.getPlanRowCount());
    assertThat(countSql("SELECT COUNT(*) FROM cms_workshop_labor_raw WHERE import_batch_id = " + response.getImportBatchId()))
        .isEqualTo(response.getWorkshopRowCount());
    assertThat(countSql("SELECT COUNT(*) FROM cms_product_subject_cost_raw WHERE import_batch_id = " + response.getImportBatchId()))
        .isEqualTo(response.getSubjectRowCount());
    assertThat(countSql("SELECT COUNT(*) FROM cms_subject_setting_raw WHERE import_batch_id = " + response.getImportBatchId()))
        .isEqualTo(response.getSubjectSettingRowCount());

    var salaryResponse = salaryEffectiveService.generateDefaultSources(2026, importedBy, BUSINESS_UNIT_TYPE);
    var auxResponse = auxEffectiveService.generateDefaultSources(2026, importedBy, BUSINESS_UNIT_TYPE);
    assertThat(salaryResponse.getInsertedCount()).isPositive();
    assertThat(auxResponse.getInsertedCount()).isPositive();

    CmsCostSourceEffective direct = effective(PRODUCT_CODE, "SALARY_DIRECT", "0301");
    CmsCostSourceEffective indirect = effective(PRODUCT_CODE, "SALARY_INDIRECT", "0302");
    assertThat(direct.getPeriod()).startsWith("2026-");
    assertThat(direct.getSubjectName()).isEqualTo("直接人工工资");
    assertThat(direct.getAmountYuan()).isEqualByComparingTo("4.000000");
    assertThat(indirect.getPeriod()).startsWith("2026-");
    assertThat(indirect.getSubjectName()).isEqualTo("辅助人员工资");
    assertThat(indirect.getAmountYuan()).isEqualByComparingTo("0.220000");

    Map<String, BigDecimal> auxAmounts = effectiveAuxAmounts(PRODUCT_CODE);
    assertThat(auxAmounts).containsOnlyKeys("0201", "0202", "0205", "0208", "0212", "0216", "0217");
    assertThat(auxAmounts.get("0201")).isEqualByComparingTo("0.400000");
    assertThat(auxAmounts.get("0202")).isEqualByComparingTo("0.600000");
    assertThat(auxAmounts.get("0205")).isEqualByComparingTo("0.520000");
    assertThat(auxAmounts.get("0208")).isEqualByComparingTo("0.200000");
    assertThat(auxAmounts.get("0212")).isEqualByComparingTo("0.032700");
    assertThat(auxAmounts.get("0216")).isEqualByComparingTo("0.030000");
    assertThat(auxAmounts.get("0217")).isEqualByComparingTo("0.040000");
    assertThat(auxAmounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("1.822700");

    List<CostRunCostItemDto> cmsAuxItems =
        costItemService.buildAuxItems(Set.of(PRODUCT_CODE), 2026, BUSINESS_UNIT_TYPE).stream()
            .filter(item -> item.getCostCode() != null && item.getCostCode().startsWith("AUX_02"))
            .toList();
    assertThat(cmsAuxItems).extracting(CostRunCostItemDto::getCostCode)
        .containsExactly("AUX_0201", "AUX_0202", "AUX_0205", "AUX_0208", "AUX_0212", "AUX_0216", "AUX_0217");
    assertThat(cmsAuxItems).allSatisfy(item -> assertThat(item.getRate()).isNull());
    assertThat(cmsAuxItems.stream().map(CostRunCostItemDto::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("1.822700");
  }

  @Test
  @DisplayName("工资刷新：A 从 2026-01 刷到 2026-04，B/C 保持默认期间，并写刷新日志")
  void refreshesOnlySelectedSalarySourceAndKeepsOtherParentsUntouched() {
    String a = "T17_A_" + suffix;
    String b = "T17_B_" + suffix;
    String c = "T17_C_" + suffix;
    importService.importParsedRows(refreshRequest(a, b, c));
    salaryEffectiveService.generateDefaultSources(2026, refreshImportedBy, BUSINESS_UNIT_TYPE);

    assertThat(effective(a, "SALARY_DIRECT", "0301").getPeriod()).isEqualTo("2026-01");
    assertThat(effective(b, "SALARY_DIRECT", "0301").getPeriod()).isEqualTo("2026-01");
    assertThat(effective(c, "SALARY_DIRECT", "0301").getPeriod()).isEqualTo("2026-01");

    refreshSalary(a, "SALARY_DIRECT", "2026-04");
    refreshSalary(a, "SALARY_INDIRECT", "2026-04");

    assertThat(effective(a, "SALARY_DIRECT", "0301").getPeriod()).isEqualTo("2026-04");
    assertThat(effective(a, "SALARY_DIRECT", "0301").getAmountYuan()).isEqualByComparingTo("4.000000");
    assertThat(effective(a, "SALARY_INDIRECT", "0302").getPeriod()).isEqualTo("2026-04");
    assertThat(effective(a, "SALARY_INDIRECT", "0302").getAmountYuan()).isEqualByComparingTo("0.220000");
    assertThat(effective(b, "SALARY_DIRECT", "0301").getPeriod()).isEqualTo("2026-01");
    assertThat(effective(c, "SALARY_DIRECT", "0301").getPeriod()).isEqualTo("2026-01");

    List<CmsCostSourceEffectiveLog> refreshLogs =
        effectiveLogMapper.selectList(
            new QueryWrapper<CmsCostSourceEffectiveLog>()
                .eq("parent_code", a)
                .eq("action_type", "REFRESH")
                .orderByAsc("source_type"));
    assertThat(refreshLogs).hasSize(2);
    assertThat(refreshLogs).extracting(CmsCostSourceEffectiveLog::getOldPeriod)
        .containsOnly("2026-01");
    assertThat(refreshLogs).extracting(CmsCostSourceEffectiveLog::getNewPeriod)
        .containsOnly("2026-04");
  }

  @Test
  @DisplayName("未审批工时/辅料：工资和辅料均不生成公共生效来源，并写 BLOCKED 日志")
  void blocksUnapprovedLaborAndAuxSubjectsFromEffectiveSources() {
    importService.importParsedRows(blockedRequest());

    salaryEffectiveService.generateDefaultSources(2026, blockedImportedBy, BUSINESS_UNIT_TYPE);
    auxEffectiveService.generateDefaultSources(2026, blockedImportedBy, BUSINESS_UNIT_TYPE);

    assertThat(effectiveCount(blockedParentCode)).isZero();
    List<CmsCostSourceEffectiveLog> logs =
        effectiveLogMapper.selectList(
            new QueryWrapper<CmsCostSourceEffectiveLog>()
                .eq("parent_code", blockedParentCode)
                .eq("action_type", "BLOCKED")
                .orderByAsc("source_type"));
    assertThat(logs).extracting(CmsCostSourceEffectiveLog::getSourceType)
        .contains("SALARY_DIRECT", "SALARY_INDIRECT", "AUX_SUBJECT");
    assertThat(logs).allSatisfy(log -> assertThat(log.getMessage()).containsAnyOf("工时", "辅料"));
  }

  @Test
  @DisplayName("OA 参考料号：报价员只选参考料号，核算按报价年份读取参考料号公共生效来源")
  void costCalculationReadsCurrentEffectiveSourcesFromReferenceMaterial() throws Exception {
    importService.importParsedRows(referenceRequest());
    salaryEffectiveService.generateDefaultSources(2026, refImportedBy, BUSINESS_UNIT_TYPE);
    auxEffectiveService.generateDefaultSources(2026, refImportedBy, BUSINESS_UNIT_TYPE);
    seedReferenceOaAndLegacyRows();

    List<CostRunCostItemDto> items =
        costItemService.listByMaterialCodes(refOaNo, refActualCode, Set.of(refActualCode), ignored -> {});

    assertThat(item(items, "DIRECT_LABOR").getAmount()).isEqualByComparingTo("4.000000");
    assertThat(item(items, "INDIRECT_LABOR").getAmount()).isEqualByComparingTo("0.220000");
    CostRunCostItemDto aux = item(items, "AUX_0201");
    assertThat(aux.getCostName()).isEqualTo("辅助焊料类");
    assertThat(aux.getRate()).isNull();
    assertThat(aux.getAmount()).isEqualByComparingTo("0.400000");
  }

  private CmsCostImportResponse importSamples(String user) throws Exception {
    try (InputStream plan = Files.newInputStream(PLAN_SAMPLE);
        InputStream workshop = Files.newInputStream(WORKSHOP_SAMPLE);
        InputStream subject = Files.newInputStream(SUBJECT_SAMPLE);
        InputStream subjectSetting = Files.newInputStream(SUBJECT_SETTING_SAMPLE)) {
      return importService.importExcel(
          plan,
          PLAN_SAMPLE.getFileName().toString(),
          workshop,
          WORKSHOP_SAMPLE.getFileName().toString(),
          subject,
          SUBJECT_SAMPLE.getFileName().toString(),
          subjectSetting,
          SUBJECT_SETTING_SAMPLE.getFileName().toString(),
          false,
          user,
          BUSINESS_UNIT_TYPE);
    }
  }

  private CmsCostImportRequest refreshRequest(String a, String b, String c) {
    CmsCostImportRequest request = baseRequest(refreshImportedBy, "T17-刷新.xlsx");
    request.setPlanRows(List.of(plan(a, "2026-01", null), plan(a, "2026-04", null),
        plan(b, "2026-01", null), plan(c, "2026-01", null)));
    request.setWorkshopRows(List.of(workshop(a, "2026-01", "100.000000"), workshop(a, "2026-04", "400.000000"),
        workshop(b, "2026-01", "200.000000"), workshop(c, "2026-01", "300.000000")));
    request.setSubjectRows(List.of(indirect(a, "2026-01", "11.000000"), indirect(a, "2026-04", "22.000000"),
        indirect(b, "2026-01", "12.000000"), indirect(c, "2026-01", "13.000000")));
    return request;
  }

  private CmsCostImportRequest blockedRequest() {
    CmsCostImportRequest request = baseRequest(blockedImportedBy, "T17-阻断.xlsx");
    request.setPlanRows(List.of(plan(blockedParentCode, "2026-01", "工时;辅料")));
    request.setWorkshopRows(List.of(workshop(blockedParentCode, "2026-01", "400.000000")));
    request.setSubjectRows(List.of(indirect(blockedParentCode, "2026-01", "22.000000"),
        aux(blockedParentCode, "2026-01", "0201", "辅助焊料类", "40.000000")));
    return request;
  }

  private CmsCostImportRequest referenceRequest() {
    CmsCostImportRequest request = baseRequest(refImportedBy, "T17-参考料号.xlsx");
    request.setPlanRows(List.of(plan(refSourceCode, "2026-01", null)));
    request.setWorkshopRows(List.of(workshop(refSourceCode, "2026-01", "400.000000")));
    request.setSubjectRows(List.of(indirect(refSourceCode, "2026-01", "22.000000"),
        aux(refSourceCode, "2026-01", "0201", "辅助焊料类", "40.000000")));
    return request;
  }

  private CmsCostImportRequest baseRequest(String user, String fileName) {
    CmsCostImportRequest request = new CmsCostImportRequest();
    request.setPlanFileName(fileName);
    request.setWorkshopFileName(fileName);
    request.setSubjectFileName(fileName);
    request.setSubjectSettingFileName(fileName);
    request.setImportedBy(user);
    request.setBusinessUnitType(BUSINESS_UNIT_TYPE);
    request.setSubjectSettingRows(List.of(subjectSetting("03", "工资", "0302", "辅助人员工资"),
        subjectSetting("02", "辅助材料", "0201", "辅助焊料类"),
        subjectSetting("02", "辅助材料", "0215", "包装辅料")));
    return request;
  }

  private CmsSubjectSettingExcelRow subjectSetting(
      String firstCode, String firstName, String secondCode, String secondName) {
    CmsSubjectSettingExcelRow row = new CmsSubjectSettingExcelRow();
    row.setRowNo(4);
    row.setFirstSubjectCode(firstCode);
    row.setFirstSubjectName(firstName);
    row.setSecondSubjectCode(secondCode);
    row.setSecondSubjectName(secondName);
    return row;
  }

  private CmsPlanCostExcelRow plan(String parentCode, String period, String unapprovedItems) {
    CmsPlanCostExcelRow row = new CmsPlanCostExcelRow();
    row.setRowNo(2);
    row.setParentCode(parentCode);
    row.setParentName("T17测试成品");
    row.setEffectiveDate(LocalDate.parse(period + "-01"));
    row.setEffectivePeriod(period);
    row.setUnapprovedItems(unapprovedItems);
    return row;
  }

  private CmsWorkshopLaborExcelRow workshop(String parentCode, String period, String workingCostCent) {
    CmsWorkshopLaborExcelRow row = new CmsWorkshopLaborExcelRow();
    row.setRowNo(4);
    row.setPeriod(period);
    row.setFirstUnitName("商用部品事业部");
    row.setParentCode(parentCode);
    row.setParentName("T17测试成品");
    row.setWorkingCostCent(new BigDecimal(workingCostCent));
    row.setWorkingCostYuan(new BigDecimal(workingCostCent).divide(new BigDecimal("100")));
    row.setSourceRowId(parentCode + "|" + period + "|direct");
    return row;
  }

  private CmsProductSubjectCostExcelRow indirect(String parentCode, String period, String materialPriceCent) {
    CmsProductSubjectCostExcelRow row = subjectBase(parentCode, period, materialPriceCent);
    row.setFirstSubjectName("工资");
    row.setSecondSubjectCode("0302");
    row.setSecondSubjectName("辅助人员工资");
    row.setSourceRowId(parentCode + "|" + period + "|indirect");
    return row;
  }

  private CmsProductSubjectCostExcelRow aux(
      String parentCode, String period, String code, String name, String materialPriceCent) {
    CmsProductSubjectCostExcelRow row = subjectBase(parentCode, period, materialPriceCent);
    row.setFirstSubjectName("辅助材料");
    row.setSecondSubjectCode(code);
    row.setSecondSubjectName(name);
    row.setSourceRowId(parentCode + "|" + period + "|aux|" + code);
    return row;
  }

  private CmsProductSubjectCostExcelRow subjectBase(
      String parentCode, String period, String materialPriceCent) {
    CmsProductSubjectCostExcelRow row = new CmsProductSubjectCostExcelRow();
    row.setRowNo(4);
    row.setPeriod(period);
    row.setFirstUnitName("商用部品事业部");
    row.setParentCode(parentCode);
    row.setParentName("T17测试成品");
    row.setMaterialPrice(new BigDecimal(materialPriceCent));
    row.setMaterialPriceYuan(new BigDecimal(materialPriceCent).divide(new BigDecimal("100")));
    return row;
  }

  private void refreshSalary(String parentCode, String sourceType, String newPeriod) {
    CmsEffectiveSourceRefreshRequest request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(2026);
    request.setParentCode(parentCode);
    request.setSourceType(sourceType);
    request.setNewPeriod(newPeriod);
    request.setRefreshReason("T17刷新验证");
    salaryEffectiveService.refreshSource(request, refreshImportedBy, BUSINESS_UNIT_TYPE);
  }

  private CmsCostSourceEffective effective(String parentCode, String sourceType, String subjectCode) {
    CmsCostSourceEffective effective =
        effectiveMapper.selectOne(
            new QueryWrapper<CmsCostSourceEffective>()
                .eq("cost_year", 2026)
                .eq("parent_code", parentCode)
                .eq("source_type", sourceType)
                .eq("subject_code", subjectCode == null ? "" : subjectCode)
                .eq("business_unit_type", BUSINESS_UNIT_TYPE)
                .last("LIMIT 1"));
    assertThat(effective).as(parentCode + " " + sourceType + " " + subjectCode).isNotNull();
    return effective;
  }

  private int effectiveCount(String parentCode) {
    return effectiveMapper.selectCount(
        new QueryWrapper<CmsCostSourceEffective>()
            .eq("cost_year", 2026)
            .eq("parent_code", parentCode)
            .eq("business_unit_type", BUSINESS_UNIT_TYPE)).intValue();
  }

  private Map<String, BigDecimal> effectiveAuxAmounts(String parentCode) {
    List<CmsCostSourceEffective> rows =
        effectiveMapper.selectList(
            new QueryWrapper<CmsCostSourceEffective>()
                .eq("cost_year", 2026)
                .eq("parent_code", parentCode)
                .eq("source_type", "AUX_SUBJECT")
                .eq("business_unit_type", BUSINESS_UNIT_TYPE)
                .orderByAsc("subject_code"));
    return rows.stream()
        .collect(
            Collectors.toMap(
                CmsCostSourceEffective::getSubjectCode,
                CmsCostSourceEffective::getAmountYuan,
                (left, right) -> left,
                LinkedHashMap::new));
  }

  private CostRunCostItemDto item(List<CostRunCostItemDto> items, String code) {
    return items.stream()
        .filter(item -> code.equals(item.getCostCode()))
        .findFirst()
        .orElseThrow();
  }

  private void seedReferenceOaAndLegacyRows() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "INSERT INTO oa_form (oa_no, form_type, apply_date, customer, business_unit_type, created_at, updated_at, deleted) "
              + "VALUES ('" + refOaNo + "', 'T17参考料号', '2026-05-13', 'T17客户', '"
              + BUSINESS_UNIT_TYPE + "', NOW(), NOW(), 0)");
      stmt.executeUpdate(
          "INSERT INTO oa_form_item (oa_form_id, seq, product_name, material_no, valid_date, business_unit_type, created_at, updated_at, deleted) "
              + "SELECT id, 1, 'T17实际报价料号', '" + refActualCode + "', '2026-05-13', '"
              + BUSINESS_UNIT_TYPE + "', NOW(), NOW(), 0 FROM oa_form WHERE oa_no = '" + refOaNo + "'");
      stmt.executeUpdate(
          "INSERT INTO lp_salary_cost "
              + "(material_code, product_name, ref_material_code, direct_labor_cost, indirect_labor_cost, source, business_unit, business_unit_type, created_at, updated_at) "
              + "VALUES ('" + refActualCode + "', 'T17实际报价料号', '" + refSourceCode
              + "', 99.000000, 88.000000, 'manual', '商用部品事业部', '"
              + BUSINESS_UNIT_TYPE + "', NOW(), NOW())");
      stmt.executeUpdate(
          "INSERT INTO lp_aux_subject "
              + "(material_code, product_name, ref_material_code, aux_subject_code, aux_subject_name, unit_price, period, source, business_unit_type, created_at, updated_at) "
              + "VALUES ('" + refActualCode + "', 'T17实际报价料号', '" + refSourceCode
              + "', '0201', '旧复制辅料', 99.000000, '2026-01', 'manual', '"
              + BUSINESS_UNIT_TYPE + "', NOW(), NOW())");
    }
  }

  private void cleanT17Rows() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM lp_salary_cost WHERE material_code LIKE 'T17_%'");
      stmt.executeUpdate("DELETE FROM lp_aux_subject WHERE material_code LIKE 'T17_%'");
      stmt.executeUpdate(
          "DELETE FROM oa_form_item WHERE oa_form_id IN "
              + "(SELECT id FROM oa_form WHERE oa_no LIKE 'OA-T17-%')");
      stmt.executeUpdate("DELETE FROM oa_form WHERE oa_no LIKE 'OA-T17-%'");
      stmt.executeUpdate(
          "DELETE FROM cms_cost_source_effective_log WHERE operator LIKE '" + IMPORTED_BY_PREFIX
              + "%' OR parent_code LIKE 'T17_%' OR parent_code = '" + PRODUCT_CODE + "'");
      stmt.executeUpdate(
          "DELETE FROM cms_cost_source_effective WHERE confirmed_by LIKE '" + IMPORTED_BY_PREFIX
              + "%' OR parent_code LIKE 'T17_%' OR parent_code = '" + PRODUCT_CODE + "'");
      stmt.executeUpdate(
          "DELETE FROM cms_product_subject_cost_raw WHERE import_batch_id IN "
              + "(SELECT id FROM cms_cost_import_batch WHERE imported_by LIKE '" + IMPORTED_BY_PREFIX + "%')");
      stmt.executeUpdate(
          "DELETE FROM cms_subject_setting_raw WHERE import_batch_id IN "
              + "(SELECT id FROM cms_cost_import_batch WHERE imported_by LIKE '" + IMPORTED_BY_PREFIX + "%')");
      stmt.executeUpdate(
          "DELETE FROM cms_workshop_labor_raw WHERE import_batch_id IN "
              + "(SELECT id FROM cms_cost_import_batch WHERE imported_by LIKE '" + IMPORTED_BY_PREFIX + "%')");
      stmt.executeUpdate(
          "DELETE FROM cms_plan_cost_raw WHERE import_batch_id IN "
              + "(SELECT id FROM cms_cost_import_batch WHERE imported_by LIKE '" + IMPORTED_BY_PREFIX + "%')");
      stmt.executeUpdate(
          "DELETE FROM cms_cost_import_batch WHERE imported_by LIKE '" + IMPORTED_BY_PREFIX + "%'");
    }
  }

  private int countSql(String sql) {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void runScriptViaMysqlCli(String classpathResource, String logTag) throws Exception {
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource(classpathResource), "/tmp/" + logTag + ".sql");
    ExecResult result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
                + " " + MYSQL.getDatabaseName() + " < /tmp/" + logTag + ".sql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          logTag + " mysql CLI 执行失败: exit=" + result.getExitCode()
              + "\nstdout:\n" + result.getStdout()
              + "\nstderr:\n" + result.getStderr());
    }
  }
}

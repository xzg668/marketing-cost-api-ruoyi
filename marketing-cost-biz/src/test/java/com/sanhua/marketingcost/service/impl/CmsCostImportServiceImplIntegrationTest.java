package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.CmsCostImportRequest;
import com.sanhua.marketingcost.dto.CmsCostImportResponse;
import com.sanhua.marketingcost.dto.CmsPlanCostExcelRow;
import com.sanhua.marketingcost.dto.CmsProductSubjectCostExcelRow;
import com.sanhua.marketingcost.dto.CmsSubjectSettingExcelRow;
import com.sanhua.marketingcost.dto.CmsWorkshopLaborExcelRow;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.CmsCostImportService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("CMS 成本来源 T5 · 原始数据导入服务")
class CmsCostImportServiceImplIntegrationTest extends BomMapperTestBase {

  static {
    try {
      runV64ViaMysqlCli();
      runV66ViaMysqlCli();
    } catch (Exception e) {
      throw new IllegalStateException("CMS ImportServiceTest 初始化 V64 失败: " + e.getMessage(), e);
    }
  }

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private final String parentCode = "CMS_T5_PARENT_" + suffix;
  private final String importedBy = "cms-t5-" + suffix;
  private final String rollbackImportedBy = "cms-t5-rollback-" + suffix;

  @Autowired private CmsCostImportService importService;
  @Autowired private CmsCostImportBatchMapper batchMapper;
  @Autowired private CmsPlanCostRawMapper planMapper;
  @Autowired private CmsWorkshopLaborRawMapper workshopMapper;
  @Autowired private CmsProductSubjectCostRawMapper subjectMapper;
  @Autowired private CmsSubjectSettingRawMapper subjectSettingMapper;

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM cms_product_subject_cost_raw WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_subject_setting_raw WHERE import_batch_id IN (SELECT id FROM cms_cost_import_batch WHERE imported_by IN ('"
          + importedBy + "', '" + rollbackImportedBy + "'))");
      stmt.executeUpdate("DELETE FROM cms_workshop_labor_raw WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_plan_cost_raw WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_cost_source_effective_log WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_cost_source_effective WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate(
          "DELETE FROM cms_cost_import_batch WHERE imported_by IN ('"
              + importedBy + "', '" + rollbackImportedBy + "')");
    }
  }

  @Test
  @DisplayName("导入解析行：创建批次并保存三类 CMS 原始数据")
  void importParsedRowsPersistsBatchAndRawRows() {
    CmsCostImportRequest request = validRequest(importedBy);

    CmsCostImportResponse response = importService.importParsedRows(request);

    assertThat(response.getImportBatchId()).isNotNull();
    assertThat(response.getStatus()).isEqualTo("IMPORTED");
    assertThat(response.getPlanRowCount()).isEqualTo(1);
    assertThat(response.getWorkshopRowCount()).isEqualTo(1);
    assertThat(response.getSubjectRowCount()).isEqualTo(1);
    assertThat(response.getSubjectSettingRowCount()).isEqualTo(1);
    assertThat(response.getSalaryInsertCount()).isZero();
    assertThat(response.getAuxInsertCount()).isZero();

    CmsCostImportBatch batch = batchMapper.selectById(response.getImportBatchId());
    assertThat(batch.getImportType()).isEqualTo("EXCEL");
    assertThat(batch.getPlanFileName()).isEqualTo("产品计划成本汇总.xlsx");
    assertThat(batch.getWorkshopFileName()).isEqualTo("产品车间料工费汇总.xlsx");
    assertThat(batch.getSubjectFileName()).isEqualTo("产品科目成本汇总.xlsx");
    assertThat(batch.getSubjectSettingFileName()).isEqualTo("科目设置导出.xlsx");
    assertThat(batch.getImportedBy()).isEqualTo(importedBy);
    assertThat(batch.getBusinessUnitType()).isEqualTo("COMMERCIAL");

    List<CmsPlanCostRaw> plans =
        planMapper.selectList(new QueryWrapper<CmsPlanCostRaw>().eq("parent_code", parentCode));
    assertThat(plans).hasSize(1);
    assertThat(plans.get(0).getImportBatchId()).isEqualTo(batch.getId());
    assertThat(plans.get(0).getEffectiveDate()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(plans.get(0).getUnapprovedItems()).isEqualTo("工时");

    List<CmsWorkshopLaborRaw> workshops =
        workshopMapper.selectList(new QueryWrapper<CmsWorkshopLaborRaw>().eq("parent_code", parentCode));
    assertThat(workshops).hasSize(1);
    assertThat(workshops.get(0).getWorkingCostCent()).isEqualByComparingTo("200.000000");
    assertThat(workshops.get(0).getWorkingCostYuan()).isEqualByComparingTo("2.000000");
    assertThat(workshops.get(0).getMaterialPrice()).isEqualByComparingTo("3084.486211");

    List<CmsProductSubjectCostRaw> subjects =
        subjectMapper.selectList(
            new QueryWrapper<CmsProductSubjectCostRaw>().eq("parent_code", parentCode));
    assertThat(subjects).hasSize(1);
    assertThat(subjects.get(0).getFirstSubjectName()).isEqualTo("辅助材料");
    assertThat(subjects.get(0).getSecondSubjectName()).isEqualTo("辅助焊料类");
    assertThat(subjects.get(0).getMaterialPrice()).isEqualByComparingTo("22.000000");
    assertThat(subjects.get(0).getMaterialPriceYuan()).isEqualByComparingTo("0.220000");

    List<CmsSubjectSettingRaw> subjectSettings =
        subjectSettingMapper.selectList(
            new QueryWrapper<CmsSubjectSettingRaw>()
                .eq("import_batch_id", response.getImportBatchId())
                .eq("second_subject_code", "0201"));
    assertThat(subjectSettings).hasSize(1);
    assertThat(subjectSettings.get(0).getFirstSubjectName()).isEqualTo("辅助材料");
    assertThat(subjectSettings.get(0).getSecondSubjectName()).isEqualTo("辅助焊料类");

    assertThat(countSql("SELECT COUNT(*) FROM cms_cost_source_effective WHERE parent_code = '" + parentCode + "'"))
        .isZero();
    assertThat(countSql("SELECT COUNT(*) FROM cms_cost_source_effective_log WHERE parent_code = '" + parentCode + "'"))
        .isZero();
    assertThat(countSql("SELECT COUNT(*) FROM lp_salary_cost WHERE material_code = '" + parentCode + "'"))
        .isZero();
    assertThat(countSql("SELECT COUNT(*) FROM lp_aux_subject WHERE material_code = '" + parentCode + "'"))
        .isZero();
  }

  @Test
  @DisplayName("科目设置导出重复行：按一级/二级/三级科目编码联合去重")
  void importParsedRowsDeduplicatesSubjectSettingBySubjectCodes() {
    CmsCostImportRequest request = validRequest(importedBy);
    CmsSubjectSettingExcelRow duplicate = subjectSettingRow();
    duplicate.setRowNo(5);
    duplicate.setSecondSubjectName("辅助焊料类-重复行更新");
    request.setSubjectSettingRows(List.of(subjectSettingRow(), duplicate));

    CmsCostImportResponse response = importService.importParsedRows(request);

    assertThat(response.getSubjectSettingRowCount()).isEqualTo(2);
    List<CmsSubjectSettingRaw> subjectSettings =
        subjectSettingMapper.selectList(
            new QueryWrapper<CmsSubjectSettingRaw>()
                .eq("first_subject_code", "02")
                .eq("second_subject_code", "0201")
                .eq("third_subject_code", "020101"));
    assertThat(subjectSettings).hasSize(1);
    assertThat(subjectSettings.get(0).getImportBatchId()).isEqualTo(response.getImportBatchId());
    assertThat(subjectSettings.get(0).getRowNo()).isEqualTo(5);
    assertThat(subjectSettings.get(0).getSecondSubjectName()).isEqualTo("辅助焊料类-重复行更新");

    CmsCostImportRequest householdRequest = validRequest(rollbackImportedBy);
    householdRequest.setBusinessUnitType("HOUSEHOLD");
    CmsSubjectSettingExcelRow householdDuplicate = subjectSettingRow();
    householdDuplicate.setRowNo(6);
    householdDuplicate.setSecondSubjectName("辅助焊料类-跨业务单元更新");
    householdRequest.setSubjectSettingRows(List.of(householdDuplicate));
    CmsCostImportResponse householdResponse = importService.importParsedRows(householdRequest);

    subjectSettings =
        subjectSettingMapper.selectList(
            new QueryWrapper<CmsSubjectSettingRaw>()
                .eq("first_subject_code", "02")
                .eq("second_subject_code", "0201")
                .eq("third_subject_code", "020101"));
    assertThat(subjectSettings).hasSize(1);
    assertThat(subjectSettings.get(0).getImportBatchId()).isEqualTo(householdResponse.getImportBatchId());
    assertThat(subjectSettings.get(0).getRowNo()).isEqualTo(6);
    assertThat(subjectSettings.get(0).getSecondSubjectName()).isEqualTo("辅助焊料类-跨业务单元更新");
    assertThat(subjectSettings.get(0).getBusinessUnitType()).isEqualTo("HOUSEHOLD");
  }

  @Test
  @DisplayName("重复导入同一 CMS 原始行：raw 表按唯一键更新，不重复累计")
  void importParsedRowsUpsertsDuplicateRawRows() {
    CmsCostImportRequest first = validRequest(importedBy);
    CmsCostImportResponse firstResponse = importService.importParsedRows(first);

    CmsCostImportRequest second = validRequest(importedBy);
    second.getPlanRows().get(0).setUnapprovedItems("辅料");
    second.getWorkshopRows().get(0).setWorkingCostCent(new BigDecimal("400.000000"));
    second.getWorkshopRows().get(0).setWorkingCostYuan(new BigDecimal("4.000000"));
    second.getSubjectRows().get(0).setMaterialPrice(new BigDecimal("44.000000"));
    second.getSubjectRows().get(0).setMaterialPriceYuan(new BigDecimal("0.440000"));
    CmsCostImportResponse secondResponse = importService.importParsedRows(second);

    assertThat(secondResponse.getImportBatchId()).isNotEqualTo(firstResponse.getImportBatchId());
    assertThat(planMapper.selectCount(new QueryWrapper<CmsPlanCostRaw>().eq("parent_code", parentCode)))
        .isEqualTo(1L);
    assertThat(
            workshopMapper.selectCount(
                new QueryWrapper<CmsWorkshopLaborRaw>().eq("parent_code", parentCode)))
        .isEqualTo(1L);
    assertThat(
            subjectMapper.selectCount(
                new QueryWrapper<CmsProductSubjectCostRaw>().eq("parent_code", parentCode)))
        .isEqualTo(1L);

    CmsPlanCostRaw plan =
        planMapper.selectOne(new QueryWrapper<CmsPlanCostRaw>().eq("parent_code", parentCode).last("LIMIT 1"));
    CmsWorkshopLaborRaw workshop =
        workshopMapper.selectOne(
            new QueryWrapper<CmsWorkshopLaborRaw>().eq("parent_code", parentCode).last("LIMIT 1"));
    CmsProductSubjectCostRaw subject =
        subjectMapper.selectOne(
            new QueryWrapper<CmsProductSubjectCostRaw>().eq("parent_code", parentCode).last("LIMIT 1"));

    assertThat(plan.getImportBatchId()).isEqualTo(secondResponse.getImportBatchId());
    assertThat(plan.getUnapprovedItems()).isEqualTo("辅料");
    assertThat(workshop.getImportBatchId()).isEqualTo(secondResponse.getImportBatchId());
    assertThat(workshop.getWorkingCostCent()).isEqualByComparingTo("400.000000");
    assertThat(subject.getImportBatchId()).isEqualTo(secondResponse.getImportBatchId());
    assertThat(subject.getMaterialPrice()).isEqualByComparingTo("44.000000");
  }

  @Test
  @DisplayName("原始行写入失败时事务回滚：批次和已写入原始行均不保留")
  void importParsedRowsRollsBackWhenRawInsertFails() {
    CmsCostImportRequest request = validRequest(rollbackImportedBy);
    request.getWorkshopRows().get(0).setPeriod(null);

    assertThatThrownBy(() -> importService.importParsedRows(request))
        .isInstanceOf(RuntimeException.class);

    assertThat(
            batchMapper.selectCount(
                new QueryWrapper<CmsCostImportBatch>().eq("imported_by", rollbackImportedBy)))
        .isZero();
    assertThat(planMapper.selectCount(new QueryWrapper<CmsPlanCostRaw>().eq("parent_code", parentCode)))
        .isZero();
    assertThat(
            workshopMapper.selectCount(
                new QueryWrapper<CmsWorkshopLaborRaw>().eq("parent_code", parentCode)))
        .isZero();
    assertThat(
            subjectMapper.selectCount(
                new QueryWrapper<CmsProductSubjectCostRaw>().eq("parent_code", parentCode)))
        .isZero();
  }

  private CmsCostImportRequest validRequest(String importedByValue) {
    CmsCostImportRequest request = new CmsCostImportRequest();
    request.setPlanFileName("产品计划成本汇总.xlsx");
    request.setWorkshopFileName("产品车间料工费汇总.xlsx");
    request.setSubjectFileName("产品科目成本汇总.xlsx");
    request.setSubjectSettingFileName("科目设置导出.xlsx");
    request.setImportedBy(importedByValue);
    request.setBusinessUnitType("COMMERCIAL");
    request.setPlanRows(List.of(planRow()));
    request.setWorkshopRows(List.of(workshopRow()));
    request.setSubjectRows(List.of(subjectRow()));
    request.setSubjectSettingRows(List.of(subjectSettingRow()));
    return request;
  }

  private CmsPlanCostExcelRow planRow() {
    CmsPlanCostExcelRow row = new CmsPlanCostExcelRow();
    row.setRowNo(2);
    row.setFirstUnitCode("55");
    row.setFirstUnitName("商用部品事业部");
    row.setParentCode(parentCode);
    row.setParentName("四通换向阀阀体");
    row.setParentSpec("SHF-P35792-001");
    row.setParentType("SHF-35B-79-01(P)");
    row.setUnit("只");
    row.setWorkingHours(new BigDecimal("1.500000"));
    row.setEffectiveDate(LocalDate.of(2026, 5, 1));
    row.setBusinessStatus("finishStatus");
    row.setUnapprovedItems("工时");
    row.setDescription("测试说明");
    row.setOaNo("OA-T5");
    return row;
  }

  private CmsWorkshopLaborExcelRow workshopRow() {
    CmsWorkshopLaborExcelRow row = new CmsWorkshopLaborExcelRow();
    row.setRowNo(4);
    row.setPeriod("2026-01");
    row.setFirstUnitCode("59");
    row.setFirstUnitName("商用四通阀事业部");
    row.setParentCode(parentCode);
    row.setParentName("四通换向阀阀体");
    row.setParentSpec("SHF-P35792-001");
    row.setParentType("SHF-35B-79-01(P)");
    row.setLastUnitName("焊接车间");
    row.setLastUnitCode("590102-009");
    row.setWorkingHours(new BigDecimal("100.000000"));
    row.setFunding(new BigDecimal("20.000000"));
    row.setWorkingCostCent(new BigDecimal("200.000000"));
    row.setWorkingCostYuan(new BigDecimal("2.000000"));
    row.setBuildFlag("自算");
    row.setPath("path");
    row.setSourceRowId("raw-workshop-id");
    row.setSequenceNo("SEQ-WORKSHOP");
    row.setSequenceStatus("已完成");
    row.setMaterialPrice(new BigDecimal("3084.486211"));
    return row;
  }

  private CmsProductSubjectCostExcelRow subjectRow() {
    CmsProductSubjectCostExcelRow row = new CmsProductSubjectCostExcelRow();
    row.setRowNo(4);
    row.setPeriod("2026-04");
    row.setFirstUnitCode("55");
    row.setFirstUnitName("商用部品事业部");
    row.setParentCode(parentCode);
    row.setParentName("四通换向阀阀体");
    row.setParentSpec("SHF-P35792-001");
    row.setParentType("SHF-35B-79-01(P)");
    row.setLastSubjectCode("020101");
    row.setLastSubjectName("辅助焊料类");
    row.setLastSubjectLevel("二级");
    row.setMaterialPrice(new BigDecimal("22.000000"));
    row.setMaterialPriceYuan(new BigDecimal("0.220000"));
    row.setBuildFlag("自算");
    row.setPath("path");
    row.setFirstSubjectCode("02");
    row.setFirstSubjectName("辅助材料");
    row.setSecondSubjectCode("0201");
    row.setSecondSubjectName("辅助焊料类");
    row.setSourceRowId("raw-subject-id");
    row.setSequenceNo("SEQ-SUBJECT");
    row.setSequenceStatus("已完成");
    return row;
  }

  private CmsSubjectSettingExcelRow subjectSettingRow() {
    CmsSubjectSettingExcelRow row = new CmsSubjectSettingExcelRow();
    row.setRowNo(4);
    row.setFirstSubjectCode("02");
    row.setFirstSubjectName("辅助材料");
    row.setSecondSubjectCode("0201");
    row.setSecondSubjectName("辅助焊料类");
    row.setThirdSubjectCode("020101");
    row.setThirdSubjectName("焊料类");
    return row;
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

  private static void runV64ViaMysqlCli() throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V64__cms_cost_source_schema.sql"), "/tmp/V64_T5.sql");
    ExecResult result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
                + " " + MYSQL.getDatabaseName() + " < /tmp/V64_T5.sql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          "V64_T5 mysql CLI 执行失败: exit=" + result.getExitCode()
              + "\nstdout:\n" + result.getStdout()
              + "\nstderr:\n" + result.getStderr());
    }
  }

  private static void runV66ViaMysqlCli() throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V66__cms_subject_setting_source.sql"), "/tmp/V66_T5.sql");
    ExecResult result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
                + " " + MYSQL.getDatabaseName() + " < /tmp/V66_T5.sql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          "V66_T5 mysql CLI 执行失败: exit=" + result.getExitCode()
              + "\nstdout:\n" + result.getStdout()
              + "\nstderr:\n" + result.getStderr());
    }
  }
}

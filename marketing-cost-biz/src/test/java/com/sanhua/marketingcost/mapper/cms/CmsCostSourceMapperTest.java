package com.sanhua.marketingcost.mapper.cms;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("CMS 成本来源 Mapper · CRUD")
class CmsCostSourceMapperTest extends BomMapperTestBase {

  static {
    try {
      runScriptViaMysqlCli("/db/V64__cms_cost_source_schema.sql", "V64_T3");
      runScriptViaMysqlCli("/db/V66__cms_subject_setting_source.sql", "V66_T3");
      runScriptViaMysqlCli("/db/V67__cms_subject_setting_dedupe_key.sql", "V67_T3");
      runScriptViaMysqlCli("/db/V68__drop_cms_aux_subject_config.sql", "V68_T3");
    } catch (Exception e) {
      throw new IllegalStateException("CMS MapperTest 初始化 CMS 迁移失败: " + e.getMessage(), e);
    }
  }

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private final String batchNo = "CMS_T3_" + suffix;
  private final String parentCode = "CMS_PARENT_" + suffix;

  @Autowired private CmsCostImportBatchMapper batchMapper;
  @Autowired private CmsPlanCostRawMapper planMapper;
  @Autowired private CmsWorkshopLaborRawMapper workshopMapper;
  @Autowired private CmsProductSubjectCostRawMapper subjectMapper;
  @Autowired private CmsCostSourceEffectiveMapper effectiveMapper;
  @Autowired private CmsCostSourceEffectiveLogMapper effectiveLogMapper;

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM cms_cost_source_effective_log WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_cost_source_effective WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_product_subject_cost_raw WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_workshop_labor_raw WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_plan_cost_raw WHERE parent_code = '" + parentCode + "'");
      stmt.executeUpdate("DELETE FROM cms_cost_import_batch WHERE batch_no = '" + batchNo + "'");
    }
  }

  @Test
  @DisplayName("insert/select/update：CMS 原始池、公共生效来源和刷新日志映射正确")
  void cmsMappersSupportBasicCrud() {
    CmsCostImportBatch batch = newBatch();
    assertThat(batchMapper.insert(batch)).isEqualTo(1);
    assertThat(batch.getId()).isNotNull();

    CmsCostImportBatch loadedBatch = batchMapper.selectById(batch.getId());
    assertThat(loadedBatch.getBatchNo()).isEqualTo(batchNo);
    loadedBatch.setStatus("IMPORTED");
    loadedBatch.setErrorMessage("T3 update batch mapping");
    assertThat(batchMapper.updateById(loadedBatch)).isEqualTo(1);
    CmsCostImportBatch updatedBatch = batchMapper.selectById(batch.getId());
    assertThat(updatedBatch.getStatus()).isEqualTo("IMPORTED");
    assertThat(updatedBatch.getErrorMessage()).isEqualTo("T3 update batch mapping");

    CmsPlanCostRaw plan = newPlan(batch.getId());
    assertThat(planMapper.insert(plan)).isEqualTo(1);
    CmsPlanCostRaw loadedPlan = planMapper.selectById(plan.getId());
    assertThat(loadedPlan.getUnapprovedItems()).isEqualTo("工时");
    assertThat(loadedPlan.getEffectivePeriod()).isEqualTo("2026-01");
    assertThat(loadedPlan.getOaNo()).isEqualTo("OA-T3");
    loadedPlan.setUnapprovedItems("工时;辅料");
    loadedPlan.setBusinessStatus("已审批");
    assertThat(planMapper.updateById(loadedPlan)).isEqualTo(1);
    CmsPlanCostRaw updatedPlan = planMapper.selectById(plan.getId());
    assertThat(updatedPlan.getUnapprovedItems()).isEqualTo("工时;辅料");
    assertThat(updatedPlan.getBusinessStatus()).isEqualTo("已审批");

    CmsWorkshopLaborRaw workshop = newWorkshop(batch.getId());
    assertThat(workshopMapper.insert(workshop)).isEqualTo(1);
    CmsWorkshopLaborRaw loadedWorkshop = workshopMapper.selectById(workshop.getId());
    assertThat(loadedWorkshop.getWorkingCostCent()).isEqualByComparingTo("200.000000");
    assertThat(loadedWorkshop.getWorkingCostYuan()).isEqualByComparingTo("2.000000");
    assertThat(loadedWorkshop.getSourceRowId()).isEqualTo("WORKSHOP-T3");
    loadedWorkshop.setWorkingCostCent(new BigDecimal("300.000000"));
    loadedWorkshop.setWorkingCostYuan(new BigDecimal("3.000000"));
    assertThat(workshopMapper.updateById(loadedWorkshop)).isEqualTo(1);
    assertThat(workshopMapper.selectById(workshop.getId()).getWorkingCostYuan())
        .isEqualByComparingTo("3.000000");

    CmsProductSubjectCostRaw subject = newSubject(batch.getId());
    assertThat(subjectMapper.insert(subject)).isEqualTo(1);
    CmsProductSubjectCostRaw loadedSubject = subjectMapper.selectById(subject.getId());
    assertThat(loadedSubject.getMaterialPrice()).isEqualByComparingTo("22.000000");
    assertThat(loadedSubject.getMaterialPriceYuan()).isEqualByComparingTo("0.220000");
    assertThat(loadedSubject.getSourceRowId()).isEqualTo("SUBJECT-T3");
    loadedSubject.setMaterialPrice(new BigDecimal("33.000000"));
    loadedSubject.setMaterialPriceYuan(new BigDecimal("0.330000"));
    assertThat(subjectMapper.updateById(loadedSubject)).isEqualTo(1);
    assertThat(subjectMapper.selectById(subject.getId()).getMaterialPriceYuan())
        .isEqualByComparingTo("0.330000");

    CmsCostSourceEffective effective = newEffective();
    assertThat(effectiveMapper.insert(effective)).isEqualTo(1);
    CmsCostSourceEffective loadedEffective = effectiveMapper.selectById(effective.getId());
    assertThat(loadedEffective.getCostYear()).isEqualTo(2026);
    assertThat(loadedEffective.getSourceType()).isEqualTo("SALARY_DIRECT");
    assertThat(loadedEffective.getAmountYuan()).isEqualByComparingTo("2.000000");
    assertThat(loadedEffective.getSubjectCode()).isEmpty();
    loadedEffective.setPeriod("2026-04");
    loadedEffective.setAmountYuan(new BigDecimal("3.000000"));
    loadedEffective.setDefaultFlag(0);
    loadedEffective.setRefreshReason("T3 update effective source");
    assertThat(effectiveMapper.updateById(loadedEffective)).isEqualTo(1);
    CmsCostSourceEffective updatedEffective = effectiveMapper.selectById(effective.getId());
    assertThat(updatedEffective.getPeriod()).isEqualTo("2026-04");
    assertThat(updatedEffective.getAmountYuan()).isEqualByComparingTo("3.000000");
    assertThat(updatedEffective.getDefaultFlag()).isZero();

    CmsCostSourceEffectiveLog log = newEffectiveLog(effective.getId());
    assertThat(effectiveLogMapper.insert(log)).isEqualTo(1);
    CmsCostSourceEffectiveLog loadedLog = effectiveLogMapper.selectById(log.getId());
    assertThat(loadedLog.getActionType()).isEqualTo("DEFAULT");
    assertThat(loadedLog.getNewAmountYuan()).isEqualByComparingTo("2.000000");
    loadedLog.setActionType("REFRESH");
    loadedLog.setOldPeriod("2026-01");
    loadedLog.setNewPeriod("2026-04");
    loadedLog.setOldAmountYuan(new BigDecimal("2.000000"));
    loadedLog.setNewAmountYuan(new BigDecimal("3.000000"));
    assertThat(effectiveLogMapper.updateById(loadedLog)).isEqualTo(1);
    CmsCostSourceEffectiveLog updatedLog = effectiveLogMapper.selectById(log.getId());
    assertThat(updatedLog.getActionType()).isEqualTo("REFRESH");
    assertThat(updatedLog.getOldPeriod()).isEqualTo("2026-01");
    assertThat(updatedLog.getNewPeriod()).isEqualTo("2026-04");
  }

  private CmsCostImportBatch newBatch() {
    CmsCostImportBatch batch = new CmsCostImportBatch();
    batch.setBatchNo(batchNo);
    batch.setImportType("EXCEL");
    batch.setStatus("PENDING");
    batch.setPlanFileName("产品计划成本汇总.xlsx");
    batch.setWorkshopFileName("产品车间料工费汇总.xlsx");
    batch.setSubjectFileName("产品科目成本汇总.xlsx");
    batch.setPlanRowCount(1);
    batch.setWorkshopRowCount(1);
    batch.setSubjectRowCount(1);
    batch.setBusinessUnitType("COMMERCIAL");
    return batch;
  }

  private CmsPlanCostRaw newPlan(Long batchId) {
    CmsPlanCostRaw row = new CmsPlanCostRaw();
    row.setImportBatchId(batchId);
    row.setRowNo(2);
    row.setParentCode(parentCode);
    row.setParentName("测试成品");
    row.setWorkingHours(new BigDecimal("1.500000"));
    row.setEffectiveDate(LocalDate.of(2026, 1, 1));
    row.setEffectivePeriod("2026-01");
    row.setBusinessStatus("待审批");
    row.setUnapprovedItems("工时");
    row.setOaNo("OA-T3");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private CmsWorkshopLaborRaw newWorkshop(Long batchId) {
    CmsWorkshopLaborRaw row = new CmsWorkshopLaborRaw();
    row.setImportBatchId(batchId);
    row.setRowNo(4);
    row.setPeriod("2026-01");
    row.setParentCode(parentCode);
    row.setWorkingCostCent(new BigDecimal("200.000000"));
    row.setWorkingCostYuan(new BigDecimal("2.000000"));
    row.setSourceRowId("WORKSHOP-T3");
    row.setSequenceNo("WL-T3");
    row.setSequenceStatus("APPROVED");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private CmsProductSubjectCostRaw newSubject(Long batchId) {
    CmsProductSubjectCostRaw row = new CmsProductSubjectCostRaw();
    row.setImportBatchId(batchId);
    row.setRowNo(4);
    row.setPeriod("2026-01");
    row.setParentCode(parentCode);
    row.setMaterialPrice(new BigDecimal("22.000000"));
    row.setMaterialPriceYuan(new BigDecimal("0.220000"));
    row.setFirstSubjectName("工资");
    row.setSecondSubjectCode("0200");
    row.setSecondSubjectName("辅助人员工资");
    row.setSourceRowId("SUBJECT-T3");
    row.setSequenceNo("PS-T3");
    row.setSequenceStatus("APPROVED");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private CmsCostSourceEffective newEffective() {
    CmsCostSourceEffective row = new CmsCostSourceEffective();
    row.setCostYear(2026);
    row.setSourceType("SALARY_DIRECT");
    row.setParentCode(parentCode);
    row.setPeriod("2026-01");
    row.setSubjectCode("");
    row.setSourceTable("cms_workshop_labor_raw");
    row.setSourceRowIds("1,2");
    row.setAmountYuan(new BigDecimal("2.000000"));
    row.setDefaultFlag(1);
    row.setRefreshReason("年度默认来源");
    row.setConfirmedBy("tester");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private CmsCostSourceEffectiveLog newEffectiveLog(Long effectiveSourceId) {
    CmsCostSourceEffectiveLog row = new CmsCostSourceEffectiveLog();
    row.setEffectiveSourceId(effectiveSourceId);
    row.setCostYear(2026);
    row.setSourceType("SALARY_DIRECT");
    row.setParentCode(parentCode);
    row.setNewPeriod("2026-01");
    row.setSubjectCode("");
    row.setNewAmountYuan(new BigDecimal("2.000000"));
    row.setActionType("DEFAULT");
    row.setMessage("年度默认来源");
    row.setOperator("tester");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private static void runScriptViaMysqlCli(String classpathResource, String tag) throws Exception {
    String containerFile = "/tmp/" + tag + ".sql";
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource(classpathResource), containerFile);
    ExecResult result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
                + " " + MYSQL.getDatabaseName() + " < " + containerFile);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          tag + " mysql CLI 执行失败: exit=" + result.getExitCode()
              + "\nstdout:\n" + result.getStdout()
              + "\nstderr:\n" + result.getStderr());
    }
  }
}

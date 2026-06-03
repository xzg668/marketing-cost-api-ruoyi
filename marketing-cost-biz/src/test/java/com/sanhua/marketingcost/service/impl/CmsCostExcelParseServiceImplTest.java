package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sanhua.marketingcost.dto.CmsCostExcelParseResult;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefSourceRow;
import com.sanhua.marketingcost.dto.CmsPlanCostExcelRow;
import com.sanhua.marketingcost.dto.CmsProductSubjectCostExcelRow;
import com.sanhua.marketingcost.dto.CmsSubjectSettingExcelRow;
import com.sanhua.marketingcost.dto.CmsWorkshopLaborExcelRow;
import com.sanhua.marketingcost.util.CmsFieldNormalizeUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CmsCostExcelParseServiceImplTest {
  private static final Path PLAN_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/产品计划成本汇总-空值-2026-05-13-09-43.xlsx");
  private static final Path WORKSHOP_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/产品车间料工费汇总_商用导出20260512150458.xlsx");
  private static final Path WORKSHOP_REORDERED_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/demo4/cms/产品车间料工费汇总_商用导出20260529153518.xlsx");
  private static final Path SUBJECT_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/产品科目成本汇总_商用导出20260512150308.xlsx");
  private static final Path SUBJECT_SETTING_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/科目设置导出20260514103958.xlsx");
  private static final Path MATERIAL_SCRAP_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/原材料对应回收废料信息-列表导出20260512150059-new.xlsx");

  private CmsCostExcelParseServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new CmsCostExcelParseServiceImpl();
  }

  @Test
  void parsesPlanCostChineseHeaderFromSecondRow() {
    CmsCostExcelParseResult<CmsPlanCostExcelRow> result =
        service.parsePlanCost(
            workbook(
                List.of(
                    List.of(
                        "一级编码",
                        "一级编码名称",
                        "父件编码",
                        "父件名称",
                        "父件规格",
                        "父件型号",
                        "单位",
                        "工时",
                        "生效时间",
                        "主材成本",
                        "辅材成本",
                        "工资成本",
                        "经费成本",
                        "损失成本",
                        "计划价(总)",
                        "业务状态",
                        "未审批项",
                        "制定说明",
                        "OA单号"),
                    List.of(
                        "55",
                        "商用部品事业部",
                        "1079900000536",
                        "四通换向阀阀体",
                        "SHF-P35792-001",
                        "SHF-35B-79-01(P)",
                        "只",
                        "1.5",
                        "2026/5/1",
                        "10",
                        "20",
                        "30",
                        "40",
                        "50",
                        "150",
                        "finishStatus",
                        "工时",
                        "测试说明",
                        "OA-1"))));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(1);
    CmsPlanCostExcelRow row = result.getRows().get(0);
    assertThat(row.getRowNo()).isEqualTo(2);
    assertThat(row.getParentCode()).isEqualTo("1079900000536");
    assertThat(row.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(row.getUnapprovedItems()).isEqualTo("工时");
    assertThat(row.getWorkingHours()).isEqualByComparingTo("1.5");
  }

  @Test
  void parsesWorkshopLaborEnglishHeaderFromFourthRowAndConvertsCentToYuan() {
    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> result =
        service.parseWorkshopLabor(
            workbook(
                List.of(
                    workshopHeader(),
                    workshopChineseHeader(),
                    workshopChineseHeader(),
                    List.of(
                        "2026-01",
                        "59",
                        "商用四通阀事业部",
                        "1079900000536",
                        "四通换向阀阀体",
                        "SHF-P35792-001",
                        "SHF-35B-79-01(P)",
                        "焊接车间",
                        "590102-009",
                        "100",
                        "20",
                        "80",
                        "自算",
                        "path",
                        "raw-id-1",
                        "name",
                        "admin",
                        "",
                        "admin",
                        "",
                        "2026-02-02 10:44:20",
                        "",
                        "",
                        "",
                        "SEQ-1",
                        "已完成",
                        "3084.48621144",
                        "",
                        "",
                        "",
                        "",
                        "",
                        ""))));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(1);
    CmsWorkshopLaborExcelRow row = result.getRows().get(0);
    assertThat(row.getRowNo()).isEqualTo(4);
    assertThat(row.getPeriod()).isEqualTo("2026-01");
    assertThat(row.getParentCode()).isEqualTo("1079900000536");
    assertThat(row.getSourceRowId()).isEqualTo("raw-id-1");
    assertThat(row.getWorkingCostCent()).isEqualByComparingTo("80");
    assertThat(row.getWorkingCostYuan()).isEqualByComparingTo("0.8");
    assertThat(row.getMaterialPriceYuan()).isEqualByComparingTo("30.844862");
  }

  @Test
  void parsesWorkshopLaborWhenEnglishPeriodHeaderMissingAndColumnsReordered() {
    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> result =
        service.parseWorkshopLabor(
            workbook(
                List.of(
                    workshopReorderedHeaderWithMissingPeriod(),
                    workshopReorderedChineseHeader(),
                    workshopReorderedChineseHeader(),
                    workshopReorderedRow())));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(1);
    CmsWorkshopLaborExcelRow row = result.getRows().get(0);
    assertThat(row.getPeriod()).isEqualTo("2026-04");
    assertThat(row.getParentCode()).isEqualTo("1001900001090");
    assertThat(row.getSourceRowId()).isEqualTo("raw-id-new");
    assertThat(row.getWorkingCostCent()).isEqualByComparingTo("113.729166");
    assertThat(row.getWorkingCostYuan()).isEqualByComparingTo("1.137292");
    assertThat(row.getMaterialPrice()).isEqualByComparingTo("4349.26");
    assertThat(row.getFirstSubjectCode()).isEqualTo("02");
    assertThat(row.getSecondSubjectName()).isEqualTo("包装辅料");
  }

  @Test
  void parsesProductSubjectEnglishHeaderFromFourthRowAndConvertsMaterialPrice() {
    CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> result =
        service.parseProductSubjectCost(
            workbook(
                List.of(
                    subjectHeader(),
                    subjectChineseHeader(),
                    subjectChineseHeader(),
                    List.of(
                        "2026-04-01 00:00:00",
                        "55",
                        "商用部品事业部",
                        "1079900000536",
                        "四通换向阀阀体",
                        "SHF-P35792-001",
                        "SHF-35B-79-01(P)",
                        "020101",
                        "辅助焊料类",
                        "二级",
                        "22",
                        "自算",
                        "path",
                        "02",
                        "辅助材料",
                        "0201",
                        "辅助焊料类",
                        "",
                        "",
                        "raw-id-2",
                        "name",
                        "admin",
                        "",
                        "admin",
                        "",
                        "2026-04-30 20:22:27",
                        "",
                        "",
                        "",
                        "SEQ-2",
                        "已完成"))));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(1);
    CmsProductSubjectCostExcelRow row = result.getRows().get(0);
    assertThat(row.getRowNo()).isEqualTo(4);
    assertThat(row.getPeriod()).isEqualTo("2026-04");
    assertThat(row.getParentCode()).isEqualTo("1079900000536");
    assertThat(row.getSourceRowId()).isEqualTo("raw-id-2");
    assertThat(row.getFirstSubjectName()).isEqualTo("辅助材料");
    assertThat(row.getSecondSubjectName()).isEqualTo("辅助焊料类");
    assertThat(row.getMaterialPrice()).isEqualByComparingTo("22");
    assertThat(row.getMaterialPriceYuan()).isEqualByComparingTo("0.22");
  }

  @Test
  void parsesSubjectSettingEnglishHeaderFromFourthRow() {
    CmsCostExcelParseResult<CmsSubjectSettingExcelRow> result =
        service.parseSubjectSetting(
            workbook(
                List.of(
                    subjectSettingHeader(),
                    subjectSettingChineseHeader(),
                    subjectSettingChineseHeader(),
                    List.of("03", "工资", "0302", "辅助人员工资", "", ""))));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(1);
    CmsSubjectSettingExcelRow row = result.getRows().get(0);
    assertThat(row.getRowNo()).isEqualTo(4);
    assertThat(row.getFirstSubjectName()).isEqualTo("工资");
    assertThat(row.getSecondSubjectCode()).isEqualTo("0302");
    assertThat(row.getSecondSubjectName()).isEqualTo("辅助人员工资");
  }

  @Test
  void normalizesCmsCodesByRemovingAllBlankCharacters() {
    assertThat(CmsFieldNormalizeUtils.normalize(null)).isEmpty();
    assertThat(CmsFieldNormalizeUtils.normalize(" 301 050\t066\n")).isEqualTo("301050066");
    assertThat(CmsFieldNormalizeUtils.normalize("00Cr13Si2　Φ10.2\r\n(-0.04/-0.07)"))
        .isEqualTo("00Cr13Si2Φ10.2(-0.04/-0.07)");
  }

  @Test
  void parsesMaterialScrapRefExcelToReusableSourceRows() {
    CmsCostExcelParseResult<CmsMaterialScrapRefSourceRow> result =
        service.parseMaterialScrapRef(
            workbook(
                List.of(
                    materialScrapHeader(),
                    materialScrapChineseHeader(),
                    materialScrapChineseHeader(),
                    materialScrapChineseHeader(),
                    materialScrapRow(
                        " 301 050\t066\n",
                        "拉制铜管",
                        "T2  Y2",
                        "301 990 317",
                        "废紫铜沫（干净）",
                        "",
                        "已完成"),
                    materialScrapRow(
                        "301280056",
                        "不锈钢丝网",
                        "304 网",
                        "301990444",
                        "废不锈钢沫和丝网",
                        "",
                        ""),
                    materialScrapRow(
                        "301240123",
                        "不锈钢带",
                        "",
                        "301990444",
                        "废不锈钢沫和丝网",
                        "",
                        "已撤回"),
                    materialScrapRow("", "", "", "", "", "", ""))));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(3);
    CmsMaterialScrapRefSourceRow spacedRow = result.getRows().get(0);
    assertThat(spacedRow.getNormalizedMaterialCode()).isEqualTo("301050066");
    assertThat(spacedRow.getNormalizedRecycleMaterialCode()).isEqualTo("301990317");
    assertThat(spacedRow.getNormalizedMaterialSpec()).isEqualTo("T2Y2");
    assertThat(spacedRow.isCurrentMappingCandidate()).isTrue();

    CmsMaterialScrapRefSourceRow blankStatusRow = result.getRows().get(1);
    assertThat(blankStatusRow.getNormalizedMaterialCode()).isEqualTo("301280056");
    assertThat(blankStatusRow.getSequenceStatus()).isNull();
    assertThat(blankStatusRow.isCurrentMappingCandidate()).isTrue();

    CmsMaterialScrapRefSourceRow invalidStatusRow = result.getRows().get(2);
    assertThat(invalidStatusRow.isInvalidSequenceStatus()).isTrue();
    assertThat(invalidStatusRow.isCurrentMappingCandidate()).isFalse();
  }

  @Test
  void parsesProvidedMaterialScrapSampleWhenAvailable() throws IOException {
    assumeTrue(Files.exists(MATERIAL_SCRAP_SAMPLE));

    CmsCostExcelParseResult<CmsMaterialScrapRefSourceRow> result;
    try (InputStream input = Files.newInputStream(MATERIAL_SCRAP_SAMPLE)) {
      result = service.parseMaterialScrapRef(input);
    }

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows())
        .filteredOn(CmsMaterialScrapRefSourceRow::isCurrentMappingCandidate)
        .hasSize(6);
    assertThat(result.getRows())
        .filteredOn(CmsMaterialScrapRefSourceRow::isCurrentMappingCandidate)
        .extracting(
            row -> row.getNormalizedMaterialCode() + "->" + row.getNormalizedRecycleMaterialCode())
        .contains(
            "701010001000->301990218",
            "301050066->301990317",
            "301220046->301990752",
            "301240123->301990444",
            "301050054->301990317",
            "301280056->301990444");
    assertThat(result.getRows())
        .filteredOn(row -> "301280056".equals(row.getNormalizedMaterialCode()))
        .singleElement()
        .satisfies(row -> assertThat(row.isCurrentMappingCandidate()).isTrue());
  }

  @Test
  void returnsParseErrorForMissingRequiredParentCode() {
    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> result =
        service.parseWorkshopLabor(
            workbook(
                List.of(
                    workshopHeader(),
                    workshopChineseHeader(),
                    workshopChineseHeader(),
                    List.of(
                        "2026-01",
                        "59",
                        "商用四通阀事业部",
                        "",
                        "四通换向阀阀体",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "80",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        ""))));

    assertThat(result.getRows()).isEmpty();
    assertThat(result.getErrors()).extracting("rowNo").contains(4);
    assertThat(result.getErrors()).extracting("columnName").contains("parentCode");
  }

  @Test
  void returnsParseErrorForInvalidNumericValueWithRowNoAndReason() {
    CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> result =
        service.parseProductSubjectCost(
            workbook(
                List.of(
                    subjectHeader(),
                    subjectChineseHeader(),
                    subjectChineseHeader(),
                    List.of(
                        "2026-04",
                        "55",
                        "商用部品事业部",
                        "1079900000536",
                        "四通换向阀阀体",
                        "",
                        "",
                        "020101",
                        "辅助焊料类",
                        "二级",
                        "abc",
                        "自算",
                        "path",
                        "02",
                        "辅助材料",
                        "0201",
                        "辅助焊料类",
                        "",
                        "",
                        "raw-id-err",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "SEQ-ERR",
                        "已完成"))));

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().get(0).getRowNo()).isEqualTo(4);
    assertThat(result.getErrors().get(0).getColumnName()).isEqualTo("materialPrice");
    assertThat(result.getErrors().get(0).getMessage()).contains("数字格式不正确").contains("abc");
  }

  @Test
  void returnsParseErrorForMissingCmsSourceRowId() {
    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> result =
        service.parseWorkshopLabor(
            workbook(
                List.of(
                    workshopHeader(),
                    workshopChineseHeader(),
                    workshopChineseHeader(),
                    List.of(
                        "2026-01",
                        "59",
                        "商用四通阀事业部",
                        "1079900000536",
                        "四通换向阀阀体",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "80",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        ""))));

    assertThat(result.getRows()).isEmpty();
    assertThat(result.getErrors()).extracting("rowNo").contains(4);
    assertThat(result.getErrors()).extracting("columnName").contains("id");
  }

  @Test
  void parserServiceHasNoMapperOrDatabaseDependency() {
    assertThat(
            Arrays.stream(CmsCostExcelParseServiceImpl.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .filter(typeName -> typeName.contains(".mapper."))
                .toList())
        .isEmpty();
  }

  @Test
  void parsesProvidedCmsSamplesWhenAvailable() throws IOException {
    assumeTrue(
        Files.exists(PLAN_SAMPLE)
            && Files.exists(WORKSHOP_SAMPLE)
            && Files.exists(SUBJECT_SAMPLE)
            && Files.exists(SUBJECT_SETTING_SAMPLE));

    CmsCostExcelParseResult<CmsPlanCostExcelRow> plan;
    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> workshop;
    CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> subject;
    CmsCostExcelParseResult<CmsSubjectSettingExcelRow> subjectSetting;
    try (InputStream input = Files.newInputStream(PLAN_SAMPLE)) {
      plan = service.parsePlanCost(input);
    }
    try (InputStream input = Files.newInputStream(WORKSHOP_SAMPLE)) {
      workshop = service.parseWorkshopLabor(input);
    }
    try (InputStream input = Files.newInputStream(SUBJECT_SAMPLE)) {
      subject = service.parseProductSubjectCost(input);
    }
    try (InputStream input = Files.newInputStream(SUBJECT_SETTING_SAMPLE)) {
      subjectSetting = service.parseSubjectSetting(input);
    }

    assertThat(plan.getErrors()).isEmpty();
    assertThat(workshop.getErrors()).isEmpty();
    assertThat(subject.getErrors()).isEmpty();
    assertThat(subjectSetting.getErrors()).isEmpty();
    assertThat(plan.getRows()).isNotEmpty();
    assertThat(workshop.getRows()).extracting("parentCode").contains("1079900000536");
    assertThat(workshop.getRows()).extracting(CmsWorkshopLaborExcelRow::getWorkingCostCent)
        .anySatisfy(value -> assertThat(value).isEqualByComparingTo("80"));
    assertThat(workshop.getRows().get(0).getWorkingCostYuan()).isEqualByComparingTo("0.8");
    assertThat(subject.getRows()).extracting("secondSubjectName").contains("辅助人员工资");
    assertThat(subject.getRows()).extracting("firstSubjectName").contains("辅助材料");
    assertThat(subject.getRows()).extracting(CmsProductSubjectCostExcelRow::getMaterialPrice)
        .anySatisfy(value -> assertThat(value).isEqualByComparingTo("22"));
    assertThat(subjectSetting.getRows()).extracting(CmsSubjectSettingExcelRow::getSecondSubjectName)
        .contains("辅助人员工资", "包装辅料");
  }

  @Test
  void parsesProvidedReorderedWorkshopSampleWhenAvailable() throws IOException {
    assumeTrue(Files.exists(WORKSHOP_REORDERED_SAMPLE));

    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> result;
    try (InputStream input = Files.newInputStream(WORKSHOP_REORDERED_SAMPLE)) {
      result = service.parseWorkshopLabor(input);
    }

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).isNotEmpty();
    assertThat(result.getRows()).extracting(CmsWorkshopLaborExcelRow::getPeriod).contains("2026-04");
    assertThat(result.getRows()).extracting(CmsWorkshopLaborExcelRow::getParentCode)
        .contains("1001900001090");
    assertThat(result.getRows()).extracting(CmsWorkshopLaborExcelRow::getSourceRowId)
        .contains("f341cd8091f209d362a817fd61e3f27b");
    assertThat(result.getRows()).extracting(CmsWorkshopLaborExcelRow::getMaterialPrice)
        .anySatisfy(value -> assertThat(value).isEqualByComparingTo("4349.26"));
    assertThat(result.getRows()).extracting(CmsWorkshopLaborExcelRow::getWorkingCostCent)
        .anySatisfy(value -> assertThat(value).isEqualByComparingTo("113.729166"));
  }

  private InputStream workbook(List<List<String>> rows) {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Sheet0");
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        Row row = sheet.createRow(rowIndex);
        List<String> values = rows.get(rowIndex);
        for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
          row.createCell(columnIndex).setCellValue(values.get(columnIndex));
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return new ByteArrayInputStream(out.toByteArray());
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private List<String> workshopHeader() {
    return List.of(
        "period",
        "firstUnitCode",
        "firstUnitName",
        "parentCode",
        "parentName",
        "parentSpec",
        "parentType",
        "lastUnitName",
        "lastUnitCode",
        "workingHours",
        "funding",
        "workingCost",
        "buildFlag",
        "path",
        "id",
        "name",
        "creater",
        "createdDeptId",
        "owner",
        "ownerDeptId",
        "createdTime",
        "modifier",
        "modifiedTime",
        "workflowInstanceId",
        "sequenceNo",
        "sequenceStatus",
        "materialPrice",
        "firstSubjectCode",
        "firstSubjectName",
        "secondSubjectCode",
        "secondSubjectName",
        "thirdSubjectCode",
        "thirdSubjectName");
  }

  private List<String> workshopChineseHeader() {
    return List.of(
        "期间",
        "一级生产单元编码",
        "一级生产单元名称",
        "父件编码",
        "父件名称",
        "父件规格",
        "父件型号",
        "末级生产单元名称",
        "末级生产单元编码",
        "工时",
        "经费",
        "工资",
        "构建标记",
        "核算路径",
        "id",
        "数据标题",
        "创建人",
        "创建人部门",
        "拥有者",
        "拥有者部门",
        "创建时间",
        "修改人",
        "修改时间",
        "流程实例ID",
        "单据号",
        "单据状态",
        "材料计价",
        "一级科目编码",
        "一级科目名称",
        "二级科目编码",
        "二级科目名称",
        "三级级科目编码",
        "三级级科目名称");
  }

  private List<String> workshopReorderedHeaderWithMissingPeriod() {
    return List.of(
        "",
        "firstUnitCode",
        "firstUnitName",
        "parentCode",
        "parentName",
        "parentSpec",
        "parentType",
        "lastUnitName",
        "lastUnitCode",
        "workingHours",
        "funding",
        "materialPrice",
        "workingCost",
        "buildFlag",
        "path",
        "firstSubjectCode",
        "firstSubjectName",
        "secondSubjectCode",
        "secondSubjectName",
        "thirdSubjectCode",
        "thirdSubjectName",
        "createdTime",
        "id",
        "name",
        "creater",
        "createdDeptId",
        "owner",
        "ownerDeptId",
        "modifier",
        "modifiedTime",
        "workflowInstanceId",
        "sequenceNo",
        "sequenceStatus");
  }

  private List<String> workshopReorderedChineseHeader() {
    return List.of(
        "期间",
        "一级生产单元编码",
        "一级生产单元名称",
        "父件编码",
        "父件名称",
        "父件规格",
        "父件型号",
        "末级生产单元名称",
        "末级生产单元编码",
        "工时",
        "经费",
        "材料计价",
        "工资",
        "构建标记",
        "核算路径",
        "一级科目编码",
        "一级科目名称",
        "二级科目编码",
        "二级科目名称",
        "三级级科目编码",
        "三级级科目名称",
        "创建时间",
        "id",
        "数据标题",
        "创建人",
        "创建人部门",
        "拥有者",
        "拥有者部门",
        "修改人",
        "修改时间",
        "流程实例ID",
        "单据号",
        "单据状态");
  }

  private List<String> workshopReorderedRow() {
    return List.of(
        "2026-04",
        "55",
        "商用部品事业部",
        "1001900001090",
        "电磁阀阀体",
        "HDF25H51K",
        "HDF25H51K",
        "商用部品金加工一车间-科玛特KT420-21刀",
        "550101-029",
        "150",
        "21.006357",
        "4349.26",
        "113.729166",
        "自算",
        "path-new",
        "02",
        "辅助材料",
        "0215",
        "包装辅料",
        "",
        "",
        "2026-04-30 20:15:27",
        "raw-id-new",
        "1001900001090-产品车间料工费汇总550101-029",
        "admin",
        "",
        "admin",
        "",
        "",
        "",
        "",
        "SEQ-NEW",
        "已完成");
  }

  private List<String> subjectHeader() {
    return List.of(
        "period",
        "firstUnitCode",
        "firstUnitName",
        "parentCode",
        "parentName",
        "parentSpec",
        "parentType",
        "lastSubjectCode",
        "lastSubjectName",
        "lastSubjectLevel",
        "materialPrice",
        "buildFlag",
        "path",
        "firstSubjectCode",
        "firstSubjectName",
        "secondSubjectCode",
        "secondSubjectName",
        "thirdSubjectCode",
        "thirdSubjectName",
        "id",
        "name",
        "creater",
        "createdDeptId",
        "owner",
        "ownerDeptId",
        "createdTime",
        "modifier",
        "modifiedTime",
        "workflowInstanceId",
        "sequenceNo",
        "sequenceStatus");
  }

  private List<String> subjectChineseHeader() {
    return List.of(
        "期间",
        "一级生产单元编码",
        "一级生产单元名称",
        "父件编码",
        "父件名称",
        "父件规格",
        "父件型号",
        "末级科目编码",
        "末级科目名称",
        "末级科目层级",
        "材料计价",
        "构建标记",
        "核算路径",
        "一级科目编码",
        "一级科目名称",
        "二级科目编码",
        "二级科目名称",
        "三级科目编码",
        "三级科目名称",
        "id",
        "数据标题",
        "创建人",
        "创建人部门",
        "拥有者",
        "拥有者部门",
        "创建时间",
        "修改人",
        "修改时间",
        "流程实例ID",
        "单据号",
        "单据状态");
  }

  private List<String> subjectSettingHeader() {
    return List.of(
        "firstLevelSubjectCode",
        "firstLevelSubjectName",
        "secondLevelSubjectCode",
        "secondLevelSubjectName",
        "thiedLevelSubjectCode",
        "thirdLevelSubjectName");
  }

  private List<String> subjectSettingChineseHeader() {
    return List.of("一级科目编号", "一级科目名称", "二级科目编号", "二级科目名称", "三级科目编号", "三级科目名称");
  }

  private List<String> materialScrapHeader() {
    return List.of(
        "materialCode",
        "materialName",
        "materialSpecifications",
        "materialModel",
        "materialUnit",
        "recycleMaterialCode",
        "recycleMaterialName",
        "recycleMaterialSpecification",
        "recycleMaterialModel",
        "recycleMaterialUnit",
        "RecycleMaterialInfoVersion",
        "costGroupName",
        "creater",
        "createdDeptId",
        "owner",
        "ownerDeptId",
        "createdTime",
        "modifier",
        "modifiedTime",
        "sequenceNo",
        "id",
        "name",
        "workflowInstanceId",
        "sequenceStatus",
        "linkDetailId",
        "approvalPerson",
        "syncTime",
        "approvalTime",
        "costGroupCode",
        "costGroup",
        "effectiveDate",
        "postingPeriod");
  }

  private List<String> materialScrapChineseHeader() {
    return List.of(
        "物料料号",
        "物料品名",
        "物料规格",
        "物料型号",
        "物料单位",
        "回收料号",
        "回收料品名",
        "回收料规格",
        "回收料型号",
        "回收料单位",
        "同步版本",
        "成本组名称",
        "创建人",
        "创建人部门",
        "拥有者",
        "拥有者部门",
        "创建时间",
        "修改人",
        "修改时间",
        "单据号",
        "id",
        "数据标题",
        "流程实例ID",
        "单据状态",
        "关联明细id",
        "审核人",
        "同步时间",
        "审核时间",
        "成本组编码",
        "成本组关联Id",
        "生效时间",
        "期间");
  }

  private List<String> materialScrapRow(
      String materialCode,
      String materialName,
      String materialSpec,
      String recycleMaterialCode,
      String recycleMaterialName,
      String effectiveDate,
      String sequenceStatus) {
    return List.of(
        materialCode,
        materialName,
        materialSpec,
        "",
        "千克",
        recycleMaterialCode,
        recycleMaterialName,
        "",
        "",
        "千克",
        "1",
        "商用大明市生产基地",
        "admin",
        "",
        "admin",
        "",
        "2025-09-12 18:14:50",
        "admin",
        "2025-09-12 18:14:50",
        "SEQ-1",
        "CMS-ID-1",
        "CMS单据",
        "",
        sequenceStatus,
        "LINK-1",
        "审核人",
        "2025-09-12",
        "2025-09-12",
        "002",
        "COST-GROUP-1",
        effectiveDate,
        "2025-09");
  }
}

package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteExcelImportServiceImplTest {
  private QuoteIngestService quoteIngestService;
  private QuoteExcelImportServiceImpl service;

  @BeforeEach
  void setUp() {
    quoteIngestService = mock(QuoteIngestService.class);
    QuoteNormalizeService normalizeService =
        new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService());
    service = new QuoteExcelImportServiceImpl(normalizeService, quoteIngestService);
  }

  @Test
  void previewSingleFormSingleItemSucceeds() {
    QuoteExcelImportPreviewResponse response =
        service.preview(excel(List.of(header("OA-T6-001", "FI-SC-006")), List.of(item("OA-T6-001", 1))), "t6.xlsx");

    assertThat(response.isValid()).isTrue();
    assertThat(response.getFormCount()).isEqualTo(1);
    assertThat(response.getItemCount()).isEqualTo(1);
    assertThat(response.getForms()).hasSize(1);
    assertThat(response.getForms().get(0).getOaNo()).isEqualTo("OA-T6-001");
    assertThat(response.getForms().get(0).getQuoteScenario()).isEqualTo("STANDARD_BATCH");
    verify(quoteIngestService, never()).ingest(any());
  }

  @Test
  void previewSingleFormMultipleItemsSucceeds() {
    QuoteExcelImportPreviewResponse response =
        service.preview(
            excel(
                List.of(header("OA-T6-002", "FI-SC-006")),
                List.of(item("OA-T6-002", 1), item("OA-T6-002", 2))),
            "t6.xlsx");

    assertThat(response.isValid()).isTrue();
    assertThat(response.getFormCount()).isEqualTo(1);
    assertThat(response.getItemCount()).isEqualTo(2);
    assertThat(response.getForms().get(0).getItemCount()).isEqualTo(2);
  }

  @Test
  void commitImportsExtraFeeThroughUnifiedIngestService() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    ingestResponse.setOaNo("OA-T6-FEE");
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    QuoteExcelImportCommitResponse response =
        service.commit(
            excel(
                List.of(header("OA-T6-FEE", "FI-SR-005")),
                List.of(newItem("OA-T6-FEE", 1, "新品", "MAT-FEE", "SHF-FEE")),
                List.of(fee("OA-T6-FEE", 1, "MOLD_TOTAL", "模具费用总金额", "MOLD", "50000"))),
            "t6.xlsx");

    assertThat(response.isCommitted()).isTrue();
    assertThat(response.getPreview().getFeeCount()).isEqualTo(1);
    assertThat(response.getResults()).hasSize(1);
    ArgumentCaptor<QuoteIngestRequest> captor = ArgumentCaptor.forClass(QuoteIngestRequest.class);
    verify(quoteIngestService).ingest(captor.capture());
    assertThat(captor.getValue().getItems().get(0).getExtraFees()).hasSize(1);
    assertThat(captor.getValue().getItems().get(0).getExtraFees().get(0).getFeeCode())
        .isEqualTo("MOLD_TOTAL");
  }

  @Test
  void blankFormNoReturnsErrorAndCommitDoesNotCallIngest() {
    InputStream excel = excel(List.of(header("", "FI-SC-006")), List.of(item("", 1)));

    QuoteExcelImportPreviewResponse preview = service.preview(excel, "t6.xlsx");

    assertThat(preview.isValid()).isFalse();
    assertThat(preview.getErrors()).extracting("code").contains("FORM_NO_REQUIRED");

    QuoteExcelImportCommitResponse commit =
        service.commit(excel(List.of(header("", "FI-SC-006")), List.of(item("", 1))), "t6.xlsx");
    assertThat(commit.isCommitted()).isFalse();
    verify(quoteIngestService, never()).ingest(any());
  }

  @Test
  void noProductRowsReturnsErrorAndCommitDoesNotCallIngest() {
    QuoteExcelImportPreviewResponse preview =
        service.preview(excel(List.of(header("OA-T6-NO-ITEM", "FI-SC-006")), List.of()), "t6.xlsx");

    assertThat(preview.isValid()).isFalse();
    assertThat(preview.getErrors()).extracting("code").contains("ITEMS_REQUIRED");

    QuoteExcelImportCommitResponse commit =
        service.commit(excel(List.of(header("OA-T6-NO-ITEM", "FI-SC-006")), List.of()), "t6.xlsx");
    assertThat(commit.isCommitted()).isFalse();
    verify(quoteIngestService, never()).ingest(any());
  }

  @Test
  void itemWithoutMaterialAndModelReturnsError() {
    QuoteExcelImportPreviewResponse response =
        service.preview(
            excel(
                List.of(header("OA-T6-BAD-ITEM", "FI-SC-006")),
                List.of(newItem("OA-T6-BAD-ITEM", 1, "批量品", "", ""))),
            "t6.xlsx");

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("PRODUCT_KEY_REQUIRED");
  }

  @Test
  void blankGoldPriceReturnsWarningAndCanCommit() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    QuoteExcelImportPreviewResponse preview =
        service.preview(excel(List.of(header("OA-T6-GOLD", "FI-SC-006")), List.of(item("OA-T6-GOLD", 1))), "t6.xlsx");

    assertThat(preview.isValid()).isTrue();
    assertThat(preview.getWarnings()).extracting("code").contains("GOLD_PRICE_EMPTY");

    QuoteExcelImportCommitResponse commit =
        service.commit(excel(List.of(header("OA-T6-GOLD", "FI-SC-006")), List.of(item("OA-T6-GOLD", 1))), "t6.xlsx");
    assertThat(commit.isCommitted()).isTrue();
    verify(quoteIngestService).ingest(any());
  }

  @Test
  void commitProvidesStableIdempotencyInputForUnifiedIngestService() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    service.commit(
        excel(List.of(header("OA-T6-IDEMPOTENT", "FI-SC-006")), List.of(item("OA-T6-IDEMPOTENT", 1))),
        "t6.xlsx");

    ArgumentCaptor<QuoteIngestRequest> captor = ArgumentCaptor.forClass(QuoteIngestRequest.class);
    verify(quoteIngestService).ingest(captor.capture());
    assertThat(captor.getValue().getSourceType()).isEqualTo("EXCEL");
    assertThat(captor.getValue().getSourceSystem()).isEqualTo("EXCEL_TEMPLATE");
    assertThat(captor.getValue().getExternalFormNo()).isEqualTo("OA-T6-IDEMPOTENT");
    assertThat(captor.getValue().getVersion()).isEqualTo("1");
    assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("EXCEL:OA-T6-IDEMPOTENT:1");
  }

  @Test
  void fiSr005WithoutBusinessTypeWarnsAndCanCommitAsPending() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    ingestResponse.setClassificationStatus("PENDING");
    ingestResponse.setQuoteScenario("UNKNOWN");
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    InputStream file =
        excel(
            List.of(header("OA-T6-PENDING", "FI-SR-005")),
            List.of(newItem("OA-T6-PENDING", 1, "", "MAT-PENDING", "SHF-PENDING")));
    QuoteExcelImportPreviewResponse preview = service.preview(file, "t6.xlsx");

    assertThat(preview.isValid()).isTrue();
    assertThat(preview.getForms().get(0).isClassificationPending()).isTrue();
    assertThat(preview.getWarnings()).extracting("code").contains("FI_SR_005_BUSINESS_TYPE_EMPTY");

    QuoteExcelImportCommitResponse commit =
        service.commit(
            excel(
                List.of(header("OA-T6-PENDING", "FI-SR-005")),
                List.of(newItem("OA-T6-PENDING", 1, "", "MAT-PENDING", "SHF-PENDING"))),
            "t6.xlsx");
    assertThat(commit.isCommitted()).isTrue();
    assertThat(commit.getResults().get(0).getClassificationStatus()).isEqualTo("PENDING");
  }

  @Test
  void previewOaOriginalFormUsesHiddenMappingAndSplitsFields() {
    QuoteExcelImportPreviewResponse response =
        service.preview(oaOriginalExcel("FI-SR-005", "NEW_PRODUCT", "家代商业务单元", "家代商代销产品", "新品", true), "oa.xlsx");

    assertThat(response.isValid()).isTrue();
    assertThat(response.getFormCount()).isEqualTo(1);
    assertThat(response.getItemCount()).isEqualTo(1);
    assertThat(response.getFeeCount()).isGreaterThanOrEqualTo(3);
    assertThat(response.getForms().get(0).getOaNo()).isEqualTo("FI-SR-005-20260327-Q4");
    assertThat(response.getForms().get(0).getQuoteScenario()).isEqualTo("NEW_PRODUCT");
    assertThat(response.getForms().get(0).getClassificationStatus()).isEqualTo("CONFIRMED");
    QuoteIngestPreviewResponse form = response.getForms().get(0);
    assertThat(form.getSourceType()).isEqualTo("EXCEL");
    assertThat(form.getAccountingContext().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(form.getAccountingContext().getAccountingPeriodMonth()).isEqualTo("2026-03");
    assertThat(form.getAccountingContext().getExpenseProductCategory()).isEqualTo("家代商代销产品");
    assertThat(form.getHeaderSummary().getProcessCode()).isEqualTo("FI-SR-005");
    assertThat(form.getHeaderSummary().getApplicantDept()).isEqualTo("欧美业务管理部");
    assertThat(form.getItems()).hasSize(1);
    assertThat(form.getItems().get(0).getSeq()).isEqualTo(1);
    assertThat(form.getItems().get(0).getBusinessType()).isEqualTo("新品");
    assertThat(form.getItems().get(0).getTotalWithShip()).isEqualByComparingTo("123.45");
    assertThat(form.getItems().get(0).getMaterialCost()).isEqualByComparingTo("80.00");
    assertThat(form.getItems().get(0).getManagementCost()).isEqualByComparingTo("3.50");
    assertThat(form.getItems().get(0).getValidMonth()).isEqualTo(6);
    assertThat(form.getItems().get(0).getSus304WeightG()).isEqualByComparingTo("11.00");
    assertThat(form.getItems().get(0).getSus316WeightG()).isEqualByComparingTo("3.00");
    assertThat(form.getItems().get(0).getCopperWeightG()).isEqualByComparingTo("8.50");
    assertThat(form.getItems().get(0).getScrapRate()).isEqualByComparingTo("0.02");
    assertThat(form.getExtraFees()).extracting("feeCode").contains("moldTotalAmount");
    assertThat(form.getExtraFees().get(0).getSourceFieldPath()).contains("OA原始表单!");
  }

  @Test
  void commitOaOriginalFormBuildsUnifiedIngestRequestOnly() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    ingestResponse.setOaNo("FI-SR-005-20260327-Q4");
    ingestResponse.setOaFormId(100L);
    ingestResponse.setIngestLogId(200L);
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    QuoteExcelImportCommitResponse response =
        service.commit(
            oaOriginalExcel("FI-SR-005", "DERIVED_PRODUCT", "家代商业务单元", "家代商代销产品", "衍生品", true),
            "oa.xlsx");

    assertThat(response.isCommitted()).isTrue();
    assertThat(response.getResults().get(0).getOaNo()).isEqualTo("FI-SR-005-20260327-Q4");
    assertThat(response.getResults().get(0).getOaFormId()).isEqualTo(100L);
    assertThat(response.getResults().get(0).getIngestLogId()).isEqualTo(200L);
    ArgumentCaptor<QuoteIngestRequest> captor = ArgumentCaptor.forClass(QuoteIngestRequest.class);
    verify(quoteIngestService).ingest(captor.capture());
    QuoteIngestRequest request = captor.getValue();
    assertThat(request.getSourceType()).isEqualTo("EXCEL");
    assertThat(request.getSourceSystem()).isEqualTo("EXCEL_TEMPLATE");
    assertThat(request.getIdempotencyKey()).isEqualTo("EXCEL:FI-SR-005-20260327-Q4:1");
    assertThat(request.getHeader().getApplyDate()).isEqualTo("2026-03-27");
    assertThat(request.getHeader().getApplicantUnit()).isEqualTo("家代商业务单元");
    assertThat(request.getHeader().getApplicantDept()).isEqualTo("欧美业务管理部");
    assertThat(request.getHeader().getApplicantOffice()).isEqualTo("欧洲业务管理部");
    assertThat(request.getHeader().getSourceCompany()).isEqualTo("浙江三花商用制冷有限公司");
    assertThat(request.getHeader().getSourceBusinessDivision()).isEqualTo("商用四通阀事业部");
    assertThat(request.getItems()).hasSize(1);
    assertThat(request.getItems().get(0).getBusinessType()).isEqualTo("衍生品");
    assertThat(request.getItems().get(0).getScrapRate()).isEqualTo("2%");
    assertThat(request.getItems().get(0).getValidMonth()).isEqualTo("6");
    assertThat(request.getItems().get(0).getSus304WeightG()).isEqualTo("11.00");
    assertThat(request.getItems().get(0).getExtraFees()).extracting("feeCode").contains("moldTotalAmount");
    assertThat(request.getExtraFields()).extracting("fieldCode").contains("applyDateTime", "owner1");
    assertThat(request.getItems().get(0).getExtraFields()).extracting("fieldCode").contains("u11Code", "remark");
  }

  @Test
  void oaOriginalFormWithoutMappingSheetReturnsStructuredError() {
    QuoteExcelImportPreviewResponse response = service.preview(oaOriginalExcelWithoutMapping(), "broken.xlsx");

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("MAPPING_SHEET_REQUIRED");
  }

  @Test
  void nonExcelFileFailsWithParseError() {
    InputStream inputStream = new ByteArrayInputStream("not an excel file".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.preview(inputStream, "broken.txt"))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("Excel 解析失败");
  }

  @Test
  void desktopOaOriginalTemplatesParseWhenAvailable() throws Exception {
    Path base = Path.of("/Users/xiexicheng/Desktop/demo3");
    List<String> templateFileNames =
        List.of(
            "报价单导入模板_01_FI-SC-020_板换科技直销.xlsx",
            "报价单导入模板_02_FI-SC-006_标准品批量品.xlsx",
            "报价单导入模板_03_FI-SC-005_新品.xlsx",
            "报价单导入模板_04_FI-SR-005_家代商新品.xlsx",
            "报价单导入模板_05_FI-SR-005_家代商批量品.xlsx",
            "报价单导入模板_06_FI-SR-005_家代商衍生品.xlsx");
    Assumptions.assumeTrue(
        templateFileNames.stream().map(base::resolve).allMatch(Files::exists));

    for (String name : templateFileNames) {
      try (InputStream inputStream = new FileInputStream(base.resolve(name).toFile())) {
        QuoteExcelImportPreviewResponse response = service.preview(inputStream, name);
        assertThat(response.getFormCount()).as(name).isEqualTo(1);
        assertThat(response.getForms()).as(name).hasSize(1);
      }
    }
  }

  private InputStream excel(List<List<String>> headers, List<List<String>> items) {
    return excel(headers, items, List.of());
  }

  private InputStream excel(List<List<String>> headers, List<List<String>> items, List<List<String>> fees) {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet headerSheet = workbook.createSheet("报价单表头");
      writeRows(
          headerSheet,
          List.of(
              List.of(
                  "报价单号",
                  "来源类型",
                  "流程编号",
                  "申请日期",
                  "客户名称",
                  "产品属性",
                  "铜基价",
                  "锌基价",
                  "铝基价",
                  "不锈钢基价",
                  "黄金基价")));
      appendRows(headerSheet, headers);

      Sheet itemSheet = workbook.createSheet("产品明细");
      writeRows(
          itemSheet,
          List.of(List.of("报价单号", "行号", "业务类型", "产品名称", "料号", "三花型号", "规格", "三花配套量")));
      appendRows(itemSheet, items);

      Sheet feeSheet = workbook.createSheet("额外费用");
      writeRows(feeSheet, List.of(List.of("报价单号", "行号", "费用编码", "费用名称", "费用分类", "金额", "单位")));
      appendRows(feeSheet, fees);

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return new ByteArrayInputStream(out.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private InputStream oaOriginalExcel(
      String processCode,
      String scenario,
      String applicantUnit,
      String expenseProductCategory,
      String businessType,
      boolean withFees) {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet form = workbook.createSheet("OA原始表单");
      set(form, "B1", "EXCEL");
      set(form, "D1", processCode);
      set(form, "F1", "OA 原始样式测试表单");
      set(form, "H1", scenario);
      set(form, "J1", "COMMERCIAL");
      set(form, "L1", expenseProductCategory);
      set(form, "E5", "owner-a");
      set(form, "E11", "普通");
      set(form, "Q11", processCode + "-20260327-Q4");
      set(form, "E12", "张三");
      set(form, "Q12", "2026-03-27 14:36");
      set(form, "Q13", applicantUnit);
      set(form, "E14", "欧美业务管理部");
      set(form, "Q14", "欧洲业务管理部");
      set(form, "E18", "浙江三花商用制冷有限公司");
      set(form, "E19", "示例客户");
      set(form, "Q20", businessType);
      set(form, "E21", businessType);
      set(form, "Q21", "商用四通阀事业部");
      set(form, "E23", "FOB");
      set(form, "Q23", "7.10");
      set(form, "E25", "联动");
      set(form, "E26", "否");
      set(form, "E27", "90000");
      set(form, "Q30", "");
      set(form, "A43", "seq");
      set(form, "A44", "1");
      set(form, "B43", "customerCode");
      set(form, "B44", "31219");
      set(form, "C43", "u11Code");
      set(form, "C44", "U11-X");
      set(form, "D43", "materialNo");
      set(form, "D44", "");
      set(form, "E43", "sunlModel");
      set(form, "E44", "RFQ-2026-Q4");
      set(form, "F43", "productName");
      set(form, "F44", "阀件组件");
      set(form, "G43", "spec");
      set(form, "G44", "DN20");
      set(form, "I43", "annualVolume");
      set(form, "I44", "10000");
      set(form, "K43", "totalWithShip");
      set(form, "K44", "123.45");
      set(form, "L43", "totalNoShip");
      set(form, "L44", "120.00");
      set(form, "M43", "materialCost");
      set(form, "M44", "80.00");
      set(form, "P43", "managementCost");
      set(form, "P44", "3.50");
      set(form, "Q43", "validMonth");
      set(form, "Q44", "6");
      set(form, "R43", "sus304WeightG");
      set(form, "R44", "11.00");
      set(form, "S43", "sus316WeightG");
      set(form, "S44", "3.00");
      set(form, "T43", "copperWeightG");
      set(form, "T44", "8.50");
      set(form, "N43", "validDate");
      set(form, "N44", "2026-09-30");
      set(form, "O43", "remark");
      set(form, "O44", "样例，可删除");
      set(form, "V43", "unitLaborCost");
      set(form, "V44", "1.25");
      set(form, "W43", "scrapRate");
      set(form, "W44", "2%");
      if (withFees) {
        set(form, "Y43", "fixtureTotalAmount");
        set(form, "Y44", "10000");
        set(form, "AA43", "moldTotalAmount");
        set(form, "AA44", "20000");
        set(form, "AE43", "certificationFeeWan");
        set(form, "AE44", "1.2");
      }

      Sheet mapping = workbook.createSheet("解析字段映射");
      writeRows(
          mapping,
          List.of(
              List.of("scope", "field_code", "field_name", "source_range", "target_table"),
              mappingConstant("HEADER", "sourceType", "sourceType", "EXCEL", "oa_form"),
              mappingConstant("HEADER", "processCode", "processCode", processCode, "oa_form"),
              mappingConstant("HEADER", "processName", "processName", "OA 原始样式测试表单", "oa_form"),
              mappingConstant("HEADER", "quoteScenario", "quoteScenario", scenario, "oa_form"),
              mappingConstant("HEADER", "businessUnitType", "businessUnitType", "COMMERCIAL", "oa_form"),
              mappingConstant(
                  "HEADER", "expenseProductCategory", "expenseProductCategory", expenseProductCategory, "oa_form"),
              mapping("HEADER", "owner1", "流程OWNER1", "E5", "oa_form"),
              mapping("HEADER", "urgency", "紧急程度", "E11", "oa_form"),
              mapping("HEADER", "oaNo", "流程编号", "Q11", "oa_form"),
              mapping("HEADER", "applicantName", "申请人", "E12", "oa_form"),
              mapping("HEADER", "applyDateTime", "申请时间", "Q12", "oa_form"),
              mapping("HEADER", "applicantUnit", "申请单位", "Q13", "oa_form"),
              mapping("HEADER", "applicantDept", "申请部门", "E14", "oa_form"),
              mapping("HEADER", "applicantOffice", "申请处室", "Q14", "oa_form"),
              mapping("HEADER", "sourceCompany", "所属公司", "E18", "oa_form"),
              mapping("HEADER", "customer", "客户名称", "E19", "oa_form"),
              mapping("HEADER", "productAttr", "产品属性", "Q20", "oa_form"),
              mapping("HEADER", "businessType", "业务类型", "E21", "oa_form"),
              mapping("HEADER", "sourceBusinessDivision", "事业部", "Q21", "oa_form"),
              mapping("HEADER", "tradeTerms", "贸易条款", "E23", "oa_form"),
              mapping("HEADER", "exchangeRate", "汇率", "Q23", "oa_form"),
              mapping("HEADER", "priceLinkMode", "销售价格是否联动", "E25", "oa_form"),
              mapping("HEADER", "overseasSalesMode", "是否通过海外仓库发终端客户", "E26", "oa_form"),
              mapping("HEADER", "copperPrice", "铜基价", "E27", "oa_form"),
              mapping("HEADER", "goldPrice", "黄金基价", "Q30", "oa_form"),
              mapping("ITEM", "seq", "序号", "A44:A72", "oa_form_item"),
              mapping("ITEM", "customerCode", "客户编码", "B44:B72", "oa_form_item"),
              mapping("ITEM", "u11Code", "U11位码", "C44:C72", "oa_form_item"),
              mapping("ITEM", "materialNo", "料号", "D44:D72", "oa_form_item"),
              mapping("ITEM", "sunlModel", "三花型号", "E44:E72", "oa_form_item"),
              mapping("ITEM", "productName", "品名", "F44:F72", "oa_form_item"),
              mapping("ITEM", "spec", "规格", "G44:G72", "oa_form_item"),
              mapping("ITEM", "annualVolume", "预计年用量(只)", "I44:I72", "oa_form_item"),
              mapping("ITEM", "totalWithShip", "含运输费总成本", "K44:K72", "oa_form_item"),
              mapping("ITEM", "totalNoShip", "不含运输费总成本", "L44:L72", "oa_form_item"),
              mapping("ITEM", "materialCost", "直接材料费", "M44:M72", "oa_form_item"),
              mapping("ITEM", "managementCost", "企业管理费", "P44:P72", "oa_form_item"),
              mapping("ITEM", "validMonth", "成本有效期(月)", "Q44:Q72", "oa_form_item"),
              mapping("ITEM", "sus304WeightG", "不锈钢SUS304(克)", "R44:R72", "oa_form_item"),
              mapping("ITEM", "sus316WeightG", "不锈钢SUS316(克)", "S44:S72", "oa_form_item"),
              mapping("ITEM", "copperWeightG", "铜重(克)", "T44:T72", "oa_form_item"),
              mapping("ITEM", "validDate", "成本失效日期", "N44:N72", "oa_form_item"),
              mapping("ITEM", "remark", "备注", "O44:O72", "oa_form_item"),
              mapping("ITEM", "unitLaborCost", "单件工资（元/只）", "V44:V72", "oa_form_item"),
              mapping("ITEM", "scrapRate", "净损失率/报废率", "W44:W72", "oa_form_item"),
              mapping("ITEM", "fixtureTotalAmount", "工装夹具费用总金额", "Y44:Y72", "oa_form_item"),
              mapping("ITEM", "moldTotalAmount", "模具费用总金额", "AA44:AA72", "oa_form_item"),
              mapping("ITEM", "certificationFeeWan", "认证费（万元）", "AE44:AE72", "oa_form_item")));
      workbook.setSheetHidden(workbook.getSheetIndex(mapping), true);
      workbook.setSheetHidden(workbook.getSheetIndex(workbook.createSheet("填写说明")), true);

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return new ByteArrayInputStream(out.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private InputStream oaOriginalExcelWithoutMapping() {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      workbook.createSheet("OA原始表单");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return new ByteArrayInputStream(out.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private List<String> mapping(String scope, String fieldCode, String fieldName, String sourceRange, String target) {
    return List.of(scope, fieldCode, fieldName, "'OA原始表单'!" + sourceRange, target);
  }

  private List<String> mappingConstant(String scope, String fieldCode, String fieldName, String value, String target) {
    return List.of(scope, fieldCode, fieldName, "CONST:" + value, target);
  }

  private void set(Sheet sheet, String ref, String value) {
    Cell cell = sheet.getRow(new org.apache.poi.ss.util.CellReference(ref).getRow()) == null
        ? sheet.createRow(new org.apache.poi.ss.util.CellReference(ref).getRow())
            .createCell(new org.apache.poi.ss.util.CellReference(ref).getCol())
        : sheet.getRow(new org.apache.poi.ss.util.CellReference(ref).getRow())
            .createCell(new org.apache.poi.ss.util.CellReference(ref).getCol());
    cell.setCellValue(value);
  }

  private List<String> header(String oaNo, String processCode) {
    return List.of(
        oaNo,
        "EXCEL",
        processCode,
        "2026-05-11",
        "示例客户",
        "批量品",
        "90000",
        "21684",
        "23386",
        "17200",
        "");
  }

  private List<String> item(String oaNo, int seq) {
    return newItem(oaNo, seq, "批量品", "MAT-" + seq, "SHF-" + seq);
  }

  private List<String> newItem(String oaNo, int seq, String businessType, String materialNo, String sunlModel) {
    return List.of(
        oaNo,
        String.valueOf(seq),
        businessType,
        "测试产品" + seq,
        materialNo,
        sunlModel,
        "规格" + seq,
        "1");
  }

  private List<String> fee(
      String oaNo, int seq, String feeCode, String feeName, String feeCategory, String amount) {
    return List.of(oaNo, String.valueOf(seq), feeCode, feeName, feeCategory, amount, "元");
  }

  private void appendRows(Sheet sheet, List<List<String>> rows) {
    int start = sheet.getLastRowNum() + 1;
    for (int index = 0; index < rows.size(); index++) {
      writeRow(sheet.createRow(start + index), rows.get(index));
    }
  }

  private void writeRows(Sheet sheet, List<List<String>> rows) {
    for (int index = 0; index < rows.size(); index++) {
      writeRow(sheet.createRow(index), rows.get(index));
    }
  }

  private void writeRow(Row row, List<String> values) {
    for (int index = 0; index < values.size(); index++) {
      row.createCell(index).setCellValue(values.get(index));
    }
  }
}

package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelTemplateInfoResponse;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteExcelTemplateServiceImplTest {
  private QuoteExcelTemplateServiceImpl templateService;
  private QuoteExcelImportServiceImpl importService;

  @BeforeEach
  void setUp() {
    templateService = new QuoteExcelTemplateServiceImpl();
    QuoteNormalizeService normalizeService =
        new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService());
    importService = new QuoteExcelImportServiceImpl(normalizeService, mock(QuoteIngestService.class));
  }

  @Test
  void listTemplatesReturnsSixTemplateTypes() {
    List<QuoteExcelTemplateInfoResponse> templates = templateService.listTemplates();

    assertThat(templates).hasSize(6);
    assertThat(templates)
        .extracting(QuoteExcelTemplateInfoResponse::getTemplateType)
        .containsExactly(
            "FI-SC-020",
            "FI-SC-006",
            "FI-SC-005",
            "FI-SR-005_NEW",
            "FI-SR-005_MASS",
            "FI-SR-005_DERIVED");
  }

  @Test
  void allTemplateResourcesKeepOnlyOaFormVisibleAndMappingReadable() throws Exception {
    for (QuoteExcelTemplateInfoResponse template : templateService.listTemplates()) {
      QuoteExcelTemplateFile file = templateService.getTemplate(template.getTemplateType());

      assertThat(file.getContent()).as(template.getTemplateType()).isNotEmpty();
      assertWorkbookSheetState(template, file);

      QuoteExcelImportPreviewResponse preview =
          importService.preview(new ByteArrayInputStream(file.getContent()), file.getFileName());
      assertThat(preview.getForms()).as(template.getTemplateType()).hasSize(1);
      assertThat(preview.getForms().get(0).getProcessCode()).as(template.getTemplateType()).isNotBlank();
    }
  }

  @Test
  void unknownTemplateTypeThrowsReadableException() {
    assertThatThrownBy(() -> templateService.getTemplate("UNKNOWN"))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("未知报价单模板类型");
  }

  private void assertWorkbookSheetState(
      QuoteExcelTemplateInfoResponse template, QuoteExcelTemplateFile file) throws Exception {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getContent()))) {
      List<String> visibleSheets = new ArrayList<>();
      for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
        if (SheetVisibility.VISIBLE.equals(workbook.getSheetVisibility(index))) {
          visibleSheets.add(workbook.getSheetName(index));
        }
      }
      assertThat(visibleSheets).as(template.getTemplateType()).containsExactly("OA原始表单");
      assertThat(workbook.getSheetIndex("解析字段映射")).as(template.getTemplateType()).isGreaterThanOrEqualTo(0);
      assertThat(workbook.getSheetVisibility(workbook.getSheetIndex("解析字段映射")))
          .as(template.getTemplateType())
          .isEqualTo(SheetVisibility.HIDDEN);
      if ("FI-SC-020".equals(template.getTemplateType())) {
        assertFiSc020MatchesOaHeaderAndDetailShape(workbook);
      }
      if ("FI-SC-006".equals(template.getTemplateType())) {
        assertFiSc006MatchesOaHeaderAndDetailShape(workbook);
      }
    }
  }

  private void assertFiSc020MatchesOaHeaderAndDetailShape(Workbook workbook) {
    Sheet sheet = workbook.getSheet("OA原始表单");
    DataFormatter formatter = new DataFormatter();
    FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

    assertThat(cell(sheet, 0, 0, formatter, evaluator)).isEqualTo("FI-SC-020.成本核算联系单（板换科技-直销）");
    assertThat(cell(sheet, 0, 0, formatter, evaluator)).isNotEqualTo("sourceType");
    assertThat(cell(sheet, 7, 12, formatter, evaluator)).isEqualTo("流程编号");
    assertThat(cell(sheet, 7, 16, formatter, evaluator)).isEqualTo("FI-SC-020-20260418-001");
    assertThat(cell(sheet, 11, 0, formatter, evaluator)).isEqualTo(">>申请信息");
    assertThat(cell(sheet, 12, 12, formatter, evaluator)).isBlank();
    assertThat(cell(sheet, 12, 16, formatter, evaluator)).isBlank();
    assertThat(cell(sheet, 27, 1, formatter, evaluator)).isEqualTo("序号");
    assertThat(cell(sheet, 27, 14, formatter, evaluator)).isEqualTo("成本失效日期");
    assertThat(cell(sheet, 27, 15, formatter, evaluator)).contains("SUS304");
    assertThat(cell(sheet, 27, 17, formatter, evaluator)).contains("铜重");
    assertThat(cell(sheet, 28, 2, formatter, evaluator)).isEqualTo("31219");
    assertThat(cell(sheet, 28, 6, formatter, evaluator)).contains("板式");
  }

  private void assertFiSc006MatchesOaHeaderAndDetailShape(Workbook workbook) {
    Sheet sheet = workbook.getSheet("OA原始表单");
    DataFormatter formatter = new DataFormatter();
    FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

    assertThat(cell(sheet, 0, 0, formatter, evaluator)).isEqualTo("FI-SC-006.标准品/批量品成本核算流程");
    assertThat(cell(sheet, 0, 0, formatter, evaluator)).isNotEqualTo("sourceType");
    assertThat(cell(sheet, 6, 12, formatter, evaluator)).isEqualTo("流程编号");
    assertThat(cell(sheet, 6, 16, formatter, evaluator)).isEqualTo("FI-SC-006-20260421-007");
    assertThat(cell(sheet, 28, 2, formatter, evaluator)).isEqualTo("产品名称");
    assertThat(cell(sheet, 28, 12, formatter, evaluator)).isEqualTo("直接材料费");
    assertThat(cell(sheet, 28, 21, formatter, evaluator)).contains("工装");
    assertThat(cell(sheet, 29, 2, formatter, evaluator)).contains("板式");
  }

  private String cell(Sheet sheet, int rowIndex, int columnIndex, DataFormatter formatter, FormulaEvaluator evaluator) {
    return formatter.formatCellValue(sheet.getRow(rowIndex).getCell(columnIndex), evaluator);
  }
}

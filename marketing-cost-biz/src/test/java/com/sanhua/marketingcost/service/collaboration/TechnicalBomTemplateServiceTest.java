package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftResponse;
import com.sanhua.marketingcost.service.collaboration.TechnicalBomDraftApplicationService.ElectronicBomTemplateSnapshot;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-11 电子图库BOM模板契约")
class TechnicalBomTemplateServiceTest {

  @Test
  void exportsEveryNodeWithExplicitParentRelationshipAndUsage() throws Exception {
    TechnicalBomDraftApplicationService draftService = mock(TechnicalBomDraftApplicationService.class);
    TechnicalBomDraftResponse.Node root = node("R", null, 0, "P-1", "产品", "1", "1");
    TechnicalBomDraftResponse.Node child = node("C", "R", 1, "C-1", "铜管", "0.286", "0.286");
    TechnicalBomDraftResponse draft = new TechnicalBomDraftResponse(9L, 4, "U9_COPY",
        "REF-1", true, List.of(), List.of(root), List.of(root, child));
    when(draftService.exportSnapshot(10L)).thenReturn(new ElectronicBomTemplateSnapshot(
        10L, 4, "P-1", null, "产品", "S", "M", "DRAW-001", "COMMERCIAL", "210", draft));

    var file = new TechnicalBomTemplateService(draftService).export(10L);

    assertThat(file.fileName()).contains("P-1");
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(file.bytes()))) {
      var sheet = workbook.getSheet("BOM导入");
      assertThat(sheet.getLastRowNum()).isEqualTo(2);
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("行号");
      assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("父项料号");
      assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("C");
      assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("R");
      assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("P-1");
      assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("C-1");
      assertThat(sheet.getRow(2).getCell(11).getNumericCellValue()).isEqualTo(0.286d);
    }
  }

  private TechnicalBomDraftResponse.Node node(
      String id, String parent, int level, String code, String name, String quantity, String top) {
    return new TechnicalBomDraftResponse.Node(id, parent, level, code, false, name, "规格",
        "型号", "图号", level == 0 ? "MANUFACTURE" : "PURCHASE",
        new BigDecimal(quantity), new BigDecimal(top), "件", level + 1, false, List.of());
  }
}

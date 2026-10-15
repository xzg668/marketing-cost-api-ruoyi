package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.formula.normalize.*;
import com.sanhua.marketingcost.service.PriceLinkedFactorWorkbookParser;
import org.junit.jupiter.api.Test;

class TechnicalFactorPreflightTest {
  @Test
  void oneValidRowCannotHideAnotherInvalidFactorRow() {
    var parser = mock(PriceLinkedFactorWorkbookParser.class);
    var parsed = new FactorWorkbookParseResult();
    var sheet = new FactorSheetParseResult();
    sheet.setSheetName("影响因素");
    sheet.getRows().add(new FactorRowParseResult());
    sheet.getErrors().add(new FactorSheetParseResult.ParseError(3, "价格不是有效数值"));
    parsed.getSheets().add(sheet);
    when(parser.parse(any(), any())).thenReturn(parsed);
    var workbook =
        new TechnicalPriceCorrectionWorkbook(
            mock(FormulaNormalizer.class), mock(FormulaValidator.class), parser);
    assertThatThrownBy(() -> workbook.factorRowCount(new byte[] {1}))
        .hasMessageContaining("影响因素")
        .hasMessageContaining("第3行")
        .hasMessageContaining("有效数值");
  }
}

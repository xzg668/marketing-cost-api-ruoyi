package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.technicaldata.*;
import org.junit.jupiter.api.Test;

/** 公共原稿统一走完整预检；技术补录关联不得绕过原审批入口。 */
class PriceLinkedStandardImportDispatchRegressionTest {
  private final PriceLinkedWorkbookTypeDetector detector=mock(PriceLinkedWorkbookTypeDetector.class);
  private final PriceLinkedItemService standard=mock(PriceLinkedItemService.class);
  private final PriceLinkedType2ImportOrchestrator type2=mock(PriceLinkedType2ImportOrchestrator.class);
  private final TechnicalPriceCorrectionWorkbook workbook=mock(TechnicalPriceCorrectionWorkbook.class);
  private final OriginalLinkedImportService originals=mock(OriginalLinkedImportService.class);
  private final PriceLinkedImportDispatchServiceImpl service=new PriceLinkedImportDispatchServiceImpl(
      detector, standard, type2, mock(TechnicalPriceCorrectionService.class),workbook,
      mock(TechnicalPriceCorrectionImportService.class),mock(TechnicalDataActorProvider.class),originals);

  @Test
  void bothPublicLayoutsUseFullPrecheckAndAtomicPublication() {
    for(String name:new String[]{"标准原稿.xlsx","类型2原稿.xls"}) {
      var command=new PriceLinkedImportCommand(new byte[]{1},name,"2026-10","COMMERCIAL",false,null,null,null,"file").withPreviewFingerprint("version");
      var preview=new PriceLinkedType2ImportPreviewResponse();
      preview.setCanConfirm(false);
      when(originals.preview(command)).thenReturn(preview);
      assertThat(service.preview(command)).isSameAs(preview);
      var response=new PriceItemImportResponse();
      when(originals.confirm(command)).thenReturn(response);
      assertThat(service.confirm(command)).isSameAs(response);
      verify(originals).confirm(command);
    }
    verifyNoInteractions(detector, standard, type2);
  }

  @Test
  void associationWorkbookCannotPublishThroughPublicEntry() {
    when(workbook.hasAssociation(any())).thenReturn(true);
    var command=new PriceLinkedImportCommand(new byte[]{1},"补录.xlsx","2026-10","COMMERCIAL",false,null,null,null,null);
    assertThatThrownBy(()->service.preview(command)).hasMessageContaining("原产品核算页");
    assertThatThrownBy(()->service.confirm(command)).hasMessageContaining("原产品核算页");
    verifyNoInteractions(originals,standard,type2);
  }
}

package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalDataPackageSourceCheckTest {
  private final TechnicalDataPackageSourceQuery sources = mock(TechnicalDataPackageSourceQuery.class);
  private final QuoteProductTypeResolveService types = mock(QuoteProductTypeResolveService.class);
  private final ElectronicDrawingWorkflowContextPort drawings = mock(ElectronicDrawingWorkflowContextPort.class);
  private final QuoteBomSupplementDetailMapper details = mock(QuoteBomSupplementDetailMapper.class);
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final TechnicalDataSharedModules shared = mock(TechnicalDataSharedModules.class);
  private final TechnicalDataPackageSourceCheck check = new TechnicalDataPackageSourceCheck(sources, types,
      drawings, details, materials, new OaMessageCodec(new ObjectMapper().findAndRegisterModules()), shared);
  private final QuoteBomReadContext context = new QuoteBomReadContext(1L, 2L, "OA", "2026-09", "COMMERCIAL",
      "PRODUCT", "产品", null, null, "210", "COMMERCIAL", LocalDate.of(2026, 9, 1), LocalDateTime.now());

  @Test void existingBomWithoutPackageIsMissingButReadFailureIsNeverTreatedAsMissing() {
    when(sources.inspectProduct("PRODUCT", "2026-09", "COMMERCIAL", "210"))
        .thenReturn(new TechnicalDataPackageSourceQuery.ProductPackaging(List.of(), List.of()));
    assertThat(check.check(context, TechnicalDataAvailability.AVAILABLE).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    when(sources.inspectProduct(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("timeout"));
    assertThat(check.check(context, TechnicalDataAvailability.AVAILABLE).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
  }

  @Test void bareProductCanFillPackagingWithoutWaitingForDrawing() {
    when(types.resolve("PRODUCT", "COMMERCIAL")).thenReturn(new QuoteProductTypeResolveResult("PRODUCT", QuoteProductType.BARE, "11", null, null, null, null));
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
  }

  @Test void nonBareUnknownDrawingDoesNotCreateSpeculativePackagingTodo() {
    when(types.resolve("PRODUCT", "COMMERCIAL")).thenReturn(new QuoteProductTypeResolveResult("PRODUCT", QuoteProductType.NON_BARE, "12", null, null, null, null));
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
    assertThat(check.check(context, TechnicalDataAvailability.ERROR).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
  }

  @Test void sharedDrawingUsesOriginalQuoteItemAndMonthForPackageAssessment() {
    when(shared.find("PRODUCT", 2L, "DRAWING_BOM")).thenReturn(owner("COMMERCIAL", "210", 90L, "2026-08"));
    verifiedPackage(90L, "2026-08");
    var result = check.check(context, TechnicalDataAvailability.MISSING);
    assertThat(result.availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    assertThat(result.sourceReference()).isEqualTo("DRAWING:42");
    verify(drawings).load(90L, "COMMERCIAL", "210", "2026-08");
    verify(drawings, never()).load(2L, "COMMERCIAL", "210", "2026-09");
  }

  @Test void sameQuoteItemInAnotherMonthStillUsesOriginalDrawingMonth() {
    when(shared.find("PRODUCT", 2L, "DRAWING_BOM")).thenReturn(owner("COMMERCIAL", "210", 2L, "2026-08"));
    verifiedPackage(2L, "2026-08");
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability())
        .isEqualTo(TechnicalDataAvailability.AVAILABLE);
    verify(drawings).load(2L, "COMMERCIAL", "210", "2026-08");
  }

  @Test void sharedDrawingCannotCrossBusinessUnitOrPriceOrganization() {
    for (var owner : List.of(owner("HOUSEHOLD", "210", 90L, "2026-09"),
        owner("COMMERCIAL", "220", 90L, "2026-09"))) {
      when(shared.find("PRODUCT", 2L, "DRAWING_BOM")).thenReturn(owner);
      var result = check.check(context, TechnicalDataAvailability.MISSING);
      assertThat(result.availability()).isEqualTo(TechnicalDataAvailability.ERROR);
      assertThat(result.reason()).contains("组织与本报价不一致");
    }
    verifyNoInteractions(drawings, details, materials);
  }

  @Test void failedSharedLookupIsAnErrorAndNeverFallsBackToCurrentDrawing() {
    when(shared.find("PRODUCT", 2L, "DRAWING_BOM")).thenThrow(new IllegalStateException("database failed"));
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability())
        .isEqualTo(TechnicalDataAvailability.ERROR);
    verifyNoInteractions(drawings, details, materials);
  }

  private TechnicalDataSharedModuleRepository.Owner owner(String unit, String org, long item, String month) {
    return new TechnicalDataSharedModuleRepository.Owner(11L, 12L, 13L, item, "PRODUCT", "DRAWING_BOM",
        "SUBMITTED", "SUBMITTED", 100L, "技术员", 21L, "SUBMITTED", unit, org, month);
  }

  private void verifiedPackage(long item, String month) {
    var drawing = new ElectronicDrawingWorkContext(11L, 1, 21L, 42L, 1L, item,
        "TASK", "OA", "PRODUCT", null, "产品", null, null, "NON_BARE", "COMMERCIAL", month,
        "210", "COMMERCIAL", "COMMERCIAL", "210", true, true, "READY_FOR_COSTING", "COSTING",
        null, null, "verified");
    when(drawings.load(item, "COMMERCIAL", "210", month)).thenReturn(drawing);
    var parent = new com.sanhua.marketingcost.entity.QuoteBomSupplementDetail();
    parent.setMaterialCode("PK"); parent.setPath("/PRODUCT/PK/"); parent.setLevel(1);
    var child = new com.sanhua.marketingcost.entity.QuoteBomSupplementDetail();
    child.setMaterialCode("BOX"); child.setParentCode("PK"); child.setPath("/PRODUCT/PK/BOX/"); child.setLevel(2);
    when(details.selectList(any())).thenReturn(List.of(parent, child));
    var material = new com.sanhua.marketingcost.entity.MaterialMasterRaw();
    material.setMaterialCode("PK"); material.setMainCategoryName("包装组件");
    when(materials.selectByLatestBatchAndCodes(java.util.Set.of("PK", "BOX"), null, "COMMERCIAL"))
        .thenReturn(List.of(material));
  }

  @Test void bareClassificationDoesNotOverrideAnAlreadyVerifiedDrawingPackage() {
    when(types.resolve("PRODUCT", "COMMERCIAL")).thenReturn(new QuoteProductTypeResolveResult("PRODUCT", QuoteProductType.BARE, "11", null, null, null, null));
    var drawing = new ElectronicDrawingWorkContext(11L, 1, 21L, 42L, 1L, 2L,
        "TASK", "OA", "PRODUCT", null, "产品", null, null, "BARE", "COMMERCIAL", "2026-09",
        "210", "COMMERCIAL", "COMMERCIAL", "210", true, true, "READY_FOR_COSTING", "COSTING",
        null, null, "verified");
    when(drawings.load(any(), any(), any(), any())).thenReturn(drawing);
    var parent = new com.sanhua.marketingcost.entity.QuoteBomSupplementDetail();
    parent.setMaterialCode("PK"); parent.setPath("/PRODUCT/PK/"); parent.setLevel(1);
    var child = new com.sanhua.marketingcost.entity.QuoteBomSupplementDetail();
    child.setMaterialCode("BOX"); child.setParentCode("PK"); child.setPath("/PRODUCT/PK/BOX/"); child.setLevel(2);
    when(details.selectList(any())).thenReturn(List.of(parent, child));
    var material = new com.sanhua.marketingcost.entity.MaterialMasterRaw(); material.setMaterialCode("PK");
    material.setMainCategoryName("包装组件");
    when(materials.selectByLatestBatchAndCodes(java.util.Set.of("PK", "BOX"), null, "COMMERCIAL")).thenReturn(List.of(material));
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    verify(materials).selectByLatestBatchAndCodes(java.util.Set.of("PK", "BOX"), null, "COMMERCIAL");
    verify(materials, never()).selectPackageComponentParentsByLatestBatch(any(), any(), any());

    var emptyParent = new com.sanhua.marketingcost.entity.QuoteBomSupplementDetail();
    emptyParent.setMaterialCode("PK"); emptyParent.setPath("/PRODUCT/OTHER/PK/"); emptyParent.setLevel(2);
    when(details.selectList(any())).thenReturn(List.of(parent, child, emptyParent));
    var missing = check.check(context, TechnicalDataAvailability.MISSING);
    assertThat(missing.availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    assertThat(missing.reason()).contains("PK");
    when(details.selectList(any())).thenReturn(List.of(parent, child));

    material.setMainCategoryName("普通组件");
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    material.setMainCategoryName("包装组件");
    child.setParentCode("OTHER");
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    when(materials.selectByLatestBatchAndCodes(anyCollection(), isNull(), eq("COMMERCIAL")))
        .thenThrow(new IllegalStateException("source unavailable"));
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
  }
}

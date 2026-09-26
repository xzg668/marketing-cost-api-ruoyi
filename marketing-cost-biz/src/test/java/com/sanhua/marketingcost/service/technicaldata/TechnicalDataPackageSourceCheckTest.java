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
  private final TechnicalDataPackageSourceCheck check = new TechnicalDataPackageSourceCheck(sources, types,
      drawings, details, materials, new OaMessageCodec(new ObjectMapper().findAndRegisterModules()));
  private final QuoteBomReadContext context = new QuoteBomReadContext(1L, 2L, "OA", "2026-09", "COMMERCIAL",
      "PRODUCT", "产品", null, null, "210", "COMMERCIAL", LocalDate.of(2026, 9, 1), LocalDateTime.now());

  @Test void existingBomWithoutPackageIsMissingButReadFailureIsNeverTreatedAsMissing() {
    when(sources.forProduct("PRODUCT", "2026-09", "COMMERCIAL", "210")).thenReturn(List.of());
    assertThat(check.check(context, TechnicalDataAvailability.AVAILABLE).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    when(sources.forProduct(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("timeout"));
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
    when(materials.selectPackageComponentParentsByLatestBatch("包装组件", null, "COMMERCIAL")).thenReturn(List.of(material));
    assertThat(check.check(context, TechnicalDataAvailability.MISSING).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
  }
}

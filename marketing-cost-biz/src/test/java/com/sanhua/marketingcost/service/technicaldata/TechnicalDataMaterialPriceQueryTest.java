package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Manufacturing;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Solder;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.SolderItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalDataMaterialPriceQueryTest {
  private final MakePartMaterialPriceResolveService prices = mock(MakePartMaterialPriceResolveService.class);
  private final TechnicalDataMaterialPriceQuery query = new TechnicalDataMaterialPriceQuery(prices);
  private final ElectronicDrawingWorkContext context = new ElectronicDrawingWorkContext(11L, 1, 21L, 31L, 1L, 11L,
      "TASK", "OA", "TOP", null, null, null, null, null, "FULL_BOM", "2026-09", "210", "COMMERCIAL", "COMMERCIAL", "210",
      true, true, "NEED_TECH", "MATERIALS_MATCHED", null, null, null);

  @Test void sharedRawAndCmsScrapAreCheckedOnceAndRemovingLastReferenceRemovesDemand() throws Exception {
    when(prices.calculateMaterialUnitPrice(eq("RAW"), eq("2026-09"), any(), any(), eq("OA"), eq("COMMERCIAL"), any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class)))
        .thenReturn(MakePartMaterialPriceResolveResult.ok("RAW", "固定价", new BigDecimal("66"), "现有价格", null));
    when(prices.calculateMaterialUnitPrice(eq("SCRAP"), eq("2026-09"), any(), any(), eq("OA"), eq("COMMERCIAL"), any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class)))
        .thenReturn(MakePartMaterialPriceResolveResult.miss("SCRAP", "MISSING_PRICE", "缺价格", null));
    var content = content("MATCHED");
    var result = query.check(context, content);
    assertThat(result).extracting(row -> row.materialNo() + ":" + row.status()).containsExactly("RAW:OK", "SCRAP:MISSING_PRICE");
    verify(prices, times(2)).calculateMaterialUnitPrice(any(), eq("2026-09"), any(), any(), eq("OA"), eq("COMMERCIAL"),
        argThat(scope -> scope != null && "210".equals(scope.supplierReviews().priceOrgCode())
            && Long.valueOf(11L).equals(scope.supplierReviews().oaFormItemId())), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class));
    verify(prices, times(2)).calculateMaterialUnitPrice(any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class));
    assertThat(query.check(context, new Manufacturing(List.of(content.items().getFirst()), null))).hasSize(2);
    clearInvocations(prices);
    assertThat(query.check(context, new Manufacturing(List.of(), null))).isEmpty();
    verifyNoInteractions(prices);
  }

  @Test void AmbiguousMappingAndFailedLookupNeverBecomeInventedScrapOrZeroPrice() throws Exception {
    when(prices.calculateMaterialUnitPrice(any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class))).thenThrow(new IllegalStateException("source timeout"));
    var result = query.check(context, content("AMBIGUOUS"));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().materialNo()).isEqualTo("RAW");
    assertThat(result.getFirst().status()).isEqualTo("ERROR");
    assertThat(result.getFirst().unitPrice()).isNull();
  }

  @Test void solderUsesTheTaskOrganizationRatherThanTheLoginBusinessUnit() {
    var task = new QuoteTechTask();
    task.setOaNo("OA"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("220");
    var product = new QuoteTechProduct();
    product.setOaFormItemId(77L); product.setAccountingMonth("2026-10");
    var content = new Solder(List.of(new SolderItem("solder", "SOLDER", null, BigDecimal.ONE, "kg", null, null, null)), "MANUAL", null);
    when(prices.calculateMaterialUnitPrice(eq("SOLDER"), eq("2026-10"), any(), any(), eq("OA"), eq("COMMERCIAL"),
        argThat(scope -> scope != null && "220".equals(scope.supplierReviews().priceOrgCode())
            && Long.valueOf(77L).equals(scope.supplierReviews().oaFormItemId())), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class)))
        .thenReturn(MakePartMaterialPriceResolveResult.ok("SOLDER", "固定价", BigDecimal.TEN, "有效固定价", null));
    assertThat(query.check(task, product, content)).singleElement().satisfies(item -> {
      assertThat(item.status()).isEqualTo("OK");
      assertThat(item.unitPrice()).isEqualByComparingTo(BigDecimal.TEN);
    });
  }

  private Manufacturing content(String status) throws Exception {
    String row = "{\"rawMaterialNo\":\"RAW\",\"evidence\":{\"scrapStatus\":\"" + status
        + "\",\"scrapMappings\":[{\"materialNo\":\"SCRAP\",\"unit\":\"kg\"}]}}";
    return new ObjectMapper().readValue("{\"items\":[" + row + "," + row + "]}", Manufacturing.class);
  }
}

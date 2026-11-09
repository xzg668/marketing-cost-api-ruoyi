package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplierPriceReviewMaterialsTest {
  @Test void sameCodeReadsItsActualOrganizationAndKeepsMissingModelBlank() {
    var mapper = mock(MaterialMasterRawMapper.class);
    var commercial = new MaterialMasterRaw();
    commercial.setMaterialCode("M"); commercial.setMaterialName("商用料");
    commercial.setMaterialSpec("商用规格");
    var plate = new MaterialMasterRaw();
    plate.setMaterialCode("M"); plate.setMaterialName("板换料");
    plate.setMaterialSpec("板换规格"); plate.setMaterialModel("板换型号");
    when(mapper.selectByLatestBatchAndCodes(anyCollection(), isNull(), eq("COMMERCIAL"))).thenReturn(List.of(commercial));
    when(mapper.selectByLatestBatchAndCodes(anyCollection(), isNull(), eq("PLATE"))).thenReturn(List.of(plate));
    var result = service(mapper).describe(List.of(review("210"), review("220")));
    assertThat(result).extracting(SupplierPriceReviewMaterials.Material::materialName).containsExactly("商用料", "板换料");
    assertThat(result.getFirst().specification()).isEqualTo("商用规格");
    assertThat(result.getFirst().model()).isNull();
    assertThat(result.get(1).model()).isEqualTo("板换型号");
    verify(mapper, times(2)).selectByLatestBatchAndCodes(anyCollection(), isNull(), anyString());
  }

  @Test void noReviewsSkipsMasterLookupAndMissingMasterDoesNotBorrowAnotherOrganization() {
    var mapper = mock(MaterialMasterRawMapper.class);
    var service = service(mapper);
    assertThat(service.describe(List.of())).isEmpty();
    verifyNoInteractions(mapper);
    assertThat(service.describe(List.of(review("220"))).getFirst().materialName()).isNull();
    verify(mapper).selectByLatestBatchAndCodes(anyCollection(), isNull(), eq("PLATE"));
    verifyNoMoreInteractions(mapper);
  }

  private SupplierPriceReview review(String organization) {
    return new SupplierPriceReview(organization, "fp", new SupplierPriceScope("COMMERCIAL", "OA", 1L,
        "2026-11", LocalDate.of(2026, 11, 8), organization, "M", "FIXED"), List.of(), "PENDING", null, null, "待补齐");
  }

  @Test void restoresOnlyThisProductsAdoptedHighestReviewAndNeverOverwritesFreshPendingReview() {
    var mapper = mock(MaterialMasterRawMapper.class);
    var prices = mock(com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceStorage.class);
    var codec = new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper());
    var service = new SupplierPriceReviewMaterials(mapper, mock(com.sanhua.marketingcost.mapper.PricePrepareItemMapper.class), prices, codec);
    var pending = review("210");
    var highest = new SupplierPriceReview(pending.scopeKey(), pending.fingerprint(), pending.scope(), pending.candidates(), "FALLBACK_HIGH", "finance", null, "已确认最高价");
    var family = new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceFamilyKey(
        java.time.YearMonth.of(2026, 11), "COMMERCIAL", "M", null, "件", "CNY",
        com.sanhua.marketingcost.service.pricing.monthly.TaxBasis.EXCLUDED, null);
    var saved = new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceStorage.StoredPrice(
        11L, family, null, null, null, null, null, null,
        "{\"supplierReviews\":[" + codec.unwrap(codec.encode(highest)) + "]}");
    when(prices.readByIds(eq("COMMERCIAL"), anyCollection())).thenReturn(List.of(saved));
    var response = new com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse();
    response.setOaNo("OA"); response.setOaFormItemId(1L); response.setPeriodMonth("2026-11");
    var row = new com.sanhua.marketingcost.entity.PricePrepareItem();
    row.setOaNo("OA"); row.setOaFormItemId(1L); row.setPeriodMonth("2026-11");
    row.setBusinessUnitType("COMMERCIAL"); row.setPriceOrgCode("210"); row.setMonthlyPriceId(11L);
    service.restoreHighest(response, List.of(row));
    assertThat(response.getSupplierPriceReviews()).containsExactly(highest);
    response.setSupplierPriceReviews(List.of(pending));
    service.restoreHighest(response, List.of(row));
    assertThat(response.getSupplierPriceReviews()).containsExactly(pending);
    response.setSupplierPriceReviews(List.of()); row.setPriceOrgCode("220");
    service.restoreHighest(response, List.of(row));
    assertThat(response.getSupplierPriceReviews()).isEmpty();
    response.setOaFormItemId(2L); row.setOaFormItemId(2L); row.setPriceOrgCode("210");
    service.restoreHighest(response, List.of(row));
    assertThat(response.getSupplierPriceReviews()).isEmpty();
    response.setOaFormItemId(1L); row.setOaFormItemId(1L); response.setPeriodMonth("2026-12"); row.setPeriodMonth("2026-12");
    service.restoreHighest(response, List.of(row));
    assertThat(response.getSupplierPriceReviews()).isEmpty();
  }

  private SupplierPriceReviewMaterials service(MaterialMasterRawMapper mapper) {
    return new SupplierPriceReviewMaterials(mapper, mock(com.sanhua.marketingcost.mapper.PricePrepareItemMapper.class),
        mock(com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceStorage.class),
        new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
  }
}

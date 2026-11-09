package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.impl.SupplierSupplyRatioWorkbookParserImpl;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupplierPriceReviewExportServiceTest {
  private QuotePricePrepareWorkbenchService prices;
  private SupplierSupplyRatioMapper ratios;
  private SupplierPriceReviewExportService service;
  private QuotePricePrepareWorkbenchResponse response;

  @BeforeEach void setup() {
    prices = mock(QuotePricePrepareWorkbenchService.class);
    ratios = mock(SupplierSupplyRatioMapper.class);
    service = new SupplierPriceReviewExportService(prices, ratios);
    response = new QuotePricePrepareWorkbenchResponse();
    when(prices.getPricePrepare("OA", 1L, "2026-11")).thenReturn(new QuotePricePrepareWorkbenchResponse());
    when(prices.checkPriceSources(eq("OA"), eq(1L), any())).thenReturn(response);
  }

  @Test void exportedWorkbookRoundTripsAllRelationsWithSeparateSpecificationAndModel() throws Exception {
    var first = review("pending", "000123", "PENDING", "S001", "S002");
    var second = review("highest", "M2", "FALLBACK_HIGH", "S005");
    response.setSupplierPriceReviews(List.of(first, second));
    response.setSupplierPriceReviewMaterials(List.of(material("pending", "真正型号"), material("highest", null)));
    var configured = relation("000123", "S001", "供应商S001", "0.000001");
    configured.setSelectionRule("无供货比率，取最高价");
    when(ratios.currentRelations(eq("COMMERCIAL"), isNull(), anyCollection())).thenReturn(List.of(
        configured,
        relation("000123", "S003", "非候选零比率供应商", "0"),
        relation("000123", "S004", "非候选空比率供应商", null)));
    byte[] bytes = service.export("OA", 1L, "2026-11");
    var parsed = new SupplierSupplyRatioWorkbookParserImpl().parse(new ByteArrayInputStream(bytes), "补齐.xlsx", null);
    assertThat(parsed.getErrors()).isEmpty();
    assertThat(parsed.getRows()).hasSize(5);
    assertThat(parsed.getRows().getFirst().getSelectionRule()).isEqualTo("无供货比率，取最高价");
    assertThat(parsed.getRows().getFirst().getMaterialCode()).isEqualTo("000123");
    assertThat(parsed.getRows().getFirst().getSpecModel()).isEqualTo("真正型号");
    assertThat(parsed.getRows().getFirst().getSupplyRatio()).isEqualByComparingTo("0.000001");
    assertThat(parsed.getRows()).filteredOn(row -> "S003".equals(row.getSupplierCode()))
        .singleElement().satisfies(row -> assertThat(row.getSupplyRatio()).isZero());
    assertThat(parsed.getRows()).filteredOn(row -> "S002".equals(row.getSupplierCode()))
        .singleElement().satisfies(row -> assertThat(row.getSupplyRatio()).isNull());
    assertThat(parsed.getRows()).filteredOn(row -> "M2".equals(row.getMaterialCode()))
        .singleElement().satisfies(row -> assertThat(row.getSpecModel()).isNull());
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      var sheet = book.getSheetAt(0);
      assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
      assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("真正规格");
      assertThat(sheet.getRow(5).getCell(9).getStringCellValue()).contains("最高价");
      assertThat(sheet.getRow(0).getCell(8).getCellComment().getString().getString()).contains("完整供应商集合");
    }
    var request = ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest.class);
    verify(prices).checkPriceSources(eq("OA"), eq(1L), request.capture());
    assertThat(request.getValue().getPeriodMonth()).isEqualTo("2026-11");
    assertThat(request.getValue().isRefreshCandidates()).isTrue();
    verify(prices).getPricePrepare("OA", 1L, "2026-11");
    verifyNoMoreInteractions(prices);
    assertThat(response.getSupplierPriceReviews()).containsExactly(first, second);
  }

  @Test void repeatedReviewScopesMergeSuppliersAndRetainExistingNameOnlyRatio() throws Exception {
    response.setSupplierPriceReviews(List.of(review("one", "M", "WAIT_IMPORT", "S1"), review("two", "M", "FALLBACK_HIGH", "S1", "S2")));
    response.setSupplierPriceReviewMaterials(List.of(material("one", "型号"), material("two", "型号")));
    when(ratios.currentRelations(anyString(), isNull(), anyCollection())).thenReturn(List.of(relation("M", null, "供应商S1", "0.6")));
    byte[] bytes = service.export("OA", 1L, "2026-11");
    var parsed = new SupplierSupplyRatioWorkbookParserImpl().parse(new ByteArrayInputStream(bytes), "a.xlsx", null);
    assertThat(parsed.getErrors()).isEmpty();
    assertThat(parsed.getRows()).hasSize(2);
    assertThat(parsed.getRows().getFirst().getSupplierCode()).isEqualTo("S1");
    assertThat(parsed.getRows().getFirst().getSupplyRatio()).isEqualByComparingTo("0.6");
  }

  @Test void emptyReviewDoesNotExportOtherMaterials() {
    assertThatIllegalArgumentException().isThrownBy(() -> service.export("OA", 1L, "2026-11")).withMessageContaining("没有");
    verifyNoInteractions(ratios);
  }

  @Test void inconsistentOrganizationMetadataCannotBeSilentlyMerged() {
    response.setSupplierPriceReviews(List.of(review("one", "M", "PENDING", "S1"), review("two", "M", "PENDING", "S2")));
    response.setSupplierPriceReviewMaterials(List.of(material("one", "型号A"), material("two", "型号B")));
    assertThatIllegalArgumentException().isThrownBy(() -> service.export("OA", 1L, "2026-11")).withMessageContaining("档案不一致");
    verifyNoInteractions(ratios);
  }

  @Test void duplicateCurrentSupplierVersionsRequireExplicitReview() {
    response.setSupplierPriceReviews(List.of(review("one", "M", "PENDING", "S1")));
    response.setSupplierPriceReviewMaterials(List.of(material("one", "型号")));
    when(ratios.currentRelations(anyString(), isNull(), anyCollection())).thenReturn(List.of(
        relation("M", "S1", "供应商S1", "0.6"), relation("M", "S1", "供应商S1", "0.7")));
    assertThatIllegalArgumentException().isThrownBy(() -> service.export("OA", 1L, "2026-11")).withMessageContaining("多个当前版本");
  }

  @Test void exportIsReadOnly() throws Exception {
    assertThat(SupplierPriceReviewExportService.class.getMethod("export", String.class, Long.class, String.class)
        .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
  }

  @Test void adoptedMonthlyPriceStillExportsTheSavedHighestPriceReview() throws Exception {
    var saved = new QuotePricePrepareWorkbenchResponse();
    saved.setSupplierPriceReviews(List.of(review("saved", "M", "FALLBACK_HIGH", "S1", "S2")));
    saved.setSupplierPriceReviewMaterials(List.of(material("saved", "型号")));
    when(prices.getPricePrepare("OA", 1L, "2026-11")).thenReturn(saved);
    var parsed = new SupplierSupplyRatioWorkbookParserImpl().parse(
        new ByteArrayInputStream(service.export("OA", 1L, "2026-11")), "补齐.xlsx", null);
    assertThat(parsed.getErrors()).isEmpty();
    assertThat(parsed.getRows()).hasSize(2);
  }

  private SupplierPriceReviewMaterials.Material material(String scope, String model) {
    return new SupplierPriceReviewMaterials.Material(scope, "物料名称", "真正规格", model, "件", "采购件");
  }

  private SupplierPriceReview review(String scope, String material, String status, String... suppliers) {
    var candidates = java.util.Arrays.stream(suppliers).map(code -> new SupplierPriceReview.Candidate(
        new SupplierPriceCandidate(code, code, "供应商" + code, BigDecimal.TEN, null, null), null)).toList();
    return new SupplierPriceReview(scope, "fp", new SupplierPriceScope("COMMERCIAL", "OA", 1L,
        "2026-11", LocalDate.of(2026, 11, 8), "210", material, "FIXED"), candidates, status, null, null, "待补齐");
  }

  private MonthlySupplierRatioInput relation(String material, String code, String name, String ratio) {
    var row = new MonthlySupplierRatioInput(); row.setRequestedMaterialCode(material); row.setMaterialCode(material);
    row.setSupplierCode(code); row.setSupplierName(name); row.setSupplyRatio(ratio == null ? null : new BigDecimal(ratio));
    return row;
  }
}

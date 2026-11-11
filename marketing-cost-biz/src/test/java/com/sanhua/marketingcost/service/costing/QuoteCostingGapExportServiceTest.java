package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestItemResponse;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class QuoteCostingGapExportServiceTest {
  private QuoteRequestItemResponse product(String code) {
    var item=new QuoteRequestItemResponse();item.setMaterialNo(code);return item;
  }
  @Test void repeatedBomMaterialsAreGroupedByCodeAndRetainAffectedProducts() {
    var gaps=new LinkedHashMap<String,QuoteCostingGapExportService.Gap>();
    String message="缺少供货比率：000123、M2；缺少可用价格：P1；供货比率请财务维护或确认取价方式";
    QuoteCostingGapExportService.addSummary(gaps,product("TOP1"),QuoteCostingGapExportService.Category.RATIO,message);
    QuoteCostingGapExportService.addSummary(gaps,product("TOP2"),QuoteCostingGapExportService.Category.RATIO,message);
    assertThat(gaps).containsOnlyKeys("000123","M2");
    assertThat(gaps.get("000123").products).containsExactly("TOP1","TOP2");
    assertThat(gaps.get("000123").reasons).containsExactly("缺少供货比率");
  }
  @Test void priceCategoriesCombineSameMaterialAcrossRawAndOrdinaryRoles() {
    var gaps=new LinkedHashMap<String,QuoteCostingGapExportService.Gap>();
    QuoteCostingGapExportService.addSummary(gaps,product("TOP"),QuoteCostingGapExportService.Category.PRICE,
        "缺少原料价格：000123；缺少废料价格：S1；缺少可用价格：000123；计价资料待完善：OTHER");
    assertThat(gaps).containsOnlyKeys("000123","S1");
    assertThat(gaps.get("000123").reasons).containsExactly("缺少原料价格","缺少可用价格");
  }
  @Test void otherFailureUsesExplicitMaterialAndProductOnlyWhenNoMaterialWasIdentified() {
    var gaps=new LinkedHashMap<String,QuoteCostingGapExportService.Gap>();
    QuoteCostingGapExportService.addSummary(gaps,product("TOP"),QuoteCostingGapExportService.Category.OTHER,
        "物料=205700099，供应商=新亚，缺少行情档位：120000");
    QuoteCostingGapExportService.addSummary(gaps,product("SALARY"),QuoteCostingGapExportService.Category.OTHER,
        "CMS 已完成核实，本产品两项工资均不存在");
    assertThat(gaps).containsOnlyKeys("205700099","SALARY");
    assertThat(gaps.get("205700099").reasons).containsExactly("物料=205700099，供应商=新亚，缺少行情档位：120000");
  }

  @Test void oldDataWithoutCapturedWeightFieldsDoesNotInventTheMissingWeightOrRepriceEveryProduct() throws Exception {
    var quotes=org.mockito.Mockito.mock(com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService.class);
    var prices=org.mockito.Mockito.mock(com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService.class);
    var item=product("TOP");item.setId(1L);
    var workspace=new com.sanhua.marketingcost.dto.ingest.QuoteCostingWorkspaceResponse();
    workspace.setWorkspaceStatus("WAIT_PRICE");workspace.setLastErrorMessage("计价资料待完善：M1");item.setCostingWorkspace(workspace);
    var quote=new com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse();
    quote.setId(1L);quote.setBusinessUnitType("COMMERCIAL");quote.setItems(java.util.List.of(item));
    org.mockito.Mockito.when(quotes.getRequestDetail("OA")).thenReturn(quote);
    org.mockito.Mockito.when(prices.checkPriceSources(org.mockito.ArgumentMatchers.eq("OA"),org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalArgumentException("缺少行情档位：120000"));
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin",null,
        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      var service=new QuoteCostingGapExportService(quotes,
          org.mockito.Mockito.mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class),
          org.mockito.Mockito.mock(com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper.class),
          org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
          new com.fasterxml.jackson.databind.ObjectMapper(),prices,unit->"2026-11",org.mockito.Mockito.mock(CostingPriceSourceReview.class));
      try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(service.export("OA",QuoteCostingGapExportService.Category.OTHER)))) {
        assertThat(book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("M1");
        assertThat(book.getSheetAt(0).getRow(1).getCell(5).getStringCellValue()).contains("旧检查未记录具体缺项");
      }
      org.mockito.Mockito.verifyNoInteractions(prices);
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }
}

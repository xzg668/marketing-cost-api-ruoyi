package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.service.pricing.SupplierPriceCandidate;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReview;
import com.sanhua.marketingcost.service.pricing.SupplierPriceScope;
import com.sanhua.marketingcost.service.supplier.SupplierSupplyRatioWorkbook;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteCostingGapExportServiceTest {
  @Test void wholeQuoteExportsUnifiedImportSheetWithoutLosingSuppliersRulesOrMaterialColumns() throws Exception {
    var quotes=org.mockito.Mockito.mock(com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService.class);
    var prices=org.mockito.Mockito.mock(com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService.class);
    var masters=org.mockito.Mockito.mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class);
    var ratios=org.mockito.Mockito.mock(com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper.class);
    var sql=org.mockito.Mockito.mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
    var item=product("TOP");item.setId(1L);
    var workspace=new com.sanhua.marketingcost.dto.ingest.QuoteCostingWorkspaceResponse();
    workspace.setWorkspaceStatus("WAIT_PRICE");workspace.setLastErrorMessage("缺少供货比率：000123");item.setCostingWorkspace(workspace);
    var second=product("OTHER-TOP");second.setId(2L);second.setCostingWorkspace(workspace);
    var unavailable=product("LEGACY-TOP");unavailable.setId(3L);unavailable.setCostingWorkspace(workspace);
    var quote=new com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse();
    quote.setId(1L);quote.setBusinessUnitType("COMMERCIAL");quote.setItems(java.util.List.of(item,second,unavailable));
    org.mockito.Mockito.when(quotes.getRequestDetail("OA")).thenReturn(quote);
    var firstPrices = new QuotePricePrepareWorkbenchResponse();
    firstPrices.setSupplierPriceReviews(List.of(review(1L,"2026-11","FIXED", "000123",
        candidate("S1","供应商1","1.25")), review(1L,"2026-11","LINKED","000123",
        candidate(null,"供应商2","2.125")), review(1L,"2026-10","FIXED","000123",candidate("S1","供应商1","99")),
        review(1L,"2026-11","FIXED","OTHER-MATERIAL",candidate("S1","供应商1","99"))));
    var secondPrices = new QuotePricePrepareWorkbenchResponse();
    secondPrices.setSupplierPriceReviews(List.of(review(2L,"2026-11","RANGE","000123",candidate("S2","供应商2","2.5"))));
    org.mockito.Mockito.when(prices.checkPriceSources(org.mockito.ArgumentMatchers.eq("OA"),
        org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.any())).thenReturn(firstPrices);
    org.mockito.Mockito.when(prices.checkPriceSources(org.mockito.ArgumentMatchers.eq("OA"),
        org.mockito.ArgumentMatchers.eq(2L),org.mockito.ArgumentMatchers.any())).thenReturn(secondPrices);
    org.mockito.Mockito.when(prices.checkPriceSources(org.mockito.ArgumentMatchers.eq("OA"),
        org.mockito.ArgumentMatchers.eq(3L),org.mockito.ArgumentMatchers.any()))
        .thenThrow(new com.sanhua.marketingcost.service.ingest.QuoteIngestException("旧BOM已过期"));
    var material=new com.sanhua.marketingcost.entity.MaterialMasterRaw();
    material.setMaterialCode("000123");material.setMaterialName("物料");material.setMaterialSpec("独立规格");
    material.setMaterialModel("真正型号");material.setUnit("件");material.setShapeAttr("采购件");
    org.mockito.Mockito.when(masters.selectByLatestBatchAndCodes(org.mockito.ArgumentMatchers.anyCollection(),
        org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("COMMERCIAL"))).thenReturn(java.util.List.of(material));
    org.mockito.Mockito.when(sql.queryForList(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.<String,Object>anyMap())).thenAnswer(call -> {
          String statement=call.getArgument(0);
          if(statement.contains("lp_price_fixed_item"))return java.util.List.of(java.util.Map.<String,Object>of(
              "material_code","000123","supplier_code","S1","supplier_name","供应商1","spec_model","来源规格"));
          if(statement.contains("lp_price_linked_item"))return java.util.List.of(java.util.Map.<String,Object>of(
              "material_code","000123","supplier_code","S2","supplier_name","供应商2"));
          return java.util.List.of();
        });
    var zero=new com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput();
    zero.setRequestedMaterialCode("000123");zero.setSupplierCode("S3");zero.setSupplierName("非候选零比例供应商");
    zero.setSupplyRatio(java.math.BigDecimal.ZERO);
    var highest=new com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput();
    highest.setRequestedMaterialCode("000123");highest.setSupplierCode("S1");highest.setSupplierName("供应商1");
    highest.setSelectionRule("无供货比率，取最高价");
    org.mockito.Mockito.when(ratios.currentRelations(org.mockito.ArgumentMatchers.eq("COMMERCIAL"),
        org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.List.of(zero,highest));
    var service=new QuoteCostingGapExportService(quotes,masters,ratios,
        org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),new com.fasterxml.jackson.databind.ObjectMapper(),
        prices,unit->"2026-11",org.mockito.Mockito.mock(CostingPriceSourceReview.class));
    org.springframework.test.util.ReflectionTestUtils.setField(service,"sql",sql);
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin",null,
        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      byte[] bytes=service.export("OA",QuoteCostingGapExportService.Category.RATIO);
      try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
        var sheet=book.getSheetAt(0);
        assertThat(sheet.getSheetName()).isEqualTo(com.sanhua.marketingcost.service.supplier.SupplierSupplyRatioWorkbook.SHEET_NAME);
        var headers = new ArrayList<>(SupplierSupplyRatioWorkbook.HEADERS);
        headers.add(SupplierSupplyRatioWorkbook.SUPPLIER_PRICE_HEADER);
        assertThat(java.util.stream.IntStream.range(0,sheet.getRow(0).getLastCellNum())
            .mapToObj(i->sheet.getRow(0).getCell(i).getStringCellValue()).toList())
            .isEqualTo(headers);
        assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
        assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("独立规格");
        assertThat(sheet.getRow(1).getCell(11).getNumericCellValue()).isEqualTo(1.25);
        assertThat(sheet.getRow(2).getCell(11).getStringCellValue())
            .contains("2.125（TOP / 210 / 联动价）", "2.5（OTHER-TOP / 210 / 区间价）");
        assertThat(sheet.getRow(3).getCell(11).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
        assertThat(book.getSheetAt(1).getSheetName()).isEqualTo("缺供货比率明细");
        assertThat(book.getSheet("填写说明")).isNotNull();
      }
      var parser=new com.sanhua.marketingcost.service.impl.SupplierSupplyRatioWorkbookParserImpl();
      var parsed=parser.parse(new java.io.ByteArrayInputStream(bytes),"整单补齐.xlsx",null);
      assertThat(parsed.getErrors()).isEmpty();assertThat(parsed.getRows()).hasSize(3);
      assertThat(parsed.getRows()).allSatisfy(row->{
        assertThat(row.getMaterialCode()).isEqualTo("000123");assertThat(row.getSpecModel()).isEqualTo("真正型号");
        assertThat(row.getUnit()).isEqualTo("件");assertThat(row.getMaterialShape()).isEqualTo("采购件");
      });
      assertThat(parsed.getRows()).filteredOn(row->"S1".equals(row.getSupplierCode())).singleElement().satisfies(row->{
        assertThat(row.getSupplyRatio()).isNull();assertThat(row.getSelectionRule()).isEqualTo("无供货比率，取最高价");
      });
      assertThat(parsed.getRows()).filteredOn(row->"S3".equals(row.getSupplierCode())).singleElement()
          .satisfies(row->assertThat(row.getSupplyRatio()).isZero());
      // 档案未维护型号时，不能把规格或来源规格塞进型号。
      material.setMaterialModel(null);
      var blankModel=parser.parse(new java.io.ByteArrayInputStream(service.export("OA",QuoteCostingGapExportService.Category.RATIO)),"空型号.xlsx",null);
      assertThat(blankModel.getErrors()).isEmpty();
      assertThat(blankModel.getRows()).allSatisfy(row->assertThat(row.getSpecModel()).isNull());
      for (long itemId : List.of(1L, 2L, 3L)) org.mockito.Mockito.verify(prices, org.mockito.Mockito.times(2))
          .checkPriceSources(org.mockito.ArgumentMatchers.eq("OA"), org.mockito.ArgumentMatchers.eq(itemId),
              org.mockito.ArgumentMatchers.argThat(req -> req.isRefreshCandidates() && "2026-11".equals(req.getPeriodMonth())));
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }

  private static SupplierPriceReview review(long id, String month, String type, String material,
      SupplierPriceReview.Candidate candidate) {
    return new SupplierPriceReview("scope", "fingerprint", new SupplierPriceScope("COMMERCIAL", "OA", id,
        month, LocalDate.parse(month + "-01"), "210", material, type), List.of(candidate), "WAIT", null, null, "");
  }

  private static SupplierPriceReview.Candidate candidate(String code, String name, String unitPrice) {
    return new SupplierPriceReview.Candidate(new SupplierPriceCandidate("key", code, name, new BigDecimal(unitPrice),
        null, null), null);
  }
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

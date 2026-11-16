package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomNodeResponse;
import java.io.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class JjbPackagingIdentityTest {
  private Row component(String code, String path) {
    return Row.builder().code(code).paths(path == null ? List.of() : List.of(path)).build();
  }
  private QuoteEffectiveBomNodeResponse node(String code, String path, String spec) {
    return new ObjectMapper().convertValue(Map.of("materialCode",code,"nodePath",path,"materialSpec",spec), QuoteEffectiveBomNodeResponse.class);
  }
  @Test void savedOccurrenceSuppliesSpecificationWithoutBorrowingAnotherBranch() {
    var nodes=List.of(node("001", "TOP/A", "A规格"),node("001", "TOP/B", "B规格"));
    assertThat(JjbPackagingIdentity.from(List.of(component("001","/TOP/A/")),nodes))
        .isEqualTo(new JjbPackagingIdentity.Identity("001","A规格"));
    assertThat(JjbPackagingIdentity.from(List.of(component("001",null)),nodes).specifications()).isEmpty();
  }
  @Test void multiplePackagesKeepCodeSpecificationPairsAndLegacyGapsRemainBlank() {
    var labels=JjbPackagingIdentity.from(List.of(component("001","TOP/A"),component("002",null),component("001","TOP/A")),
        List.of(node("001","TOP/A","箱规格")));
    assertThat(labels).isEqualTo(new JjbPackagingIdentity.Identity("001\n002","箱规格\n"));
    assertThat(JjbPackagingIdentity.from(List.of(),List.of()).codes()).isNull();
  }
  @Test void excelShowsPackageIdentityInTheSingleExistingFeeRow() throws Exception {
    var base=JjbExcelRendererTest.model(0,0,0);
    var fee=Row.builder().key("COST:45").kind("CONTRIBUTION").section("MATERIAL_DETAIL")
        .code("OTHER_EXP_PACKAGE").name("包装费用").displayCode("001\n002").drawingNo("箱规格\n托规格")
        .amount("0.324345").costItemIds(List.of(45L)).build();
    var model=new JjbReadModel(base.identity(),base.header(),List.of(fee),base.monthlyPrices(),base.totals(),base.warnings());
    var out=new ByteArrayOutputStream();new JjbExcelRenderer().write(model,out);
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
      var matches=new ArrayList<org.apache.poi.ss.usermodel.Row>();
      for(var row:book.getSheetAt(0)) if(row.getCell(1)!=null && "包装费用".equals(row.getCell(1).toString()))matches.add(row);
      assertThat(matches).singleElement().satisfies(row->{
        assertThat(row.getCell(0).getStringCellValue()).isEqualTo("001\n002");
        assertThat(row.getCell(2).getStringCellValue()).isEqualTo("箱规格\n托规格");
        assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(0.324345);
      });
    }
  }
}

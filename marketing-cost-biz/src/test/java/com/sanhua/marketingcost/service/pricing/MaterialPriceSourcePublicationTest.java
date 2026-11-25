package com.sanhua.marketingcost.service.pricing;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialPriceSourcePublicationTest {
  @Test void unpricedMakesScanOncePerOrganizationAndKeepOnlyMaterialsWithAnAlternative() {
    var repository=mock(MaterialPriceSourceRepository.class);
    var scanner=mock(MaterialPriceSourceScanner.class);
    var months=mock(MaterialPriceMonthSelector.class);
    var make = source("A","210","MAKE");
    var fixed = source("A","210","FIXED");
    var makeOnly = source("B","210","MAKE");
    var secondOrganizationMake = source("A","220","MAKE");
    when(repository.installed()).thenReturn(true);
    when(repository.manufacturedMaterials("COMMERCIAL","2026-11",42L))
        .thenReturn(List.of(make.key(),makeOnly.key(),secondOrganizationMake.key()));
    when(scanner.scan(anyMap())).thenAnswer(call -> {
      Map<String,Object> params=call.getArgument(0);
      if ("220".equals(params.get("organization"))) return List.of(secondOrganizationMake);
      return List.of(make,fixed,makeOnly);
    });
    when(repository.quotationRows(anyMap())).thenReturn(List.of(new MaterialPriceSourceCatalog.Entry(
        1,"A","材料",null,null,"210","商用","FIXED","固定价",List.of(),"PURCHASE",false,1)));
    when(repository.lock(anyMap())).thenReturn(7L);

    new MaterialPriceSourceService(repository,scanner,months)
        .includeUnpricedManufactures("COMMERCIAL","2026-11",42L);

    var order = inOrder(scanner,repository);
    order.verify(scanner).scan(argThat(params -> "210".equals(params.get("organization"))
        && List.of("A","B").equals(params.get("materialCodes"))));
    order.verify(scanner).scan(argThat(params -> "220".equals(params.get("organization"))
        && List.of("A").equals(params.get("materialCodes"))));
    order.verify(repository).lock(anyMap());
    order.verify(repository).append(anyMap(),eq(List.of(make)),eq(7L),eq("MAKE_CALCULATED"));
    verify(scanner,times(2)).scan(anyMap());
    verify(repository,times(1)).lock(anyMap());
  }

  private static MaterialPriceSourceCatalog.Candidate source(String code,String org,String type) {
    return new MaterialPriceSourceCatalog.Candidate(
        new MaterialPriceSourceCatalog.Key(code,org,type),"材料",null,null,"商用",List.of(),
        "MAKE".equals(type) ? "MANUFACTURE" : null);
  }

  @Test void anOlderSchemaCanStillCalculateWithoutInstallingOrPublishingCatalogData() {
    var repository=mock(MaterialPriceSourceRepository.class);
    var scanner=mock(MaterialPriceSourceScanner.class);
    var months=mock(MaterialPriceMonthSelector.class);
    new MaterialPriceSourceService(repository,scanner,months)
        .includeCalculatedMake("COMMERCIAL","2026-11","210","MATERIAL");
    verify(repository).installed();verifyNoMoreInteractions(repository);
    verifyNoInteractions(scanner,months);
  }
  @Test void exportIncludesEveryPageEverySupplierNumericZeroAndSeparateUnits() throws Exception {
    var repository=mock(MaterialPriceSourceRepository.class);
    var source=new MaterialPriceSourceCatalog.Entry(1,"MAT-1","材料","型号","规格","210","商用","LINKED","联动价",
        java.util.List.of(new MaterialPriceSourceCatalog.Price("0","只","CNY","EXCLUDED","S1","供应商1","条件","已有价格"),
            new MaterialPriceSourceCatalog.Price("1.5","KG","CNY","EXCLUDED","S2","供应商2","条件","已有价格")),"OUTSOURCE",false,1);
    var make=new MaterialPriceSourceCatalog.Entry(2,"MAT-2","自制件","型号","规格","210","商用","MAKE","自制件算价",
        java.util.List.of(new MaterialPriceSourceCatalog.Price("9","件","CNY","EXCLUDED",null,null,"计算批次","已有价格")),"MANUFACTURE",true,0);
    when(repository.list(anyMap())).thenAnswer(call -> {
      java.util.Map<String,Object> params=call.getArgument(0);
      org.assertj.core.api.Assertions.assertThat(params).containsEntry("unit","COMMERCIAL").containsEntry("code","MAT");
      boolean first=((Number)params.get("offset")).longValue()==0;
      return new MaterialPriceSourceCatalog.Page("2026-11",501,first?java.util.Collections.nCopies(500,source):java.util.List.of(make),null);
    });
    var bytes=new java.io.ByteArrayOutputStream();
    new MaterialPriceSourceService(repository,mock(MaterialPriceSourceScanner.class),mock(MaterialPriceMonthSelector.class))
        .export("COMMERCIAL",new MaterialPriceSourceCatalog.Filter("2026-11","MAT","210","",9,1),bytes);
    try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
      var sheet=book.getSheetAt(0);
      org.assertj.core.api.Assertions.assertThat(sheet.getLastRowNum()).isEqualTo(1001);
      org.assertj.core.api.Assertions.assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("单位");
      org.assertj.core.api.Assertions.assertThat(sheet.getRow(1).getCell(8).getNumericCellValue()).isZero();
      org.assertj.core.api.Assertions.assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).isEqualTo("只");
      org.assertj.core.api.Assertions.assertThat(sheet.getRow(2).getCell(12).getStringCellValue()).isEqualTo("S2");
      org.assertj.core.api.Assertions.assertThat(sheet.getRow(1001).getCell(14).getStringCellValue()).isEqualTo("自制件");
    }
    verify(repository,times(2)).list(anyMap());
  }
}

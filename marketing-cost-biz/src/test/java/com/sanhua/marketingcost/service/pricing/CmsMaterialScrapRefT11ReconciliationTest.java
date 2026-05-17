package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportResponse;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MakePartSpecMapper;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.service.PriceScrapService;
import com.sanhua.marketingcost.service.impl.CmsCostExcelParseServiceImpl;
import com.sanhua.marketingcost.service.impl.CmsMaterialScrapRefImportServiceImpl;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class CmsMaterialScrapRefT11ReconciliationTest {
  private static final Path MATERIAL_SCRAP_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/原材料对应回收废料信息-列表导出20260512150059-new.xlsx");
  private static final BigDecimal TOLERANCE = new BigDecimal("0.000001");

  private MaterialScrapRefMapper materialScrapRefMapper;
  private CmsMaterialScrapRefImportServiceImpl importService;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MakePartSpec.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MaterialScrapRef.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), PriceScrap.class);
    materialScrapRefMapper = mock(MaterialScrapRefMapper.class);
    importService =
        new CmsMaterialScrapRefImportServiceImpl(
            materialScrapRefMapper,
            new CmsCostExcelParseServiceImpl());
    when(materialScrapRefMapper.selectOne(any())).thenReturn(null);
  }

  @Test
  @DisplayName("T11: 真实样例导入后补齐缺价，4月自制件新旧口径可对账")
  void importsSampleAndReconcilesAprilMakeParts() throws Exception {
    assumeTrue(Files.exists(MATERIAL_SCRAP_SAMPLE));

    CmsMaterialScrapRefImportResponse response;
    try (InputStream input = Files.newInputStream(MATERIAL_SCRAP_SAMPLE)) {
      response =
          importService.importExcel(
              input, "COMMERCIAL");
    }

    assertThat(response.getStatus()).isEqualTo("IMPORTED");
    assertThat(response.getSourceRowCount()).isEqualTo(6);
    assertThat(response.getUpdatedMappingCount()).isEqualTo(6);

    ArgumentCaptor<MaterialScrapRef> mappingCaptor = ArgumentCaptor.forClass(MaterialScrapRef.class);
    Mockito.verify(materialScrapRefMapper, Mockito.times(6)).insert(mappingCaptor.capture());
    Map<String, List<MaterialScrapRef>> mappingsByMaterial =
        mappingCaptor.getAllValues().stream().collect(Collectors.groupingBy(MaterialScrapRef::getMaterialCode));
    assertThat(mappingsByMaterial.keySet())
        .contains("301050066", "301280056", "301240123", "301220046", "301050054");

    Map<String, PriceScrap> currentPrices =
        Map.of(
            "301990317", price("301990317", "75.6637168141593"),
            "301990444", price("301990444", "6"),
            "301990752", price("301990752", "4"));
    for (AprilMakePart sample : aprilSamples()) {
      PriceResolveResult result = resolve(sample.spec(), mappingsByMaterial, currentPrices);
      assertThat(result.unitPrice())
          .as(sample.name() + " new-vs-old")
          .isCloseTo(sample.oldUnitPrice(), within(TOLERANCE));
      if (sample.expectScrapTrace()) {
        assertThat(result.remark())
            .as(sample.name() + " trace")
            .contains("scrap=" + sample.expectedScrapCode());
      }
    }
  }

  private PriceResolveResult resolve(
      MakePartSpec spec,
      Map<String, List<MaterialScrapRef>> mappingsByMaterial,
      Map<String, PriceScrap> currentPrices) {
    MakePartSpecMapper specMapper = mock(MakePartSpecMapper.class);
    MaterialScrapRefMapper mappingMapper = mock(MaterialScrapRefMapper.class);
    PriceScrapService priceScrapService = mock(PriceScrapService.class);
    MakeSpecPriceResolver resolver =
        new MakeSpecPriceResolver(specMapper, mappingMapper, priceScrapService);

    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(spec));
    when(mappingMapper.selectList(any(Wrapper.class)))
        .thenReturn(mappingsByMaterial.getOrDefault(spec.getRawMaterialCode(), List.of()));
    when(priceScrapService.getCurrentByScrapCodes(any())).thenReturn(currentPrices);

    return resolver.resolve("OA-APRIL-T11", part(spec.getMaterialCode()), route());
  }

  private List<AprilMakePart> aprilSamples() {
    return List.of(
        sample(
            "203250749",
            "S接管",
            "301050066",
            "83.053885",
            "80.000000",
            "82.94690265486727",
            "301990317",
            "6.65799422"),
        sample(
            "203250724",
            "EC接管",
            "301050066",
            "74.990401",
            "72.000000",
            "82.94690265486727",
            "301990317",
            "5.99395664"),
        sample(
            "203259851",
            "小阀体部件",
            "301280056",
            "0.010000",
            "0.090000",
            "0.795",
            "301990444",
            "0.00000795",
            false),
        sample(
            "201800231",
            "簧片",
            "301240123",
            "0.110000",
            "0.100000",
            "9.8319",
            "301990444",
            "0.00102151"),
        sample(
            "203240252",
            "封头",
            "301220046",
            "7.720000",
            "7.500000",
            "27.1858",
            "301990752",
            "0.20899438"),
        sample(
            "203250582",
            "D接管",
            "301050054",
            "56.375376",
            "55.000000",
            "82.9469",
            "301990317",
            "4.57209662"));
  }

  private AprilMakePart sample(
      String materialCode,
      String name,
      String rawMaterialCode,
      String blankWeight,
      String netWeight,
      String rawUnitPrice,
      String expectedScrapCode,
      String oldUnitPrice) {
    return sample(
        materialCode,
        name,
        rawMaterialCode,
        blankWeight,
        netWeight,
        rawUnitPrice,
        expectedScrapCode,
        oldUnitPrice,
        true);
  }

  private AprilMakePart sample(
      String materialCode,
      String name,
      String rawMaterialCode,
      String blankWeight,
      String netWeight,
      String rawUnitPrice,
      String expectedScrapCode,
      String oldUnitPrice,
      boolean expectScrapTrace) {
    MakePartSpec spec = new MakePartSpec();
    spec.setMaterialCode(materialCode);
    spec.setMaterialName(name);
    spec.setRawMaterialCode(rawMaterialCode);
    spec.setBlankWeight(new BigDecimal(blankWeight));
    spec.setNetWeight(new BigDecimal(netWeight));
    spec.setRawUnitPrice(new BigDecimal(rawUnitPrice));
    return new AprilMakePart(spec, name, expectedScrapCode, new BigDecimal(oldUnitPrice), expectScrapTrace);
  }

  private static CostRunPartItemDto part(String code) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setPartCode(code);
    dto.setPartQty(BigDecimal.ONE);
    return dto;
  }

  private static PriceTypeRoute route() {
    return new PriceTypeRoute(
        "M", MaterialFormAttrEnum.MANUFACTURED, PriceTypeEnum.MAKE, 1, null, null, "T11");
  }

  private static PriceScrap price(String scrapCode, String recyclePrice) {
    PriceScrap price = new PriceScrap();
    price.setScrapCode(scrapCode);
    price.setScrapName("CMS " + scrapCode);
    price.setUnit("千克");
    price.setRecyclePrice(new BigDecimal(recyclePrice));
    price.setDeleted(0);
    return price;
  }

  private record AprilMakePart(
      MakePartSpec spec,
      String name,
      String expectedScrapCode,
      BigDecimal oldUnitPrice,
      boolean expectScrapTrace) {}
}

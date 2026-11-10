package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MakePartChildNetWeight;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.mapper.MakePartChildNetWeightMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.U9BomByproductMasterMapper;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class MakePartWeightServiceImplTest {

  @BeforeAll
  static void initializeByproductMetadata() {
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        new org.apache.ibatis.builder.MapperBuilderAssistant(
            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
        U9BomByproductMaster.class);
  }

  @Test
  @DisplayName("单子料有U9副产品时优先反算，不使用父件理论净重")
  void singleRawMaterialPrefersByproductOverParentTheoreticalNetWeight() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartScrapMappingService scrapMappingService = mock(MakePartScrapMappingService.class);
    U9BomByproductMasterMapper byproductMapper = mock(U9BomByproductMasterMapper.class);
    MakePartWeightServiceImpl service =
        new MakePartWeightServiceImpl(
            mapper, childWeightMapper, scrapMappingService, byproductMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE")))
        .thenReturn(List.of(raw("1053000301690", "5.553"), raw("301070047", "")));
    when(scrapMappingService.listMappings("301070047", "COMMERCIAL"))
        .thenReturn(List.of(scrap("301990315")));
    when(byproductMapper.selectList(any()))
        .thenReturn(List.of(byproduct("301990315", "0.0006", "千克")));

    MakePartWeightResult result =
        service.resolveWeights(
            "1053000301690", childInOrg("301070047", "0.0056", "220"), "原材料加工",
            "2026-08", LocalDate.of(2026, 8, 31), "COMMERCIAL", false);

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getGrossWeightG()).isEqualByComparingTo("5.6000");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("5.0");
    assertThat(result.getRemark()).contains("净重按U9副产品反算", "byproduct_weight_g=0.6");
  }

  @Test
  @DisplayName("毛坯加工毛重取 child 理论净重，净重取 parent 理论净重")
  void blankProcessGrossWeightUsesChildTheoreticalNetWeight() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = service(mapper, childWeightMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P-001", "55"), raw("BLANK-001", "80")));

    MakePartWeightResult result =
        service.resolveWeights(
            "P-001",
            child("BLANK-001", "0.080"),
            MakePartProcessTypePolicy.PROCESS_TYPE_BLANK,
            "2026-07",
            LocalDate.of(2026, 7, 31),
            "COMMERCIAL",
            false);

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getGrossWeightG()).isEqualByComparingTo("80");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("55");
  }

  @Test
  @DisplayName("缺理论净重或用量时返回 MISSING_WEIGHT，不按 0 静默计算")
  void missingWeightReturnsMissingStatus() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = service(mapper, childWeightMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P-001", ""), raw("RAW-001", "80")));

    MakePartWeightResult result =
        service.resolveWeights(
            "P-001", child("RAW-001", null), "原材料加工", "2026-07",
            LocalDate.of(2026, 7, 31), "COMMERCIAL", false);

    assertThat(result.getStatus()).isEqualTo("MISSING_WEIGHT");
    assertThat(result.getGrossWeightG()).isNull();
    assertThat(result.getNetWeightG()).isNull();
    assertThat(result.getRemark()).contains("缺 qty_per_parent", "缺 parent 理论净重");
    assertThat(result.getRemark()).doesNotContain("null");
  }

  @Test
  @DisplayName("BOM 子行缺 priceOrgCode 时直接报错，不默认读商用料品档案")
  void missingPriceOrgCodeFailsFast() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = service(mapper, childWeightMapper);
    BomU9Source child = child("RAW-001", "0.080");
    child.setPriceOrgCode(null);

    assertThatThrownBy(
            () -> service.resolveWeights(
                "P-001", child, "原材料加工", "2026-07",
                LocalDate.of(2026, 7, 31), "COMMERCIAL", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceOrgCode");
  }

  @Test
  @DisplayName("多原材料制造件按父料号 + 子料号分别读取净重")
  void multipleRawMaterialsUseTheirOwnParentChildNetWeight() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartScrapMappingService scrapMappingService = mock(MakePartScrapMappingService.class);
    U9BomByproductMasterMapper byproductMapper = mock(U9BomByproductMasterMapper.class);
    MakePartWeightServiceImpl service =
        new MakePartWeightServiceImpl(
            mapper, childWeightMapper, scrapMappingService, byproductMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE")))
        .thenReturn(List.of(raw("1053000301687", "45.896")));
    when(childWeightMapper.selectEffective(
            "PLATE", "1053000301687", "301240299", "F001", "2026-07"))
        .thenReturn(childNetWeight("33.60000000"));
    when(childWeightMapper.selectEffective(
            "PLATE", "1053000301687", "301070047", "F001", "2026-07"))
        .thenReturn(childNetWeight("5.00000000"));

    BomU9Source stainless = child("301240299", "0.0379");
    stainless.setPriceOrgCode("220");
    BomU9Source copper = child("301070047", "0.0056");
    copper.setPriceOrgCode("220");

    MakePartWeightResult stainlessResult = service.resolveWeights(
        "1053000301687", stainless, "原材料加工", "2026-07",
        LocalDate.of(2026, 7, 31), "COMMERCIAL", true);
    MakePartWeightResult copperResult = service.resolveWeights(
        "1053000301687", copper, "原材料加工", "2026-07",
        LocalDate.of(2026, 7, 31), "COMMERCIAL", true);

    assertThat(stainlessResult.getGrossWeightG()).isEqualByComparingTo("37.9");
    assertThat(stainlessResult.getNetWeightG()).isEqualByComparingTo("33.6");
    assertThat(copperResult.getGrossWeightG()).isEqualByComparingTo("5.6");
    assertThat(copperResult.getNetWeightG()).isEqualByComparingTo("5.0");
    verify(scrapMappingService).listMappings("301240299", "COMMERCIAL");
    verify(scrapMappingService).listMappings("301070047", "COMMERCIAL");
  }

  @Test
  @DisplayName("多原材料缺父子净重时按原材料废料映射和U9副产品用量反算净重")
  void missingChildNetWeightUsesMappedByproductOutput() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartScrapMappingService scrapMappingService = mock(MakePartScrapMappingService.class);
    U9BomByproductMasterMapper byproductMapper = mock(U9BomByproductMasterMapper.class);
    MakePartWeightServiceImpl service =
        new MakePartWeightServiceImpl(
            mapper, childWeightMapper, scrapMappingService, byproductMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE")))
        .thenReturn(List.of(raw("1053000301687", "45.896")));
    when(scrapMappingService.listMappings("301240299", "COMMERCIAL"))
        .thenReturn(List.of(scrap("301990044")));
    when(scrapMappingService.listMappings("301070047", "COMMERCIAL"))
        .thenReturn(List.of(scrap("301990315")));
    when(byproductMapper.selectList(any()))
        .thenReturn(
            List.of(byproduct("301990044", "0.0043", "千克")),
            List.of(byproduct("301990315", "0.0006", "千克")));

    BomU9Source stainless = child("301240299", "0.0379");
    stainless.setPriceOrgCode("220");
    BomU9Source copper = child("301070047", "0.0056");
    copper.setPriceOrgCode("220");

    MakePartWeightResult stainlessResult = service.resolveWeights(
        "1053000301687", stainless, "原材料加工", "2026-08",
        LocalDate.of(2026, 8, 31), "COMMERCIAL", true);
    MakePartWeightResult copperResult = service.resolveWeights(
        "1053000301687", copper, "原材料加工", "2026-08",
        LocalDate.of(2026, 8, 31), "COMMERCIAL", true);

    assertThat(stainlessResult.getStatus()).isEqualTo("OK");
    assertThat(stainlessResult.getGrossWeightG()).isEqualByComparingTo("37.9");
    assertThat(stainlessResult.getNetWeightG()).isEqualByComparingTo("33.6");
    assertThat(stainlessResult.getRemark())
        .contains("净重按U9副产品反算", "output_qty=0.0043", "byproduct_weight_g=4.3");
    assertThat(copperResult.getStatus()).isEqualTo("OK");
    assertThat(copperResult.getGrossWeightG()).isEqualByComparingTo("5.6");
    assertThat(copperResult.getNetWeightG()).isEqualByComparingTo("5.0");
    assertThat(copperResult.getRemark())
        .contains("output_qty=0.0006", "byproduct_weight_g=0.6");
  }

  @Test
  @DisplayName("单子料缺父件料品档案净重时仍可使用U9副产品反算")
  void missingParentTheoreticalNetWeightFallsBackToByproduct() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartScrapMappingService scrapMappingService = mock(MakePartScrapMappingService.class);
    U9BomByproductMasterMapper byproductMapper = mock(U9BomByproductMasterMapper.class);
    MakePartWeightServiceImpl service =
        new MakePartWeightServiceImpl(
            mapper, childWeightMapper, scrapMappingService, byproductMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P-001", "")));
    when(scrapMappingService.listMappings("RAW-001", "COMMERCIAL"))
        .thenReturn(List.of(scrap("SCRAP-001")));
    when(byproductMapper.selectList(any()))
        .thenReturn(List.of(byproduct("SCRAP-001", "0.025", "千克")));

    MakePartWeightResult result = service.resolveWeights(
        "P-001", child("RAW-001", "0.080"), "原材料加工", "2026-08",
        LocalDate.of(2026, 8, 31), "COMMERCIAL", false);

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("55");
    assertThat(result.getRemark())
        .contains("净重按U9副产品反算", "byproduct_weight_g=25", "net_weight_g=55");
  }

  @Test
  @DisplayName("多原材料缺父子净重时禁止回退父件总净重")
  void multipleRawMaterialsDoNotReuseParentTotalNetWeight() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MakePartChildNetWeightMapper childWeightMapper = mock(MakePartChildNetWeightMapper.class);
    MakePartWeightServiceImpl service = service(mapper, childWeightMapper);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P-001", "45.896")));

    MakePartWeightResult result = service.resolveWeights(
        "P-001", child("RAW-001", "0.0379"), "原材料加工", "2026-07",
        LocalDate.of(2026, 7, 31), "COMMERCIAL", true);

    assertThat(result.getStatus()).isEqualTo("MISSING_WEIGHT");
    assertThat(result.getNetWeightG()).isNull();
    assertThat(result.getRemark()).contains("缺父子材料净重", "P-001", "RAW-001");
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> processAndUnits() {
    return java.util.stream.Stream.of("原材料加工", "毛坯加工").flatMap(process ->
        java.util.stream.Stream.of("千克", "公斤", "kg", "KG", "kgs", "克", "g")
            .map(unit -> org.junit.jupiter.params.provider.Arguments.of(
                process, unit, List.of("克", "g").contains(unit) ? "2.50753" : "0.00250753")));
  }

  @ParameterizedTest
  @MethodSource("processAndUnits")
  @DisplayName("原材料及毛坯均先统一克制，再以副产品反算净重并完成扣废计价")
  void bothProcessesConvertByproductUnitsBeforeCalculatingPrice(
      String process, String unit, String quantity) {
    var rawMapper = mock(MaterialMasterRawMapper.class);
    var netMapper = mock(MakePartChildNetWeightMapper.class);
    var mappings = mock(MakePartScrapMappingService.class);
    var products = mock(U9BomByproductMasterMapper.class);
    var service = new MakePartWeightServiceImpl(rawMapper, netMapper, mappings, products);
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("203240172", "20.016"), raw("301200309", "22.20653")));
    // 多子料即使旧父子配置存在，有效副产品也必须优先。
    when(netMapper.selectEffective(any(), any(), any(), any(), any()))
        .thenReturn(childNetWeight("20.1"));
    when(mappings.listMappings("301200309", "COMMERCIAL"))
        .thenReturn(List.of(scrap("301990044")));
    when(products.selectList(any()))
        .thenReturn(List.of(byproduct("301990044", quantity, unit)));
    var result = service.resolveWeights("203240172", child("301200309", "0.02220653"),
        process, "2026-11", LocalDate.of(2026, 11, 9), "COMMERCIAL", true);
    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getGrossWeightG()).isEqualByComparingTo("22.20653");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("19.699");
    assertThat(result.getRemark()).contains("byproduct_weight_g=2.50753");
    var row = new com.sanhua.marketingcost.entity.MakePartPriceCalcRow();
    row.setParentMaterialNo("203240172");
    row.setItemProcessType(process);
    row.setGrossWeightG(result.getGrossWeightG());
    row.setNetWeightG(result.getNetWeightG());
    row.setRawUnitPrice(new BigDecimal("10"));
    row.setScrapCode("301990044");
    row.setScrapUnitPrice(new BigDecimal("2"));
    var calculated = new com.sanhua.marketingcost.service.MakePartPriceCalculator()
        .calculate(List.of(row)).getFirst();
    assertThat(calculated.getCostPrice()).isEqualByComparingTo(
        "原材料加工".equals(process) ? "0.21705024" : "9.99498494");
    assertThat(calculated.getRemark()).contains("scrap_weight_kg=0.00250753");
  }

  @ParameterizedTest
  @CsvSource({"原材料加工,0,kg,22.20653", "毛坯加工,0,克,22.20653",
      "原材料加工,0.02220653,kg,0", "毛坯加工,22.20653,g,0"})
  void zeroByproductAndZeroNetWeightRemainValid(
      String process, String output, String unit, String expectedNet) {
    var rawMapper = mock(MaterialMasterRawMapper.class);
    var mappings = mock(MakePartScrapMappingService.class);
    var products = mock(U9BomByproductMasterMapper.class);
    var service = new MakePartWeightServiceImpl(rawMapper,
        mock(MakePartChildNetWeightMapper.class), mappings, products);
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P", "20.016"), raw("C", "22.20653")));
    when(mappings.listMappings("C", "COMMERCIAL")).thenReturn(List.of(scrap("S")));
    when(products.selectList(any())).thenReturn(List.of(byproduct("S", output, unit)));
    var result = service.resolveWeights("P", child("C", "0.02220653"), process,
        "2026-11", LocalDate.of(2026, 11, 9), "COMMERCIAL", false);
    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getNetWeightG()).isEqualByComparingTo(expectedNet);
  }

  @ParameterizedTest
  @CsvSource({"原材料加工,0.0025,吨", "毛坯加工,0.0025,吨",
      "原材料加工,-0.0025,kg", "毛坯加工,-2.5,g",
      "原材料加工,0.1,kg", "毛坯加工,100,g"})
  void unusableByproductFallsBackToParentWithReason(String process, String output, String unit) {
    var rawMapper = mock(MaterialMasterRawMapper.class);
    var mappings = mock(MakePartScrapMappingService.class);
    var products = mock(U9BomByproductMasterMapper.class);
    var service = new MakePartWeightServiceImpl(rawMapper,
        mock(MakePartChildNetWeightMapper.class), mappings, products);
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P", "20.016"), raw("C", "22.20653")));
    when(mappings.listMappings("C", "COMMERCIAL")).thenReturn(List.of(scrap("S")));
    when(products.selectList(any())).thenReturn(List.of(byproduct("S", output, unit)));
    var result = service.resolveWeights("P", child("C", "0.02220653"), process,
        "2026-11", LocalDate.of(2026, 11, 9), "COMMERCIAL", false);
    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("20.016");
    assertThat(result.getRemark()).contains("副产品反算失败", "净重取 parent 理论净重");
  }

  @ParameterizedTest
  @CsvSource({"原材料加工,false", "毛坯加工,false", "原材料加工,true", "毛坯加工,true"})
  void singleMaterialCannotUseByproductWithDifferentOrMissingCmsScrapCode(
      String process, boolean mappingMissing) {
    var rawMapper = mock(MaterialMasterRawMapper.class);
    var mappings = mock(MakePartScrapMappingService.class);
    var products = mock(U9BomByproductMasterMapper.class);
    var service = new MakePartWeightServiceImpl(rawMapper,
        mock(MakePartChildNetWeightMapper.class), mappings, products);
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("203240172", "20.016"), raw("301200309", "22.20653")));
    when(mappings.listMappings("301200309", "COMMERCIAL"))
        .thenReturn(mappingMissing ? List.of() : List.of(scrap("301990444")));
    when(products.selectList(any()))
        .thenReturn(List.of(byproduct("301990044", "0.00250753", "千克")));
    var result = service.resolveWeights("203240172", child("301200309", "0.02220653"),
        process, "2026-11", LocalDate.of(2026, 11, 9), "COMMERCIAL", false);
    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("20.016");
    assertThat(result.getRemark()).contains("副产品反算失败", "净重取 parent 理论净重");
    assertThat(result.getRemark()).doesNotContain("净重按U9副产品反算");
  }

  @Test void ambiguousMappedSingleMaterialByproductsCannotBeGuessed() {
    var rawMapper = mock(MaterialMasterRawMapper.class);
    var products = mock(U9BomByproductMasterMapper.class);
    var mappings = mock(MakePartScrapMappingService.class);
    var service = new MakePartWeightServiceImpl(rawMapper,
        mock(MakePartChildNetWeightMapper.class), mappings, products);
    when(rawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("P", "20.016")));
    when(mappings.listMappings("C", "COMMERCIAL")).thenReturn(List.of(scrap("S")));
    when(products.selectList(any())).thenReturn(List.of(
        byproduct("S", "0.0025", "kg"), byproduct("S", "0.001", "kg")));
    var result = service.resolveWeights("P", child("C", "0.02220653"), "原材料加工",
        "2026-11", LocalDate.of(2026, 11, 9), "COMMERCIAL", false);
    assertThat(result.getNetWeightG()).isEqualByComparingTo("20.016");
    assertThat(result.getRemark()).contains("有效副产品匹配不唯一", "净重取 parent 理论净重");
  }

  private BomU9Source child(String childCode, String qtyPerParent) {
    return childInOrg(childCode, qtyPerParent, "210");
  }

  private BomU9Source childInOrg(
      String childCode, String qtyPerParent, String priceOrgCode) {
    BomU9Source child = new BomU9Source();
    child.setChildMaterialNo(childCode);
    child.setPriceOrgCode(priceOrgCode);
    child.setBomVersion("F001");
    if (qtyPerParent != null) {
      child.setQtyPerParent(new BigDecimal(qtyPerParent));
    }
    return child;
  }

  private MaterialMasterRaw raw(String materialCode, String theoreticalNetWeight) {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setMaterialCode(materialCode);
    row.setGlobalSeg3TheoreticalNetWeight(theoreticalNetWeight);
    return row;
  }

  private MakePartChildNetWeight childNetWeight(String netWeightG) {
    MakePartChildNetWeight row = new MakePartChildNetWeight();
    row.setNetWeightG(new BigDecimal(netWeightG));
    return row;
  }

  private MaterialScrapRef scrap(String scrapCode) {
    MaterialScrapRef row = new MaterialScrapRef();
    row.setScrapCode(scrapCode);
    return row;
  }

  private U9BomByproductMaster byproduct(String materialCode, String outputQty, String unit) {
    U9BomByproductMaster row = new U9BomByproductMaster();
    row.setByproductMaterialNo(materialCode);
    row.setOutputQty(new BigDecimal(outputQty));
    row.setUnit(unit);
    return row;
  }

  private MakePartWeightServiceImpl service(
      MaterialMasterRawMapper mapper, MakePartChildNetWeightMapper childWeightMapper) {
    return new MakePartWeightServiceImpl(
        mapper,
        childWeightMapper,
        mock(MakePartScrapMappingService.class),
        mock(U9BomByproductMasterMapper.class));
  }
}

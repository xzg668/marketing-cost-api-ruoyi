package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.mapper.MakePartChildNetWeightMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.U9BomByproductMasterMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MakePartByproductSelectionTest {
  private final MaterialMasterRawMapper masters = mock(MaterialMasterRawMapper.class);
  private final com.sanhua.marketingcost.service.MakePartScrapMappingService mappings =
      mock(com.sanhua.marketingcost.service.MakePartScrapMappingService.class);
  private final U9BomByproductMasterMapper products = mock(U9BomByproductMasterMapper.class);
  private final MakePartWeightServiceImpl service = new MakePartWeightServiceImpl(
      masters, mock(MakePartChildNetWeightMapper.class), mappings, products);

  @BeforeAll static void metadata() {
    TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), U9BomByproductMaster.class);
  }

  @ParameterizedTest
  @CsvSource({
      "203259597,203259596,只,1,53.190,301991111,0.047891,101.081,53.190",
      "301210230,301240292,千克,1.00179292,,301990044,0.00179292,1001.79292,1000",
      "203259498,301280056,米,0.0108,,301990444,0.0000317,,"
  })
  void originalThreeInputsUseTheirSoleManufacturingByproduct(String parent, String material,
      String unit, String quantity, String parentNet, String byproductCode, String scrapKg,
      String gross, String net) {
    var master = new MaterialMasterRaw(); master.setMaterialCode(parent);
    master.setGlobalSeg3TheoreticalNetWeight(parentNet);
    when(masters.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(master));
    when(products.selectList(any())).thenAnswer(call -> {
      LambdaQueryWrapper<U9BomByproductMaster> query = call.getArgument(0);
      assertThat(query.getSqlSegment()).as("先数制造BOM内全部有效副产品，不能先按CMS料号筛掉唯一记录")
          .contains("parent_material_no", "price_org_code", "version_no", "status", "effective_from", "effective_to")
          .doesNotContain("byproduct_material_no IN");
      return List.of(product(byproductCode, scrapKg));
    });
    var child = child(material, unit, quantity);
    var weight = service.resolveWeights(parent, child, unit.equals("只") ? "毛坯加工" : "原材料加工",
        "2026-11", LocalDate.of(2026, 11, 11), "COMMERCIAL", false);
    assertThat(weight.getStatus()).isEqualTo("OK");
    if (gross == null) {
      assertThat(weight.getGrossWeightG()).isNull();
      assertThat(weight.getNetWeightG()).isNull();
      assertThat(weight.getRecoveredScrapWeightG()).isEqualByComparingTo(new BigDecimal(scrapKg).movePointRight(3));
    } else {
      assertThat(weight.getGrossWeightG()).isEqualByComparingTo(gross);
      assertThat(weight.getNetWeightG()).isEqualByComparingTo(net);
    }
    assertThat(weight.getRemark()).contains("byproduct_material_no=" + byproductCode);
    verifyNoInteractions(mappings);
  }

  @Test void multipleByproductsUseOnlyTheUniqueCmsMatch() {
    when(products.selectList(any())).thenReturn(List.of(product("OTHER", "0.03"), product("CMS", "0.01")));
    var mapping = new MaterialScrapRef(); mapping.setScrapCode("CMS");
    when(mappings.listMappings("RAW", "COMMERCIAL")).thenReturn(List.of(mapping));
    var weight = service.resolveWeights("P", child("RAW", "kg", "0.08"), "原材料加工",
        "2026-11", LocalDate.of(2026, 11, 11), "COMMERCIAL", true);
    assertThat(weight.getStatus()).isEqualTo("OK");
    assertThat(weight.getNetWeightG()).isEqualByComparingTo("70");
    assertThat(weight.getRemark()).contains("byproduct_material_no=CMS");
  }

  @ParameterizedTest @CsvSource({"MISSING", "A", "BOTH"})
  void multipleUnmatchedOrAmbiguousByproductsDoNotGuess(String mappingMode) {
    when(products.selectList(any())).thenReturn(List.of(product("A", "0.01"), product("A", "0.02")));
    var mapping = new MaterialScrapRef(); mapping.setScrapCode(mappingMode.equals("A") ? "A" : "OTHER");
    when(mappings.listMappings("RAW", "COMMERCIAL"))
        .thenReturn(mappingMode.equals("MISSING") ? List.of() : List.of(mapping));
    var weight = service.resolveWeights("P", child("RAW", "kg", "0.08"), "原材料加工",
        "2026-11", LocalDate.of(2026, 11, 11), "COMMERCIAL", true);
    assertThat(weight.getStatus()).isEqualTo("MISSING_WEIGHT");
    assertThat(weight.getNetWeightG()).isNull();
    assertThat(weight.getRemark()).contains("副产品反算失败");
  }

  private static BomU9Source child(String code, String unit, String quantity) {
    var value = new BomU9Source(); value.setChildMaterialNo(code); value.setStockUnit(unit);
    value.setPriceOrgCode("210"); value.setBomVersion("F007");
    value.setQtyPerParent(new BigDecimal(quantity)); return value;
  }

  private static U9BomByproductMaster product(String code, String kg) {
    var value = new U9BomByproductMaster(); value.setByproductMaterialNo(code);
    value.setOutputQty(new BigDecimal(kg)); value.setUnit("kg"); return value;
  }
}

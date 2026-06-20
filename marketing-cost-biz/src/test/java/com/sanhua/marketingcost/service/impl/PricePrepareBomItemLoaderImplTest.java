package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PricePrepareBomItemLoaderImplTest {

  private BomCostingRowMapper costingRowMapper;
  private BomRawHierarchyMapper rawHierarchyMapper;
  private OaFormItemMapper oaFormItemMapper;
  private PackageComponentIdentifyService packageComponentIdentifyService;
  private PricePrepareBomItemLoaderImpl loader;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, BomCostingRow.class);
    TableInfoHelper.initTableInfo(assistant, BomRawHierarchy.class);
  }

  @BeforeEach
  void setUp() {
    costingRowMapper = mock(BomCostingRowMapper.class);
    rawHierarchyMapper = mock(BomRawHierarchyMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    packageComponentIdentifyService = mock(PackageComponentIdentifyService.class);
    loader =
        new PricePrepareBomItemLoaderImpl(
            costingRowMapper,
            rawHierarchyMapper,
            oaFormItemMapper,
            packageComponentIdentifyService);
  }

  @Test
  @DisplayName("包装组件父料号只在 parent_code 中出现时，补入价格准备计划行")
  void appendsSyntheticPackageParentRows() {
    BomCostingRow childA = costingRow("OA-GOLDEN-001", "1079900000536", "9830000026238", "250011491", "1");
    BomCostingRow childB = costingRow("OA-GOLDEN-001", "1079900000536", "9830000026238", "250020958", "6");
    when(costingRowMapper.selectList(any())).thenReturn(List.of(childA, childB));
    when(packageComponentIdentifyService.batchIdentify(any(), anyString()))
        .thenReturn(Map.of("9830000026238", true));
    when(rawHierarchyMapper.selectList(any()))
        .thenReturn(List.of(rawPackageParent("1079900000536", "9830000026238")));

    List<BomCostingRow> rows = loader.loadByOaNo(" OA-GOLDEN-001 ");

    assertThat(rows).hasSize(3);
    BomCostingRow packageParent = rows.get(2);
    assertThat(packageParent.getOaNo()).isEqualTo("OA-GOLDEN-001");
    assertThat(packageParent.getTopProductCode()).isEqualTo("1079900000536");
    assertThat(packageParent.getMaterialCode()).isEqualTo("9830000026238");
    assertThat(packageParent.getParentCode()).isEqualTo("1079900000536");
    assertThat(packageParent.getMaterialName()).isEqualTo("包装组件");
    assertThat(packageParent.getQtyPerTop()).isEqualByComparingTo("1.00000000");
  }

  @Test
  @DisplayName("包装组件父料号已是结算行时不重复补入")
  void doesNotDuplicateExistingPackageParentRows() {
    BomCostingRow packageParent =
        costingRow("OA-GOLDEN-001", "1079900000536", "1079900000536", "9830000026238", "1");
    BomCostingRow child =
        costingRow("OA-GOLDEN-001", "1079900000536", "9830000026238", "250011491", "1");
    when(costingRowMapper.selectList(any())).thenReturn(List.of(packageParent, child));
    when(packageComponentIdentifyService.batchIdentify(any(), anyString()))
        .thenReturn(Map.of("1079900000536", false, "9830000026238", true));
    when(rawHierarchyMapper.selectList(any()))
        .thenReturn(List.of(rawPackageParent("1079900000536", "9830000026238")));

    List<BomCostingRow> rows = loader.loadByOaNo("OA-GOLDEN-001");

    assertThat(rows).hasSize(2);
    assertThat(rows)
        .filteredOn(row -> "9830000026238".equals(row.getMaterialCode()))
        .hasSize(1);
  }

  private BomCostingRow costingRow(
      String oaNo, String topProductCode, String parentCode, String materialCode, String qty) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo(oaNo);
    row.setTopProductCode(topProductCode);
    row.setParentCode(parentCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-name");
    row.setQtyPerParent(new BigDecimal(qty));
    row.setQtyPerTop(new BigDecimal(qty));
    return row;
  }

  private BomRawHierarchy rawPackageParent(String topProductCode, String packageCode) {
    BomRawHierarchy raw = new BomRawHierarchy();
    raw.setId(900L);
    raw.setTopProductCode(topProductCode);
    raw.setParentCode(topProductCode);
    raw.setMaterialCode(packageCode);
    raw.setLevel(1);
    raw.setPath("/" + topProductCode + "/" + packageCode + "/");
    raw.setQtyPerParent(new BigDecimal("1.00000000"));
    raw.setQtyPerTop(new BigDecimal("1.00000000"));
    raw.setMaterialName("包装组件");
    raw.setShapeAttr("虚拟");
    raw.setBomPurpose("主制造");
    raw.setSourceType("U9");
    raw.setBusinessUnitType("COMMERCIAL");
    return raw;
  }
}

package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PricePrepareBomItemLoaderImplTest {

  private BomCostingRowMapper costingRowMapper;
  private BomRawHierarchyMapper rawHierarchyMapper;
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
    packageComponentIdentifyService = mock(PackageComponentIdentifyService.class);
    loader =
        new PricePrepareBomItemLoaderImpl(
            costingRowMapper,
            rawHierarchyMapper,
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
    assertThat(packageParent.getPriceOrgCode()).isEqualTo("210");
    assertThat(packageParent.getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
    verify(packageComponentIdentifyService).batchIdentify(any(), eq("COMMERCIAL"));
    assertRawHierarchyQueryUsesPriceOrg("210");
  }

  @Test
  @DisplayName("板换 OA 补包装父节点时只回查 220 raw hierarchy")
  void appendsSyntheticPackageParentRowsFromPlateOrganizationOnly() {
    BomCostingRow child =
        costingRow("FI-SC-020-20260707-001", "PLATE-TOP", "PLATE-PKG", "PLATE-CHILD", "1");
    child.setPriceOrgCode("220");
    child.setMaterialOrganizationCode("PLATE");
    when(costingRowMapper.selectList(any())).thenReturn(List.of(child));
    when(packageComponentIdentifyService.batchIdentify(any(), anyString()))
        .thenReturn(Map.of("PLATE-PKG", true));
    when(rawHierarchyMapper.selectList(any()))
        .thenReturn(List.of(rawPackageParent("PLATE-TOP", "PLATE-PKG")));

    List<BomCostingRow> rows = loader.loadByOaNo("FI-SC-020-20260707-001");

    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).getMaterialCode()).isEqualTo("PLATE-PKG");
    assertThat(rows.get(1).getPriceOrgCode()).isEqualTo("220");
    assertThat(rows.get(1).getMaterialOrganizationCode()).isEqualTo("PLATE");
    verify(packageComponentIdentifyService).batchIdentify(any(), eq("PLATE"));
    assertRawHierarchyQueryUsesPriceOrg("220");
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

  @Test
  @DisplayName("成本行缺上游组织时不按 OA 或产品名重猜")
  void failsWhenCostingRowMissingOrganization() {
    BomCostingRow child = costingRow("FI-SC-020-20260707-001", "PLATE-TOP", "PLATE-PKG", "PLATE-CHILD", "1");
    child.setPriceOrgCode(null);
    child.setMaterialOrganizationCode(null);
    when(costingRowMapper.selectList(any())).thenReturn(List.of(child));

    assertThatThrownBy(() -> loader.loadByOaNo("FI-SC-020-20260707-001"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少上游组织");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void assertRawHierarchyQueryUsesPriceOrg(String expectedPriceOrgCode) {
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(rawHierarchyMapper).selectList(captor.capture());
    Wrapper wrapper = captor.getValue();
    assertThat(wrapper.getSqlSegment()).contains("price_org_code");
    assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs())
        .containsValue(expectedPriceOrgCode);
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
    row.setPriceOrgCode("210");
    row.setMaterialOrganizationCode("COMMERCIAL");
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

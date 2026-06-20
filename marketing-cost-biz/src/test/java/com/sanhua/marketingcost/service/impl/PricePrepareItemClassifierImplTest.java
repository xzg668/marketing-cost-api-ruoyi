package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
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

class PricePrepareItemClassifierImplTest {

  private PackageComponentIdentifyService packageComponentIdentifyService;
  private MaterialMasterMapper materialMasterMapper;
  private MaterialMasterRawMapper materialMasterRawMapper;
  private OaFormItemMapper oaFormItemMapper;
  private PricePrepareItemClassifierImpl classifier;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, MaterialMaster.class);
    TableInfoHelper.initTableInfo(assistant, MaterialMasterRaw.class);
  }

  @BeforeEach
  void setUp() {
    packageComponentIdentifyService = mock(PackageComponentIdentifyService.class);
    materialMasterMapper = mock(MaterialMasterMapper.class);
    materialMasterRawMapper = mock(MaterialMasterRawMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    classifier =
        new PricePrepareItemClassifierImpl(
            packageComponentIdentifyService,
            materialMasterMapper,
            materialMasterRawMapper,
            oaFormItemMapper);
  }

  @Test
  @DisplayName("普通料号分类为 NORMAL")
  void classifiesNormalMaterial() {
    BomCostingRow row = bomRow("TOP-A", "MAT-001", "采购件");
    when(packageComponentIdentifyService.batchIdentify(any())).thenReturn(Map.of("MAT-001", false));
    when(materialMasterMapper.selectList(any())).thenReturn(List.of(master("MAT-001", "采购件")));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of());

    List<PricePreparePlanItem> items = classifier.classify(List.of(row));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getItemType()).isEqualTo("NORMAL");
    assertThat(items.get(0).getStatus()).isEqualTo("READY");
  }

  @Test
  @DisplayName("包装组件按 15155% + 虚拟 识别结果分类")
  void classifiesPackageComponent() {
    BomCostingRow row = bomRow("1079900000536", "9830000026238", "虚拟");
    when(packageComponentIdentifyService.batchIdentify(any()))
        .thenReturn(Map.of("9830000026238", true));
    when(materialMasterMapper.selectList(any())).thenReturn(List.of());
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), any()))
        .thenReturn(List.of(raw("9830000026238", "虚拟", "1515501")));

    List<PricePreparePlanItem> items = classifier.classify(List.of(row));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getItemType()).isEqualTo("PACKAGE_COMPONENT");
    assertThat(items.get(0).getTopProductCode()).isEqualTo("1079900000536");
    assertThat(items.get(0).getStatus()).isEqualTo("READY");
  }

  @Test
  @DisplayName("自制件分类为 MAKE_PART")
  void classifiesMakePart() {
    BomCostingRow row = bomRow("TOP-A", "MAKE-001", null);
    when(packageComponentIdentifyService.batchIdentify(any())).thenReturn(Map.of("MAKE-001", false));
    when(materialMasterMapper.selectList(any())).thenReturn(List.of(master("MAKE-001", "制造件")));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of());

    List<PricePreparePlanItem> items = classifier.classify(List.of(row));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getItemType()).isEqualTo("MAKE_PART");
    assertThat(items.get(0).getStatus()).isEqualTo("READY");
  }

  @Test
  @DisplayName("缺主档分类为缺口计划")
  void classifiesMissingMaster() {
    BomCostingRow row = bomRow("TOP-A", "MISS-001", null);
    row.setMaterialName(null);
    when(packageComponentIdentifyService.batchIdentify(any())).thenReturn(Map.of("MISS-001", false));
    when(materialMasterMapper.selectList(any())).thenReturn(List.of());
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of());

    List<PricePreparePlanItem> items = classifier.classify(List.of(row));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getItemType()).isEqualTo("NORMAL");
    assertThat(items.get(0).getStatus()).isEqualTo("MISSING_MASTER");
    assertThat(items.get(0).getMessage()).contains("缺料品主档");
  }

  @Test
  @DisplayName("同一个 OA 多个顶级成品保持各自行上下文")
  void keepsMultipleTopProductContext() {
    BomCostingRow row1 = bomRow("TOP-A", "MAT-A", "采购件");
    BomCostingRow row2 = bomRow("TOP-B", "MAKE-B", "自制");
    when(packageComponentIdentifyService.batchIdentify(any()))
        .thenReturn(Map.of("MAT-A", false, "MAKE-B", false));
    when(materialMasterMapper.selectList(any()))
        .thenReturn(List.of(master("MAT-A", "采购件"), master("MAKE-B", "制造件")));
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of());

    List<PricePreparePlanItem> items = classifier.classify(List.of(row1, row2));

    assertThat(items).extracting(PricePreparePlanItem::getTopProductCode)
        .containsExactly("TOP-A", "TOP-B");
    assertThat(items).extracting(PricePreparePlanItem::getItemType)
        .containsExactly("NORMAL", "MAKE_PART");
  }

  private BomCostingRow bomRow(String topProductCode, String materialCode, String shapeAttr) {
    BomCostingRow row = new BomCostingRow();
    row.setId((long) materialCode.hashCode());
    row.setTopProductCode(topProductCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-name");
    row.setShapeAttr(shapeAttr);
    row.setQtyPerTop(BigDecimal.ONE);
    return row;
  }

  private MaterialMaster master(String materialCode, String shapeAttr) {
    MaterialMaster master = new MaterialMaster();
    master.setMaterialCode(materialCode);
    master.setMaterialName(materialCode + "-master");
    master.setShapeAttr(shapeAttr);
    return master;
  }

  private MaterialMasterRaw raw(String materialCode, String shapeAttr, String mainCategoryCode) {
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode(materialCode);
    raw.setMaterialName(materialCode + "-raw");
    raw.setShapeAttr(shapeAttr);
    raw.setMainCategoryCode(mainCategoryCode);
    return raw;
  }
}

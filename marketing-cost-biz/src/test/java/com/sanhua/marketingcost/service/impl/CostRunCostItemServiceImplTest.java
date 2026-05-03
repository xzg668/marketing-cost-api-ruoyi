package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.SalaryCostMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成本汇总口径校准测试 (Task #9)。
 *
 * <p>本测试聚焦在新接入的产品属性系数查询逻辑——金标产品 1079900000536 是标准品，
 * coefficient = 1.0；未维护属性的兜底料号必须回落到 1（与 V11 DEFAULT 对齐）。
 * 整链路（含 calculateItems 12 个 mapper）的金标断言由 GoldenSampleRegressionTest 兜底。
 */
class CostRunCostItemServiceImplTest {

  @Test
  @DisplayName("产品属性系数：命中 lp_product_property → 返回 coefficient")
  void coefficientHit() {
    // T19：lookup 走 cacheLookup（@Cacheable），不再直接 mapper
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    ProductProperty property = new ProductProperty();
    property.setParentCode("P-NON-STD");
    property.setCoefficient(new BigDecimal("1.2500"));
    when(lookup.findProductProperty("P-NON-STD")).thenReturn(property);

    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);
    assertThat(svc.lookupProductCoefficient("P-NON-STD"))
        .isEqualByComparingTo(new BigDecimal("1.2500"));
  }

  @Test
  @DisplayName("产品属性系数：查无记录 → 回落 1（标准品兜底）")
  void coefficientMissDefault() {
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    when(lookup.findProductProperty("P-UNKNOWN")).thenReturn(null);

    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);
    assertThat(svc.lookupProductCoefficient("P-UNKNOWN"))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  @DisplayName("产品属性系数：coefficient 字段为 null → 也回落 1")
  void coefficientNullDefault() {
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    ProductProperty property = new ProductProperty();
    property.setParentCode("P-NULL-COEF");
    property.setCoefficient(null);
    when(lookup.findProductProperty("P-NULL-COEF")).thenReturn(property);

    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);
    assertThat(svc.lookupProductCoefficient("P-NULL-COEF"))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  @DisplayName("产品属性系数：productCode 为空 → 直接返回 1，不查库")
  void coefficientBlankCode() {
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);

    assertThat(svc.lookupProductCoefficient(null)).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(svc.lookupProductCoefficient("  ")).isEqualByComparingTo(BigDecimal.ONE);
    // 没有任何查库行为
    org.mockito.Mockito.verifyNoInteractions(lookup);
  }

  // -------- T11 splitPartAmount --------

  @Test
  @DisplayName("T11 splitPartAmount: 多部品按 cost_element 拆分包装/非包装")
  void splitPartAmount_buckets() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    // 4 个部品：2 包装 + 2 非包装；amount 含 1 个 NULL（验证 NULL 跳过）
    CostRunPartItem p1 = newPart("PKG-001", new BigDecimal("10.000000"));
    CostRunPartItem p2 = newPart("PKG-002", new BigDecimal("5.500000"));
    CostRunPartItem p3 = newPart("RAW-001", new BigDecimal("20.000000"));
    CostRunPartItem p4 = newPart("RAW-002", null); // NULL 应被跳过
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of(p1, p2, p3, p4));

    // 主档：PKG-001 / PKG-002 是包装材料，RAW-* 不返回（被 .eq(cost_element=包装) 过滤掉）
    when(masterMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newMaster("PKG-001"), newMaster("PKG-002")));

    CostRunCostItemServiceImpl svc = buildWith(partMapper, masterMapper, mock(OaFormMapper.class), mock(OaFormItemMapper.class));
    var split = svc.splitPartAmount("OA-1", "P-1");

    // 包装总额 = 10 + 5.5 = 15.5；非包装 = 20.0（NULL 跳）
    assertThat(split.packageTotal()).isEqualByComparingTo(new BigDecimal("15.500000"));
    assertThat(split.nonPackageTotal()).isEqualByComparingTo(new BigDecimal("20.000000"));
  }

  @Test
  @DisplayName("T11 splitPartAmount: 主档查无包装件 → 全部归非包装")
  void splitPartAmount_noPackageRow() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(partMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newPart("RAW-A", new BigDecimal("3.000000"))));
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of()); // 无包装

    CostRunCostItemServiceImpl svc = buildWith(partMapper, masterMapper, mock(OaFormMapper.class), mock(OaFormItemMapper.class));
    var split = svc.splitPartAmount("OA-1", "P-1");

    assertThat(split.packageTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(split.nonPackageTotal()).isEqualByComparingTo(new BigDecimal("3.000000"));
  }

  // -------- T11 lookupFreight --------

  @Test
  @DisplayName("T11 lookupFreight: 命中 OaFormItem.shipping_fee → 求和")
  void lookupFreight_hit() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);

    OaForm form = new OaForm();
    form.setId(99L);
    form.setOaNo("OA-1");
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(form);

    OaFormItem r1 = new OaFormItem();
    r1.setShippingFee(new BigDecimal("1.250000"));
    OaFormItem r2 = new OaFormItem();
    r2.setShippingFee(new BigDecimal("0.500000"));
    OaFormItem r3 = new OaFormItem();
    r3.setShippingFee(null); // NULL 跳过
    when(formItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r1, r2, r3));

    CostRunCostItemServiceImpl svc =
        buildWith(mock(CostRunPartItemMapper.class), mock(MaterialMasterMapper.class), formMapper, formItemMapper);
    BigDecimal freight = svc.lookupFreight("OA-1", "P-1");

    assertThat(freight).isEqualByComparingTo(new BigDecimal("1.750000"));
  }

  @Test
  @DisplayName("T11 lookupFreight: OA 不存在 → 0（不抛错）")
  void lookupFreight_noForm() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    CostRunCostItemServiceImpl svc =
        buildWith(mock(CostRunPartItemMapper.class), mock(MaterialMasterMapper.class), formMapper, mock(OaFormItemMapper.class));
    assertThat(svc.lookupFreight("OA-MISSING", "P-1")).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // -------- T24 BOM_BUCKET 焊料 / 包装聚合 --------

  @Test
  @DisplayName("T24 焊料聚合：part 7 行命中焊料子件 → SUM amount")
  void bucketWeld_happyPath() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    // mock part_item：3 行焊料 + 1 行非焊料
    CostRunPartItem p1 = newPart("301010012", new BigDecimal("1.625677"));
    CostRunPartItem p2 = newPart("301020895", new BigDecimal("1.101030"));
    CostRunPartItem p3 = newPart("301030001", new BigDecimal("0.200252"));
    CostRunPartItem pOther = newPart("203250606", new BigDecimal("0.788363"));
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of(p1, p2, p3, pOther));

    // mock material_master：只返焊料 3 行（master 已经被 cost_element=焊料 过滤）
    when(masterMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newWeldMaster("301010012"), newWeldMaster("301020895"), newWeldMaster("301030001")));

    CostRunCostItemServiceImpl svc = buildWith(partMapper, masterMapper, mock(OaFormMapper.class), mock(OaFormItemMapper.class));
    BigDecimal sum = svc.sumPartByCostElement("OA-1", "P-1", "主要材料-焊料");

    // 1.625677 + 1.101030 + 0.200252 = 2.926959；非焊料 part 不计入
    assertThat(sum).isEqualByComparingTo(new BigDecimal("2.926959"));
  }

  @Test
  @DisplayName("T24 焊料聚合：part 没有焊料子件 → 返 0（buildBucketItems 跳过此行）")
  void bucketWeld_emptyMaster() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(partMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newPart("RAW-001", new BigDecimal("5.000000"))));
    // 主档查无焊料命中
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    CostRunCostItemServiceImpl svc = buildWith(partMapper, masterMapper, mock(OaFormMapper.class), mock(OaFormItemMapper.class));
    assertThat(svc.sumPartByCostElement("OA-1", "P-1", "主要材料-焊料"))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("T24 包装聚合：BOM 父件命中包装组件 → 子件 SUM × 1.05 / 12 = 1.113105")
  void bucketPackage_happyPath() {
    // 用 OA-001 实测 4 行包装数据 mock（瓦楞纸箱/垫板/纸板隔档/隔板）
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    // 1) raw 主档返 1 行 main_category=包装组件 的虚拟父件
    when(rawMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newRawMaster("9830000026238", "包装组件")));
    // 2) BOM 返该父件下 4 个子件
    when(bomMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            newBomChild("250011491"), newBomChild("250030575"),
            newBomChild("250020958"), newBomChild("250050674")));
    // 3) part_item 返 4 行子件 amount（与 OA-001 实测一致）
    when(partMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            newPart("250011491", new BigDecimal("5.4620")),
            newPart("250030575", new BigDecimal("3.1588")),
            newPart("250020958", new BigDecimal("2.7384")),
            newPart("250050674", new BigDecimal("1.3620"))));

    CostRunCostItemServiceImpl svc = buildBucketSvc(partMapper, rawMapper, bomMapper);
    BigDecimal childSum = svc.sumPartByBomParentMainCategory("OA-1", "1079900000536", "包装组件");

    // 子件 SUM = 12.7212；包装算法 = SUM × 1.05 / 12 = 1.113105（与 Excel 见机表 r45 严格一致）
    assertThat(childSum).isEqualByComparingTo(new BigDecimal("12.721200"));
    BigDecimal pkgAmount = childSum.multiply(new BigDecimal("1.05"))
        .divide(new BigDecimal("12"), 6, java.math.RoundingMode.HALF_UP);
    assertThat(pkgAmount).isEqualByComparingTo(new BigDecimal("1.113105"));
    assertThat(svc.calculatePackageBucketAmount("OA-1", "1079900000536"))
        .isEqualByComparingTo(new BigDecimal("1.113105"));
  }

  @Test
  @DisplayName("T24 包装聚合：BOM 找不到包装组件父件 → 返 0（buildBucketItems 跳过此行）")
  void bucketPackage_noParent() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    // raw 主档无包装组件
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    CostRunCostItemServiceImpl svc = buildBucketSvc(partMapper, rawMapper, bomMapper);
    assertThat(svc.sumPartByBomParentMainCategory("OA-1", "P-1", "包装组件"))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("T24 包装聚合：BOM 父件命中但下面没子件 → 返 0")
  void bucketPackage_noBomChildren() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    when(rawMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newRawMaster("9830000026238", "包装组件")));
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of()); // BOM 没子件

    CostRunCostItemServiceImpl svc = buildBucketSvc(partMapper, rawMapper, bomMapper);
    assertThat(svc.sumPartByBomParentMainCategory("OA-1", "P-1", "包装组件"))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ---------- 辅助 ----------

  private static CostRunPartItem newPart(String code, BigDecimal amount) {
    CostRunPartItem p = new CostRunPartItem();
    p.setPartCode(code);
    p.setAmount(amount);
    return p;
  }

  private static MaterialMaster newMaster(String code) {
    MaterialMaster m = new MaterialMaster();
    m.setMaterialCode(code);
    m.setCostElement("主要材料-包装材料");
    return m;
  }

  /** T24：焊料 master mock — cost_element=主要材料-焊料 */
  private static MaterialMaster newWeldMaster(String code) {
    MaterialMaster m = new MaterialMaster();
    m.setMaterialCode(code);
    m.setCostElement("主要材料-焊料");
    return m;
  }

  /** T24：raw 主档 mock — main_category_name=指定值（用于包装组件父件） */
  private static MaterialMasterRaw newRawMaster(String code, String mainCategoryName) {
    MaterialMasterRaw m = new MaterialMasterRaw();
    m.setMaterialCode(code);
    m.setMainCategoryName(mainCategoryName);
    return m;
  }

  /** T24：BOM hierarchy mock — material_code=指定值（视为父件下挂的子件） */
  private static BomRawHierarchy newBomChild(String materialCode) {
    BomRawHierarchy h = new BomRawHierarchy();
    h.setMaterialCode(materialCode);
    return h;
  }

  /** T24 单测专用：注入 part / raw / bom 3 个 mapper（包装聚合用），其余 mock 兜底 */
  private CostRunCostItemServiceImpl buildBucketSvc(
      CostRunPartItemMapper partMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(DepartmentFundRateMapper.class),
        mock(AuxCostItemMapper.class),
        partMapper,
        mock(QualityLossRateMapper.class),
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        mock(ProductPropertyMapper.class),
        mock(MaterialMasterMapper.class),
        rawMapper,
        bomMapper,
        mock(CostRunCacheLookupService.class),
        false);
  }

  /** T19：T19 之前的 4 个 coefficient test 走这个 helper（直接 mock cacheLookup） */
  private CostRunCostItemServiceImpl buildWithLookup(CostRunCacheLookupService lookup) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(DepartmentFundRateMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(QualityLossRateMapper.class),
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        mock(ProductPropertyMapper.class),
        mock(MaterialMasterMapper.class),
        mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class),
        mock(com.sanhua.marketingcost.mapper.BomRawHierarchyMapper.class),
        lookup,
        false);
  }

  /** T11 单测专用：只关心 part / master / oaForm* 4 个 mapper，其余 mock 兜底 */
  private CostRunCostItemServiceImpl buildWith(
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        formMapper,
        formItemMapper,
        mock(SalaryCostMapper.class),
        mock(DepartmentFundRateMapper.class),
        mock(AuxCostItemMapper.class),
        partMapper,
        mock(QualityLossRateMapper.class),
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        mock(ProductPropertyMapper.class),
        masterMapper,
        mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class),
        mock(com.sanhua.marketingcost.mapper.BomRawHierarchyMapper.class),
        mock(CostRunCacheLookupService.class),
        false);
  }
}

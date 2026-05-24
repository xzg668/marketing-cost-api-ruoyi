package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * MakeSpecPriceResolver 单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>spec.raw_unit_price 已填 → 直接用</li>
 *   <li>spec.raw_unit_price 明确为 0 → 按 0 价命中</li>
 *   <li>spec.raw_unit_price 为空 → 缺价，不按 0 兜底，不用 raw_material_code 递归取价</li>
 * </ul>
 */
class MakeSpecPriceResolverTest {

  private MakePartSpecMapper specMapper;
  private MaterialScrapRefMapper materialScrapRefMapper;
  private PriceScrapService priceScrapService;
  private MakeSpecPriceResolver resolver;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MaterialScrapRef.class);
    specMapper = Mockito.mock(MakePartSpecMapper.class);
    materialScrapRefMapper = Mockito.mock(MaterialScrapRefMapper.class);
    priceScrapService = Mockito.mock(PriceScrapService.class);
    resolver = new MakeSpecPriceResolver(specMapper, materialScrapRefMapper, priceScrapService);
  }

  // ---------- T07 基础 ----------

  @Test
  @DisplayName("priceType() 必须是 MAKE 桶")
  void priceTypeIsMake() {
    assertThat(resolver.priceType()).isEqualTo(PriceTypeEnum.MAKE);
  }

  @Test
  @DisplayName("命中：raw 已填 + blank=0 → unit_price = process_fee + outsource_fee（材料 0）")
  void hitWithoutRaw() {
    MakePartSpec s = spec("M-A");
    s.setProcessFee(new BigDecimal("3.20"));
    s.setOutsourceFee(new BigDecimal("1.50"));
    s.setBlankWeight(null);
    s.setRawUnitPrice(new BigDecimal("99")); // raw 价已维护；毛重为空时材料成本自然为 0
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-A"), route());

    assertThat(r.unitPrice()).isEqualByComparingTo("4.70");
    assertThat(r.priceSource()).isEqualTo("自制件");
  }

  @Test
  @DisplayName("spec 不存在 → miss('lp_make_part_spec 无该料号: ...')")
  void missWhenSpecAbsent() {
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

    PriceResolveResult r = resolver.resolve("OA-1", part("MAT-X"), route());

    assertThat(r.unitPrice()).isNull();
    assertThat(r.remark()).contains("lp_make_part_spec 无该料号").contains("MAT-X");
  }

  @Test
  @DisplayName("partCode 为空 → miss('partCode 为空')，不查 mapper")
  void missWhenPartCodeBlank() {
    PriceResolveResult r = resolver.resolve("OA-1", part(""), route());

    assertThat(r.unitPrice()).isNull();
    assertThat(r.remark()).isEqualTo("partCode 为空");
    Mockito.verifyNoInteractions(specMapper);
  }

  // ---------- 原料单价 ----------

  @Test
  @DisplayName("raw_unit_price 已填 → 直接用，不查 raw_material_code")
  void rawUnitPriceShortCircuit() {
    MakePartSpec s = spec("M-B");
    s.setBlankWeight(new BigDecimal("100")); // 100g
    s.setRawUnitPrice(new BigDecimal("60"));  // 60 元/kg
    s.setRawMaterialCode("RAW-IGNORED");      // 即使填了 raw code 也不走递归
    s.setProcessFee(new BigDecimal("0.5"));
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-B"), route());

    // material = 100 × 60 / 1000 = 6；总 = 6 + 0.5 = 6.5
    assertThat(r.unitPrice()).isEqualByComparingTo("6.5");
    Mockito.verifyNoInteractions(materialScrapRefMapper, priceScrapService);
  }

  @Test
  @DisplayName("raw_unit_price 为空但 raw_material_code 有值 → 缺价，不递归查价")
  void rawMaterialCodeDoesNotTriggerRecursiveLookup() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("83.05"));   // 83.05g
    s.setRawMaterialCode("TP2-301050081");
    s.setProcessFee(new BigDecimal("1.50"));
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    assertThat(r.unitPrice()).isNull();
    assertThat(r.remark())
        .contains("raw_unit_price为空")
        .contains("raw_material_code=TP2-301050081")
        .contains("未按0计算")
        .contains("未递归查raw_material_code");
    Mockito.verifyNoInteractions(materialScrapRefMapper, priceScrapService);
  }

  @Test
  @DisplayName("raw_unit_price 明确为 0 → 按 0 价命中，不查 raw_material_code")
  void rawUnitPriceZeroIsHit() {
    MakePartSpec s = spec("M-ZERO");
    s.setBlankWeight(new BigDecimal("100"));
    s.setRawUnitPrice(BigDecimal.ZERO);
    s.setRawMaterialCode("RAW-IGNORED");
    s.setProcessFee(new BigDecimal("2.5"));
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-ZERO"), route());

    assertThat(r.unitPrice()).isEqualByComparingTo("2.5");
    assertThat(r.priceSource()).isEqualTo("自制件");
    Mockito.verifyNoInteractions(materialScrapRefMapper, priceScrapService);
  }

  // ---------- T6 CMS 废料抵扣 ----------

  @Test
  @DisplayName("T6: 单废料 CMS 映射命中 → 结果与旧手工同价口径一致")
  void scrapDeductionFromCmsMapping() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("83.053885"));
    s.setNetWeight(new BigDecimal("80"));
    s.setRawUnitPrice(new BigDecimal("82.946903"));
    s.setRawMaterialCode(" 301 050066 ");
    s.setRecycleCode("A");
    s.setRecycleUnitPrice(new BigDecimal("999999")); // 历史字段不应参与新取价。
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));
    when(materialScrapRefMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(mapping("301050066", "301990317")));
    when(priceScrapService.getCurrentByScrapCodes(any()))
        .thenReturn(Map.of("301990317", scrap("301990317", "75.663717")));

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    // material = 83.053885 × 82.946903 / 1000 = 6.88909...
    // scrap   = 3.053885 × 75.663717 / 1000   = 0.23107...
    // unit    ≈ 6.6580
    assertThat(r.unitPrice()).isCloseTo(
        new BigDecimal("6.6580"), within(new BigDecimal("0.001")));
    assertThat(r.priceSource()).isEqualTo("自制件");
    assertThat(r.remark())
        .contains("CMS废料")
        .contains("raw=301050066")
        .contains("scrap=301990317")
        .contains("price=75.663717")
        .contains("weight=3.053885")
        .contains("deduct=");
  }

  @Test
  @DisplayName("T6: 多废料 CMS 映射 → 每条分别算 material-scrap 后求和")
  void multiScrapDeductionSumsEachMaterialMinusDeduction() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("100"));
    s.setNetWeight(new BigDecimal("80"));
    s.setRawUnitPrice(new BigDecimal("50"));
    s.setRawMaterialCode("301050066");
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));
    when(materialScrapRefMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            mapping("301050066", "301990317"),
            mapping("301050066", "301990999")));
    when(priceScrapService.getCurrentByScrapCodes(any()))
        .thenReturn(Map.of(
            "301990317", scrap("301990317", "10"),
            "301990999", scrap("301990999", "20")));

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    // material=100*50/1000=5；deduction1=20*10/1000=0.2；deduction2=20*20/1000=0.4；
    // 多废料口径=(5-0.2)+(5-0.4)=9.4。
    assertThat(r.unitPrice()).isEqualByComparingTo("9.40000000");
    assertThat(r.remark())
        .contains("CMS废料(raw=301050066,scrap=301990317")
        .contains("price=10")
        .contains("weight=20")
        .contains("deduct=0.2")
        .contains("CMS废料(raw=301050066,scrap=301990999")
        .contains("price=20")
        .contains("deduct=0.4");
    assertThat(r.remark()).hasSizeLessThan(200);
  }

  @Test
  @DisplayName("T6: 缺 CMS 映射 → 不用历史 recycle_unit_price，保留材料成本并写 remark")
  void missingCmsMappingAddsRemarkWithoutHistoricalFallback() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("83.053885"));
    s.setNetWeight(new BigDecimal("80"));
    s.setRawUnitPrice(new BigDecimal("82.946903"));
    s.setRawMaterialCode("301050066");
    s.setRecycleCode("A");
    s.setRecycleUnitPrice(new BigDecimal("75.663717")); // 历史字段废弃，不兜底。
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));
    when(materialScrapRefMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    // 缺映射时不扣废料，不能静默；也不能回退到 spec.recycle_unit_price。
    assertThat(r.unitPrice()).isCloseTo(
        new BigDecimal("6.8891"), within(new BigDecimal("0.001")));
    assertThat(r.remark()).isEqualTo("缺CMS废料映射(raw_material_code=301050066)");
    Mockito.verifyNoInteractions(priceScrapService);
  }

  @Test
  @DisplayName("T6: CMS 映射有、当前废料价缺 → 不用历史 recycle_unit_price，写缺价 remark")
  void missingCurrentScrapPriceAddsRemark() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("83.053885"));
    s.setNetWeight(new BigDecimal("80"));
    s.setRawUnitPrice(new BigDecimal("82.946903"));
    s.setRawMaterialCode("301050066");
    s.setRecycleCode("A");
    s.setRecycleUnitPrice(new BigDecimal("75.663717")); // 历史字段废弃，不兜底。
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));
    when(materialScrapRefMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(mapping("301050066", "301990317")));
    when(priceScrapService.getCurrentByScrapCodes(any())).thenReturn(Map.of());

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    // 缺价时本条废料抵扣按 0 处理，但必须显式提示。
    assertThat(r.unitPrice()).isCloseTo(
        new BigDecimal("6.8891"), within(new BigDecimal("0.001")));
    assertThat(r.remark())
        .contains("CMS废料")
        .contains("scrap=301990317")
        .contains("price=缺失")
        .contains("deduct=0")
        .contains("缺废料价(scrap_code=301990317)");
  }

  @Test
  @DisplayName("T6: blank <= net（无废料或负废料重量）→ 不查 CMS 映射和废料价，抵扣 0")
  void noScrapWhenBlankEqualsNet() {
    MakePartSpec s = spec("M-X");
    s.setBlankWeight(new BigDecimal("50"));
    s.setNetWeight(new BigDecimal("60"));
    s.setRawUnitPrice(new BigDecimal("100"));
    s.setRawMaterialCode("301050066");
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-X"), route());

    // material = 50 × 100 / 1000 = 5；不抵扣
    assertThat(r.unitPrice()).isEqualByComparingTo("5");
    Mockito.verifyNoInteractions(materialScrapRefMapper, priceScrapService);
  }

  @Test
  @DisplayName("raw_material_code 与 raw_unit_price 都空 → 缺价，不用加工费兜底")
  void neitherRawNorPriceMarksMissingPrice() {
    MakePartSpec s = spec("M-D");
    s.setBlankWeight(new BigDecimal("100"));
    s.setRawUnitPrice(null);
    s.setRawMaterialCode(null);
    s.setProcessFee(new BigDecimal("2.5"));
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-D"), route());

    assertThat(r.unitPrice()).isNull();
    assertThat(r.remark())
        .contains("raw_unit_price为空")
        .contains("raw_material_code=空")
        .contains("未按0计算");
    Mockito.verifyNoInteractions(materialScrapRefMapper, priceScrapService);
  }

  // ============================ 辅助构造 ============================

  private static CostRunPartItemDto part(String code) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setPartCode(code);
    dto.setPartQty(BigDecimal.ONE);
    return dto;
  }

  private static PriceTypeRoute route() {
    return new PriceTypeRoute(
        "M", MaterialFormAttrEnum.MANUFACTURED, PriceTypeEnum.MAKE,
        1, null, null, "manual", "自制件");
  }

  private static MakePartSpec spec(String code) {
    MakePartSpec s = new MakePartSpec();
    s.setMaterialCode(code);
    return s;
  }

  private static MaterialScrapRef mapping(String materialCode, String scrapCode) {
    MaterialScrapRef ref = new MaterialScrapRef();
    ref.setMaterialCode(materialCode);
    ref.setScrapCode(scrapCode);
    ref.setSourceDocNo("SEQ-100");
    ref.setCmsPostingPeriod("2025-09");
    return ref;
  }

  private static PriceScrap scrap(String scrapCode, String price) {
    PriceScrap scrap = new PriceScrap();
    scrap.setScrapCode(scrapCode);
    scrap.setRecyclePrice(new BigDecimal(price));
    return scrap;
  }
}

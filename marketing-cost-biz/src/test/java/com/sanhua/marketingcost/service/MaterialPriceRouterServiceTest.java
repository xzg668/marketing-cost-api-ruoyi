package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.MaterialPriceTypeSourceCandidate;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeSourceMapper;
import com.sanhua.marketingcost.service.impl.MaterialPriceRouterServiceImpl;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * MaterialPriceRouterService 单测 —— 全 Mock，不依赖 Spring/数据库。
 *
 * <p>T3 自动价格类型调整后覆盖：
 * <ul>
 *   <li>Mapper 按 created_at、id 倒序返回的当前记录是唯一价格类型</li>
 *   <li>价格类型不按报价月份或有效期失效；价格源负责历史价沿用</li>
 *   <li>当前 priceType 非法时阻塞，不降级到旧类型</li>
 *   <li>主档 shape_attr 优先；主档无该料号 → fallback 用路由表 material_shape</li>
 *   <li>materialCode 或 period 缺失时短路返回空</li>
 * </ul>
 */
class MaterialPriceRouterServiceTest {

  private MaterialPriceTypeMapper mapper;
  private MaterialMasterMapper masterMapper;
  private MaterialPriceTypeSourceMapper sourceMapper;
  private MaterialPriceRouterServiceImpl router;

  @BeforeEach
  void setUp() {
    mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    masterMapper = Mockito.mock(MaterialMasterMapper.class);
    sourceMapper = Mockito.mock(MaterialPriceTypeSourceMapper.class);
    // 默认 master 查不到任何料号 → formAttr 走 fallback 用路由表 material_shape
    when(masterMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    router = new MaterialPriceRouterServiceImpl(mapper, masterMapper, sourceMapper);
  }

  @Test
  @DisplayName("只返回 created_at、id 倒序后的当前价格类型，不降级旧类型")
  void resolvePicksLowestPriority() {
    // 注意：v1.1 起 SQL 在 DB 层按 priority ASC 排序；mock 直接给"已排序后"列表
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-001", "采购件", "固定价", 1, null, null, "manual"),
                row("MAT-001", "采购件", "联动价", 2, null, null, "manual")));

    Optional<PriceTypeRoute> hit = router.resolve("MAT-001", "2026-04", LocalDate.parse("2026-04-20"));

    assertThat(hit).isPresent();
    assertThat(hit.get().priceType()).isEqualTo(PriceTypeEnum.FIXED);
    assertThat(hit.get().priority()).isEqualTo(1);

    List<PriceTypeRoute> all = router.listCandidates("MAT-001", "2026-04", LocalDate.parse("2026-04-20"));
    assertThat(all).hasSize(1);
  }

  @Test
  @DisplayName("价格类型忽略旧有效期，仍以最新导入记录为准")
  void effectiveWindowDoesNotExpirePriceType() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-002", "采购件", "固定价", 1,
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), "manual"),
                row("MAT-002", "采购件", "联动价", 2,
                    LocalDate.parse("2026-04-01"), null, "manual")));

    Optional<PriceTypeRoute> hit = router.resolve("MAT-002", "2026-04", LocalDate.parse("2026-04-20"));

    assertThat(hit).isPresent();
    assertThat(hit.get().priceType()).isEqualTo(PriceTypeEnum.FIXED);
  }

  @Test
  @DisplayName("effective_to 到期不阻断价格类型识别")
  void effectiveToDoesNotBlockQuotation() {
    LocalDate effectiveTo = LocalDate.parse("2026-07-30");
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(row(
            "MAT-END-DATE",
            "采购件",
            "固定价",
            1,
            LocalDate.parse("2026-04-01"),
            effectiveTo,
            "manual")));

    assertThat(router.resolve("MAT-END-DATE", "2026-07", effectiveTo)).isPresent();
    assertThat(router.resolve("MAT-END-DATE", "2026-07", effectiveTo.plusDays(1))).isPresent();
  }

  @Test
  @DisplayName("v1.1：未识别 shape 不再丢弃整条记录，formAttr 置 null 但保留路由")
  void unknownShapeKeepsRouteWithNullFormAttr() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-003", "未知形态", "固定价", 1, null, null, "manual"),
                row("MAT-003", "采购件", "结算固定价", 2, null, null, "manual")));

    List<PriceTypeRoute> all = router.listCandidates("MAT-003", "2026-04", null);

    assertThat(all).hasSize(1);
    assertThat(all.get(0).priceType()).isEqualTo(PriceTypeEnum.FIXED);
    assertThat(all.get(0).formAttr()).isNull();  // 未识别 shape
  }

  @Test
  @DisplayName("当前 priceType 非法时阻塞，不降级到旧类型")
  void unknownPriceTypeDropped() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-005", "采购件", "完全不合法的桶", 1, null, null, "manual"),
                row("MAT-005", "采购件", "联动价", 2, null, null, "manual")));

    List<PriceTypeRoute> all = router.listCandidates("MAT-005", "2026-04", null);

    assertThat(all).isEmpty();
  }

  @Test
  @DisplayName("materialCode 缺失时直接返回空；period 缺失时查询全局路由")
  void blankInputShortCircuits() {
    assertThat(router.resolve(null, "2026-04", null)).isEmpty();
    assertThat(router.resolve("", "2026-04", null)).isEmpty();
    Mockito.verifyNoInteractions(mapper, masterMapper);

    assertThat(router.resolve("MAT", null, null)).isEmpty();
    Mockito.verify(mapper).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("历史 priority 不再改变当前类型选择")
  void nullPriorityLastResort() {
    // mock 已按 SQL 排序后的顺序：priority=3 在前，priority=null 在后
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-004", "采购件", "联动价", 3, null, null, "manual"),
                row("MAT-004", "采购件", "固定价", null, null, null, "manual")));

    Optional<PriceTypeRoute> hit = router.resolve("MAT-004", "2026-04", null);

    assertThat(hit).isPresent();
    assertThat(hit.get().priceType()).isEqualTo(PriceTypeEnum.LINKED);
    assertThat(hit.get().priority()).isEqualTo(3);
  }

  @Test
  @DisplayName("v1.1：master.shape_attr 优先于路由表 material_shape")
  void masterShapeOverridesRouteShape() {
    // 主档说 MAT-006 是制造件
    MaterialMaster master = new MaterialMaster();
    master.setMaterialCode("MAT-006");
    master.setShapeAttr("制造件");
    when(masterMapper.selectOne(any(Wrapper.class))).thenReturn(master);

    // 路由表说是 "采购件"（过时数据）
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(row("MAT-006", "采购件", "自制件", 1, null, null, "manual")));

    Optional<PriceTypeRoute> hit = router.resolve("MAT-006", "2026-04", null);

    assertThat(hit).isPresent();
    assertThat(hit.get().formAttr()).isEqualTo(MaterialFormAttrEnum.MANUFACTURED);  // 主档赢
    assertThat(hit.get().priceType()).isEqualTo(PriceTypeEnum.MAKE);
  }

  @Test
  @DisplayName("价格类型表无记录时从最新正式价格源自动推断，不误报缺类型")
  void missingRouteInfersFromFormalPriceSource() {
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    MaterialPriceTypeSourceCandidate source = new MaterialPriceTypeSourceCandidate();
    source.setMaterialCode("208200130");
    source.setPriceType("SETTLE_FIXED");
    source.setSourceSystem("PRICE_FIXED");
    source.setEffectiveFrom(LocalDate.parse("2026-05-01"));
    source.setEffectiveTo(LocalDate.parse("2026-07-31"));
    when(sourceMapper.selectLatest("208200130", null)).thenReturn(source);

    Optional<PriceTypeRoute> hit =
        router.resolve("208200130", "2026-08", LocalDate.parse("2026-08-19"));

    assertThat(hit).isPresent();
    assertThat(hit.get().priceType()).isEqualTo(PriceTypeEnum.FIXED);
    assertThat(hit.get().rawPriceType()).isEqualTo("SETTLE_FIXED");
    assertThat(hit.get().sourceSystem()).isEqualTo("PRICE_SOURCE_INFERRED:PRICE_FIXED");
  }

  // ============================ 辅助构造 ============================

  private static MaterialPriceType row(
      String code, String shape, String priceType, Integer priority,
      LocalDate from, LocalDate to, String sourceSystem) {
    MaterialPriceType row = new MaterialPriceType();
    row.setMaterialCode(code);
    row.setMaterialShape(shape);
    row.setPriceType(priceType);
    row.setPeriod("2026-04");
    row.setPriority(priority);
    row.setEffectiveFrom(from);
    row.setEffectiveTo(to);
    row.setSourceSystem(sourceSystem);
    return row;
  }
}

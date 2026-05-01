package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
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
 * <p>v1.1 (T03) 调整后覆盖：
 * <ul>
 *   <li>有候选时按 priority ASC（数值小者优先级高）取 winner，listCandidates 保持 winner-first 排序</li>
 *   <li>quoteDate 落在 effective 窗口外的记录被过滤</li>
 *   <li>未识别的 priceType 被丢弃 + WARN（合法 priceType 永远保留，shape 不识别仅置 formAttr=null）</li>
 *   <li>主档 shape_attr 优先；主档无该料号 → fallback 用路由表 material_shape</li>
 *   <li>materialCode 或 period 缺失时短路返回空</li>
 * </ul>
 */
class MaterialPriceRouterServiceTest {

  private MaterialPriceTypeMapper mapper;
  private MaterialMasterMapper masterMapper;
  private MaterialPriceRouterServiceImpl router;

  @BeforeEach
  void setUp() {
    mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    masterMapper = Mockito.mock(MaterialMasterMapper.class);
    // 默认 master 查不到任何料号 → formAttr 走 fallback 用路由表 material_shape
    when(masterMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    router = new MaterialPriceRouterServiceImpl(mapper, masterMapper);
  }

  @Test
  @DisplayName("priority=1 命中（数值小者优先），priority=2 仅作降级候选")
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
    assertThat(all).hasSize(2);
    assertThat(all.get(1).priceType()).isEqualTo(PriceTypeEnum.LINKED);
  }

  @Test
  @DisplayName("quoteDate 在 effective 窗口外的记录被过滤")
  void effectiveWindowFiltersOut() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-002", "采购件", "固定价", 1,
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), "manual"),
                row("MAT-002", "采购件", "联动价", 2,
                    LocalDate.parse("2026-04-01"), null, "manual")));

    Optional<PriceTypeRoute> hit = router.resolve("MAT-002", "2026-04", LocalDate.parse("2026-04-20"));

    assertThat(hit).isPresent();
    assertThat(hit.get().priceType()).isEqualTo(PriceTypeEnum.LINKED);
  }

  @Test
  @DisplayName("v1.1：未识别 shape 不再丢弃整条记录，formAttr 置 null 但保留路由")
  void unknownShapeKeepsRouteWithNullFormAttr() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-003", "未知形态", "固定价", 1, null, null, "manual"),
                row("MAT-003", "采购件", "结算价", 2, null, null, "manual")));

    List<PriceTypeRoute> all = router.listCandidates("MAT-003", "2026-04", null);

    // 两条都保留：第 1 条 priceType 合法（FIXED），shape 不识别 formAttr=null
    // 第 2 条 priceType="结算价" 通过双别名映射为 FIXED，shape="采购件" → PURCHASED
    assertThat(all).hasSize(2);
    assertThat(all.get(0).priceType()).isEqualTo(PriceTypeEnum.FIXED);
    assertThat(all.get(0).formAttr()).isNull();  // 未识别 shape
    assertThat(all.get(1).priceType()).isEqualTo(PriceTypeEnum.FIXED);  // "结算价" 双别名 → FIXED
    assertThat(all.get(1).formAttr()).isEqualTo(MaterialFormAttrEnum.PURCHASED);
  }

  @Test
  @DisplayName("priceType 完全不合法的记录被丢弃 + WARN")
  void unknownPriceTypeDropped() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-005", "采购件", "完全不合法的桶", 1, null, null, "manual"),
                row("MAT-005", "采购件", "联动价", 2, null, null, "manual")));

    List<PriceTypeRoute> all = router.listCandidates("MAT-005", "2026-04", null);

    // 只剩 1 条合法的
    assertThat(all).hasSize(1);
    assertThat(all.get(0).priceType()).isEqualTo(PriceTypeEnum.LINKED);
  }

  @Test
  @DisplayName("materialCode 或 period 缺失时直接返回空，不查库")
  void blankInputShortCircuits() {
    assertThat(router.resolve(null, "2026-04", null)).isEmpty();
    assertThat(router.resolve("", "2026-04", null)).isEmpty();
    assertThat(router.resolve("MAT", null, null)).isEmpty();
    Mockito.verifyNoInteractions(mapper, masterMapper);
  }

  @Test
  @DisplayName("priority=null 排在最后（999999 兜底）")
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

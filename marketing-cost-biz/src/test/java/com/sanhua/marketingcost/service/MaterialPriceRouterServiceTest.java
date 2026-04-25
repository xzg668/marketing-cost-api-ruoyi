package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
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
 * MaterialPriceRouterService 单元测试 —— 全 Mock，不依赖 Spring 与数据库。
 *
 * <p>覆盖：
 * <ul>
 *   <li>有候选时按 priority 升序命中</li>
 *   <li>quoteDate 落在 effective_from..to 之外的记录被过滤</li>
 *   <li>未识别的 material_shape / price_type 文案被丢弃</li>
 *   <li>materialCode 或 period 缺失时返回空</li>
 * </ul>
 */
class MaterialPriceRouterServiceTest {

  private MaterialPriceTypeMapper mapper;
  private MaterialPriceRouterServiceImpl router;

  @BeforeEach
  void setUp() {
    mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    router = new MaterialPriceRouterServiceImpl(mapper);
  }

  @Test
  @DisplayName("priority=1 命中，priority=2 仅作降级候选")
  void resolvePicksLowestPriority() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-001", "采购件", "联动价", 2, null, null, "manual"),
                row("MAT-001", "采购件", "固定价", 1, null, null, "manual")));

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
  @DisplayName("未识别的 material_shape 被丢弃，返回剩余候选")
  void unknownShapeIsDropped() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-003", "未知形态", "固定价", 1, null, null, "manual"),
                row("MAT-003", "采购件", "结算价", 2, null, null, "manual")));

    List<PriceTypeRoute> all = router.listCandidates("MAT-003", "2026-04", null);

    assertThat(all).hasSize(1);
    assertThat(all.get(0).priceType()).isEqualTo(PriceTypeEnum.SETTLE);
    assertThat(all.get(0).formAttr()).isEqualTo(MaterialFormAttrEnum.PURCHASED);
  }

  @Test
  @DisplayName("materialCode 或 period 缺失时直接返回空，不查库")
  void blankInputShortCircuits() {
    assertThat(router.resolve(null, "2026-04", null)).isEmpty();
    assertThat(router.resolve("", "2026-04", null)).isEmpty();
    assertThat(router.resolve("MAT", null, null)).isEmpty();
    Mockito.verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("priority 为 null 排在最后")
  void nullPriorityLastResort() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row("MAT-004", "采购件", "固定价", null, null, null, "manual"),
                row("MAT-004", "采购件", "联动价", 3, null, null, "manual")));

    Optional<PriceTypeRoute> hit = router.resolve("MAT-004", "2026-04", null);

    assertThat(hit).isPresent();
    assertThat(hit.get().priceType()).isEqualTo(PriceTypeEnum.LINKED);
    assertThat(hit.get().priority()).isEqualTo(3);
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

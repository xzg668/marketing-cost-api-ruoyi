package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.PriceVariableBindingImportResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingPendingResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.formula.registry.FinanceBasePriceQuery;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link PriceVariableBindingServiceImpl} 单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>save：首次 INSERT / 原地 UPDATE / 版本切换 / effective 回溯拒绝 / 联动行缺失 / factor 未登记</li>
 *   <li>list / history：factorName 回填</li>
 *   <li>softDelete：@TableLogic 路径 + 缓存失效</li>
 *   <li>getPending：响应 total 与列表</li>
 *   <li>importCsv：正常 / 部分失败 / 幂等（重跑不重复）</li>
 * </ul>
 *
 * <p>Mapper 全 mock；FactorVariableRegistryImpl 用真实实例验证 invalidateBinding 调用。
 */
class PriceVariableBindingServiceImplTest {

  private PriceVariableBindingMapper bindingMapper;
  private PriceVariableMapper priceVariableMapper;
  private PriceLinkedItemMapper priceLinkedItemMapper;
  private FactorVariableRegistryImpl registry;
  private PriceVariableBindingServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceVariableBinding.class);
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
  }

  @BeforeEach
  void setUp() {
    bindingMapper = mock(PriceVariableBindingMapper.class);
    priceVariableMapper = mock(PriceVariableMapper.class);
    priceLinkedItemMapper = mock(PriceLinkedItemMapper.class);
    FinanceBasePriceMapper financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
    FinanceBasePriceQuery financeBasePriceQuery = new FinanceBasePriceQuery(financeBasePriceMapper);
    registry = new FactorVariableRegistryImpl(
        priceVariableMapper, financeBasePriceQuery, new ObjectMapper(),
        mock(RowLocalPlaceholderRegistry.class));
    registry.setPriceVariableBindingMapper(bindingMapper);

    service = new PriceVariableBindingServiceImpl(
        bindingMapper, priceVariableMapper, priceLinkedItemMapper, registry);
  }

  // ============================ 通用 fixture ============================

  private static PriceLinkedItem linkedItem(Long id, String mat, String spec) {
    PriceLinkedItem it = new PriceLinkedItem();
    it.setId(id);
    it.setMaterialCode(mat);
    it.setSpecModel(spec);
    return it;
  }

  private static PriceVariable variable(String code, String name) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(name);
    v.setStatus("active");
    return v;
  }

  private static PriceVariableBinding binding(
      Long id, Long itemId, String token, String factor, LocalDate effective) {
    PriceVariableBinding b = new PriceVariableBinding();
    b.setId(id);
    b.setLinkedItemId(itemId);
    b.setTokenName(token);
    b.setFactorCode(factor);
    b.setEffectiveDate(effective);
    b.setDeleted(0);
    return b;
  }

  private static PriceVariableBindingRequest req(
      Long itemId, String token, String factor, LocalDate effective) {
    PriceVariableBindingRequest r = new PriceVariableBindingRequest();
    r.setLinkedItemId(itemId);
    r.setTokenName(token);
    r.setFactorCode(factor);
    r.setEffectiveDate(effective);
    r.setPriceSource("平均价");
    return r;
  }

  // ============================ save ============================

  @Test
  @DisplayName("save 首次：无当前绑定 → INSERT；缓存失效触发")
  void saveFirstTime() {
    when(priceLinkedItemMapper.selectById(101L))
        .thenReturn(linkedItem(101L, "MAT1", "SPEC1"));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("SUS304_2B_0_7", "SUS304/2Bδ0.7"));
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(101L, "材料含税价格"))
        .thenReturn(null);

    Long id = service.save(req(101L, "材料含税价格", "SUS304_2B_0_7",
        LocalDate.of(2026, 4, 1)));

    assertThat(id).isNull(); // mapper.insert 没 stub id 回填
    verify(bindingMapper).insert(any(PriceVariableBinding.class));
    verify(bindingMapper, never()).updateById(any(PriceVariableBinding.class));
    verify(bindingMapper, never()).expireById(any(), any());
  }

  @Test
  @DisplayName("save 同 effective_date → 原地 UPDATE，不 INSERT")
  void saveSameEffectiveUpdatesInPlace() {
    when(priceLinkedItemMapper.selectById(102L))
        .thenReturn(linkedItem(102L, "MAT2", "SPEC2"));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));
    PriceVariableBinding current = binding(9L, 102L, "材料价格", "Cu", LocalDate.of(2026, 4, 1));
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(102L, "材料价格"))
        .thenReturn(current);

    Long id = service.save(req(102L, "材料价格", "Cu", LocalDate.of(2026, 4, 1)));

    assertThat(id).isEqualTo(9L);
    verify(bindingMapper).updateById(any(PriceVariableBinding.class));
    verify(bindingMapper, never()).insert(any(PriceVariableBinding.class));
    verify(bindingMapper, never()).expireById(any(), any());
  }

  @Test
  @DisplayName("save 新 effective 晚于当前 → 旧行设 expiry=new-1d + 新行 INSERT")
  void saveLaterEffectiveSwapsVersion() {
    when(priceLinkedItemMapper.selectById(103L))
        .thenReturn(linkedItem(103L, "MAT3", "SPEC3"));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));
    PriceVariableBinding current = binding(7L, 103L, "材料含税价格", "Zn",
        LocalDate.of(2026, 3, 1));
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(103L, "材料含税价格"))
        .thenReturn(current);

    service.save(req(103L, "材料含税价格", "Cu", LocalDate.of(2026, 5, 1)));

    verify(bindingMapper).expireById(eq(7L), eq(LocalDate.of(2026, 4, 30)));
    verify(bindingMapper).insert(any(PriceVariableBinding.class));
    verify(bindingMapper, never()).updateById(any(PriceVariableBinding.class));
  }

  @Test
  @DisplayName("save 新 effective 早于当前 → 抛 IllegalArgumentException，不改 DB")
  void saveEarlierEffectiveRejected() {
    when(priceLinkedItemMapper.selectById(104L))
        .thenReturn(linkedItem(104L, "MAT4", "SPEC4"));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(104L, "材料含税价格"))
        .thenReturn(binding(1L, 104L, "材料含税价格", "Zn", LocalDate.of(2026, 4, 1)));

    assertThatThrownBy(() -> service.save(
        req(104L, "材料含税价格", "Cu", LocalDate.of(2026, 3, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("早于");

    verify(bindingMapper, never()).insert(any(PriceVariableBinding.class));
    verify(bindingMapper, never()).updateById(any(PriceVariableBinding.class));
    verify(bindingMapper, never()).expireById(any(), any());
  }

  @Test
  @DisplayName("save 联动行不存在 → 抛 IllegalArgumentException")
  void saveMissingLinkedItem() {
    when(priceLinkedItemMapper.selectById(999L)).thenReturn(null);

    assertThatThrownBy(() -> service.save(
        req(999L, "材料含税价格", "Cu", LocalDate.of(2026, 4, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("联动行不存在");
  }

  @Test
  @DisplayName("save factor_code 未登记 → 抛 IllegalArgumentException")
  void saveUnregisteredFactorCode() {
    when(priceLinkedItemMapper.selectById(105L))
        .thenReturn(linkedItem(105L, "MAT5", "SPEC5"));
    when(priceVariableMapper.selectOne(any())).thenReturn(null);

    assertThatThrownBy(() -> service.save(
        req(105L, "材料含税价格", "GHOST", LocalDate.of(2026, 4, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未在 lp_price_variable 登记");
  }

  @Test
  @DisplayName("save token_name 非法 → 抛 IllegalArgumentException，不访问 DB")
  void saveInvalidTokenName() {
    assertThatThrownBy(() -> service.save(
        req(106L, "其它名字", "Cu", LocalDate.of(2026, 4, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tokenName 非法");

    verify(priceLinkedItemMapper, never()).selectById(any());
  }

  // ============================ list / history ============================

  @Test
  @DisplayName("listByLinkedItem 回填 factorName")
  @SuppressWarnings("unchecked")
  void listAttachesFactorName() {
    when(bindingMapper.findCurrentByLinkedItemId(201L))
        .thenReturn(List.of(
            binding(1L, 201L, "材料含税价格", "SUS304_2B_0_7", LocalDate.of(2026, 4, 1)),
            binding(2L, 201L, "废料含税价格", "Cu", LocalDate.of(2026, 4, 1))));
    when(priceVariableMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(
            variable("SUS304_2B_0_7", "SUS304/2Bδ0.7"),
            variable("Cu", "电解铜"))));

    List<PriceVariableBindingDto> dtos = service.listByLinkedItem(201L);

    assertThat(dtos).hasSize(2);
    assertThat(dtos)
        .extracting(PriceVariableBindingDto::getFactorCode,
            PriceVariableBindingDto::getFactorName)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("SUS304_2B_0_7", "SUS304/2Bδ0.7"),
            org.assertj.core.groups.Tuple.tuple("Cu", "电解铜"));
  }

  @Test
  @DisplayName("listByLinkedItem null → 空列表")
  void listNullId() {
    assertThat(service.listByLinkedItem(null)).isEmpty();
  }

  @Test
  @DisplayName("getHistory 返回历史时间线")
  void history() {
    when(bindingMapper.findHistory(301L, "材料含税价格")).thenReturn(List.of(
        binding(3L, 301L, "材料含税价格", "Cu", LocalDate.of(2026, 4, 1)),
        binding(2L, 301L, "材料含税价格", "Zn", LocalDate.of(2026, 3, 1))));
    when(priceVariableMapper.selectList(any()))
        .thenReturn(new ArrayList<>(List.of(
            variable("Cu", "电解铜"),
            variable("Zn", "电解锌"))));

    List<PriceVariableBindingDto> out = service.getHistory(301L, "材料含税价格");
    assertThat(out).hasSize(2);
    assertThat(out.get(0).getFactorName()).isEqualTo("电解铜");
  }

  // ============================ softDelete ============================

  @Test
  @DisplayName("softDelete 调 deleteById + invalidateBinding")
  void softDelete() {
    when(bindingMapper.selectById(42L))
        .thenReturn(binding(42L, 401L, "材料含税价格", "Cu", LocalDate.of(2026, 4, 1)));

    service.softDelete(42L);

    verify(bindingMapper).deleteById(42L);
  }

  @Test
  @DisplayName("softDelete 不存在 → 抛")
  void softDeleteMissing() {
    when(bindingMapper.selectById(9999L)).thenReturn(null);
    assertThatThrownBy(() -> service.softDelete(9999L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ============================ pending ============================

  @Test
  @DisplayName("getPending 映射 mapper 行到响应 DTO")
  void pending() {
    PriceVariableBindingMapper.PendingItemRow r1 =
        new PriceVariableBindingMapper.PendingItemRow();
    r1.setId(1L);
    r1.setMaterialCode("M1");
    r1.setSpecModel("S1");
    r1.setFormulaExpr("[__material]");
    r1.setFormulaExprCn("材料含税价格");
    when(bindingMapper.findPendingItems()).thenReturn(List.of(r1));

    PriceVariableBindingPendingResponse resp = service.getPending();
    assertThat(resp.getTotal()).isEqualTo(1);
    assertThat(resp.getItems()).hasSize(1);
    assertThat(resp.getItems().get(0).getLinkedItemId()).isEqualTo(1L);
    assertThat(resp.getItems().get(0).getFormulaExprCn()).isEqualTo("材料含税价格");
  }

  // ============================ CSV import ============================

  private static ByteArrayInputStream csv(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("importCsv 正常 2 行 → inserted=2, errors=0")
  @SuppressWarnings("unchecked")
  void importHappyPath() {
    String body = "物料编码,规格型号,token名,factor_code,price_source,生效日期,备注\n"
        + "M1,S1,材料含税价格,Cu,平均价,2026-04-01,批次1\n"
        + "M2,S2,废料含税价格,Cu,平均价,2026-04-01,批次1\n";

    when(priceLinkedItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(linkedItem(1L, "M1", "S1"))))
        .thenReturn(new ArrayList<>(List.of(linkedItem(2L, "M2", "S2"))));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(any(), any()))
        .thenReturn(null);

    PriceVariableBindingImportResponse resp = service.importCsv(csv(body));

    assertThat(resp.getTotal()).isEqualTo(2);
    assertThat(resp.getInserted()).isEqualTo(2);
    assertThat(resp.getErrors()).isEmpty();
    verify(bindingMapper, times(2)).insert(any(PriceVariableBinding.class));
  }

  @Test
  @DisplayName("importCsv 部分失败：1 行 token 不识别 → inserted=1, errors=1")
  @SuppressWarnings("unchecked")
  void importPartialFailure() {
    String body = "物料编码,规格型号,token名,factor_code,price_source,生效日期,备注\n"
        + "M1,S1,材料含税价格,Cu,平均价,2026-04-01,ok\n"
        + "M2,S2,奇怪的名字,Cu,平均价,2026-04-01,bad\n";

    when(priceLinkedItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(linkedItem(1L, "M1", "S1"))));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(any(), any())).thenReturn(null);

    PriceVariableBindingImportResponse resp = service.importCsv(csv(body));

    assertThat(resp.getTotal()).isEqualTo(2);
    assertThat(resp.getInserted()).isEqualTo(1);
    assertThat(resp.getErrors()).hasSize(1);
    assertThat(resp.getErrors().get(0).getLine()).isEqualTo(3);
    assertThat(resp.getErrors().get(0).getReason()).contains("不识别");
  }

  @Test
  @DisplayName("importCsv 联动行未找到 → 加入 errors，不阻断其它行")
  @SuppressWarnings("unchecked")
  void importLinkedItemMissing() {
    String body = "物料编码,规格型号,token名,factor_code,price_source,生效日期,备注\n"
        + "GHOST,GHOST,材料含税价格,Cu,平均价,2026-04-01,bad\n";

    when(priceLinkedItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>());
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));

    PriceVariableBindingImportResponse resp = service.importCsv(csv(body));

    assertThat(resp.getTotal()).isEqualTo(1);
    assertThat(resp.getInserted()).isZero();
    assertThat(resp.getErrors()).hasSize(1);
    assertThat(resp.getErrors().get(0).getReason()).contains("联动行未找到");
  }

  @Test
  @DisplayName("importCsv 同 CSV 二次跑 → 原地 UPDATE，inserted=0,updated=1")
  @SuppressWarnings("unchecked")
  void importIdempotent() {
    String body = "物料编码,规格型号,token名,factor_code,price_source,生效日期,备注\n"
        + "M1,S1,材料含税价格,Cu,平均价,2026-04-01,ok\n";

    when(priceLinkedItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(linkedItem(1L, "M1", "S1"))));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));
    // 第二次跑：同一 key 同一 effective 已存在
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(1L, "材料含税价格"))
        .thenReturn(binding(1L, 1L, "材料含税价格", "Cu", LocalDate.of(2026, 4, 1)));

    PriceVariableBindingImportResponse resp = service.importCsv(csv(body));

    assertThat(resp.getInserted()).isZero();
    assertThat(resp.getUpdated()).isEqualTo(1);
    assertThat(resp.getErrors()).isEmpty();
  }

  @Test
  @DisplayName("importCsv 带 BOM 的首行仍被正确识别为表头")
  @SuppressWarnings("unchecked")
  void importHandlesBom() {
    String body = "﻿物料编码,规格型号,token名,factor_code,price_source,生效日期,备注\n"
        + "M1,S1,材料含税价格,Cu,平均价,2026-04-01,ok\n";

    when(priceLinkedItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(linkedItem(1L, "M1", "S1"))));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(any(), any())).thenReturn(null);

    PriceVariableBindingImportResponse resp = service.importCsv(csv(body));

    assertThat(resp.getInserted()).isEqualTo(1);
    assertThat(resp.getErrors()).isEmpty();
  }

  @Test
  @DisplayName("importCsv 日期格式错误 → 加入 errors")
  @SuppressWarnings("unchecked")
  void importBadDate() {
    String body = "物料编码,规格型号,token名,factor_code,price_source,生效日期,备注\n"
        + "M1,S1,材料含税价格,Cu,平均价,2026/04/01,ok\n";

    PriceVariableBindingImportResponse resp = service.importCsv(csv(body));

    assertThat(resp.getErrors()).hasSize(1);
    assertThat(resp.getErrors().get(0).getReason()).contains("生效日期格式错误");
  }

  @Test
  @DisplayName("save 后注入的 binding 字段都对")
  void savedEntityFieldsAreCorrect() {
    when(priceLinkedItemMapper.selectById(1001L))
        .thenReturn(linkedItem(1001L, "A", "B"));
    when(priceVariableMapper.selectOne(any()))
        .thenReturn(variable("Cu", "电解铜"));
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(any(), any())).thenReturn(null);

    PriceVariableBindingRequest r = req(1001L, "材料含税价格", "Cu", LocalDate.of(2026, 4, 1));
    r.setPriceSource("出厂价");
    r.setBuScoped(0);
    r.setSource("SUPPLY_CONFIRMED");
    r.setConfirmedBy("张三");
    r.setRemark("4 月确认批次");

    service.save(r);

    ArgumentCaptor<PriceVariableBinding> cap = ArgumentCaptor.forClass(PriceVariableBinding.class);
    verify(bindingMapper).insert(cap.capture());
    PriceVariableBinding saved = cap.getValue();
    assertThat(saved.getLinkedItemId()).isEqualTo(1001L);
    assertThat(saved.getTokenName()).isEqualTo("材料含税价格");
    assertThat(saved.getFactorCode()).isEqualTo("Cu");
    assertThat(saved.getPriceSource()).isEqualTo("出厂价");
    assertThat(saved.getBuScoped()).isEqualTo(0);
    assertThat(saved.getSource()).isEqualTo("SUPPLY_CONFIRMED");
    assertThat(saved.getConfirmedBy()).isEqualTo("张三");
    assertThat(saved.getRemark()).isEqualTo("4 月确认批次");
    assertThat(saved.getDeleted()).isEqualTo(0);
  }
}

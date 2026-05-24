package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupplierSupplyRatioResolveServiceImplTest {

  private SupplierSupplyRatioMapper mapper;
  private SupplierSupplyRatioResolveServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = mock(SupplierSupplyRatioMapper.class);
    service = new SupplierSupplyRatioResolveServiceImpl(mapper);
  }

  @Test
  @DisplayName("多供应商按供货比例最大排序取主供")
  void resolvePicksLargestSupplyRatio() {
    SupplierSupplyRatio best = row(2L, "供应商B", "0.75", LocalDateTime.parse("2026-05-02T10:00:00"));
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(best);

    SupplierSupplyRatioResolveResult result = service.resolve(
        "COMMERCIAL", "203250307", "连杆", "SHF-H35-020002", LocalDate.parse("2026-05-18"));

    assertThat(result.isMatched()).isTrue();
    assertThat(result.getSupplierName()).isEqualTo("供应商B");
    assertThat(result.getSupplyRatio()).isEqualByComparingTo("0.75");

    QueryWrapper<SupplierSupplyRatio> query = capturedQuery();
    assertThat(query.getSqlSegment()).contains(
        "supply_ratio DESC",
        "updated_at",
        "id DESC",
        "LIMIT 1");
  }

  @Test
  @DisplayName("最高比例并列时 SQL 按 updated_at DESC, id DESC 兜底")
  void tieUsesLatestUpdatedAtThenId() {
    SupplierSupplyRatio latest = row(9L, "供应商最新", "0.60", LocalDateTime.parse("2026-05-18T09:00:00"));
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(latest);

    SupplierSupplyRatioResolveResult result = service.resolve(
        "COMMERCIAL", "203240251", "小阀座", "SHF-000-036003", null);

    assertThat(result.isMatched()).isTrue();
    assertThat(result.getSupplierName()).isEqualTo("供应商最新");
    assertThat(result.getTraceMessage()).contains("严格匹配");
    assertThat(capturedQuery().getSqlSegment()).contains(
        "ORDER BY supply_ratio DESC",
        "IFNULL(updated_at",
        "id DESC");
  }

  @Test
  @DisplayName("未维护供货比例返回未命中，不抛异常")
  void unresolvedReturnsMiss() {
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

    SupplierSupplyRatioResolveResult result = service.resolve(
        "COMMERCIAL", "NO-MAT", "不存在", "NO-SPEC", LocalDate.parse("2026-05-18"));

    assertThat(result.isMatched()).isFalse();
    assertThat(result.getTraceMessage()).contains("未命中供货比例");
    assertThat(capturedQuery().getSqlSegment()).contains("deleted");
  }

  @Test
  @DisplayName("生效日期过滤：effective_from/effective_to 任一端为空视为开放")
  void filtersByEffectiveDateWindow() {
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(row(1L, "供应商A", "1", null));

    service.resolve("COMMERCIAL", "203240251", "小阀座", "SHF-000-036003",
        LocalDate.parse("2026-05-18"));

    assertThat(capturedQuery().getSqlSegment()).contains(
        "effective_from",
        "IS NULL",
        "effective_to");
  }

  @Test
  @DisplayName("materialName 为空时降级为 material_code + spec_model，并在 trace 说明")
  void fallbackWhenMaterialNameMissing() {
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(row(3L, "供应商A", "1", null));

    SupplierSupplyRatioResolveResult result = service.resolve(
        "COMMERCIAL", "203240251", " ", "SHF-000-036003", null);

    assertThat(result.isMatched()).isTrue();
    assertThat(result.getTraceMessage()).contains("降级匹配");
    assertThat(capturedQuery().getSqlSegment())
        .contains("material_code", "spec_model")
        .doesNotContain("material_name");
  }

  @Test
  @DisplayName("pricingMonth 支持 yyyy-MM，并参与生效期过滤")
  void resolveByMonthUsesFirstDayOfMonth() {
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(row(4L, "供应商月度", "1", null));

    SupplierSupplyRatioResolveResult result = service.resolveByMonth(
        "COMMERCIAL", "203240251", "小阀座", "SHF-000-036003", "2026-05");

    assertThat(result.isMatched()).isTrue();
    assertThat(capturedQuery().getSqlSegment()).contains("effective_from", "effective_to");
  }

  private QueryWrapper<SupplierSupplyRatio> capturedQuery() {
    ArgumentCaptor<QueryWrapper<SupplierSupplyRatio>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
    verify(mapper).selectOne(captor.capture());
    return captor.getValue();
  }

  private SupplierSupplyRatio row(Long id, String supplierName, String ratio, LocalDateTime updatedAt) {
    SupplierSupplyRatio row = new SupplierSupplyRatio();
    row.setId(id);
    row.setSupplierName(supplierName);
    row.setSupplierCode("S-" + id);
    row.setSupplyRatio(new BigDecimal(ratio));
    row.setSourceType("EXCEL");
    row.setSourceBatchNo("SSR-batch");
    row.setUpdatedAt(updatedAt);
    return row;
  }
}

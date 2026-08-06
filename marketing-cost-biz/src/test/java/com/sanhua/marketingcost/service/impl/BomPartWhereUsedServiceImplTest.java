package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.BomPartWhereUsed;
import com.sanhua.marketingcost.mapper.BomPartWhereUsedMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("BomPartWhereUsedServiceImpl")
class BomPartWhereUsedServiceImplTest {
  private BomPartWhereUsedMapper mapper;
  private BomPartWhereUsedServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = mock(BomPartWhereUsedMapper.class);
    service = new BomPartWhereUsedServiceImpl(mapper);
  }

  @Test
  @DisplayName("空物料料号不扫描五十万行关系表")
  void blankPartCodeReturnsEmptyWithoutQuery() {
    var result = service.page("COMMERCIAL", " ", null, 1, 50);

    assertThat(result.getTotal()).isZero();
    assertThat(result.getList()).isEmpty();
    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("按组织和完整物料料号分页，并可筛选顶层产品料号前缀")
  void queriesByIndexedOrganizationAndPartCode() {
    when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
      Page<BomPartWhereUsed> page = invocation.getArgument(0);
      BomPartWhereUsed row = new BomPartWhereUsed();
      row.setPriceOrgCode("220");
      row.setPartCode("301070074");
      row.setPartName("铜箔");
      row.setTopProductCode("1053900000062");
      row.setTopProductName("板式换热器");
      row.setTotalQtyPerTop(new BigDecimal("0.12500000"));
      row.setBomPathCount(2L);
      row.setMinLevel(2);
      row.setMaxLevel(3);
      row.setHasLeafOccurrence(1);
      row.setHasNonLeafOccurrence(0);
      row.setSnapshotDate(LocalDate.of(2026, 7, 28));
      page.setTotal(1);
      page.setRecords(List.of(row));
      return page;
    });

    var result = service.page("PLATE", " 301070074 ", "10539", 0, 500);

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getList()).hasSize(1);
    assertThat(result.getList().get(0).priceOrgCode()).isEqualTo("220");
    assertThat(result.getList().get(0).hasLeafOccurrence()).isTrue();
    assertThat(result.getList().get(0).hasNonLeafOccurrence()).isFalse();
    assertThat(result.getList().get(0).totalQtyPerTop())
        .isEqualByComparingTo("0.12500000");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Page<BomPartWhereUsed>> pageCaptor =
        ArgumentCaptor.forClass(Page.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<QueryWrapper<BomPartWhereUsed>> wrapperCaptor =
        ArgumentCaptor.forClass(QueryWrapper.class);
    verify(mapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(200);
    assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
        .contains(
            "price_org_code",
            "part_code",
            "top_product_code",
            "ORDER BY top_product_code ASC");
    assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
        .contains("220", "301070074", "10539%");
  }
}

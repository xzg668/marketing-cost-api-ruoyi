package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.PriceScrapImportRequest;
import com.sanhua.marketingcost.dto.PriceScrapUpdateRequest;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.mapper.PriceScrapMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PriceScrapServiceImplTest {
  private PriceScrapMapper mapper;
  private PriceScrapServiceImpl service;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), PriceScrap.class);
    mapper = mock(PriceScrapMapper.class);
    service = new PriceScrapServiceImpl(mapper);
    var auth = new UsernamePasswordAuthenticationToken("cms-user", "N/A");
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("T5 当前价查询：只按 CMS 回收料号取价，不按 pricing_month 过滤")
  void getCurrentByScrapCodeDoesNotFilterByPricingMonth() {
    PriceScrap current = scrap(9L, "301990317", "2026-03", "75.66");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(current));

    PriceScrap result = service.getCurrentByScrapCode(" 301 990317 ");

    assertThat(result).isSameAs(current);
    String sql = capturedSelectListWrapper().getCustomSqlSegment();
    assertThat(sql)
        .contains("scrap_code")
        .contains("deleted")
        .contains("business_unit_type")
        .doesNotContain("pricing_month");
    assertParamContains(capturedSelectListParams(), "301990317");
  }

  @Test
  @DisplayName("T5 历史等级：A/B/C/D 不参与新当前价取价")
  void getCurrentByScrapCodeIgnoresHistoricalGrade() {
    PriceScrap result = service.getCurrentByScrapCode(" A ");

    assertThat(result).isNull();
    verify(mapper, never()).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("T5 新增：同一 CMS 回收料号已有当前价时执行更新，不新增并行记录")
  void createUpdatesExistingCurrentScrapCode() {
    PriceScrap existing = scrap(12L, "301990317", "2026-03", "75.66");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
    PriceScrapUpdateRequest request = updateRequest("301 990317", "2026-05", "80.12");

    PriceScrap result = service.create(request);

    assertThat(result.getId()).isEqualTo(12L);
    assertThat(result.getScrapCode()).isEqualTo("301990317");
    assertThat(result.getPricingMonth()).isEqualTo("2026-05");
    assertThat(result.getRecyclePrice()).isEqualByComparingTo("80.12");
    verify(mapper, never()).insert(any(PriceScrap.class));
    verify(mapper).updateById(existing);
    assertThat(capturedSelectListWrapper().getCustomSqlSegment()).doesNotContain("pricing_month");
  }

  @Test
  @DisplayName("T5 导入：重复 CMS 回收料号跨月份导入只更新同一条当前价")
  void importItemsUpsertsSameCmsScrapCodeAcrossMonths() {
    List<PriceScrap> repo = new ArrayList<>();
    when(mapper.selectList(any(Wrapper.class)))
        .thenAnswer(
            invocation -> repo.isEmpty() ? List.of() : List.of(repo.get(0)));
    when(mapper.insert(any(PriceScrap.class)))
        .thenAnswer(
            invocation -> {
              PriceScrap row = invocation.getArgument(0);
              row.setId(100L + repo.size());
              repo.add(row);
              return 1;
            });

    PriceScrapImportRequest request = new PriceScrapImportRequest();
    request.setRows(
        List.of(
            importRow("301990317", "2026-03", "75.66"),
            importRow("301 990317", "2026-05", "80.12")));

    List<PriceScrap> imported = service.importItems(request);

    assertThat(imported).hasSize(2);
    assertThat(repo).hasSize(1);
    assertThat(repo.get(0).getScrapCode()).isEqualTo("301990317");
    assertThat(repo.get(0).getPricingMonth()).isEqualTo("2026-05");
    assertThat(repo.get(0).getRecyclePrice()).isEqualByComparingTo("80.12");
    verify(mapper).insert(any(PriceScrap.class));
    verify(mapper).updateById(repo.get(0));
  }

  @Test
  @DisplayName("T5 批量当前价：返回 key 为标准化 CMS 回收料号的当前价 Map")
  void getCurrentByScrapCodesReturnsNormalizedKeyMap() {
    PriceScrap newer = scrap(12L, "301990317", "2026-05", "80.12");
    PriceScrap older = scrap(11L, "301990317", "2026-03", "75.66");
    PriceScrap stainless = scrap(10L, "301990444", "2026-05", "12.34");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(newer, older, stainless));

    Map<String, PriceScrap> result =
        service.getCurrentByScrapCodes(List.of("301 990317", "301990444", "A"));

    assertThat(result).containsOnlyKeys("301990317", "301990444");
    assertThat(result.get("301990317")).isSameAs(newer);
    assertThat(capturedSelectListWrapper().getCustomSqlSegment()).doesNotContain("pricing_month");
  }

  private PriceScrap scrap(Long id, String scrapCode, String pricingMonth, String price) {
    PriceScrap scrap = new PriceScrap();
    scrap.setId(id);
    scrap.setScrapCode(scrapCode);
    scrap.setPricingMonth(pricingMonth);
    scrap.setRecyclePrice(new BigDecimal(price));
    scrap.setDeleted(0);
    scrap.setBusinessUnitType("COMMERCIAL");
    return scrap;
  }

  private PriceScrapUpdateRequest updateRequest(String scrapCode, String pricingMonth, String price) {
    PriceScrapUpdateRequest request = new PriceScrapUpdateRequest();
    request.setScrapCode(scrapCode);
    request.setPricingMonth(pricingMonth);
    request.setScrapName("废紫铜沫（干净）");
    request.setUnit("千克");
    request.setRecyclePrice(new BigDecimal(price));
    return request;
  }

  private PriceScrapImportRequest.PriceScrapImportRow importRow(
      String scrapCode, String pricingMonth, String price) {
    PriceScrapImportRequest.PriceScrapImportRow row =
        new PriceScrapImportRequest.PriceScrapImportRow();
    row.setScrapCode(scrapCode);
    row.setPricingMonth(pricingMonth);
    row.setScrapName("废紫铜沫（干净）");
    row.setUnit("千克");
    row.setRecyclePrice(new BigDecimal(price));
    return row;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Wrapper<?> capturedSelectListWrapper() {
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper, org.mockito.Mockito.atLeastOnce()).selectList(captor.capture());
    return captor.getValue();
  }

  private List<Object> capturedSelectListParams() {
    Wrapper<?> wrapper = capturedSelectListWrapper();
    if (wrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper) {
      return new ArrayList<>(abstractWrapper.getParamNameValuePairs().values());
    }
    return List.of();
  }

  private void assertParamContains(List<Object> params, String expected) {
    assertThat(params.stream().map(String::valueOf).toList())
        .anySatisfy(value -> assertThat(value).contains(expected));
  }
}

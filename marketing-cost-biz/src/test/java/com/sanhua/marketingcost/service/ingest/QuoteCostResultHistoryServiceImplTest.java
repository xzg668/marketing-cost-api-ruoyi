package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ingest.QuoteCostResultHistoryResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteMonthlyCostResultDetailResponse;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.MonthlyRepriceCostItem;
import com.sanhua.marketingcost.entity.MonthlyRepricePartItem;
import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceCostItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepricePartItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class QuoteCostResultHistoryServiceImplTest {
  private OaFormMapper formMapper;
  private OaFormItemMapper itemMapper;
  private QuoteCostRunVersionMapper versionMapper;
  private MonthlyRepriceBatchMapper batchMapper;
  private MonthlyRepriceResultMapper resultMapper;
  private MonthlyRepricePartItemMapper partMapper;
  private MonthlyRepriceCostItemMapper costMapper;
  private QuoteCostResultHistoryServiceImpl service;

  @BeforeEach
  void setUp() {
    formMapper = mock(OaFormMapper.class);
    itemMapper = mock(OaFormItemMapper.class);
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    batchMapper = mock(MonthlyRepriceBatchMapper.class);
    resultMapper = mock(MonthlyRepriceResultMapper.class);
    partMapper = mock(MonthlyRepricePartItemMapper.class);
    costMapper = mock(MonthlyRepriceCostItemMapper.class);
    service =
        new QuoteCostResultHistoryServiceImpl(
            formMapper, itemMapper, versionMapper, batchMapper, resultMapper, partMapper, costMapper);

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("finance", null, List.of());
    authentication.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);

    when(formMapper.selectOne(any())).thenReturn(form());
    when(itemMapper.selectById(9L)).thenReturn(item());
    when(itemMapper.selectCount(any())).thenReturn(1L);
    when(partMapper.selectList(any())).thenReturn(List.of());
    when(costMapper.selectList(any())).thenReturn(List.of());
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void keepsOriginalQuoteResultDefaultAndShowsConfirmedMonthlyResultSeparately() {
    QuoteCostRunVersion quote = quoteVersion();
    MonthlyRepriceResult monthly = monthlyResult();
    when(versionMapper.selectList(any())).thenReturn(List.of(quote));
    when(resultMapper.selectList(any())).thenReturn(List.of(monthly));
    when(batchMapper.selectList(any())).thenReturn(List.of(confirmedBatch()));

    QuoteCostResultHistoryResponse response =
        service.listHistory("FI-SC-006-20260327-037", 9L);

    assertThat(response.getResults()).hasSize(2);
    assertThat(response.getResults().get(0).getResultTypeLabel()).isEqualTo("原报价结果");
    assertThat(response.getResults().get(0).getPeriodMonth()).isEqualTo("2026-03");
    assertThat(response.getResults().get(0).getTotalCost()).isEqualByComparingTo("151.063341");
    assertThat(response.getResults().get(0).isDefaultResult()).isTrue();
    assertThat(response.getResults().get(1).getResultTypeLabel()).isEqualTo("月度调价结果");
    assertThat(response.getResults().get(1).getPeriodMonth()).isEqualTo("2026-05");
    assertThat(response.getResults().get(1).getTotalCost()).isEqualByComparingTo("151.063345");
    assertThat(response.getResults().get(1).isDefaultResult()).isFalse();
  }

  @Test
  void readsMonthlyDetailsForDeletedLegacyItemWhenOneActiveProductMatches() {
    MonthlyRepriceResult monthly = monthlyResult();
    MonthlyRepricePartItem part = new MonthlyRepricePartItem();
    part.setPartCode("PART-1");
    MonthlyRepriceCostItem cost = new MonthlyRepriceCostItem();
    cost.setCostItemCode("TOTAL");
    when(resultMapper.selectById(6L)).thenReturn(monthly);
    when(batchMapper.selectOne(any())).thenReturn(confirmedBatch());
    when(partMapper.selectList(any())).thenReturn(List.of(part));
    when(costMapper.selectList(any())).thenReturn(List.of(cost));

    QuoteMonthlyCostResultDetailResponse response =
        service.getMonthlyResult("FI-SC-006-20260327-037", 9L, 6L);

    assertThat(response.getResult().getResultType()).isEqualTo("MONTHLY_REPRICE");
    assertThat(response.getPartItems()).extracting("partCode").containsExactly("PART-1");
    assertThat(response.getCostItems()).extracting("costItemCode").containsExactly("TOTAL");
  }

  @Test
  void doesNotExposeUnconfirmedMonthlyResult() {
    when(versionMapper.selectList(any())).thenReturn(List.of(quoteVersion()));
    when(resultMapper.selectList(any())).thenReturn(List.of(monthlyResult()));
    when(batchMapper.selectList(any())).thenReturn(List.of());

    QuoteCostResultHistoryResponse response =
        service.listHistory("FI-SC-006-20260327-037", 9L);

    assertThat(response.getResults()).hasSize(1);
    assertThat(response.getResults().get(0).getResultType()).isEqualTo("QUOTE_COST");
  }

  @Test
  void rejectsMonthlyResultFromAnotherProduct() {
    MonthlyRepriceResult monthly = monthlyResult();
    monthly.setProductCode("OTHER");
    when(resultMapper.selectById(6L)).thenReturn(monthly);

    assertThatThrownBy(
            () -> service.getMonthlyResult("FI-SC-006-20260327-037", 9L, 6L))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("不属于当前报价产品行");
  }

  private OaForm form() {
    OaForm form = new OaForm();
    form.setId(8L);
    form.setOaNo("FI-SC-006-20260327-037");
    form.setAccountingPeriodMonth("2026-03");
    form.setBusinessUnitType("COMMERCIAL");
    return form;
  }

  private OaFormItem item() {
    OaFormItem item = new OaFormItem();
    item.setId(9L);
    item.setOaFormId(8L);
    item.setMaterialNo("1079900000536");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private QuoteCostRunVersion quoteVersion() {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(7L);
    version.setOaNo("FI-SC-006-20260327-037");
    version.setOaFormItemId(9L);
    version.setProductCode("1079900000536");
    version.setPricingMonth("2026-03");
    version.setResultPeriod("2026-03");
    version.setCostRunNo("LEGACY-RESULT-7");
    version.setStatus("HISTORY");
    version.setTotalCost(new BigDecimal("151.063341"));
    version.setTrialFinishedAt(LocalDateTime.of(2026, 3, 27, 12, 0));
    return version;
  }

  private MonthlyRepriceResult monthlyResult() {
    MonthlyRepriceResult result = new MonthlyRepriceResult();
    result.setId(6L);
    result.setRepriceNo("MRP20260527194551835e704decb");
    result.setPricingMonth("2026-05");
    result.setBusinessUnitType("COMMERCIAL");
    result.setOaNo("FI-SC-006-20260327-037");
    result.setOaFormItemId(8L);
    result.setProductCode("1079900000536");
    result.setCalcObjectKey("OA:8:1079900000536");
    result.setCalcStatus("SUCCESS");
    result.setTotalCost(new BigDecimal("151.063345"));
    result.setMaterialCost(new BigDecimal("100"));
    result.setUpdatedAt(LocalDateTime.of(2026, 5, 27, 19, 45));
    return result;
  }

  private MonthlyRepriceBatch confirmedBatch() {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setRepriceNo("MRP20260527194551835e704decb");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setStatus("CONFIRMED");
    return batch;
  }
}

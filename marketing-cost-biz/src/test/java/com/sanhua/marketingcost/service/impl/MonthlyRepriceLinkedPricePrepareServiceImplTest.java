package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MonthlyRepriceAuditService;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MonthlyRepriceLinkedPricePrepareServiceImplTest {

  private MonthlyRepriceBatchMapper batchMapper;
  private PriceLinkedItemMapper priceLinkedItemMapper;
  private LinkedPriceEnsureService linkedPriceEnsureService;
  private MonthlyRepriceAuditLogMapper auditLogMapper;
  private MonthlyRepriceAuditService auditService;
  private MonthlyRepriceLinkedPricePrepareServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = Mockito.mock(MonthlyRepriceBatchMapper.class);
    priceLinkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    linkedPriceEnsureService = Mockito.mock(LinkedPriceEnsureService.class);
    auditLogMapper = Mockito.mock(MonthlyRepriceAuditLogMapper.class);
    auditService = Mockito.mock(MonthlyRepriceAuditService.class);
    service =
        new MonthlyRepriceLinkedPricePrepareServiceImpl(
            batchMapper,
            priceLinkedItemMapper,
            linkedPriceEnsureService,
            auditLogMapper,
            auditService,
            new ObjectMapper());
  }

  @Test
  void prepareCreatesMonthlyAdjustResultsAndMarksBatchRunning() {
    when(batchMapper.selectOne(any())).thenReturn(batch());
    when(priceLinkedItemMapper.selectList(any()))
        .thenReturn(List.of(linkedItem("MAT-2"), linkedItem("MAT-1"), linkedItem(" MAT-1 ")));
    LinkedPriceEnsureResult ensureResult = new LinkedPriceEnsureResult();
    ensureResult.setRequestedCount(2);
    ensureResult.setCreatedCount(1);
    ensureResult.setUpdatedCount(1);
    when(linkedPriceEnsureService.ensure(any(LinkedPriceEnsureRequest.class)))
        .thenReturn(ensureResult);

    var result = service.prepare(" MRP-001 ", "alice");

    assertThat(result.getBatchStatus()).isEqualTo("RUNNING");
    assertThat(result.getItemCount()).isEqualTo(2);
    ArgumentCaptor<LinkedPriceEnsureRequest> requestCaptor =
        ArgumentCaptor.forClass(LinkedPriceEnsureRequest.class);
    verify(linkedPriceEnsureService).ensure(requestCaptor.capture());
    LinkedPriceEnsureRequest request = requestCaptor.getValue();
    assertThat(request.getCalcScene().getCode()).isEqualTo("MONTHLY_ADJUST");
    assertThat(request.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(request.getPricingMonth()).isEqualTo("2026-05");
    assertThat(request.getPriceAsOfTime()).isEqualTo(LocalDateTime.of(2026, 5, 27, 10, 30));
    assertThat(request.normalizedItemCodes()).containsExactly("MAT-1", "MAT-2");
    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(priceLinkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("pricing_month <=")
        .contains("business_unit_type =")
        .doesNotContain("effective_from")
        .doesNotContain("effective_to");

    ArgumentCaptor<MonthlyRepriceBatch> batchCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceBatch.class);
    verify(batchMapper).updateById(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getStatus()).isEqualTo("RUNNING");

    ArgumentCaptor<MonthlyRepriceAuditLog> auditCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceAuditLog.class);
    verify(auditLogMapper).insert(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperationType()).isEqualTo("PREPARE_LINKED_PRICE");
    assertThat(auditCaptor.getValue().getAfterJson()).contains("\"batchStatus\":\"RUNNING\"");
    verify(auditService)
        .recordStartCalc(
            any(MonthlyRepriceBatch.class), org.mockito.ArgumentMatchers.eq("alice"), any());
  }

  @Test
  void prepareMarksBatchFailedWhenLinkedPriceHasFailedItems() {
    when(batchMapper.selectOne(any())).thenReturn(batch());
    when(priceLinkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem("MAT-1")));
    LinkedPriceEnsureResult ensureResult = new LinkedPriceEnsureResult();
    ensureResult.setRequestedCount(1);
    ensureResult.addFailedItem("MAT-1", "变量 Cu 缺失");
    when(linkedPriceEnsureService.ensure(any(LinkedPriceEnsureRequest.class)))
        .thenReturn(ensureResult);

    var result = service.prepare("MRP-001", "alice");

    assertThat(result.getBatchStatus()).isEqualTo("FAILED");
    assertThat(result.getFailedCount()).isEqualTo(1);
    ArgumentCaptor<MonthlyRepriceBatch> batchCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceBatch.class);
    verify(batchMapper).updateById(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getStatus()).isEqualTo("FAILED");
    assertThat(batchCaptor.getValue().getFinishedAt()).isNotNull();
  }

  @Test
  void prepareKeepsSameMaterialInDifferentOrganizationsSeparate() {
    when(batchMapper.selectOne(any())).thenReturn(batch());
    var first = linkedItem("MAT-1");
    first.setOrgCode("210");
    var second = linkedItem("MAT-1");
    second.setOrgCode("220");
    when(priceLinkedItemMapper.selectList(any())).thenReturn(List.of(second, first));
    when(linkedPriceEnsureService.ensure(any()))
        .thenAnswer(
            call -> {
              var result = new LinkedPriceEnsureResult();
              result.setRequestedCount(1);
              result.setCreatedCount(1);
              return result;
            });
    var result = service.prepare("MRP-001", "alice");
    assertThat(result.getItemCount()).isEqualTo(2);
    assertThat(result.getCreatedCount()).isEqualTo(2);
    var requests = ArgumentCaptor.forClass(LinkedPriceEnsureRequest.class);
    verify(linkedPriceEnsureService, Mockito.times(2)).ensure(requests.capture());
    assertThat(requests.getAllValues())
        .extracting(LinkedPriceEnsureRequest::getPriceOrgCode)
        .containsExactly("210", "220");
    for (var request : requests.getAllValues())
      assertThat(request.normalizedItemCodes()).containsExactly("MAT-1");
  }

  private MonthlyRepriceBatch batch() {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setId(7L);
    batch.setRepriceNo("MRP-001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setStatus("CREATED");
    batch.setPriceAsOfTime(LocalDateTime.of(2026, 5, 27, 10, 30));
    return batch;
  }

  private PriceLinkedItem linkedItem(String materialCode) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode(materialCode);
    item.setPricingMonth("2026-05");
    item.setBusinessUnitType("COMMERCIAL");
    item.setDeleted(0);
    return item;
  }
}

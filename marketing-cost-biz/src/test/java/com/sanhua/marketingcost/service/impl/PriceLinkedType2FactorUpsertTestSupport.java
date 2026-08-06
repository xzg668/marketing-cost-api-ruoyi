package com.sanhua.marketingcost.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowRefSaveResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityReadRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class PriceLinkedType2FactorUpsertTestSupport {

  final List<FactorIdentity> identities = new ArrayList<>();
  final List<FactorMonthlyPrice> monthlyPrices = new ArrayList<>();
  final List<FactorMonthlyPriceChangeLog> changeLogs = new ArrayList<>();
  final Map<Long, Long> bindingCounts = new LinkedHashMap<>();
  final FactorIdentityMapper identityMapper = mock(FactorIdentityMapper.class);
  final FactorMonthlyPriceMapper monthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
  final FactorMonthlyPriceChangeLogMapper changeLogMapper =
      mock(FactorMonthlyPriceChangeLogMapper.class);
  final FactorUploadBatchService factorUploadBatchService =
      mock(FactorUploadBatchService.class);
  final AtomicReference<FactorWorkbookParseResult> savedRowRefWorkbook =
      new AtomicReference<>();
  final AtomicReference<FactorMonthlyPriceUpsertResult> savedRowRefUpsert =
      new AtomicReference<>();
  final PriceLinkedType2FactorMonthlyUpsertServiceImpl service;

  private final AtomicLong identityId = new AtomicLong(1000L);
  private final AtomicLong monthlyPriceId = new AtomicLong(2000L);
  private final AtomicLong logId = new AtomicLong(3000L);

  PriceLinkedType2FactorUpsertTestSupport() {
    PriceLinkedType2TextNormalizerImpl textNormalizer =
        new PriceLinkedType2TextNormalizerImpl();
    FactorCanonicalKeyServiceImpl canonicalKeyService =
        new FactorCanonicalKeyServiceImpl(textNormalizer);
    PriceLinkedType2FactorIdentityReadRepository readRepository =
        new InMemoryReadRepository();
    PriceLinkedType2FactorIdentityResolverImpl resolver =
        new PriceLinkedType2FactorIdentityResolverImpl(
            canonicalKeyService, textNormalizer, readRepository);
    service = new PriceLinkedType2FactorMonthlyUpsertServiceImpl(
        resolver,
        identityMapper,
        monthlyPriceMapper,
        changeLogMapper,
        factorUploadBatchService);
    configureMappers();
  }

  FactorIdentity addIdentity(
      long id,
      String businessUnitType,
      String seq,
      String name,
      String shortName,
      String priceSource) {
    FactorIdentity identity = new FactorIdentity();
    identity.setId(id);
    identity.setBusinessUnitType(businessUnitType);
    identity.setFactorSeqNo(seq);
    identity.setFactorName(name);
    identity.setShortName(shortName);
    identity.setPriceSource(priceSource);
    identity.setStatus("ACTIVE");
    identities.add(identity);
    identityId.updateAndGet(current -> Math.max(current, id + 1));
    return identity;
  }

  FactorMonthlyPrice addMonthlyPrice(
      long id, long factorIdentityId, String month, String price) {
    FactorMonthlyPrice monthlyPrice = new FactorMonthlyPrice();
    monthlyPrice.setId(id);
    monthlyPrice.setFactorIdentityId(factorIdentityId);
    monthlyPrice.setPriceMonth(month);
    monthlyPrice.setPrice(new BigDecimal(price));
    monthlyPrice.setTaxIncluded(1);
    monthlyPrice.setStatus("ACTIVE");
    monthlyPrices.add(monthlyPrice);
    monthlyPriceId.updateAndGet(current -> Math.max(current, id + 1));
    return monthlyPrice;
  }

  PriceLinkedType2WorkbookParseResult workbook(PriceLinkedType2FactorRow... rows) {
    return new PriceLinkedType2WorkbookParseResult(
        "type2.xls",
        "Sheet1",
        5,
        "importdata1",
        1,
        List.of(rows),
        List.of(),
        List.of(),
        List.of());
  }

  PriceLinkedType2FactorRow factor(
      int rowNumber,
      String seq,
      String name,
      String shortName,
      String source,
      String price) {
    return new PriceLinkedType2FactorRow(
        "Sheet1",
        rowNumber,
        seq,
        name,
        shortName,
        source,
        new BigDecimal(price),
        "公斤",
        "E" + rowNumber);
  }

  private void configureMappers() {
    doAnswer(invocation -> {
      FactorIdentity value = invocation.getArgument(0);
      if (value.getId() == null) {
        value.setId(identityId.getAndIncrement());
      }
      replaceIdentity(value);
      return 1;
    }).when(identityMapper).insert(any(FactorIdentity.class));
    doAnswer(invocation -> {
      FactorIdentity value = invocation.getArgument(0);
      replaceIdentity(value);
      return 1;
    }).when(identityMapper).updateById(any(FactorIdentity.class));
    when(identityMapper.selectById(any()))
        .thenAnswer(invocation -> findIdentity(((Number) invocation.getArgument(0)).longValue()));

    doAnswer(invocation -> {
      FactorMonthlyPrice value = invocation.getArgument(0);
      if (value.getId() == null) {
        value.setId(monthlyPriceId.getAndIncrement());
      }
      replaceMonthlyPrice(value);
      return 1;
    }).when(monthlyPriceMapper).insert(any(FactorMonthlyPrice.class));
    doAnswer(invocation -> {
      FactorMonthlyPrice value = invocation.getArgument(0);
      replaceMonthlyPrice(value);
      return 1;
    }).when(monthlyPriceMapper).updateById(any(FactorMonthlyPrice.class));
    when(monthlyPriceMapper.findActiveByIdentityAndMonth(anyLong(), anyString()))
        .thenAnswer(invocation -> findMonthlyPrice(
            invocation.getArgument(0), invocation.getArgument(1)));

    doAnswer(invocation -> {
      FactorMonthlyPriceChangeLog value = invocation.getArgument(0);
      if (value.getId() == null) {
        value.setId(logId.getAndIncrement());
      }
      changeLogs.add(value);
      return 1;
    }).when(changeLogMapper).insert(any(FactorMonthlyPriceChangeLog.class));

    when(factorUploadBatchService.saveRowRefs(
        anyLong(), any(FactorWorkbookParseResult.class),
        any(FactorMonthlyPriceUpsertResult.class)))
        .thenAnswer(invocation -> {
          Long batchId = invocation.getArgument(0);
          FactorWorkbookParseResult workbook = invocation.getArgument(1);
          FactorMonthlyPriceUpsertResult upsert = invocation.getArgument(2);
          savedRowRefWorkbook.set(workbook);
          savedRowRefUpsert.set(upsert);
          FactorRowRefSaveResult result = new FactorRowRefSaveResult();
          result.setFactorUploadBatchId(batchId);
          result.setInsertedCount(upsert.getRows().size());
          return result;
        });
  }

  private FactorIdentity findIdentity(long id) {
    return identities.stream()
        .filter(identity -> Objects.equals(identity.getId(), id))
        .findFirst()
        .orElse(null);
  }

  private FactorMonthlyPrice findMonthlyPrice(long identityId, String month) {
    return monthlyPrices.stream()
        .filter(price -> Objects.equals(price.getFactorIdentityId(), identityId))
        .filter(price -> Objects.equals(price.getPriceMonth(), month))
        .filter(price -> "ACTIVE".equals(price.getStatus()))
        .findFirst()
        .orElse(null);
  }

  private void replaceIdentity(FactorIdentity value) {
    identities.removeIf(existing -> Objects.equals(existing.getId(), value.getId()));
    identities.add(value);
  }

  private void replaceMonthlyPrice(FactorMonthlyPrice value) {
    monthlyPrices.removeIf(existing -> Objects.equals(existing.getId(), value.getId()));
    monthlyPrices.add(value);
  }

  private final class InMemoryReadRepository
      implements PriceLinkedType2FactorIdentityReadRepository {

    @Override
    public List<FactorIdentity> findActiveIdentities(String businessUnitType) {
      return identities.stream()
          .filter(identity -> "ACTIVE".equals(identity.getStatus()))
          .filter(identity -> businessUnitType.equalsIgnoreCase(identity.getBusinessUnitType()))
          .toList();
    }

    @Override
    public List<FactorMonthlyPrice> findActiveMonthlyPrices(
        Collection<Long> factorIdentityIds, String priceMonth) {
      return monthlyPrices.stream()
          .filter(price -> factorIdentityIds.contains(price.getFactorIdentityId()))
          .filter(price -> priceMonth.equals(price.getPriceMonth()))
          .filter(price -> "ACTIVE".equals(price.getStatus()))
          .toList();
    }

    @Override
    public Map<Long, Long> countActiveLegacyBindings(
        Collection<Long> factorIdentityIds) {
      Map<Long, Long> result = new LinkedHashMap<>();
      for (Long identityId : factorIdentityIds) {
        result.put(identityId, bindingCounts.getOrDefault(identityId, 0L));
      }
      return result;
    }
  }
}

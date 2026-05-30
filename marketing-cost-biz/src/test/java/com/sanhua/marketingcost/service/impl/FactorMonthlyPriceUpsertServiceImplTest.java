package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowParseResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.QuoteBasePriceDetectResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.enums.FactorPriceConflictStrategy;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.QuoteBasePriceMappingService;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FactorMonthlyPriceUpsertServiceImplTest {

  private FactorIdentityMapper identityMapper;
  private FactorMonthlyPriceMapper monthlyPriceMapper;
  private FactorMonthlyPriceChangeLogMapper changeLogMapper;
  private FactorMonthlyPriceUpsertServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorIdentity.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPriceChangeLog.class);
  }

  @BeforeEach
  void setUp() {
    identityMapper = mock(FactorIdentityMapper.class);
    monthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
    changeLogMapper = mock(FactorMonthlyPriceChangeLogMapper.class);
    service = new FactorMonthlyPriceUpsertServiceImpl(
        identityMapper, monthlyPriceMapper, changeLogMapper);

    doAnswer(invocation -> {
      FactorIdentity identity = invocation.getArgument(0);
      identity.setId(100L);
      return 1;
    }).when(identityMapper).insert(any(FactorIdentity.class));
    doAnswer(invocation -> {
      FactorMonthlyPrice price = invocation.getArgument(0);
      price.setId(200L);
      return 1;
    }).when(monthlyPriceMapper).insert(any(FactorMonthlyPrice.class));
  }

  @Test
  @DisplayName("upsert：首次导入新增身份和月度价格，并写 CREATE 日志")
  void upsertCreatesIdentityMonthlyPriceAndCreateLog() {
    when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    FactorMonthlyPriceUpsertResult result = service.upsert(
        workbook(row("64", "SUS304全名", "SUS304/2Bδ0.6-900", "出厂价", "16.40")),
        "2026-05", "COMMERCIAL", "alice", 9001L);

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getIdentityCreatedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceCreatedCount()).isEqualTo(1);
    assertThat(result.getRows()).hasSize(1);
    assertThat(result.getRows().getFirst().getFactorIdentityId()).isEqualTo(100L);
    assertThat(result.getRows().getFirst().getFactorMonthlyPriceId()).isEqualTo(200L);
    assertThat(result.getRows().getFirst().getFactorSeqNo()).isEqualTo("64");
    assertThat(result.getRows().getFirst().getFactorName()).isEqualTo("SUS304全名");
    assertThat(result.getRows().getFirst().getShortName()).isEqualTo("SUS304/2Bδ0.6-900");
    assertThat(result.getRows().getFirst().getPriceSource()).isEqualTo("出厂价");
    assertThat(result.getRows().getFirst().getUnit()).isEqualTo("公斤");
    assertThat(result.getRows().getFirst().getOriginalPrice()).isEqualByComparingTo("17.2");
    assertThat(result.getRows().getFirst().getIdentityAction()).isEqualTo("CREATE");
    assertThat(result.getRows().getFirst().getMonthlyPriceAction()).isEqualTo("CREATE");

    ArgumentCaptor<FactorIdentity> identityCaptor = ArgumentCaptor.forClass(FactorIdentity.class);
    verify(identityMapper).insert(identityCaptor.capture());
    assertThat(identityCaptor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(identityCaptor.getValue().getFactorSeqNo()).isEqualTo("64");
    assertThat(identityCaptor.getValue().getIdentityHash()).hasSize(64);

    ArgumentCaptor<FactorMonthlyPrice> priceCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPrice.class);
    verify(monthlyPriceMapper).insert(priceCaptor.capture());
    assertThat(priceCaptor.getValue().getPriceMonth()).isEqualTo("2026-05");
    assertThat(priceCaptor.getValue().getPrice()).isEqualByComparingTo("16.4");
    assertThat(priceCaptor.getValue().getSourceUploadBatchId()).isEqualTo(9001L);

    ArgumentCaptor<FactorMonthlyPriceChangeLog> logCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPriceChangeLog.class);
    verify(changeLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getChangeType()).isEqualTo("CREATE");
    assertThat(logCaptor.getValue().getOldPrice()).isNull();
    assertThat(logCaptor.getValue().getNewPrice()).isEqualByComparingTo("16.4");
    assertThat(logCaptor.getValue().getSourceUploadBatchId()).isEqualTo(9001L);
    assertThat(logCaptor.getValue().getChangedBy()).isEqualTo("alice");
  }

  @Test
  @DisplayName("upsert：影响因素保存后调用公共基价识别并回填统计")
  void upsertDetectsQuoteBaseMappingAfterIdentitySaved() {
    when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    QuoteBasePriceMappingService quoteBaseService = mock(QuoteBasePriceMappingService.class);
    when(quoteBaseService.detectAndSaveFactorQuoteBaseMapping(100L))
        .thenReturn(QuoteBasePriceDetectResult.recognized(
            100L, "copper_price", "铜基价", "Cu", "电解铜"));
    service.setQuoteBasePriceMappingService(quoteBaseService);

    FactorMonthlyPriceUpsertResult result = service.upsert(
        workbook(row("10", "上月长江现货1#电解铜均价", "电解铜", "平均价", "72")),
        "2026-05", "COMMERCIAL", "alice", 9001L);

    assertThat(result.getQuoteBaseRecognizedCount()).isEqualTo(1);
    assertThat(result.getQuoteBaseUnrecognizedCount()).isZero();
    assertThat(result.getQuoteBaseConflictCount()).isZero();
    assertThat(result.getRows().getFirst().getQuoteBaseDetectStatus()).isEqualTo("RECOGNIZED");
    assertThat(result.getRows().getFirst().getQuoteBaseQuoteFieldCode()).isEqualTo("copper_price");
    assertThat(result.getRows().getFirst().getQuoteBaseMatchedKeyword()).isEqualTo("电解铜");
    verify(quoteBaseService).detectAndSaveFactorQuoteBaseMapping(100L);
  }

  @Test
  @DisplayName("upsert：重复上传同身份同月份同价格，不重复新增")
  void upsertReusesExistingIdentityAndMonthlyPriceWhenSamePrice() {
    FactorIdentity existingIdentity = identity(100L);
    FactorMonthlyPrice existingPrice = monthlyPrice(200L, "16.4000");
    when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(existingIdentity);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(existingPrice);

    FactorMonthlyPriceUpsertResult result = service.upsert(
        workbook(row("64", "SUS304全名", "SUS304/2Bδ0.6-900", "出厂价", "16.4")),
        "2026-05", "COMMERCIAL", "alice", 9002L);

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getIdentityCreatedCount()).isZero();
    assertThat(result.getIdentityReusedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceCreatedCount()).isZero();
    assertThat(result.getMonthlyPriceUpdatedCount()).isZero();
    assertThat(result.getMonthlyPriceUnchangedCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getMonthlyPriceAction()).isEqualTo("NO_CHANGE");

    verify(identityMapper, never()).insert(any(FactorIdentity.class));
    verify(monthlyPriceMapper, never()).insert(any(FactorMonthlyPrice.class));
    verify(monthlyPriceMapper, never()).updateById(any(FactorMonthlyPrice.class));
    verify(changeLogMapper, never()).insert(any(FactorMonthlyPriceChangeLog.class));
  }

  @Test
  @DisplayName("upsert：同身份同月份异价且 OVERWRITE，更新当前价格并写 UPDATE 日志")
  void upsertOverwritesMonthlyPriceAndWritesChangeLogWhenPriceChanged() {
    FactorIdentity existingIdentity = identity(100L);
    FactorMonthlyPrice existingPrice = monthlyPrice(200L, "16.4");
    when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(existingIdentity);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(existingPrice);

    FactorMonthlyPriceUpsertResult result = service.upsert(
        workbook(row("64", "SUS304全名", "SUS304/2Bδ0.6-900", "出厂价", "16.8")),
        "2026-05", "COMMERCIAL", "alice", 9003L,
        FactorPriceConflictStrategy.OVERWRITE.getCode());

    assertThat(result.getMonthlyPriceUpdatedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceOverwriteCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceConflictCount()).isZero();
    assertThat(result.getRows().getFirst().getMonthlyPriceAction()).isEqualTo("UPDATE");
    assertThat(result.getRows().getFirst().getOldPrice()).isEqualByComparingTo("16.4");

    ArgumentCaptor<FactorMonthlyPrice> priceCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPrice.class);
    verify(monthlyPriceMapper).updateById(priceCaptor.capture());
    assertThat(priceCaptor.getValue().getId()).isEqualTo(200L);
    assertThat(priceCaptor.getValue().getPrice()).isEqualByComparingTo("16.8");
    assertThat(priceCaptor.getValue().getSourceUploadBatchId()).isEqualTo(9003L);

    ArgumentCaptor<FactorMonthlyPriceChangeLog> logCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPriceChangeLog.class);
    verify(changeLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getChangeType()).isEqualTo("UPDATE");
    assertThat(logCaptor.getValue().getOldPrice()).isEqualByComparingTo("16.4");
    assertThat(logCaptor.getValue().getNewPrice()).isEqualByComparingTo("16.8");
    assertThat(logCaptor.getValue().getSourceUploadBatchId()).isEqualTo(9003L);
    assertThat(logCaptor.getValue().getChangedBy()).isEqualTo("alice");
  }

  @Test
  @DisplayName("upsert：同身份同月份异价且 KEEP_EXISTING，不更新并记冲突")
  void upsertKeepExistingMarksConflictWhenMonthlyPriceChanged() {
    FactorIdentity existingIdentity = identity(100L);
    FactorMonthlyPrice existingPrice = monthlyPrice(200L, "16.4");
    when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(existingIdentity);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(existingPrice);

    FactorMonthlyPriceUpsertResult result = service.upsert(
        workbook(row("64", "SUS304全名", "SUS304/2Bδ0.6-900", "出厂价", "16.8")),
        "2026-05", "COMMERCIAL", "alice", 9003L,
        FactorPriceConflictStrategy.KEEP_EXISTING.getCode());

    assertThat(result.getMonthlyPriceUpdatedCount()).isZero();
    assertThat(result.getMonthlyPriceOverwriteCount()).isZero();
    assertThat(result.getMonthlyPriceSkippedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceConflictCount()).isEqualTo(1);
    assertThat(result.getRows()).hasSize(1);
    assertThat(result.getRows().getFirst().getFactorMonthlyPriceId()).isEqualTo(200L);
    assertThat(result.getRows().getFirst().getMonthlyPriceAction()).isEqualTo("CONFLICT_KEEP_EXISTING");
    assertThat(result.getRows().getFirst().getOldPrice()).isEqualByComparingTo("16.4");
    assertThat(result.getRows().getFirst().getNewPrice()).isEqualByComparingTo("16.4");
    verify(monthlyPriceMapper, never()).updateById(any(FactorMonthlyPrice.class));
    verify(changeLogMapper, never()).insert(any(FactorMonthlyPriceChangeLog.class));
  }

  @Test
  @DisplayName("upsert：多报价员重复导入同一影响因素表，不重复新增身份和月度价格")
  void upsertAcrossOperatorsReusesExistingIdentityAndMonthlyPrice() {
    AtomicReference<FactorIdentity> persistedIdentity = new AtomicReference<>();
    AtomicReference<FactorMonthlyPrice> persistedMonthlyPrice = new AtomicReference<>();
    doAnswer(invocation -> {
      FactorIdentity identity = invocation.getArgument(0);
      identity.setId(100L);
      persistedIdentity.set(identity);
      return 1;
    }).when(identityMapper).insert(any(FactorIdentity.class));
    doAnswer(invocation -> {
      FactorMonthlyPrice price = invocation.getArgument(0);
      price.setId(200L);
      persistedMonthlyPrice.set(price);
      return 1;
    }).when(monthlyPriceMapper).insert(any(FactorMonthlyPrice.class));
    when(identityMapper.selectOne(any(Wrapper.class)))
        .thenAnswer(invocation -> persistedIdentity.get());
    when(monthlyPriceMapper.selectOne(any(Wrapper.class)))
        .thenAnswer(invocation -> persistedMonthlyPrice.get());

    FactorWorkbookParseResult workbook =
        workbook(row("64", "SUS304全名", "SUS304/2Bδ0.6-900", "出厂价", "16.4"));

    FactorMonthlyPriceUpsertResult first = service.upsert(
        workbook, "2026-05", "COMMERCIAL", "alice", 9003L);
    FactorMonthlyPriceUpsertResult second = service.upsert(
        workbook, "2026-05", "COMMERCIAL", "bob", 9004L);

    assertThat(first.getIdentityCreatedCount()).isEqualTo(1);
    assertThat(first.getMonthlyPriceCreatedCount()).isEqualTo(1);
    assertThat(second.getIdentityReusedCount()).isEqualTo(1);
    assertThat(second.getMonthlyPriceUnchangedCount()).isEqualTo(1);
    assertThat(second.getRows().getFirst().getFactorIdentityId()).isEqualTo(100L);
    assertThat(second.getRows().getFirst().getFactorMonthlyPriceId()).isEqualTo(200L);
    verify(identityMapper, times(1)).insert(any(FactorIdentity.class));
    verify(monthlyPriceMapper, times(1)).insert(any(FactorMonthlyPrice.class));
    verify(monthlyPriceMapper, never()).updateById(any(FactorMonthlyPrice.class));
    verify(changeLogMapper, times(1)).insert(any(FactorMonthlyPriceChangeLog.class));
  }

  @Test
  @DisplayName("upsert：同一个解析结果内重复行走缓存，不重复落身份和月度价格")
  void upsertDedupesDuplicateRowsInSameParseResult() {
    when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    FactorMonthlyPriceUpsertResult result = service.upsert(
        workbook(
            row("64", "SUS304全名", "SUS304/2Bδ0.6-900", "出厂价", "16.4"),
            row("64", "SUS304全名", "SUS304/2Bδ0.6-900", "出厂价", "16.4000")),
        "2026-05", "COMMERCIAL", "alice", 9004L);

    assertThat(result.getRows()).hasSize(2);
    assertThat(result.getIdentityCreatedCount()).isEqualTo(1);
    assertThat(result.getIdentityReusedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceCreatedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceUnchangedCount()).isEqualTo(1);
    verify(identityMapper, times(1)).insert(any(FactorIdentity.class));
    verify(monthlyPriceMapper, times(1)).insert(any(FactorMonthlyPrice.class));
    verify(changeLogMapper, times(1)).insert(any(FactorMonthlyPriceChangeLog.class));
  }

  @Test
  @DisplayName("upsert：同简称同价源但序号不同，不能合并为同一个影响因素身份")
  void upsertKeepsDifferentSeqNoAsDifferentIdentity() {
    AtomicLong identityId = new AtomicLong(100L);
    AtomicLong monthlyPriceId = new AtomicLong(200L);
    doAnswer(invocation -> {
      FactorIdentity identity = invocation.getArgument(0);
      identity.setId(identityId.getAndIncrement());
      return 1;
    }).when(identityMapper).insert(any(FactorIdentity.class));
    doAnswer(invocation -> {
      FactorMonthlyPrice price = invocation.getArgument(0);
      price.setId(monthlyPriceId.getAndIncrement());
      return 1;
    }).when(monthlyPriceMapper).insert(any(FactorMonthlyPrice.class));
    when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    FactorMonthlyPriceUpsertResult result = service.upsert(
        workbook(
            row("64", "上月宁波宝新SUS304S/Bδ0.6出厂价-900元", "SUS304/2Bδ0.6-900", "出厂价", "16.4"),
            row("65", "上月宁波宝新SUS304S/Bδ0.6出厂价-1000元", "SUS304/2Bδ0.6-900", "出厂价", "16.6")),
        "2026-05", "COMMERCIAL", "alice", 9006L);

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getIdentityCreatedCount()).isEqualTo(2);
    assertThat(result.getIdentityReusedCount()).isZero();
    assertThat(result.getMonthlyPriceCreatedCount()).isEqualTo(2);
    assertThat(result.getRows()).extracting(FactorMonthlyPriceUpsertResult.RowResult::getFactorIdentityId)
        .containsExactly(100L, 101L);
    assertThat(result.getRows()).extracting(FactorMonthlyPriceUpsertResult.RowResult::getFactorMonthlyPriceId)
        .containsExactly(200L, 201L);

    ArgumentCaptor<FactorIdentity> identityCaptor = ArgumentCaptor.forClass(FactorIdentity.class);
    verify(identityMapper, times(2)).insert(identityCaptor.capture());
    assertThat(identityCaptor.getAllValues()).extracting(FactorIdentity::getFactorSeqNo)
        .containsExactly("64", "65");
    assertThat(identityCaptor.getAllValues()).extracting(FactorIdentity::getShortName)
        .containsExactly("SUS304/2Bδ0.6-900", "SUS304/2Bδ0.6-900");
    verify(monthlyPriceMapper, times(2)).insert(any(FactorMonthlyPrice.class));
    verify(changeLogMapper, times(2)).insert(any(FactorMonthlyPriceChangeLog.class));
  }

  @Test
  @DisplayName("upsert：价格非法行返回错误，不落库")
  void upsertReportsErrorForInvalidPriceRow() {
    FactorMonthlyPriceUpsertResult result = service.upsert(
        workbook(row("64", "SUS304全名", "SUS304/2Bδ0.6-900", "出厂价", null)),
        "2026-05", "COMMERCIAL", "alice", 9005L);

    assertThat(result.getRows()).isEmpty();
    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getMessage()).contains("价格");
    verify(identityMapper, never()).insert(any(FactorIdentity.class));
    verify(monthlyPriceMapper, never()).insert(any(FactorMonthlyPrice.class));
    verify(changeLogMapper, never()).insert(any(FactorMonthlyPriceChangeLog.class));
  }

  private FactorIdentity identity(Long id) {
    FactorIdentity identity = new FactorIdentity();
    identity.setId(id);
    identity.setBusinessUnitType("COMMERCIAL");
    identity.setFactorSeqNo("64");
    identity.setFactorName("SUS304全名");
    identity.setShortName("SUS304/2Bδ0.6-900");
    identity.setPriceSource("出厂价");
    return identity;
  }

  private FactorMonthlyPrice monthlyPrice(Long id, String price) {
    FactorMonthlyPrice monthlyPrice = new FactorMonthlyPrice();
    monthlyPrice.setId(id);
    monthlyPrice.setFactorIdentityId(100L);
    monthlyPrice.setPriceMonth("2026-05");
    monthlyPrice.setPrice(new BigDecimal(price));
    return monthlyPrice;
  }

  private FactorWorkbookParseResult workbook(FactorRowParseResult... rows) {
    FactorWorkbookParseResult workbook = new FactorWorkbookParseResult();
    workbook.setSourceFileName("monthly.xlsx");
    FactorSheetParseResult sheet = new FactorSheetParseResult();
    sheet.setSheetName("财务报价基准");
    sheet.setHeaderRowNumber(2);
    for (FactorRowParseResult row : rows) {
      sheet.getRows().add(row);
    }
    workbook.getSheets().add(sheet);
    return workbook;
  }

  private FactorRowParseResult row(
      String seq, String factorName, String shortName, String priceSource, String price) {
    FactorRowParseResult row = new FactorRowParseResult();
    row.setSourceSheetName("财务报价基准");
    row.setSourceRowNumber(64);
    row.setFactorSeqNo(seq);
    row.setFactorName(factorName);
    row.setShortName(shortName);
    row.setPriceSource(priceSource);
    row.setPrice(price == null ? null : new BigDecimal(price));
    row.setOriginalPrice(new BigDecimal("17.20"));
    row.setUnit("公斤");
    return row;
  }
}

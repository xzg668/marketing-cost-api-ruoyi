package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.sanhua.marketingcost.dto.FactorRowRefSaveResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchCreateRequest;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FactorUploadBatchServiceImplTest {

  private FactorUploadBatchMapper batchMapper;
  private FactorRowRefMapper rowRefMapper;
  private FactorUploadBatchServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorUploadBatch.class);
    TableInfoHelper.initTableInfo(assistant, FactorRowRef.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(FactorUploadBatchMapper.class);
    rowRefMapper = mock(FactorRowRefMapper.class);
    service = new FactorUploadBatchServiceImpl(batchMapper, rowRefMapper);

    doAnswer(invocation -> {
      FactorUploadBatch batch = invocation.getArgument(0);
      batch.setId(batch.getId() == null ? 9001L : batch.getId());
      return 1;
    }).when(batchMapper).insert(any(FactorUploadBatch.class));
    doAnswer(invocation -> {
      FactorRowRef rowRef = invocation.getArgument(0);
      rowRef.setId(rowRef.getId() == null ? 3001L : rowRef.getId());
      return 1;
    }).when(rowRefMapper).insert(any(FactorRowRef.class));
  }

  @Test
  @DisplayName("createFactorBatch：每次上传都生成新批次，内容 hash 相同也不复用批次")
  void createFactorBatchAlwaysCreatesNewBatch() {
    FactorWorkbookParseResult workbook = workbook(row(64, "64", "SUS304全名", "SUS304", "出厂价", "16.40"));
    FactorUploadBatchCreateRequest request = request(workbook);

    FactorUploadBatch first = service.createFactorBatch(request);
    FactorUploadBatch second = service.createFactorBatch(request);

    ArgumentCaptor<FactorUploadBatch> batchCaptor =
        ArgumentCaptor.forClass(FactorUploadBatch.class);
    verify(batchMapper, times(2)).insert(batchCaptor.capture());
    assertThat(first.getBatchNo()).startsWith("FUB");
    assertThat(second.getBatchNo()).startsWith("FUB");
    assertThat(first.getBatchNo()).isNotEqualTo(second.getBatchNo());
    assertThat(first.getContentHash()).isEqualTo(second.getContentHash());
    assertThat(batchCaptor.getAllValues().getFirst().getStatus()).isEqualTo("PENDING");
    assertThat(batchCaptor.getAllValues().getFirst().getFactorSheetCount()).isEqualTo(1);
    assertThat(batchCaptor.getAllValues().getFirst().getFactorRowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("createFactorBatch：记录导入用途和生效策略")
  void createFactorBatchPersistsPurposeAndEffectiveStrategy() {
    FactorWorkbookParseResult workbook = workbook(row(64, "64", "SUS304全名", "SUS304", "出厂价", "16.40"));
    FactorUploadBatchCreateRequest request = request(workbook);
    request.setImportPurpose("LINKED_APPEND_ONLY");
    request.setEffectiveStrategy("APPEND_ONLY");

    service.createFactorBatch(request);

    ArgumentCaptor<FactorUploadBatch> batchCaptor =
        ArgumentCaptor.forClass(FactorUploadBatch.class);
    verify(batchMapper).insert(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getImportPurpose()).isEqualTo("LINKED_APPEND_ONLY");
    assertThat(batchCaptor.getValue().getEffectiveStrategy()).isEqualTo("APPEND_ONLY");
  }

  @Test
  @DisplayName("saveRowRefs：即使身份和月度价格来自复用结果，也保存本次 sheet 行号映射")
  void saveRowRefsPersistsCurrentUploadRowMapping() {
    FactorWorkbookParseResult workbook = workbook(row(64, "64", "SUS304全名", "SUS304", "出厂价", "16.40"));
    FactorMonthlyPriceUpsertResult upsertResult = upsertRows(rowResult("影响因素", 64, 100L, 200L));
    when(rowRefMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(batchMapper.selectById(eq(9001L))).thenReturn(batch(9001L));

    FactorRowRefSaveResult result = service.saveRowRefs(9001L, workbook, upsertResult);

    assertThat(result.getInsertedCount()).isEqualTo(1);
    assertThat(result.getErrors()).isEmpty();

    ArgumentCaptor<FactorRowRef> rowRefCaptor = ArgumentCaptor.forClass(FactorRowRef.class);
    verify(rowRefMapper).insert(rowRefCaptor.capture());
    FactorRowRef rowRef = rowRefCaptor.getValue();
    assertThat(rowRef.getFactorUploadBatchId()).isEqualTo(9001L);
    assertThat(rowRef.getSourceWorkbookName()).isEqualTo("monthly.xlsx");
    assertThat(rowRef.getSourceSheetName()).isEqualTo("影响因素");
    assertThat(rowRef.getSourceRowNumber()).isEqualTo(64);
    assertThat(rowRef.getFactorIdentityId()).isEqualTo(100L);
    assertThat(rowRef.getFactorMonthlyPriceId()).isEqualTo(200L);
    assertThat(rowRef.getFactorSeqNo()).isEqualTo("64");
    assertThat(rowRef.getShortName()).isEqualTo("SUS304");
    assertThat(rowRef.getPriceSource()).isEqualTo("出厂价");
    assertThat(rowRef.getPrice()).isEqualByComparingTo("16.4");

    ArgumentCaptor<FactorUploadBatch> batchCaptor =
        ArgumentCaptor.forClass(FactorUploadBatch.class);
    verify(batchMapper).updateById(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
    assertThat(batchCaptor.getValue().getFactorRowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("saveRowRefs：同一批次同 sheet 行号再次保存时更新原映射，不重复插入")
  void saveRowRefsUpdatesExistingMappingInSameBatch() {
    FactorWorkbookParseResult workbook = workbook(row(64, "64", "SUS304全名", "SUS304", "出厂价", "16.80"));
    FactorMonthlyPriceUpsertResult upsertResult = upsertRows(rowResult("影响因素", 64, 101L, 201L));
    FactorRowRef existing = new FactorRowRef();
    existing.setId(3001L);
    existing.setCreatedAt(LocalDateTime.of(2026, 5, 1, 9, 0));
    when(rowRefMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(batchMapper.selectById(eq(9001L))).thenReturn(batch(9001L));

    FactorRowRefSaveResult result = service.saveRowRefs(9001L, workbook, upsertResult);

    assertThat(result.getInsertedCount()).isZero();
    assertThat(result.getUpdatedCount()).isEqualTo(1);
    verify(rowRefMapper, never()).insert(any(FactorRowRef.class));

    ArgumentCaptor<FactorRowRef> rowRefCaptor = ArgumentCaptor.forClass(FactorRowRef.class);
    verify(rowRefMapper).updateById(rowRefCaptor.capture());
    assertThat(rowRefCaptor.getValue().getId()).isEqualTo(3001L);
    assertThat(rowRefCaptor.getValue().getFactorIdentityId()).isEqualTo(101L);
    assertThat(rowRefCaptor.getValue().getFactorMonthlyPriceId()).isEqualTo(201L);
    assertThat(rowRefCaptor.getValue().getPrice()).isEqualByComparingTo("16.8");
  }

  @Test
  @DisplayName("findRowRef：按批次 + sheet + Excel 行号精确查询")
  void findRowRefQueriesByBatchSheetAndRowNumber() {
    FactorRowRef existing = new FactorRowRef();
    existing.setId(3001L);
    existing.setFactorUploadBatchId(9001L);
    existing.setSourceSheetName("影响因素");
    existing.setSourceRowNumber(64);
    existing.setFactorIdentityId(100L);
    when(rowRefMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    FactorRowRef rowRef = service.findRowRef(9001L, " 影响因素 ", 64);

    assertThat(rowRef).isSameAs(existing);
    assertThat(rowRef.getFactorIdentityId()).isEqualTo(100L);
    verify(rowRefMapper).selectOne(any(Wrapper.class));
  }

  @Test
  @DisplayName("saveRowRefs：缺少身份或月度价格 id 时跳过并把批次置为失败")
  void saveRowRefsSkipsInvalidRowResult() {
    FactorWorkbookParseResult workbook = workbook(row(64, "64", "SUS304全名", "SUS304", "出厂价", "16.40"));
    FactorMonthlyPriceUpsertResult upsertResult = upsertRows(rowResult("影响因素", 64, null, 200L));
    when(batchMapper.selectById(eq(9001L))).thenReturn(batch(9001L));

    FactorRowRefSaveResult result = service.saveRowRefs(9001L, workbook, upsertResult);

    assertThat(result.getSkippedCount()).isEqualTo(1);
    assertThat(result.getErrors()).hasSize(1);
    verify(rowRefMapper, never()).insert(any(FactorRowRef.class));

    ArgumentCaptor<FactorUploadBatch> batchCaptor =
        ArgumentCaptor.forClass(FactorUploadBatch.class);
    verify(batchMapper).updateById(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getStatus()).isEqualTo("FAILED");
  }

  private FactorUploadBatchCreateRequest request(FactorWorkbookParseResult workbook) {
    FactorUploadBatchCreateRequest request = new FactorUploadBatchCreateRequest();
    request.setPriceMonth("2026-05");
    request.setBusinessUnitType("COMMERCIAL");
    request.setFileName("monthly.xlsx");
    request.setFileSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    request.setUploadedBy("alice");
    request.setParseResult(workbook);
    return request;
  }

  private FactorUploadBatch batch(Long id) {
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(id);
    batch.setStatus("PENDING");
    batch.setErrorCount(0);
    return batch;
  }

  private FactorMonthlyPriceUpsertResult upsertRows(
      FactorMonthlyPriceUpsertResult.RowResult... rows) {
    FactorMonthlyPriceUpsertResult result = new FactorMonthlyPriceUpsertResult();
    for (FactorMonthlyPriceUpsertResult.RowResult row : rows) {
      result.getRows().add(row);
    }
    return result;
  }

  private FactorMonthlyPriceUpsertResult.RowResult rowResult(
      String sheetName,
      Integer rowNumber,
      Long factorIdentityId,
      Long factorMonthlyPriceId) {
    FactorMonthlyPriceUpsertResult.RowResult row = new FactorMonthlyPriceUpsertResult.RowResult();
    row.setSourceSheetName(sheetName);
    row.setSourceRowNumber(rowNumber);
    row.setFactorIdentityId(factorIdentityId);
    row.setFactorMonthlyPriceId(factorMonthlyPriceId);
    row.setIdentityAction("REUSE");
    row.setMonthlyPriceAction("NO_CHANGE");
    return row;
  }

  private FactorWorkbookParseResult workbook(FactorRowParseResult... rows) {
    FactorWorkbookParseResult workbook = new FactorWorkbookParseResult();
    workbook.setSourceFileName("monthly.xlsx");
    FactorSheetParseResult sheet = new FactorSheetParseResult();
    sheet.setSheetName("影响因素");
    sheet.setHeaderRowNumber(2);
    for (FactorRowParseResult row : rows) {
      sheet.getRows().add(row);
    }
    workbook.getSheets().add(sheet);
    return workbook;
  }

  private FactorRowParseResult row(
      Integer rowNumber,
      String seq,
      String factorName,
      String shortName,
      String priceSource,
      String price) {
    FactorRowParseResult row = new FactorRowParseResult();
    row.setSourceSheetName("影响因素");
    row.setSourceRowNumber(rowNumber);
    row.setFactorSeqNo(seq);
    row.setFactorName(factorName);
    row.setShortName(shortName);
    row.setPriceSource(priceSource);
    row.setPrice(new BigDecimal(price));
    return row;
  }
}

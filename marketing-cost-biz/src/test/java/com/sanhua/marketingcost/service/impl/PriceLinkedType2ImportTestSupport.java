package com.sanhua.marketingcost.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveRequest;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorIdentityResolution;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.enums.PriceLinkedType2FactorIdentityResolutionStatus;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisService;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityResolver;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorMonthlyUpsertService;
import com.sanhua.marketingcost.service.PriceLinkedType2WorkbookParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class PriceLinkedType2ImportTestSupport {

  final PriceLinkedType2WorkbookParser parser = mock(PriceLinkedType2WorkbookParser.class);
  final PriceLinkedType2FactorIdentityResolver factorResolver =
      mock(PriceLinkedType2FactorIdentityResolver.class);
  final PriceLinkedType2FactorMonthlyUpsertService factorUpsertService =
      mock(PriceLinkedType2FactorMonthlyUpsertService.class);
  final PriceLinkedImportBasisService importBasisService =
      mock(PriceLinkedImportBasisService.class);
  final FactorUploadBatchService batchService = mock(FactorUploadBatchService.class);
  final FactorUploadBatchMapper batchMapper = mock(FactorUploadBatchMapper.class);
  final List<PriceLinkedImportBasisSaveRequest> savedRequests = new ArrayList<>();
  final PriceLinkedType2ImportOrchestratorImpl orchestrator;

  private final PriceLinkedType2TextNormalizerImpl textNormalizer =
      new PriceLinkedType2TextNormalizerImpl();
  private final AtomicLong linkedItemId = new AtomicLong(7001L);

  PriceLinkedType2ImportTestSupport() {
    orchestrator = new PriceLinkedType2ImportOrchestratorImpl(
        parser,
        new PriceLinkedType2RowMatcherImpl(textNormalizer),
        new PriceLinkedType2MergedRowMapperImpl(textNormalizer),
        factorResolver,
        PriceLinkedType2FormulaTestSupport.converter(),
        new PriceLinkedType2TaxNormalizerImpl(),
        new PriceLinkedType2PriceReconcilerImpl(
            row -> java.util.Optional.of(new BigDecimal("0.13"))),
        factorUpsertService,
        importBasisService,
        batchService,
        batchMapper,
        textNormalizer);
    configureDefaults();
    useWorkbook(workbook(
        List.of(product(6, "MAT-1", "供应商甲", "$E$2+G6")),
        List.of(standard(6, "MAT-1", "供应商甲", "SUP-A")),
        List.of(copper()),
        List.of()));
  }

  PriceLinkedImportCommand command(String expectedHash) {
    return command(new byte[]{1, 2, 3, 4}, expectedHash, "COMMERCIAL", false);
  }

  PriceLinkedImportCommand command(
      byte[] bytes, String expectedHash, String businessUnitType, boolean overwriteManual) {
    return new PriceLinkedImportCommand(
        bytes,
        "type2.xls",
        "2026-07",
        businessUnitType,
        overwriteManual,
        "APPEND_ONLY",
        "2026-07-01",
        "KEEP_EXISTING",
        expectedHash);
  }

  String hash(byte[] bytes) {
    return PriceLinkedImportFileDigest.sha256(bytes);
  }

  void useWorkbook(PriceLinkedType2WorkbookParseResult workbook) {
    when(parser.parse(any(), anyString())).thenReturn(workbook);
    when(factorResolver.resolve(anyList(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<PriceLinkedType2FactorRow> rows = invocation.getArgument(0);
          return rows.stream().map(this::exactResolution).toList();
        });
  }

  PriceLinkedType2WorkbookParseResult workbook(
      List<PriceLinkedType2ProductRow> products,
      List<PriceLinkedType2StandardRow> standards,
      List<PriceLinkedType2FactorRow> factors,
      List<com.sanhua.marketingcost.dto.PriceLinkedType2ParseError> errors) {
    return new PriceLinkedType2WorkbookParseResult(
        "type2.xls",
        "Sheet1",
        5,
        "importdata1",
        1,
        factors,
        products,
        standards,
        errors);
  }

  PriceLinkedType2FactorRow copper() {
    return new PriceLinkedType2FactorRow(
        "Sheet1",
        2,
        "1",
        "长江1#电解铜含税平均价",
        "1#Cu",
        "平均价",
        new BigDecimal("90"),
        "元/公斤",
        "E2");
  }

  PriceLinkedType2ProductRow product(
      int row, String materialCode, String supplier, String formula) {
    return new PriceLinkedType2ProductRow(
        "Sheet1",
        row,
        materialCode,
        "测试产品-" + materialCode,
        "TYPE2-SPEC",
        "只",
        supplier,
        formula,
        "R" + row,
        new BigDecimal("113"),
        new BigDecimal("100"),
        List.of(
            cell("Sheet1", "E2", "1#Cu", "90", "元/公斤"),
            cell("Sheet1", "G" + row, "加工费", "23", "元/只")));
  }

  PriceLinkedType2StandardRow standard(
      int row, String materialCode, String supplier, String supplierCode) {
    return new PriceLinkedType2StandardRow(
        "importdata1",
        row,
        materialCode,
        supplier,
        supplierCode,
        List.of(
            textCell("importdata1", "A" + row, "组织", "股份"),
            textCell("importdata1", "B" + row, "来源", "财务导入"),
            textCell("importdata1", "C" + row, "采购分类", "采购件"),
            textCell("importdata1", "D" + row, "物料名称", "标准品名-" + materialCode),
            textCell("importdata1", "E" + row, "规格型号", "STD-SPEC"),
            textCell("importdata1", "F" + row, "单位", "只"),
            textCell("importdata1", "G" + row, "是否含税", "FALSE"),
            textCell("importdata1", "H" + row, "生效日期", "2026-07-01"),
            cell("importdata1", "I" + row, "加工费", "23", "元/只")));
  }

  PriceLinkedType2FactorIdentityResolution conflictResolution(
      PriceLinkedType2FactorRow row) {
    return new PriceLinkedType2FactorIdentityResolution(
        row,
        "COMMERCIAL",
        "2026-07",
        "AVG|1#CU",
        PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT,
        null,
        null,
        new BigDecimal("89"),
        191L,
        191L,
        true,
        List.of(),
        List.of(),
        "系统价格89与Excel价格90不一致");
  }

  private PriceLinkedType2FactorIdentityResolution exactResolution(
      PriceLinkedType2FactorRow row) {
    return new PriceLinkedType2FactorIdentityResolution(
        row,
        "COMMERCIAL",
        "2026-07",
        "AVG|" + row.getShortName().toUpperCase(),
        PriceLinkedType2FactorIdentityResolutionStatus.EXACT_MATCH,
        191L,
        191L,
        row.getPrice(),
        null,
        null,
        false,
        List.of(),
        List.of(),
        "精确复用");
  }

  private void configureDefaults() {
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(9001L);
    batch.setBatchNo("PLI2-10-TEST");
    when(batchService.createFactorBatch(any())).thenReturn(batch);
    when(factorUpsertService.upsert(
        any(), anyString(), anyString(), anyString(), any(), anyString()))
        .thenAnswer(invocation -> factorResult(invocation.getArgument(0)));
    doAnswer(invocation -> {
      PriceLinkedImportBasisSaveRequest request = invocation.getArgument(0);
      savedRequests.add(request);
      return new PriceLinkedImportBasisSaveResult(
          PriceLinkedImportBasisSaveResult.ACTION_CREATED,
          linkedItemId.getAndIncrement(),
          null,
          request.getFormulaConversion().getFactorReplacements().size());
    }).when(importBasisService).save(any(PriceLinkedImportBasisSaveRequest.class));
  }

  private FactorMonthlyPriceUpsertResult factorResult(
      PriceLinkedType2WorkbookParseResult workbook) {
    FactorMonthlyPriceUpsertResult result = new FactorMonthlyPriceUpsertResult();
    long monthlyId = 6191L;
    for (PriceLinkedType2FactorRow factor : workbook.getFactorRows()) {
      FactorMonthlyPriceUpsertResult.RowResult row =
          new FactorMonthlyPriceUpsertResult.RowResult();
      row.setSourceSheetName(factor.getSourceSheetName());
      row.setSourceRowNumber(factor.getSourceRowNumber());
      row.setFactorIdentityId(191L);
      row.setFactorMonthlyPriceId(monthlyId++);
      row.setFactorName(factor.getFactorName());
      row.setShortName(factor.getShortName());
      row.setPriceSource(factor.getPriceSource());
      row.setNewPrice(factor.getPrice());
      row.setIdentityAction("REUSE");
      row.setMonthlyPriceAction("NO_CHANGE");
      result.getRows().add(row);
      result.setIdentityReusedCount(result.getIdentityReusedCount() + 1);
      result.setMonthlyPriceUnchangedCount(result.getMonthlyPriceUnchangedCount() + 1);
    }
    return result;
  }

  private PriceLinkedType2CellSnapshot cell(
      String sheet, String ref, String header, String value, String unit) {
    return new PriceLinkedType2CellSnapshot(
        sheet, ref, header, value, new BigDecimal(value), null, unit);
  }

  private PriceLinkedType2CellSnapshot textCell(
      String sheet, String ref, String header, String value) {
    return new PriceLinkedType2CellSnapshot(
        sheet, ref, header, value, null, null, null);
  }
}

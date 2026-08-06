package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveRequest;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorIdentityResolution;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceReconcileResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.enums.PriceLinkedType2FactorIdentityResolutionStatus;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisService;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityResolver;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorMonthlyUpsertService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@DisplayName("PLI2-12 真实类型2 Excel 端到端")
class PriceLinkedType2RealExcelEndToEndTest {

  private static final Path TYPE2_SAMPLE = Path.of(
      "/Users/xiexicheng/Desktop/price/采购价表二次开发导入模板-股份251115联动价格导入类型2.xls");
  private static final String EXPECTED_SHA256 =
      "20e820214a826a0ab4cdce141e5d731ff1b930f2cbb182bd1522144289df215b";
  private static final String MONTH = "2026-07";
  private static final String BUSINESS_UNIT = "COMMERCIAL";
  private static final long CU_ID = 99101L;
  private static final long ZN_ID = 99102L;
  private static final BigDecimal CU_PRICE = new BigDecimal("90.000");
  private static final BigDecimal ZN_PRICE = new BigDecimal("21.680");
  private static final BigDecimal VAT_RATE = new BigDecimal("0.13");

  @Test
  @DisplayName("真实原文件以Sheet1为准匹配并确认写入全部50条公式")
  void realWorkbookUsesSheet1AsSourceAndConfirmsConvertibleRows() throws Exception {
    byte[] bytes = realBytes();
    WriteGuards fixture = orchestratorWithExistingCuAndZn();
    PriceLinkedImportCommand previewCommand = command(bytes, null);

    PriceLinkedType2ImportPreviewResponse preview =
        fixture.orchestrator().preview(previewCommand);

    assertThat(preview.getFileSha256()).isEqualTo(EXPECTED_SHA256);
    assertThat(preview.getTemplateType()).isEqualTo("TYPE2");
    assertThat(preview.getBusinessSheetName()).isEqualTo("Sheet1");
    assertThat(preview.getImportDataSheetName()).isEqualTo("ImportData");
    assertThat(preview.getFactorRowCount()).isEqualTo(2);
    assertThat(preview.getCanonicalFactorReusedCount()).isEqualTo(2);
    assertThat(preview.getCanonicalFactorCreatedCount()).isZero();
    assertThat(preview.getCanonicalFactorConflictCount()).isZero();
    assertThat(preview.getBusinessRowCount()).isEqualTo(50);
    assertThat(preview.getMatchedRowCount()).isEqualTo(50);
    assertThat(preview.getUnmatchedRowCount()).isZero();
    assertThat(preview.getDuplicateRowCount()).isZero();
    assertThat(preview.getRows()).hasSize(50);
    assertThat(preview.getRows())
        .filteredOn(row -> "MATCHED".equals(row.getMatchStatus()))
        .hasSize(40);
    assertThat(preview.getRows())
        .filteredOn(row -> "MATCHED_SUPPLIER_FALLBACK".equals(row.getMatchStatus()))
        .hasSize(10);
    assertThat(preview.getFormulaConvertedCount()).isEqualTo(50);
    assertThat(preview.getFormulaMismatchCount()).isZero();
    assertThat(preview.getErrors()).isEmpty();
    assertThat(preview.isCanConfirm()).isTrue();
    verifyNoInteractions(
        fixture.factorUpsertService(),
        fixture.importBasisService(),
        fixture.batchService(),
        fixture.batchMapper());

    PriceItemImportResponse confirmation =
        fixture.orchestrator().confirm(command(bytes, EXPECTED_SHA256));

    assertThat(confirmation.getImportStatus()).isEqualTo("SUCCESS");
    assertThat(confirmation.getLinkedCount()).isEqualTo(50);
    assertThat(confirmation.getCanonicalFactorCreatedCount()).isZero();
    assertThat(confirmation.getErrors()).isEmpty();
    assertThat(fixture.savedRequests()).hasSize(50).allSatisfy(request -> {
      assertThat(request.getMergedRow().getSupplierCode()).isNotBlank();
      assertThat(request.getCandidateVersion().getMaterialName())
          .isEqualTo(request.getMergedRow().getBusinessRow().getProductName());
      assertThat(request.getCandidateVersion().getSpecModel())
          .isEqualTo(request.getMergedRow().getBusinessRow().getSpecification());
    });
    assertThat(fixture.savedRequests())
        .filteredOn(request -> request.getMergedRow().isSupplierFallback())
        .isNotEmpty()
        .allSatisfy(request -> {
          assertThat(request.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 6, 1));
          assertThat(request.getCandidateVersion().getProcessFee()).isNull();
          assertThat(request.getCandidateVersion().getAgentFee()).isNull();
        });
    assertThat(fixture.savedRequests())
        .filteredOn(request -> !request.getMergedRow().isSupplierFallback())
        .isNotEmpty()
        .allSatisfy(request ->
            assertThat(request.getEffectiveDate()).isEqualTo(LocalDate.of(2025, 11, 1)));
  }

  @Test
  @DisplayName("真实业务公式50行全部通过含税和不含税对账")
  void realWorkbookFormulaAndTaxAuditIsComplete() throws Exception {
    PriceLinkedType2WorkbookParseResult workbook = parseRealWorkbook();
    List<PriceLinkedType2FormulaFactorBinding> bindings = realBindings(workbook);
    PriceLinkedType2FormulaConverterImpl converter =
        PriceLinkedType2FormulaTestSupport.converter();
    PriceLinkedType2TaxNormalizerImpl taxNormalizer =
        new PriceLinkedType2TaxNormalizerImpl();
    PriceLinkedType2PriceReconcilerImpl reconciler =
        new PriceLinkedType2PriceReconcilerImpl(row -> Optional.of(VAT_RATE));

    int convertedCount = 0;
    for (PriceLinkedType2ProductRow product : workbook.getProductRows()) {
      PriceLinkedType2FormulaConversionResult conversion =
          converter.convert(product, bindings);
      assertThat(conversion.isSuccess())
          .as("Sheet1第%d行料号%s", product.getSourceRowNumber(), product.getMaterialCode())
          .isTrue();

      convertedCount++;
      assertThat(conversion.getSourceFormula()).isEqualTo(product.getTaxIncludedFormula());
      assertThat(conversion.getConvertedFormula())
          .contains("[factor_identity_" + CU_ID + "]")
          .contains("[factor_identity_" + ZN_ID + "]")
          .doesNotMatch(".*(?:'[^']+'!)?\\$?[A-Z]{1,3}\\$?\\d+.*");
      assertThat(conversion.getInputSnapshots()).isNotEmpty();
      assertThat(conversion.getFactorReplacements()).hasSize(2);

      PriceLinkedType2TaxNormalizationResult tax =
          taxNormalizer.normalize("FALSE", conversion);
      PriceLinkedType2PriceReconcileResult reconcile =
          reconciler.reconcile(
              PriceLinkedType2FormulaTestSupport.merged(product, "FALSE"),
              conversion,
              tax,
              new BigDecimal("0.0001"));

      assertThat(tax.isSuccess()).isTrue();
      assertThat(tax.isTaxAdjustmentRequired()).isTrue();
      assertThat(tax.getNormalizedTaxIncluded()).isZero();
      assertThat(reconcile.isSuccess()).isTrue();
      assertThat(reconcile.getVatRate()).isEqualByComparingTo(VAT_RATE);
      assertThat(reconcile.getTaxIncludedComparison().passed()).isTrue();
      assertThat(reconcile.getTaxExcludedComparison().passed()).isTrue();
    }

    assertThat(convertedCount).isEqualTo(50);
  }

  @Test
  @DisplayName("Cu临时调价时真实文件50条公式全部动态刷新")
  void changingCuRefreshesEveryConvertibleCuFormula() throws Exception {
    PriceLinkedType2WorkbookParseResult workbook = parseRealWorkbook();
    List<PriceLinkedType2FormulaFactorBinding> bindings = realBindings(workbook);
    PriceLinkedType2FormulaConverterImpl converter =
        PriceLinkedType2FormulaTestSupport.converter();
    Map<String, BigDecimal> originalFactors = factorValues(CU_PRICE);
    Map<String, BigDecimal> adjustedFactors =
        factorValues(CU_PRICE.add(BigDecimal.ONE));

    List<PriceLinkedType2FormulaConversionResult> cuDependent =
        workbook.getProductRows().stream()
            .map(product -> converter.convert(product, bindings))
            .filter(PriceLinkedType2FormulaConversionResult::isSuccess)
            .filter(conversion -> conversion.getConvertedFormula()
                .contains("[factor_identity_" + CU_ID + "]"))
            .toList();

    assertThat(cuDependent).hasSize(50);
    assertThat(cuDependent).allSatisfy(conversion -> {
      BigDecimal original = ExpressionEvaluator.evaluate(
          conversion.getConvertedFormula(), originalFactors);
      BigDecimal adjusted = ExpressionEvaluator.evaluate(
          conversion.getConvertedFormula(), adjustedFactors);
      assertThat(adjusted).isNotEqualByComparingTo(original);
    });
  }

  private WriteGuards orchestratorWithExistingCuAndZn() {
    PriceLinkedType2FactorIdentityResolver factorResolver =
        mock(PriceLinkedType2FactorIdentityResolver.class);
    when(factorResolver.resolve(anyList(), eq(BUSINESS_UNIT), eq(MONTH)))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<PriceLinkedType2FactorRow> rows = invocation.getArgument(0);
          return rows.stream().map(this::existingResolution).toList();
        });
    PriceLinkedType2FactorMonthlyUpsertService factorUpsertService =
        mock(PriceLinkedType2FactorMonthlyUpsertService.class);
    PriceLinkedImportBasisService importBasisService =
        mock(PriceLinkedImportBasisService.class);
    FactorUploadBatchService batchService = mock(FactorUploadBatchService.class);
    FactorUploadBatchMapper batchMapper = mock(FactorUploadBatchMapper.class);
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(9001L);
    batch.setBatchNo("PLI2-12-REAL");
    when(batchService.createFactorBatch(any())).thenReturn(batch);
    when(factorUpsertService.upsert(
        any(), anyString(), anyString(), anyString(), any(), anyString()))
        .thenAnswer(invocation -> factorUpsertResult(invocation.getArgument(0)));
    List<PriceLinkedImportBasisSaveRequest> savedRequests = new ArrayList<>();
    AtomicLong linkedItemId = new AtomicLong(7001L);
    doAnswer(invocation -> {
      PriceLinkedImportBasisSaveRequest request = invocation.getArgument(0);
      savedRequests.add(request);
      return new PriceLinkedImportBasisSaveResult(
          PriceLinkedImportBasisSaveResult.ACTION_CREATED,
          linkedItemId.getAndIncrement(),
          null,
          request.getFormulaConversion().getFactorReplacements().size());
    }).when(importBasisService).save(any(PriceLinkedImportBasisSaveRequest.class));
    PriceLinkedType2TextNormalizerImpl textNormalizer =
        new PriceLinkedType2TextNormalizerImpl();
    PriceLinkedType2ImportOrchestratorImpl orchestrator =
        new PriceLinkedType2ImportOrchestratorImpl(
            new PriceLinkedType2WorkbookParserImpl(
                new PriceLinkedWorkbookTypeDetectorImpl()),
            new PriceLinkedType2RowMatcherImpl(textNormalizer),
            new PriceLinkedType2MergedRowMapperImpl(textNormalizer),
            factorResolver,
            PriceLinkedType2FormulaTestSupport.converter(),
            new PriceLinkedType2TaxNormalizerImpl(),
            new PriceLinkedType2PriceReconcilerImpl(row -> Optional.of(VAT_RATE)),
            factorUpsertService,
            importBasisService,
            batchService,
            batchMapper,
            textNormalizer);
    return new WriteGuards(
        orchestrator,
        factorUpsertService,
        importBasisService,
        batchService,
        batchMapper,
        savedRequests);
  }

  private FactorMonthlyPriceUpsertResult factorUpsertResult(
      PriceLinkedType2WorkbookParseResult workbook) {
    FactorMonthlyPriceUpsertResult result = new FactorMonthlyPriceUpsertResult();
    long monthlyId = 199101L;
    for (PriceLinkedType2FactorRow factor : workbook.getFactorRows()) {
      FactorMonthlyPriceUpsertResult.RowResult row =
          new FactorMonthlyPriceUpsertResult.RowResult();
      row.setSourceSheetName(factor.getSourceSheetName());
      row.setSourceRowNumber(factor.getSourceRowNumber());
      row.setFactorIdentityId(factorIdentityId(factor));
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

  private PriceLinkedType2FactorIdentityResolution existingResolution(
      PriceLinkedType2FactorRow row) {
    long identityId = factorIdentityId(row);
    return new PriceLinkedType2FactorIdentityResolution(
        row,
        BUSINESS_UNIT,
        MONTH,
        "AVG|" + row.getShortName().toUpperCase(),
        PriceLinkedType2FactorIdentityResolutionStatus.EXACT_MATCH,
        identityId,
        identityId,
        row.getPrice(),
        null,
        null,
        false,
        List.of(),
        List.of(),
        "隔离测试库精确复用现有身份");
  }

  private PriceLinkedImportCommand command(byte[] bytes, String expectedHash) {
    return new PriceLinkedImportCommand(
        bytes,
        TYPE2_SAMPLE.getFileName().toString(),
        MONTH,
        BUSINESS_UNIT,
        false,
        "APPEND_ONLY",
        null,
        "KEEP_EXISTING",
        expectedHash);
  }

  private PriceLinkedType2WorkbookParseResult parseRealWorkbook() throws Exception {
    return new PriceLinkedType2WorkbookParserImpl(
        new PriceLinkedWorkbookTypeDetectorImpl()).parse(
            new ByteArrayInputStream(realBytes()),
            TYPE2_SAMPLE.getFileName().toString());
  }

  private List<PriceLinkedType2FormulaFactorBinding> realBindings(
      PriceLinkedType2WorkbookParseResult workbook) {
    return workbook.getFactorRows().stream()
        .map(row -> new PriceLinkedType2FormulaFactorBinding(
            row.getSourceSheetName(),
            row.getPriceCellRef(),
            row.getShortName(),
            factorIdentityId(row),
            row.getPrice()))
        .toList();
  }

  private long factorIdentityId(PriceLinkedType2FactorRow row) {
    if ("1#Cu".equalsIgnoreCase(row.getShortName())) {
      assertThat(row.getPrice()).isEqualByComparingTo(CU_PRICE);
      return CU_ID;
    }
    assertThat(row.getShortName()).isEqualToIgnoringCase("1#Zn");
    assertThat(row.getPrice()).isEqualByComparingTo(ZN_PRICE);
    return ZN_ID;
  }

  private Map<String, BigDecimal> factorValues(BigDecimal cuPrice) {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    result.put("factor_identity_" + CU_ID, cuPrice);
    result.put("factor_identity_" + ZN_ID, ZN_PRICE);
    return result;
  }

  private byte[] realBytes() throws Exception {
    assertThat(Files.exists(TYPE2_SAMPLE)).as("真实类型2样例存在").isTrue();
    byte[] bytes = Files.readAllBytes(TYPE2_SAMPLE);
    assertThat(PriceLinkedImportFileDigest.sha256(bytes)).isEqualTo(EXPECTED_SHA256);
    return bytes;
  }

  private record WriteGuards(
      PriceLinkedType2ImportOrchestratorImpl orchestrator,
      PriceLinkedType2FactorMonthlyUpsertService factorUpsertService,
      PriceLinkedImportBasisService importBasisService,
      FactorUploadBatchService batchService,
      FactorUploadBatchMapper batchMapper,
      List<PriceLinkedImportBasisSaveRequest> savedRequests) {
  }
}

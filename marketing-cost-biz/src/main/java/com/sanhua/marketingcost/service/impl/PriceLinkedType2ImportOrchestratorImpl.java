package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchCreateRequest;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveRequest;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorIdentityResolution;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceReconcileResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchSummary;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.enums.FactorPriceConflictStrategy;
import com.sanhua.marketingcost.enums.PriceLinkedType2FactorIdentityResolutionStatus;
import com.sanhua.marketingcost.enums.PriceLinkedType2RowMatchStatus;
import com.sanhua.marketingcost.enums.PriceLinkedWorkbookType;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisService;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityResolver;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorMonthlyUpsertService;
import com.sanhua.marketingcost.service.PriceLinkedType2FormulaConverter;
import com.sanhua.marketingcost.service.PriceLinkedType2ImportOrchestrator;
import com.sanhua.marketingcost.service.PriceLinkedType2MergedRowMapper;
import com.sanhua.marketingcost.service.PriceLinkedType2PriceReconciler;
import com.sanhua.marketingcost.service.PriceLinkedType2RowMatcher;
import com.sanhua.marketingcost.service.PriceLinkedType2TaxNormalizer;
import com.sanhua.marketingcost.service.PriceLinkedType2TextNormalizer;
import com.sanhua.marketingcost.service.PriceLinkedType2WorkbookParser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedType2ImportOrchestratorImpl
    implements PriceLinkedType2ImportOrchestrator {

  private static final long PREVIEW_ID_BASE = 9_000_000_000_000_000L;
  private static final String TEMPLATE_TYPE = "TYPE2";
  private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
      DateTimeFormatter.ISO_LOCAL_DATE,
      DateTimeFormatter.ofPattern("yyyy/M/d"),
      DateTimeFormatter.ofPattern("yyyy.M.d"),
      DateTimeFormatter.ofPattern("M/d/yy"));

  private final PriceLinkedType2WorkbookParser workbookParser;
  private final PriceLinkedType2RowMatcher rowMatcher;
  private final PriceLinkedType2MergedRowMapper mergedRowMapper;
  private final PriceLinkedType2FactorIdentityResolver factorResolver;
  private final PriceLinkedType2FormulaConverter formulaConverter;
  private final PriceLinkedType2TaxNormalizer taxNormalizer;
  private final PriceLinkedType2PriceReconciler priceReconciler;
  private final PriceLinkedType2FactorMonthlyUpsertService factorUpsertService;
  private final PriceLinkedImportBasisService importBasisService;
  private final FactorUploadBatchService factorUploadBatchService;
  private final FactorUploadBatchMapper factorUploadBatchMapper;
  private final PriceLinkedType2TextNormalizer textNormalizer;

  public PriceLinkedType2ImportOrchestratorImpl(
      PriceLinkedType2WorkbookParser workbookParser,
      PriceLinkedType2RowMatcher rowMatcher,
      PriceLinkedType2MergedRowMapper mergedRowMapper,
      PriceLinkedType2FactorIdentityResolver factorResolver,
      PriceLinkedType2FormulaConverter formulaConverter,
      PriceLinkedType2TaxNormalizer taxNormalizer,
      PriceLinkedType2PriceReconciler priceReconciler,
      PriceLinkedType2FactorMonthlyUpsertService factorUpsertService,
      PriceLinkedImportBasisService importBasisService,
      FactorUploadBatchService factorUploadBatchService,
      FactorUploadBatchMapper factorUploadBatchMapper,
      PriceLinkedType2TextNormalizer textNormalizer) {
    this.workbookParser = workbookParser;
    this.rowMatcher = rowMatcher;
    this.mergedRowMapper = mergedRowMapper;
    this.factorResolver = factorResolver;
    this.formulaConverter = formulaConverter;
    this.taxNormalizer = taxNormalizer;
    this.priceReconciler = priceReconciler;
    this.factorUpsertService = factorUpsertService;
    this.importBasisService = importBasisService;
    this.factorUploadBatchService = factorUploadBatchService;
    this.factorUploadBatchMapper = factorUploadBatchMapper;
    this.textNormalizer = textNormalizer;
  }

  @Override
  @Transactional(readOnly = true)
  public PriceLinkedType2ImportPreviewResponse preview(PriceLinkedImportCommand command) {
    return buildPlan(command).preview();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceItemImportResponse confirm(PriceLinkedImportCommand command) {
    ImportPlan plan = buildPlan(command);
    requireMatchingHash(command.getExpectedPreviewSha256(), plan.preview().getFileSha256());
    PriceItemImportResponse response = responseFromPreview(plan.preview(), command);
    List<RowPlan> importableRows = plan.rows().stream().filter(RowPlan::importable).toList();
    if (importableRows.isEmpty()) {
      response.setImportStatus("FAILED");
      response.setSkipped(Math.max(1, response.getErrors().size()));
      return response;
    }

    FactorUploadBatch batch = factorUploadBatchService.createFactorBatch(
        batchRequest(command, plan));
    if (batch == null || batch.getId() == null) {
      throw new IllegalStateException("类型2导入批次创建后未返回主键");
    }
    response.setFactorUploadBatchId(batch.getId());
    response.setBatchId(String.valueOf(batch.getId()));

    PriceLinkedType2WorkbookParseResult eligibleFactors =
        eligibleFactorWorkbook(plan, importableRows);
    FactorMonthlyPriceUpsertResult factorResult = new FactorMonthlyPriceUpsertResult();
    if (!eligibleFactors.getFactorRows().isEmpty()) {
      factorResult = factorUpsertService.upsert(
          eligibleFactors,
          plan.pricingMonth().toString(),
          plan.businessUnitType(),
          currentOperator(),
          batch.getId(),
          normalizeConflictStrategy(command.getFactorPriceConflictStrategy()));
      if (!factorResult.getErrors().isEmpty()) {
        throw new IllegalStateException(
            "影响因素在确认写入前发生变化：" + factorResult.getErrors().getFirst().getMessage());
      }
    }
    applyFactorResult(response, factorResult);

    List<PriceLinkedType2FormulaFactorBinding> actualBindings =
        actualFactorBindings(eligibleFactors.getFactorRows(), factorResult.getRows());
    Map<Long, Long> monthlyPriceIds = factorResult.getRows().stream()
        .filter(row -> row.getFactorIdentityId() != null)
        .collect(Collectors.toMap(
            FactorMonthlyPriceUpsertResult.RowResult::getFactorIdentityId,
            FactorMonthlyPriceUpsertResult.RowResult::getFactorMonthlyPriceId,
            (left, right) -> left,
            LinkedHashMap::new));

    for (RowPlan rowPlan : importableRows) {
      PriceLinkedType2FormulaConversionResult conversion =
          formulaConverter.convert(rowPlan.mergedRow().getBusinessRow(), actualBindings);
      PriceLinkedType2TaxNormalizationResult tax =
          taxNormalizer.normalize(rowPlan.mergedRow().getTaxIncludedText(), conversion);
      PriceLinkedType2PriceReconcileResult reconcile =
          priceReconciler.reconcile(rowPlan.mergedRow(), conversion, tax, null);
      if (!conversion.isSuccess() || !tax.isSuccess() || !reconcile.isSuccess()) {
        throw new IllegalStateException(
            "类型2确认写入前后校验结果不一致，Sheet="
                + rowPlan.mergedRow().getBusinessRow().getSourceSheetName()
                + "，行=" + rowPlan.mergedRow().getBusinessRow().getSourceRowNumber());
      }
      PriceLinkedImportBasisSaveResult saved = importBasisService.save(
          new PriceLinkedImportBasisSaveRequest(
              candidate(rowPlan.mergedRow(), plan.businessUnitType()),
              batch.getId(),
              rowPlan.mergedRow(),
              conversion,
              tax,
              reconcile,
              rowPlan.effectiveDate(),
              monthlyPriceIds));
      applyLinkedResult(response, saved);
    }

    response.setSkipped(plan.rows().size() - importableRows.size()
        + plan.matchSummary().getBlockedCount()
        + plan.parseResult().getErrors().size());
    response.setImportStatus(response.getErrors().isEmpty() ? "SUCCESS" : "PARTIAL");
    finishBatch(batch, plan, response);
    return response;
  }

  private ImportPlan buildPlan(PriceLinkedImportCommand command) {
    byte[] bytes = requireBytes(command);
    YearMonth pricingMonth = requireMonth(command.getPricingMonth());
    String businessUnitType = resolveBusinessUnitType(command.getBusinessUnitType());
    PriceLinkedType2WorkbookParseResult parsed = workbookParser.parse(
        new ByteArrayInputStream(bytes), sourceFileName(command.getSourceFileName()));
    PriceLinkedType2RowMatchSummary matches = rowMatcher.match(parsed);
    List<PriceLinkedType2MergedRow> mergedRows = mergedRowMapper.map(matches, pricingMonth);
    List<PriceLinkedType2FactorIdentityResolution> resolutions =
        factorResolver.resolve(parsed.getFactorRows(), businessUnitType, pricingMonth.toString());
    String strategy = normalizeConflictStrategy(command.getFactorPriceConflictStrategy());
    Set<String> duplicatePriceConflictKeys = duplicatePriceConflictKeys(resolutions);
    List<FactorPlan> factorPlans = factorPlans(
        resolutions, strategy, duplicatePriceConflictKeys);
    List<PriceLinkedType2FormulaFactorBinding> previewBindings = factorPlans.stream()
        .filter(plan -> !plan.blocked() && plan.previewIdentityId() != null)
        .map(plan -> new PriceLinkedType2FormulaFactorBinding(
            plan.row().getSourceSheetName(),
            plan.row().getPriceCellRef(),
            plan.row().getShortName(),
            plan.previewIdentityId(),
            plan.row().getPrice()))
        .toList();
    Map<String, FactorPlan> factorByCell = factorPlans.stream()
        .collect(Collectors.toMap(
            plan -> cellKey(plan.row().getSourceSheetName(), plan.row().getPriceCellRef()),
            Function.identity(),
            (left, right) -> left,
            LinkedHashMap::new));

    List<RowPlan> rowPlans = new ArrayList<>();
    for (PriceLinkedType2MergedRow merged : mergedRows) {
      rowPlans.add(planRow(
          merged, previewBindings, factorByCell, command.getFormulaEffectiveDate(), pricingMonth));
    }
    PriceLinkedType2ImportPreviewResponse preview =
        preview(command, parsed, matches, factorPlans, rowPlans, duplicatePriceConflictKeys);
    return new ImportPlan(
        parsed,
        matches,
        factorPlans,
        List.copyOf(rowPlans),
        preview,
        pricingMonth,
        businessUnitType);
  }

  private RowPlan planRow(
      PriceLinkedType2MergedRow merged,
      List<PriceLinkedType2FormulaFactorBinding> previewBindings,
      Map<String, FactorPlan> factorByCell,
      String requestedEffectiveDate,
      YearMonth pricingMonth) {
    PriceLinkedType2FormulaConversionResult conversion =
        formulaConverter.convert(merged.getBusinessRow(), previewBindings);
    FactorPlan blockedDependency = conversion.getErrors().stream()
        .map(error -> factorByCell.get(cellKey(error.sheetName(), error.cellRef())))
        .filter(Objects::nonNull)
        .filter(FactorPlan::blocked)
        .findFirst()
        .orElse(null);
    PriceLinkedType2TaxNormalizationResult tax =
        taxNormalizer.normalize(merged.getTaxIncludedText(), conversion);
    PriceLinkedType2PriceReconcileResult reconcile =
        priceReconciler.reconcile(merged, conversion, tax, null);
    DateResolution date = effectiveDate(
        requestedEffectiveDate, merged.getEffectiveDateText(), pricingMonth);

    String errorCode = null;
    String message = null;
    if (blockedDependency != null) {
      errorCode = "FACTOR_DEPENDENCY_BLOCKED";
      message = "公式依赖的影响因素 " + blockedDependency.row().getShortName()
          + " 存在冲突：" + blockedDependency.resolution().getMessage();
    } else if (!conversion.isSuccess()) {
      errorCode = conversion.getErrors().getFirst().code();
      message = conversion.getErrors().stream()
          .map(error -> error.message())
          .collect(Collectors.joining("；"));
    } else if (!tax.isSuccess()) {
      errorCode = tax.getErrors().getFirst().code();
      message = tax.getErrors().stream()
          .map(issue -> issue.message())
          .collect(Collectors.joining("；"));
    } else if (!reconcile.isSuccess()) {
      errorCode = reconcile.getErrors().getFirst().code();
      message = reconcile.getErrors().stream()
          .map(issue -> issue.message())
          .collect(Collectors.joining("；"));
    } else if (date.error() != null) {
      errorCode = "EFFECTIVE_DATE_INVALID";
      message = date.error();
    }
    return new RowPlan(
        merged,
        conversion,
        tax,
        reconcile,
        date.value(),
        errorCode == null,
        errorCode,
        message);
  }

  private List<FactorPlan> factorPlans(
      List<PriceLinkedType2FactorIdentityResolution> resolutions,
      String conflictStrategy,
      Set<String> duplicatePriceConflictKeys) {
    List<FactorPlan> result = new ArrayList<>();
    long previewId = PREVIEW_ID_BASE;
    for (PriceLinkedType2FactorIdentityResolution resolution : resolutions) {
      PriceLinkedType2FactorIdentityResolutionStatus status = resolution.getStatus();
      boolean duplicateConflict =
          duplicatePriceConflictKeys.contains(resolution.getCanonicalFactorKey());
      boolean overwriteConflict = status
              == PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT
          && FactorPriceConflictStrategy.OVERWRITE.getCode().equals(conflictStrategy)
          && resolution.isOverwriteAllowed()
          && resolution.getRecommendedFactorIdentityId() != null;
      boolean blocked = duplicateConflict
          || resolution.isBlocked() && !overwriteConflict;
      boolean previewIdentity = status
          == PriceLinkedType2FactorIdentityResolutionStatus.CREATE_REQUIRED;
      Long identityId;
      if (previewIdentity) {
        identityId = previewId++;
      } else if (overwriteConflict) {
        identityId = resolution.getRecommendedFactorIdentityId();
      } else {
        identityId = resolution.getSelectedFactorIdentityId();
      }
      result.add(new FactorPlan(
          resolution.getSourceRow(),
          resolution,
          blocked,
          previewIdentity,
          blocked ? null : identityId));
    }
    return List.copyOf(result);
  }

  private Set<String> duplicatePriceConflictKeys(
      List<PriceLinkedType2FactorIdentityResolution> resolutions) {
    Map<String, Set<String>> prices = new LinkedHashMap<>();
    for (PriceLinkedType2FactorIdentityResolution resolution : resolutions) {
      BigDecimal price = resolution.getSourceRow() == null
          ? null : resolution.getSourceRow().getPrice();
      prices.computeIfAbsent(resolution.getCanonicalFactorKey(), ignored -> new LinkedHashSet<>())
          .add(price == null ? "" : price.stripTrailingZeros().toPlainString());
    }
    return prices.entrySet().stream()
        .filter(entry -> StringUtils.hasText(entry.getKey()) && entry.getValue().size() > 1)
        .map(Map.Entry::getKey)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private PriceLinkedType2ImportPreviewResponse preview(
      PriceLinkedImportCommand command,
      PriceLinkedType2WorkbookParseResult parsed,
      PriceLinkedType2RowMatchSummary matches,
      List<FactorPlan> factors,
      List<RowPlan> rows,
      Set<String> duplicatePriceConflictKeys) {
    PriceLinkedType2ImportPreviewResponse response =
        new PriceLinkedType2ImportPreviewResponse();
    response.setFileSha256(PriceLinkedImportFileDigest.sha256(command.getFileBytes()));
    response.setTemplateType(PriceLinkedWorkbookType.TYPE2.name());
    response.setBusinessSheetName(parsed.getBusinessSheetName());
    response.setImportDataSheetName(parsed.getStandardSheetName());
    response.setFactorRowCount(parsed.getFactorRows().size());
    response.setBusinessRowCount(parsed.getProductRows().size());
    response.setMatchedRowCount(matches.getMatchedCount());
    response.setUnmatchedRowCount(matches.getBlockedCount());
    response.setDuplicateRowCount((int) matches.getBlockedResults().stream()
        .filter(result -> result.getStatus().name().contains("DUPLICATE"))
        .count());
    response.setFormulaConvertedCount((int) rows.stream()
        .filter(row -> row.conversion().isSuccess())
        .count());
    response.setFormulaMismatchCount((int) rows.stream()
        .filter(row -> !row.importable())
        .count());
    response.setTaxModeWarningCount(rows.stream()
        .mapToInt(row -> row.tax().getWarnings().size() + row.reconcile().getWarnings().size())
        .sum());
    response.setCanonicalFactorReusedCount((int) factors.stream()
        .filter(plan -> !plan.previewIdentity() && !plan.blocked())
        .count());
    response.setCanonicalFactorCreatedCount((int) factors.stream()
        .filter(FactorPlan::previewIdentity)
        .filter(plan -> !plan.blocked())
        .count());
    response.setCanonicalFactorConflictCount(
        (int) factors.stream().filter(FactorPlan::blocked).count()
            + duplicatePriceConflictKeys.size());

    for (var error : parsed.getErrors()) {
      response.getErrors().add(error(
          error.getRowNumber(),
          null,
          null,
          error.getSheetName(),
          "PARSE",
          "TYPE2_PARSE_ERROR",
          error.getMessage()));
    }
    for (PriceLinkedType2RowMatchResult blocked : matches.getBlockedResults()) {
      response.getRows().add(matchPreview(blocked));
      response.getErrors().add(matchError(blocked));
    }
    for (FactorPlan factor : factors) {
      response.getFactors().add(factorPreview(factor, duplicatePriceConflictKeys));
      if (factor.blocked()) {
        response.getErrors().add(error(
            factor.row() == null ? null : factor.row().getSourceRowNumber(),
            null,
            null,
            factor.row() == null ? null : factor.row().getSourceSheetName(),
            "FACTOR_RESOLUTION",
            factor.resolution().getStatus().name(),
            duplicatePriceConflictKeys.contains(factor.resolution().getCanonicalFactorKey())
                ? "同一统一因素在Excel中存在不同价格："
                    + factor.resolution().getCanonicalFactorKey()
                : factor.resolution().getMessage()));
      }
    }
    for (RowPlan row : rows) {
      response.getRows().add(rowPreview(row));
      if (!row.importable()) {
        response.getErrors().add(error(
            row.mergedRow().getBusinessRow().getSourceRowNumber(),
            row.mergedRow().getMaterialCode(),
            row.mergedRow().getSupplierCode(),
            row.mergedRow().getBusinessRow().getSourceSheetName(),
            "ROW_VALIDATION",
            row.errorCode(),
            row.message()));
      }
    }
    response.setCanConfirm(rows.stream().anyMatch(RowPlan::importable));
    return response;
  }

  private PriceLinkedType2ImportPreviewResponse.FactorPreview factorPreview(
      FactorPlan plan, Set<String> duplicatePriceConflictKeys) {
    PriceLinkedType2ImportPreviewResponse.FactorPreview preview =
        new PriceLinkedType2ImportPreviewResponse.FactorPreview();
    PriceLinkedType2FactorRow row = plan.row();
    preview.setSourceSheetName(row == null ? null : row.getSourceSheetName());
    preview.setSourceRowNumber(row == null ? null : row.getSourceRowNumber());
    preview.setOriginalName(row == null ? null : row.getFactorName());
    preview.setShortName(row == null ? null : row.getShortName());
    preview.setPriceSource(row == null ? null : row.getPriceSource());
    preview.setImportedPrice(row == null ? null : row.getPrice());
    preview.setStatus(duplicatePriceConflictKeys.contains(plan.resolution().getCanonicalFactorKey())
        ? "EXCEL_PRICE_CONFLICT" : plan.resolution().getStatus().name());
    preview.setFactorIdentityId(plan.previewIdentityId());
    preview.setPreviewIdentity(plan.previewIdentity());
    preview.setMessage(plan.resolution().getMessage());
    return preview;
  }

  private PriceLinkedType2ImportPreviewResponse.RowPreview rowPreview(RowPlan plan) {
    PriceLinkedType2ImportPreviewResponse.RowPreview preview =
        new PriceLinkedType2ImportPreviewResponse.RowPreview();
    PriceLinkedType2MergedRow merged = plan.mergedRow();
    preview.setSourceSheetName(merged.getBusinessRow().getSourceSheetName());
    preview.setSourceRowNumber(merged.getBusinessRow().getSourceRowNumber());
    preview.setMaterialCode(merged.getMaterialCode());
    preview.setSupplierName(merged.getSupplierName());
    preview.setSupplierCode(merged.getSupplierCode());
    preview.setMatchStatus(merged.isSupplierFallback()
        ? PriceLinkedType2RowMatchStatus.MATCHED_SUPPLIER_FALLBACK.name()
        : PriceLinkedType2RowMatchStatus.MATCHED.name());
    preview.setImportable(plan.importable());
    preview.setSourceFormula(plan.conversion().getSourceFormula());
    preview.setSystemFormula(plan.conversion().getConvertedFormula());
    preview.setTaxIncluded(plan.tax().getNormalizedTaxIncluded());
    preview.setExcelTaxIncludedPrice(merged.getBusinessRow().getTaxIncludedPrice());
    preview.setExcelTaxExcludedPrice(merged.getBusinessRow().getTaxExcludedPrice());
    if (plan.reconcile().getTaxIncludedComparison() != null) {
      preview.setSystemTaxIncludedPrice(
          plan.reconcile().getTaxIncludedComparison().systemPrice());
      preview.setTaxIncludedDifference(
          plan.reconcile().getTaxIncludedComparison().absoluteDifference());
    }
    if (plan.reconcile().getTaxExcludedComparison() != null) {
      preview.setSystemTaxExcludedPrice(
          plan.reconcile().getTaxExcludedComparison().systemPrice());
      preview.setTaxExcludedDifference(
          plan.reconcile().getTaxExcludedComparison().absoluteDifference());
    }
    preview.setMessage(plan.importable()
        ? merged.isSupplierFallback()
            ? "按同供应商补齐ImportData公共字段，预检通过"
            : "按料号匹配ImportData，预检通过"
        : plan.message());
    return preview;
  }

  private PriceLinkedType2ImportPreviewResponse.RowPreview matchPreview(
      PriceLinkedType2RowMatchResult match) {
    PriceLinkedType2ImportPreviewResponse.RowPreview preview =
        new PriceLinkedType2ImportPreviewResponse.RowPreview();
    if (!match.getBusinessRows().isEmpty()) {
      var row = match.getBusinessRows().getFirst();
      preview.setSourceSheetName(row.getSourceSheetName());
      preview.setSourceRowNumber(row.getSourceRowNumber());
      preview.setMaterialCode(row.getMaterialCode());
      preview.setSupplierName(row.getSupplierName());
      preview.setSourceFormula(row.getTaxIncludedFormula());
    } else if (!match.getStandardRows().isEmpty()) {
      var row = match.getStandardRows().getFirst();
      preview.setSourceSheetName(row.getSourceSheetName());
      preview.setSourceRowNumber(row.getSourceRowNumber());
      preview.setMaterialCode(row.getMaterialCode());
      preview.setSupplierName(row.getSupplierName());
      preview.setSupplierCode(row.getSupplierCode());
    }
    preview.setMatchStatus(match.getStatus().name());
    preview.setImportable(false);
    preview.setMessage(match.getMessage());
    return preview;
  }

  private PriceItemImportResponse.ErrorRow matchError(
      PriceLinkedType2RowMatchResult match) {
    PriceLinkedType2ImportPreviewResponse.RowPreview preview = matchPreview(match);
    return error(
        preview.getSourceRowNumber(),
        preview.getMaterialCode(),
        preview.getSupplierCode(),
        preview.getSourceSheetName(),
        "ROW_MATCH",
        match.getStatus().name(),
        match.getMessage());
  }

  private PriceItemImportResponse responseFromPreview(
      PriceLinkedType2ImportPreviewResponse preview,
      PriceLinkedImportCommand command) {
    PriceItemImportResponse response = new PriceItemImportResponse();
    response.setFileSha256(preview.getFileSha256());
    response.setTemplateType(TEMPLATE_TYPE);
    response.setBusinessSheetName(preview.getBusinessSheetName());
    response.setImportDataSheetName(preview.getImportDataSheetName());
    response.setBusinessRowCount(preview.getBusinessRowCount());
    response.setMatchedRowCount(preview.getMatchedRowCount());
    response.setUnmatchedRowCount(preview.getUnmatchedRowCount());
    response.setDuplicateRowCount(preview.getDuplicateRowCount());
    response.setFormulaConvertedCount(preview.getFormulaConvertedCount());
    response.setFormulaMismatchCount(preview.getFormulaMismatchCount());
    response.setTaxModeWarningCount(preview.getTaxModeWarningCount());
    response.setCanonicalFactorReusedCount(preview.getCanonicalFactorReusedCount());
    response.setCanonicalFactorCreatedCount(preview.getCanonicalFactorCreatedCount());
    response.setCanonicalFactorConflictCount(preview.getCanonicalFactorConflictCount());
    response.setEffectiveStrategy(defaultText(command.getEffectiveStrategy(), "APPEND_ONLY"));
    response.setImportPurpose("MONTHLY_LINKED_TYPE2");
    response.setFormulaEffectiveDate(command.getFormulaEffectiveDate());
    response.setFactorPriceConflictStrategy(
        normalizeConflictStrategy(command.getFactorPriceConflictStrategy()));
    response.getErrors().addAll(preview.getErrors());
    return response;
  }

  private FactorUploadBatchCreateRequest batchRequest(
      PriceLinkedImportCommand command, ImportPlan plan) {
    FactorUploadBatchCreateRequest request = new FactorUploadBatchCreateRequest();
    request.setPriceMonth(plan.pricingMonth().toString());
    request.setBusinessUnitType(plan.businessUnitType());
    request.setFileName(sourceFileName(command.getSourceFileName()));
    request.setFileSha256(plan.preview().getFileSha256());
    request.setUploadedBy(currentOperator());
    request.setImportType("PRICE_LINKED_TYPE2");
    request.setImportPurpose("MONTHLY_LINKED_TYPE2");
    request.setEffectiveStrategy(defaultText(command.getEffectiveStrategy(), "APPEND_ONLY"));
    return request;
  }

  private PriceLinkedType2WorkbookParseResult eligibleFactorWorkbook(
      ImportPlan plan, List<RowPlan> rows) {
    Set<String> referencedCells = rows.stream()
        .flatMap(row -> row.conversion().getFactorReplacements().stream())
        .map(reference -> cellKey(reference.sheetName(), reference.cellRef()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    List<PriceLinkedType2FactorRow> factors = plan.factors().stream()
        .filter(factor -> !factor.blocked())
        .map(FactorPlan::row)
        .filter(Objects::nonNull)
        .filter(row -> referencedCells.contains(cellKey(row.getSourceSheetName(), row.getPriceCellRef())))
        .toList();
    return new PriceLinkedType2WorkbookParseResult(
        plan.parseResult().getSourceFileName(),
        plan.parseResult().getBusinessSheetName(),
        plan.parseResult().getBusinessHeaderRowNumber(),
        plan.parseResult().getStandardSheetName(),
        plan.parseResult().getStandardHeaderRowNumber(),
        factors,
        plan.parseResult().getProductRows(),
        plan.parseResult().getStandardRows(),
        List.of());
  }

  private List<PriceLinkedType2FormulaFactorBinding> actualFactorBindings(
      List<PriceLinkedType2FactorRow> sourceRows,
      List<FactorMonthlyPriceUpsertResult.RowResult> results) {
    Map<String, PriceLinkedType2FactorRow> sourceByRow = sourceRows.stream()
        .collect(Collectors.toMap(
            row -> rowKey(row.getSourceSheetName(), row.getSourceRowNumber()),
            Function.identity(),
            (left, right) -> left,
            LinkedHashMap::new));
    List<PriceLinkedType2FormulaFactorBinding> bindings = new ArrayList<>();
    for (FactorMonthlyPriceUpsertResult.RowResult result : results) {
      PriceLinkedType2FactorRow source = sourceByRow.get(
          rowKey(result.getSourceSheetName(), result.getSourceRowNumber()));
      if (source == null || result.getFactorIdentityId() == null) {
        throw new IllegalStateException("影响因素写入结果无法对应原Excel来源行");
      }
      bindings.add(new PriceLinkedType2FormulaFactorBinding(
          source.getSourceSheetName(),
          source.getPriceCellRef(),
          source.getShortName(),
          result.getFactorIdentityId(),
          source.getPrice()));
    }
    return List.copyOf(bindings);
  }

  private PriceLinkedItem candidate(
      PriceLinkedType2MergedRow merged, String businessUnitType) {
    PriceLinkedItem candidate = new PriceLinkedItem();
    PriceLinkedType2StandardRow standard = merged.getStandardRow();
    candidate.setBusinessUnitType(businessUnitType);
    candidate.setPricingMonth(merged.getPricingMonth());
    candidate.setOrgCode(field(standard, "组织", "业务单元", "事业部"));
    candidate.setSourceName(merged.getSource());
    candidate.setSupplierName(merged.getSupplierName());
    candidate.setSupplierCode(merged.getSupplierCode());
    candidate.setPurchaseClass(merged.getMaterialAttribute());
    candidate.setMaterialName(merged.getBusinessRow().getProductName());
    candidate.setMaterialCode(merged.getMaterialCode());
    candidate.setSpecModel(merged.getBusinessRow().getSpecification());
    candidate.setUnit(merged.getBusinessRow().getUnit());
    if (!merged.isSupplierFallback()) {
      candidate.setBlankWeight(number(standard, "下料重", "毛重"));
      candidate.setNetWeight(number(standard, "净重"));
      candidate.setProcessFee(number(standard, "加工费"));
      candidate.setAgentFee(number(standard, "代理费"));
      candidate.setManualPrice(number(standard, "单价"));
    }
    candidate.setOrderType(defaultText(field(standard, "订单类别"), "联动"));
    return candidate;
  }

  private String field(PriceLinkedType2StandardRow row, String... aliases) {
    PriceLinkedType2CellSnapshot cell = cell(row, aliases);
    return cell == null ? null : cell.getDisplayValue();
  }

  private BigDecimal number(PriceLinkedType2StandardRow row, String... aliases) {
    PriceLinkedType2CellSnapshot cell = cell(row, aliases);
    return cell == null ? null : cell.getNumericValue();
  }

  private PriceLinkedType2CellSnapshot cell(
      PriceLinkedType2StandardRow row, String... aliases) {
    if (row == null) {
      return null;
    }
    Set<String> normalizedAliases = java.util.Arrays.stream(aliases)
        .map(textNormalizer::normalizeHeader)
        .collect(Collectors.toSet());
    return row.getCells().stream()
        .filter(cell -> normalizedAliases.contains(
            textNormalizer.normalizeHeader(cell.getHeader())))
        .findFirst()
        .orElse(null);
  }

  private void applyFactorResult(
      PriceItemImportResponse response, FactorMonthlyPriceUpsertResult factorResult) {
    response.setFactorRecognizedCount(factorResult.getRows().size());
    response.setMonthlyPriceCreatedCount(factorResult.getMonthlyPriceCreatedCount());
    response.setMonthlyPriceUpdatedCount(factorResult.getMonthlyPriceUpdatedCount());
    response.setMonthlyPriceUnchangedCount(factorResult.getMonthlyPriceUnchangedCount());
    response.setMonthlyPriceSkippedCount(factorResult.getMonthlyPriceSkippedCount());
    response.setMonthlyPriceConflictCount(factorResult.getMonthlyPriceConflictCount());
    response.setMonthlyPriceOverwriteCount(factorResult.getMonthlyPriceOverwriteCount());
    response.setCanonicalFactorCreatedCount(factorResult.getIdentityCreatedCount());
    response.setCanonicalFactorReusedCount(factorResult.getIdentityReusedCount());
    response.getFactorRows().addAll(factorResult.getRows());
  }

  private void applyLinkedResult(
      PriceItemImportResponse response, PriceLinkedImportBasisSaveResult saved) {
    if (PriceLinkedImportBasisSaveResult.ACTION_DUPLICATE_SKIPPED.equals(saved.action())) {
      response.setLinkedSkippedCount(response.getLinkedSkippedCount() + 1);
      response.setLinkedUnchangedSkippedCount(response.getLinkedUnchangedSkippedCount() + 1);
      return;
    }
    response.setLinkedCount(response.getLinkedCount() + 1);
    response.setLinkedCreatedCount(response.getLinkedCreatedCount() + 1);
    response.setLinkedVersionCreatedCount(response.getLinkedVersionCreatedCount() + 1);
    response.setAutoBindingCount(response.getAutoBindingCount() + saved.factorBindingCount());
    if (saved.previousVersionId() != null) {
      response.setLinkedExpiredCount(response.getLinkedExpiredCount() + 1);
    }
  }

  private void finishBatch(
      FactorUploadBatch batch, ImportPlan plan, PriceItemImportResponse response) {
    batch.setFactorSheetCount(plan.parseResult().getFactorRows().isEmpty() ? 0 : 1);
    batch.setLinkedSheetCount(2);
    batch.setFactorRowCount(response.getFactorRecognizedCount());
    batch.setLinkedRowCount(response.getLinkedCount());
    batch.setAutoBindingCount(response.getAutoBindingCount());
    batch.setWarningCount(response.getTaxModeWarningCount());
    batch.setErrorCount(response.getErrors().size());
    batch.setStatus(response.getImportStatus());
    batch.setErrorMessage(response.getErrors().isEmpty()
        ? null : response.getErrors().getFirst().getMessage());
    batch.setFinishedAt(LocalDateTime.now());
    batch.setUpdatedAt(LocalDateTime.now());
    factorUploadBatchMapper.updateById(batch);
  }

  private String normalizeConflictStrategy(String value) {
    if (!StringUtils.hasText(value)) {
      return FactorPriceConflictStrategy.KEEP_EXISTING.getCode();
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (FactorPriceConflictStrategy strategy : FactorPriceConflictStrategy.values()) {
      if (strategy.getCode().equals(normalized)) {
        return normalized;
      }
    }
    throw new IllegalArgumentException(
        "factorPriceConflictStrategy仅支持KEEP_EXISTING或OVERWRITE");
  }

  private DateResolution effectiveDate(
      String requested, String rowValue, YearMonth pricingMonth) {
    String selected = StringUtils.hasText(requested) ? requested.trim() : trimToNull(rowValue);
    if (!StringUtils.hasText(selected)) {
      return new DateResolution(pricingMonth.atDay(1), null);
    }
    for (DateTimeFormatter formatter : DATE_FORMATTERS) {
      try {
        return new DateResolution(LocalDate.parse(selected, formatter), null);
      } catch (DateTimeParseException ignored) {
        // 尝试下一种业务常见日期格式。
      }
    }
    return new DateResolution(null, "无法识别生效日期：" + selected);
  }

  private YearMonth requireMonth(String value) {
    try {
      return YearMonth.parse(requireText(value, "pricingMonth不能为空"));
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("pricingMonth必须为YYYY-MM");
    }
  }

  private String resolveBusinessUnitType(String requested) {
    String current = trimToNull(BusinessUnitContext.getCurrentBusinessUnitType());
    String selected = trimToNull(requested);
    if (selected == null) {
      selected = current;
    }
    selected = requireText(selected, "businessUnitType不能为空");
    if (current != null
        && !BusinessUnitContext.isAdmin()
        && !current.equalsIgnoreCase(selected)) {
      throw new IllegalArgumentException("不能为其他业务单元预检或导入联动价");
    }
    return selected;
  }

  private void requireMatchingHash(String expected, String actual) {
    if (!StringUtils.hasText(expected) || !expected.trim().equalsIgnoreCase(actual)) {
      throw new IllegalArgumentException("类型2确认导入文件与预检文件SHA-256不一致");
    }
  }

  private byte[] requireBytes(PriceLinkedImportCommand command) {
    if (command == null || command.getFileBytes().length == 0) {
      throw new IllegalArgumentException("Excel文件不能为空");
    }
    return command.getFileBytes();
  }

  private String currentOperator() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null || !StringUtils.hasText(authentication.getName())
        ? "system" : authentication.getName();
  }

  private PriceItemImportResponse.ErrorRow error(
      Integer row,
      String material,
      String supplier,
      String sheet,
      String stage,
      String code,
      String message) {
    PriceItemImportResponse.ErrorRow error =
        new PriceItemImportResponse.ErrorRow(row, material, "联动", message);
    error.setSupplierCode(supplier);
    error.setSourceSheetName(sheet);
    error.setErrorStage(stage);
    error.setErrorCode(code);
    return error;
  }

  private String cellKey(String sheet, String cell) {
    return textNormalizer.normalize(sheet) + "!" + textNormalizer.normalize(cell);
  }

  private String rowKey(String sheet, Integer row) {
    return textNormalizer.normalize(sheet) + "#" + row;
  }

  private String sourceFileName(String value) {
    return defaultText(value, "price-linked-type2.xlsx");
  }

  private String defaultText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String requireText(String value, String message) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private record ImportPlan(
      PriceLinkedType2WorkbookParseResult parseResult,
      PriceLinkedType2RowMatchSummary matchSummary,
      List<FactorPlan> factors,
      List<RowPlan> rows,
      PriceLinkedType2ImportPreviewResponse preview,
      YearMonth pricingMonth,
      String businessUnitType) {
  }

  private record FactorPlan(
      PriceLinkedType2FactorRow row,
      PriceLinkedType2FactorIdentityResolution resolution,
      boolean blocked,
      boolean previewIdentity,
      Long previewIdentityId) {
  }

  private record RowPlan(
      PriceLinkedType2MergedRow mergedRow,
      PriceLinkedType2FormulaConversionResult conversion,
      PriceLinkedType2TaxNormalizationResult tax,
      PriceLinkedType2PriceReconcileResult reconcile,
      LocalDate effectiveDate,
      boolean importable,
      String errorCode,
      String message) {
  }

  private record DateResolution(LocalDate value, String error) {
  }
}

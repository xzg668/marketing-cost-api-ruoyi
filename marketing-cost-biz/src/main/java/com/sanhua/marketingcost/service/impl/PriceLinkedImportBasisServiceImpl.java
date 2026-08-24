package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisFactorResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveRequest;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaReference;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceComparison;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceReconcileResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisRepository;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisService;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService.RouteCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedImportBasisServiceImpl implements PriceLinkedImportBasisService {

  private static final String ORDER_TYPE_LINKED = "联动";
  private static final String BINDING_SOURCE = "TYPE2_IMPORT";

  private final PriceLinkedImportBasisRepository repository;
  private final ObjectMapper objectMapper;
  private final MaterialPriceTypeRouteSyncService priceTypeRouteSyncService;

  public PriceLinkedImportBasisServiceImpl(
      PriceLinkedImportBasisRepository repository,
      ObjectMapper objectMapper,
      MaterialPriceTypeRouteSyncService priceTypeRouteSyncService) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.priceTypeRouteSyncService = priceTypeRouteSyncService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceLinkedImportBasisSaveResult save(PriceLinkedImportBasisSaveRequest request) {
    ValidatedSave validated = validate(request);
    PriceLinkedItem next = buildVersion(validated);
    PriceLinkedItem current = repository.findCurrentVersion(next);

    if (isSameType2Version(current, next)) {
      syncPriceType(current);
      return new PriceLinkedImportBasisSaveResult(
          PriceLinkedImportBasisSaveResult.ACTION_DUPLICATE_SKIPPED,
          current.getId(),
          current.getId(),
          repository.findBindings(current.getId()).size());
    }

    if (current != null) {
      // 公式版本先后改由正式导入时间决定；日期字段只保留历史展示，不阻断同日重导。
      current.setEffectiveTo(validated.effectiveDate());
      repository.updateItem(current);
    }

    repository.insertItem(next);
    if (next.getId() == null) {
      throw new IllegalStateException("保存类型2联动价版本后未返回主键");
    }

    List<PriceVariableBinding> bindings = buildBindings(validated, next.getId());
    for (PriceVariableBinding binding : bindings) {
      repository.insertBinding(binding);
    }
    syncPriceType(next);
    return new PriceLinkedImportBasisSaveResult(
        PriceLinkedImportBasisSaveResult.ACTION_CREATED,
        next.getId(),
        current == null ? null : current.getId(),
        bindings.size());
  }

  private void syncPriceType(PriceLinkedItem item) {
    priceTypeRouteSyncService.sync(new RouteCommand(
        item.getMaterialCode(),
        item.getMaterialName(),
        item.getSpecModel(),
        item.getUnit(),
        item.getBusinessUnitType(),
        "联动价",
        "price_linked_type2",
        "FORMAL_LINKED"));
  }

  @Override
  @Transactional(readOnly = true)
  public PriceLinkedImportBasisResponse getImportBasis(Long linkedItemId) {
    if (linkedItemId == null) {
      return null;
    }
    PriceLinkedItem item = repository.findById(linkedItemId);
    if (item == null || !canRead(item)) {
      return null;
    }

    PriceLinkedImportBasisResponse response = baseResponse(item);
    if (!hasImportBasis(item)) {
      response.setImportBasisAvailable(false);
      response.setMessage("历史联动价暂无类型2导入依据");
      return response;
    }

    response.setImportBasisAvailable(true);
    PriceLinkedImportBasisSnapshot snapshot = parseSnapshot(item, response);
    response.setSnapshot(snapshot);
    List<PriceVariableBinding> bindings = repository.findBindings(item.getId());
    response.setFactorBindings(toFactorResponses(snapshot, bindings));
    return response;
  }

  private ValidatedSave validate(PriceLinkedImportBasisSaveRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("导入依据写入请求不能为空");
    }
    PriceLinkedItem candidate = Objects.requireNonNull(
        request.getCandidateVersion(), "candidateVersion不能为空");
    PriceLinkedType2MergedRow merged = Objects.requireNonNull(
        request.getMergedRow(), "mergedRow不能为空");
    PriceLinkedType2FormulaConversionResult conversion = Objects.requireNonNull(
        request.getFormulaConversion(), "formulaConversion不能为空");
    PriceLinkedType2TaxNormalizationResult tax = Objects.requireNonNull(
        request.getTaxNormalization(), "taxNormalization不能为空");
    PriceLinkedType2PriceReconcileResult reconcile = Objects.requireNonNull(
        request.getPriceReconcile(), "priceReconcile不能为空");
    if (request.getSourceUploadBatchId() == null || request.getSourceUploadBatchId() <= 0) {
      throw new IllegalArgumentException("sourceUploadBatchId必须为正数");
    }
    if (request.getEffectiveDate() == null) {
      throw new IllegalArgumentException("effectiveDate不能为空");
    }
    if (!conversion.isSuccess()) {
      throw new IllegalArgumentException("类型2公式转换未通过，不能保存导入依据");
    }
    if (!tax.isSuccess()) {
      throw new IllegalArgumentException("类型2税口径校验未通过，不能保存导入依据");
    }
    if (!reconcile.isSuccess()) {
      throw new IllegalArgumentException("类型2价格对账未通过，不能保存导入依据");
    }
    requireText(merged.getPricingMonth(), "pricingMonth");
    requireText(merged.getMaterialCode(), "materialCode");
    requireText(candidate.getBusinessUnitType(), "businessUnitType");
    if (StringUtils.hasText(candidate.getFormulaExpr())
        && !sameText(candidate.getFormulaExpr(), conversion.getConvertedFormula())) {
      throw new IllegalArgumentException("候选版本公式与类型2转换公式不一致");
    }
    if (candidate.getTaxIncluded() != null
        && !candidate.getTaxIncluded().equals(tax.getNormalizedTaxIncluded())) {
      throw new IllegalArgumentException("候选版本税标记与类型2规范化结果不一致");
    }
    return new ValidatedSave(
        candidate,
        request.getSourceUploadBatchId(),
        merged,
        conversion,
        tax,
        reconcile,
        request.getEffectiveDate(),
        request.getFactorMonthlyPriceIds());
  }

  private PriceLinkedItem buildVersion(ValidatedSave validated) {
    PriceLinkedItem next = new PriceLinkedItem();
    BeanUtils.copyProperties(validated.candidate(), next);
    next.setId(null);
    next.setPricingMonth(validated.merged().getPricingMonth().trim());
    next.setMaterialCode(validated.merged().getMaterialCode().trim());
    next.setSupplierName(firstText(
        validated.merged().getSupplierName(), next.getSupplierName()));
    next.setSupplierCode(firstText(
        validated.merged().getSupplierCode(), next.getSupplierCode()));
    next.setSourceName(firstText(validated.merged().getSource(), next.getSourceName()));
    next.setMaterialName(firstText(
        next.getMaterialName(), validated.merged().getBusinessRow().getProductName()));
    next.setSpecModel(firstText(
        next.getSpecModel(), validated.merged().getBusinessRow().getSpecification()));
    next.setUnit(firstText(next.getUnit(), validated.merged().getBusinessRow().getUnit()));
    next.setPurchaseClass(firstText(
        validated.merged().getMaterialAttribute(), next.getPurchaseClass()));
    next.setFormulaExpr(validated.conversion().getConvertedFormula().trim());
    next.setTaxIncluded(validated.tax().getNormalizedTaxIncluded());
    next.setEffectiveFrom(validated.effectiveDate());
    next.setEffectiveTo(null);
    next.setOrderType(firstText(next.getOrderType(), ORDER_TYPE_LINKED));
    next.setDeleted(0);
    next.setSourceUploadBatchId(validated.sourceUploadBatchId());
    next.setSourceSheetName(validated.conversion().getSourceSheetName());
    next.setSourceRowNumber(validated.conversion().getSourceRowNumber());
    next.setSourceFormulaCellRef(validated.conversion().getSourceFormulaCellRef());
    next.setSourceFormulaExpr(validated.conversion().getSourceFormula());
    next.setSourceTaxIncludedPrice(
        validated.merged().getBusinessRow().getTaxIncludedPrice());
    next.setSourceTaxExcludedPrice(
        validated.merged().getBusinessRow().getTaxExcludedPrice());
    next.setSourceInputSnapshotJson(writeSnapshot(buildSnapshot(validated)));
    return next;
  }

  private PriceLinkedImportBasisSnapshot buildSnapshot(ValidatedSave validated) {
    List<PriceLinkedImportBasisSnapshot.InputCell> cells =
        validated.conversion().getInputSnapshots().stream()
            .map(cell -> toInputCell(cell, validated.conversion()))
            .toList();
    List<PriceLinkedImportBasisSnapshot.FactorInput> factors =
        validated.conversion().getFactorReplacements().stream()
            .map(this::toFactorInput)
            .toList();
    PriceLinkedType2TaxNormalizationResult tax = validated.tax();
    PriceLinkedImportBasisSnapshot.TaxBasis taxBasis =
        new PriceLinkedImportBasisSnapshot.TaxBasis(
            tax.getRawTaxIncludedText(),
            tax.getOriginalTaxIncluded(),
            tax.getNormalizedTaxIncluded(),
            tax.isFinalVatDivisorStripped(),
            tax.isTaxAdjustmentRequired(),
            tax.getWarnings());
    PriceLinkedType2PriceReconcileResult reconcile = validated.reconcile();
    PriceLinkedImportBasisSnapshot.ReconcileBasis reconcileBasis =
        new PriceLinkedImportBasisSnapshot.ReconcileBasis(
            reconcile.getFormulaResult(),
            reconcile.getFinalPrice(),
            reconcile.getVatRate(),
            reconcile.getTolerance(),
            toDifference(reconcile.getTaxIncludedComparison()),
            toDifference(reconcile.getTaxExcludedComparison()));
    return new PriceLinkedImportBasisSnapshot(
        validated.conversion().getSourceFormula(),
        cells,
        factors,
        taxBasis,
        reconcileBasis);
  }

  private PriceLinkedImportBasisSnapshot.InputCell toInputCell(
      PriceLinkedType2CellSnapshot cell,
      PriceLinkedType2FormulaConversionResult conversion) {
    PriceLinkedType2FormulaReference resolvedReference =
        conversion.getReferences().stream()
            .filter(reference -> sameCell(cell, reference))
            .findFirst()
            .orElse(null);
    BigDecimal calculationValue = resolvedReference == null
        ? cell.getNumericValue()
        : resolvedReference.numericValue();
    boolean blankDefaultedToZero = cell.isBlankCell()
        && cell.getNumericValue() == null
        && calculationValue != null
        && calculationValue.compareTo(BigDecimal.ZERO) == 0;
    return new PriceLinkedImportBasisSnapshot.InputCell(
        cell.getSheetName(),
        cell.getCellRef(),
        cell.getHeader(),
        cell.getDisplayValue(),
        cell.getNumericValue(),
        calculationValue,
        blankDefaultedToZero,
        cell.getFormula(),
        cell.getUnit(),
        cell.getSourceCellType());
  }

  private boolean sameCell(
      PriceLinkedType2CellSnapshot cell,
      PriceLinkedType2FormulaReference reference) {
    return normalizeCellPart(cell.getSheetName()).equals(
            normalizeCellPart(reference.sheetName()))
        && normalizeCellPart(cell.getCellRef()).equals(
            normalizeCellPart(reference.cellRef()));
  }

  private String normalizeCellPart(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private PriceLinkedImportBasisSnapshot.FactorInput toFactorInput(
      PriceLinkedType2FormulaReference reference) {
    return new PriceLinkedImportBasisSnapshot.FactorInput(
        reference.rawReference(),
        reference.factorShortName(),
        reference.sheetName(),
        reference.cellRef(),
        reference.factorIdentityId(),
        reference.numericValue(),
        stripBrackets(reference.replacement()));
  }

  private PriceLinkedImportBasisSnapshot.PriceDifference toDifference(
      PriceLinkedType2PriceComparison comparison) {
    if (comparison == null) {
      return null;
    }
    return new PriceLinkedImportBasisSnapshot.PriceDifference(
        comparison.priceType(),
        comparison.systemPrice(),
        comparison.excelPrice(),
        comparison.absoluteDifference(),
        comparison.tolerance(),
        comparison.compared(),
        comparison.passed());
  }

  private String writeSnapshot(PriceLinkedImportBasisSnapshot snapshot) {
    try {
      return objectMapper.writer()
          .with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
          .writeValueAsString(snapshot);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("序列化类型2导入依据失败", exception);
    }
  }

  private List<PriceVariableBinding> buildBindings(
      ValidatedSave validated, Long linkedItemId) {
    Map<Long, PriceLinkedType2FormulaReference> unique = new LinkedHashMap<>();
    for (PriceLinkedType2FormulaReference reference
        : validated.conversion().getFactorReplacements()) {
      if (reference.factorIdentityId() == null) {
        throw new IllegalArgumentException("公式因素缺少统一身份，不能保存绑定");
      }
      unique.putIfAbsent(reference.factorIdentityId(), reference);
    }
    List<PriceVariableBinding> result = new ArrayList<>(unique.size());
    for (PriceLinkedType2FormulaReference reference : unique.values()) {
      String factorCode = "factor_identity_" + reference.factorIdentityId();
      PriceVariableBinding binding = new PriceVariableBinding();
      binding.setLinkedItemId(linkedItemId);
      binding.setTokenName(factorCode);
      binding.setFactorCode(factorCode);
      binding.setFactorIdentityId(reference.factorIdentityId());
      binding.setFactorMonthlyPriceId(
          validated.factorMonthlyPriceIds().get(reference.factorIdentityId()));
      binding.setFactorUploadBatchId(validated.sourceUploadBatchId());
      binding.setExcelSourceSheetName(reference.sheetName());
      binding.setExcelSourceCellRef(reference.cellRef());
      binding.setExcelFormula(validated.conversion().getSourceFormula());
      binding.setBuScoped(1);
      binding.setEffectiveDate(validated.effectiveDate());
      binding.setExpiryDate(null);
      binding.setSource(BINDING_SOURCE);
      binding.setRemark("原名称=" + nullToEmpty(reference.factorShortName())
          + ";导入价=" + decimalText(reference.numericValue()));
      binding.setDeleted(0);
      result.add(binding);
    }
    return result;
  }

  private boolean isSameType2Version(PriceLinkedItem current, PriceLinkedItem next) {
    return current != null
        && hasImportBasis(current)
        && sameText(current.getFormulaExpr(), next.getFormulaExpr())
        && sameText(current.getSourceFormulaExpr(), next.getSourceFormulaExpr())
        && Objects.equals(current.getTaxIncluded(), next.getTaxIncluded());
  }

  private PriceLinkedImportBasisResponse baseResponse(PriceLinkedItem item) {
    PriceLinkedImportBasisResponse response = new PriceLinkedImportBasisResponse();
    response.setLinkedItemId(item.getId());
    response.setPricingMonth(item.getPricingMonth());
    response.setBusinessUnitType(item.getBusinessUnitType());
    response.setMaterialCode(item.getMaterialCode());
    response.setSupplierCode(item.getSupplierCode());
    response.setEffectiveFrom(item.getEffectiveFrom());
    response.setEffectiveTo(item.getEffectiveTo());
    response.setSourceUploadBatchId(item.getSourceUploadBatchId());
    FactorUploadBatch sourceBatch =
        repository.findUploadBatchById(item.getSourceUploadBatchId());
    if (sourceBatch != null) {
      response.setSourceBatchNo(sourceBatch.getBatchNo());
      response.setSourceFileName(sourceBatch.getFileName());
    }
    response.setSourceSheetName(item.getSourceSheetName());
    response.setSourceRowNumber(item.getSourceRowNumber());
    response.setSourceFormulaCellRef(item.getSourceFormulaCellRef());
    response.setSourceFormula(item.getSourceFormulaExpr());
    response.setSystemFormula(item.getFormulaExpr());
    response.setTaxIncluded(item.getTaxIncluded());
    response.setSourceTaxIncludedPrice(item.getSourceTaxIncludedPrice());
    response.setSourceTaxExcludedPrice(item.getSourceTaxExcludedPrice());
    response.setSourceInputSnapshotJson(item.getSourceInputSnapshotJson());
    return response;
  }

  private PriceLinkedImportBasisSnapshot parseSnapshot(
      PriceLinkedItem item, PriceLinkedImportBasisResponse response) {
    if (!StringUtils.hasText(item.getSourceInputSnapshotJson())) {
      response.setMessage("导入依据快照为空");
      return null;
    }
    try {
      return objectMapper.readValue(
          item.getSourceInputSnapshotJson(), PriceLinkedImportBasisSnapshot.class);
    } catch (JsonProcessingException exception) {
      response.setMessage("导入依据快照无法解析，已返回原始JSON");
      return null;
    }
  }

  private List<PriceLinkedImportBasisFactorResponse> toFactorResponses(
      PriceLinkedImportBasisSnapshot snapshot,
      List<PriceVariableBinding> bindings) {
    Map<Long, PriceVariableBinding> byIdentity = new LinkedHashMap<>();
    for (PriceVariableBinding binding : bindings) {
      if (binding.getFactorIdentityId() != null) {
        byIdentity.putIfAbsent(binding.getFactorIdentityId(), binding);
      }
    }
    if (snapshot == null) {
      return bindings.stream()
          .map(binding -> new PriceLinkedImportBasisFactorResponse(
              binding.getTokenName(),
              null,
              binding.getExcelSourceSheetName(),
              binding.getExcelSourceCellRef(),
              binding.getFactorIdentityId(),
              binding.getFactorMonthlyPriceId(),
              null,
              binding.getFactorCode(),
              binding.getSource()))
          .toList();
    }
    return snapshot.factorInputs().stream()
        .map(factor -> {
          PriceVariableBinding binding = byIdentity.get(factor.factorIdentityId());
          return new PriceLinkedImportBasisFactorResponse(
              factor.originalName(),
              factor.rawReference(),
              factor.sheetName(),
              factor.cellRef(),
              factor.factorIdentityId(),
              binding == null ? null : binding.getFactorMonthlyPriceId(),
              factor.importedPrice(),
              factor.systemVariable(),
              binding == null ? null : binding.getSource());
        })
        .toList();
  }

  private boolean canRead(PriceLinkedItem item) {
    if (BusinessUnitContext.isAdmin()) {
      return true;
    }
    String current = trimToNull(BusinessUnitContext.getCurrentBusinessUnitType());
    return current != null
        && current.equalsIgnoreCase(trimToNull(item.getBusinessUnitType()));
  }

  private boolean hasImportBasis(PriceLinkedItem item) {
    return item.getSourceUploadBatchId() != null
        || StringUtils.hasText(item.getSourceFormulaExpr())
        || StringUtils.hasText(item.getSourceInputSnapshotJson());
  }

  private String stripBrackets(String value) {
    String text = trimToNull(value);
    if (text != null && text.startsWith("[") && text.endsWith("]") && text.length() > 2) {
      return text.substring(1, text.length() - 1);
    }
    return text;
  }

  private String firstText(String first, String fallback) {
    return StringUtils.hasText(first) ? first.trim() : trimToNull(fallback);
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private boolean sameText(String left, String right) {
    return Objects.equals(trimToNull(left), trimToNull(right));
  }

  private String requireText(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }

  private String decimalText(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private record ValidatedSave(
      PriceLinkedItem candidate,
      Long sourceUploadBatchId,
      PriceLinkedType2MergedRow merged,
      PriceLinkedType2FormulaConversionResult conversion,
      PriceLinkedType2TaxNormalizationResult tax,
      PriceLinkedType2PriceReconcileResult reconcile,
      LocalDate effectiveDate,
      Map<Long, Long> factorMonthlyPriceIds) {
  }
}

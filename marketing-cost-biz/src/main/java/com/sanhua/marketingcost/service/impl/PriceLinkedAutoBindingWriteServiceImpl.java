package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteRequest;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteResult;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.entity.ExcelAutoBindingImportLog;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.mapper.ExcelAutoBindingImportLogMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceLinkedAutoBindingWriteService;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedAutoBindingWriteServiceImpl implements PriceLinkedAutoBindingWriteService {

  private static final Set<String> WRITABLE_DECISIONS =
      Set.of(PriceLinkedStandardBindingServiceImpl.ACTION_CREATE_HISTORY,
          PriceLinkedStandardBindingServiceImpl.ACTION_CONSISTENT);
  private static final String SOURCE_EXCEL_FORMULA = "EXCEL_FORMULA";
  private static final String SOURCE_MANUAL = "MANUAL";

  private final PriceVariableBindingMapper bindingMapper;
  private final PriceVariableMapper priceVariableMapper;
  private final PriceVariableBindingService priceVariableBindingService;
  private final FactorVariableRegistryImpl factorVariableRegistry;
  private final ExcelAutoBindingImportLogMapper autoBindingImportLogMapper;

  public PriceLinkedAutoBindingWriteServiceImpl(
      PriceVariableBindingMapper bindingMapper,
      PriceVariableMapper priceVariableMapper,
      PriceVariableBindingService priceVariableBindingService,
      FactorVariableRegistryImpl factorVariableRegistry,
      ExcelAutoBindingImportLogMapper autoBindingImportLogMapper) {
    this.bindingMapper = bindingMapper;
    this.priceVariableMapper = priceVariableMapper;
    this.priceVariableBindingService = priceVariableBindingService;
    this.factorVariableRegistry = factorVariableRegistry;
    this.autoBindingImportLogMapper = autoBindingImportLogMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceLinkedAutoBindingWriteResult write(PriceLinkedAutoBindingWriteRequest request) {
    PriceLinkedAutoBindingWriteResult result = new PriceLinkedAutoBindingWriteResult();
    if (request == null || request.getLinkedItemId() == null) {
      result.addError(null, "linkedItemId 必填，不能写入行级绑定");
      return result;
    }
    LocalDate effectiveDate = parseMonthStart(request.getPricingMonth());
    for (StandardBindingDecision decision : request.getDecisions()) {
      writeOne(request, decision, effectiveDate, result);
    }
    return result;
  }

  private void writeOne(
      PriceLinkedAutoBindingWriteRequest request,
      StandardBindingDecision decision,
      LocalDate effectiveDate,
      PriceLinkedAutoBindingWriteResult result) {
    String tokenName = decision == null ? null : decision.getTokenName();
    if (decision == null || !WRITABLE_DECISIONS.contains(decision.getAction())) {
      insertImportLog(request, decision, null, "SKIPPED", "FAILED",
          "历史关系校验未通过，不能写入行级绑定");
      result.addError(tokenName, "历史关系校验未通过，不能写入行级绑定");
      return;
    }
    BindingCandidate candidate = decision.getCandidate();
    if (candidate == null || candidate.getSourceRef() == null) {
      insertImportLog(request, decision, candidate, "SKIPPED", "FAILED",
          "绑定候选缺少影响因素来源，不能写入行级绑定");
      result.addError(tokenName, "绑定候选缺少影响因素来源，不能写入行级绑定");
      return;
    }
    PriceVariableBinding current = bindingMapper.findCurrentByLinkedItemIdAndToken(
        request.getLinkedItemId(), candidate.getTokenName());
    if (current != null && SOURCE_MANUAL.equalsIgnoreCase(current.getSource())
        && !Boolean.TRUE.equals(request.getOverwriteManualBinding())) {
      insertImportLog(request, decision, candidate, "SKIPPED_MANUAL", "WARNING",
          "当前已有 MANUAL 人工绑定，默认不覆盖，bindingId=" + current.getId());
      result.addManualSkipped(candidate.getTokenName(),
          "当前已有 MANUAL 人工绑定，默认不覆盖");
      return;
    }

    try {
      String factorCode = ensureFinanceVariable(candidate.getSourceRef());
      PriceVariableBindingRequest bindingRequest = buildBindingRequest(
          request, decision, candidate, factorCode, effectiveDate);
      Long bindingId = priceVariableBindingService.save(bindingRequest);
      insertImportLog(request, decision, candidate, "AUTO_BOUND", "SUCCESS",
          successMessage(current, bindingId));
      result.addWritten(candidate.getTokenName(), bindingId);
    } catch (RuntimeException e) {
      insertImportLog(request, decision, candidate, "AUTO_BOUND", "FAILED", e.getMessage());
      result.addError(candidate.getTokenName(), e.getMessage());
    }
  }

  private PriceVariableBindingRequest buildBindingRequest(
      PriceLinkedAutoBindingWriteRequest request,
      StandardBindingDecision decision,
      BindingCandidate candidate,
      String factorCode,
      LocalDate effectiveDate) {
    ResolvedFactorRef sourceRef = candidate.getSourceRef();
    PriceVariableBindingRequest bindingRequest = new PriceVariableBindingRequest();
    bindingRequest.setLinkedItemId(request.getLinkedItemId());
    bindingRequest.setTokenName(candidate.getTokenName());
    bindingRequest.setFactorCode(factorCode);
    bindingRequest.setPriceSource(sourceRef.getPriceSource());
    bindingRequest.setFactorIdentityId(candidate.getFactorIdentityId());
    bindingRequest.setFactorMonthlyPriceId(candidate.getFactorMonthlyPriceId());
    bindingRequest.setFactorUploadBatchId(request.getFactorUploadBatchId());
    bindingRequest.setStandardBindingId(decision.getStandardBindingId());
    bindingRequest.setExcelSourceSheetName(sourceRef.getSheetName());
    bindingRequest.setExcelSourceCellRef(cellRef(sourceRef));
    bindingRequest.setExcelFormula(request.getExcelFormula());
    bindingRequest.setBuScoped(1);
    bindingRequest.setEffectiveDate(effectiveDate);
    bindingRequest.setSource(SOURCE_EXCEL_FORMULA);
    bindingRequest.setRemark("由月度联动价与影响因素 Excel 自动绑定");
    return bindingRequest;
  }

  private String ensureFinanceVariable(ResolvedFactorRef sourceRef) {
    if (sourceRef == null || !StringUtils.hasText(sourceRef.getShortName())) {
      throw new IllegalArgumentException("影响因素缺少简称，不能登记公式变量");
    }
    String variableCode = sourceRef.getShortName().trim();
    PriceVariable existing = priceVariableMapper.selectOne(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getVariableCode, variableCode)
            .last("LIMIT 1"));
    if (existing != null) {
      return variableCode;
    }
    PriceVariable variable = new PriceVariable();
    variable.setVariableCode(variableCode);
    variable.setVariableName(variableCode);
    variable.setSourceType("FACTOR");
    variable.setSourceTable("lp_finance_base_price");
    variable.setSourceField("price");
    variable.setScope("BASE_PRICE");
    variable.setStatus("active");
    variable.setTaxMode("INCL");
    variable.setFactorType("FINANCE_FACTOR");
    variable.setAliasesJson("[\"" + jsonEscape(variableCode) + "\"]");
    variable.setResolverKind("FINANCE");
    variable.setResolverParams("{\"shortName\":\"" + jsonEscape(variableCode)
        + "\",\"priceSource\":\"" + jsonEscape(defaultPriceSource(sourceRef.getPriceSource()))
        + "\",\"buScoped\":true}");
    priceVariableMapper.insert(variable);
    factorVariableRegistry.invalidate();
    return variableCode;
  }

  private LocalDate parseMonthStart(String pricingMonth) {
    if (!StringUtils.hasText(pricingMonth)) {
      return LocalDate.now();
    }
    return LocalDate.parse(pricingMonth.trim() + "-01");
  }

  private String cellRef(ResolvedFactorRef sourceRef) {
    if (sourceRef == null || sourceRef.getRowNumber() == null) {
      return null;
    }
    String column = StringUtils.hasText(sourceRef.getColumnName())
        ? sourceRef.getColumnName().trim()
        : "E";
    return column + sourceRef.getRowNumber();
  }

  private String defaultPriceSource(String priceSource) {
    return StringUtils.hasText(priceSource) ? priceSource.trim() : "未指定";
  }

  private String jsonEscape(String raw) {
    return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private void insertImportLog(
      PriceLinkedAutoBindingWriteRequest request,
      StandardBindingDecision decision,
      BindingCandidate candidate,
      String action,
      String status,
      String message) {
    if (autoBindingImportLogMapper == null) {
      return;
    }
    ResolvedFactorRef sourceRef = candidate == null ? null : candidate.getSourceRef();
    ExcelAutoBindingImportLog importLog = new ExcelAutoBindingImportLog();
    importLog.setFactorUploadBatchId(request == null ? null : request.getFactorUploadBatchId());
    importLog.setLinkedItemId(request == null ? null : request.getLinkedItemId());
    importLog.setMaterialCode(candidate != null ? candidate.getMaterialCode()
        : decision == null ? null : decision.getMaterialCode());
    importLog.setSupplierCode(candidate == null ? null : supplierCode(candidate));
    importLog.setTokenName(candidate != null ? candidate.getTokenName()
        : decision == null ? null : decision.getTokenName());
    importLog.setAction(action);
    importLog.setStatus(status);
    importLog.setFactorIdentityId(candidate == null ? null : candidate.getFactorIdentityId());
    importLog.setFactorMonthlyPriceId(candidate == null ? null : candidate.getFactorMonthlyPriceId());
    importLog.setSourceWorkbookName(sourceRef == null ? null : sourceRef.getWorkbookName());
    importLog.setSourceSheetName(sourceRef == null ? null : sourceRef.getSheetName());
    importLog.setSourceCellRef(sourceRef == null ? null : cellRef(sourceRef));
    importLog.setExcelFormula(request == null ? null : request.getExcelFormula());
    importLog.setMessage(message);
    importLog.setCreatedAt(LocalDateTime.now());
    autoBindingImportLogMapper.insert(importLog);
  }

  private String supplierCode(BindingCandidate candidate) {
    if (candidate == null || !StringUtils.hasText(candidate.getLinkedItemImportKey())) {
      return null;
    }
    String[] parts = candidate.getLinkedItemImportKey().split("\\|", -1);
    return parts.length > 1 && StringUtils.hasText(parts[1]) ? parts[1].trim() : null;
  }

  private String successMessage(PriceVariableBinding oldBinding, Long newBindingId) {
    if (oldBinding != null && SOURCE_MANUAL.equalsIgnoreCase(oldBinding.getSource())) {
      return "覆盖 MANUAL 绑定：oldBindingId=" + oldBinding.getId()
          + ", oldFactorIdentityId=" + oldBinding.getFactorIdentityId()
          + ", oldFactorCode=" + oldBinding.getFactorCode()
          + ", newBindingId=" + newBindingId;
    }
    return "自动绑定成功，bindingId=" + newBindingId;
  }
}

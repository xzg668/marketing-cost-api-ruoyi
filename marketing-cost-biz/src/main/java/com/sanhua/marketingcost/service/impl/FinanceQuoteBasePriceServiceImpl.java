package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceAdjustRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeResponse;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceResponse;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceConstants;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FinanceQuoteBasePriceServiceImpl implements FinanceQuoteBasePriceService {

  static final BigDecimal MAX_PRICE_PER_TON = new BigDecimal("1000000");
  private static final BigDecimal KG_PER_TON = new BigDecimal("1000");
  private static final String AUDIT_TITLE = "财务Cu报价基准维护";

  private final FinanceBasePriceMapper financeBasePriceMapper;
  private final SysOperationLogMapper operationLogMapper;
  private final ObjectMapper objectMapper;
  private final QuoteCostRunVersionInvalidationService versionInvalidationService;

  public FinanceQuoteBasePriceServiceImpl(
      FinanceBasePriceMapper financeBasePriceMapper,
      SysOperationLogMapper operationLogMapper,
      ObjectMapper objectMapper,
      QuoteCostRunVersionInvalidationService versionInvalidationService) {
    this.financeBasePriceMapper = financeBasePriceMapper;
    this.operationLogMapper = operationLogMapper;
    this.objectMapper = objectMapper;
    this.versionInvalidationService = versionInvalidationService;
  }

  @Override
  public List<FinanceQuoteBasePriceResponse> list(String startMonth, String endMonth) {
    String businessUnitType = requireCurrentBusinessUnit();
    YearMonth start = parseOptionalMonth(startMonth, "startMonth");
    YearMonth end = parseOptionalMonth(endMonth, "endMonth");
    validateMonthRange(start, end);

    var query = Wrappers.lambdaQuery(FinanceBasePrice.class)
        .eq(FinanceBasePrice::getFactorCode, FinanceQuoteBasePriceConstants.FACTOR_CODE)
        .eq(FinanceBasePrice::getPriceSource, FinanceQuoteBasePriceConstants.PRICE_SOURCE)
        .eq(FinanceBasePrice::getBusinessUnitType, businessUnitType);
    if (start != null) {
      query.ge(FinanceBasePrice::getPriceMonth, start.toString());
    }
    if (end != null) {
      query.le(FinanceBasePrice::getPriceMonth, end.toString());
    }
    query.orderByAsc(FinanceBasePrice::getPriceMonth);
    List<FinanceBasePrice> entities = financeBasePriceMapper.selectList(query);
    Map<String, AuditSummary> auditByTargetId = loadLatestAudits(entities, businessUnitType);
    return entities.stream()
        .map(entity -> validatedResponse(
            entity, auditByTargetId.get(String.valueOf(entity.getId()))))
        .toList();
  }

  @Override
  public FinanceBasePrice getRequired(String pricingMonth) {
    YearMonth month = parseMonth(pricingMonth, "pricingMonth");
    String businessUnitType = requireCurrentBusinessUnit();
    FinanceBasePrice entity = findExact(month, businessUnitType);
    if (entity == null) {
      throw new IllegalArgumentException(
          "未维护" + month + "财务报价Cu基准，请先由财务初始化或调整后再试算。");
    }
    validateStoredValue(entity);
    return entity;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FinanceQuoteBasePriceInitializeResponse initialize(
      FinanceQuoteBasePriceInitializeRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("初始化请求不能为空");
    }
    YearMonth start = parseMonth(request.startMonth(), "startMonth");
    YearMonth end = parseMonth(request.endMonth(), "endMonth");
    validateMonthRange(start, end);
    BigDecimal pricePerKg = toPricePerKg(request.pricePerTon());
    String businessUnitType = requireCurrentBusinessUnit();
    String operator = currentOperator();
    String reason = "批量初始化财务报价Cu基准（" + start + "至" + end + "）";

    List<String> createdMonths = new ArrayList<>();
    List<String> skippedMonths = new ArrayList<>();
    List<FinanceQuoteBasePriceResponse> records = new ArrayList<>();
    for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
      FinanceBasePrice existing = findExact(month, businessUnitType);
      if (existing != null) {
        validateStoredValue(existing);
        skippedMonths.add(month.toString());
        records.add(toResponse(existing, null));
        continue;
      }

      FinanceBasePrice created = newFinanceQuoteBasePrice(month, pricePerKg, businessUnitType);
      try {
        if (financeBasePriceMapper.insert(created) != 1) {
          throw new IllegalStateException(month + "财务Cu基准写入失败");
        }
      } catch (DuplicateKeyException ex) {
        FinanceBasePrice concurrent = findExact(month, businessUnitType);
        if (concurrent == null) {
          throw ex;
        }
        validateStoredValue(concurrent);
        skippedMonths.add(month.toString());
        records.add(toResponse(concurrent, null));
        continue;
      }
      SysOperationLog audit =
          writeOperationLog(OperationType.INSERT, created, null, reason, operator);
      createdMonths.add(month.toString());
      records.add(toResponse(created, toAuditSummary(audit)));
    }
    return new FinanceQuoteBasePriceInitializeResponse(
        createdMonths.size(), skippedMonths.size(), List.copyOf(createdMonths),
        List.copyOf(skippedMonths), List.copyOf(records));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FinanceQuoteBasePriceResponse adjust(
      Long id, FinanceQuoteBasePriceAdjustRequest request) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException("财务Cu基准ID不能为空");
    }
    if (request == null) {
      throw new IllegalArgumentException("调整请求不能为空");
    }
    String reason = requireText(request.changeReason(), "调整原因");
    BigDecimal pricePerKg = toPricePerKg(request.pricePerTon());
    String businessUnitType = requireCurrentBusinessUnit();
    FinanceBasePrice entity = financeBasePriceMapper.selectOne(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .eq(FinanceBasePrice::getId, id)
            .eq(FinanceBasePrice::getFactorCode, FinanceQuoteBasePriceConstants.FACTOR_CODE)
            .eq(FinanceBasePrice::getPriceSource,
                FinanceQuoteBasePriceConstants.PRICE_SOURCE)
            .eq(FinanceBasePrice::getBusinessUnitType, businessUnitType));
    if (entity == null) {
      throw new IllegalArgumentException("当前业务单元的财务Cu基准不存在: id=" + id);
    }
    validateStoredValue(entity);
    BigDecimal previousPrice = entity.getPrice();
    Map<String, Object> before = auditSnapshot(entity);
    entity.setPrice(pricePerKg);
    if (financeBasePriceMapper.updateById(entity) != 1) {
      throw new IllegalStateException(entity.getPriceMonth() + "财务Cu基准调整失败");
    }
    SysOperationLog audit = writeOperationLog(
        OperationType.UPDATE, entity, before, reason, currentOperator());
    if (previousPrice.compareTo(pricePerKg) != 0) {
      versionInvalidationService.invalidateByFinanceCu(
          entity.getPriceMonth(), businessUnitType);
    }
    return toResponse(entity, toAuditSummary(audit));
  }

  private FinanceBasePrice findExact(YearMonth month, String businessUnitType) {
    return financeBasePriceMapper.selectOne(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .eq(FinanceBasePrice::getFactorCode, FinanceQuoteBasePriceConstants.FACTOR_CODE)
            .eq(FinanceBasePrice::getPriceMonth, month.toString())
            .eq(FinanceBasePrice::getPriceSource,
                FinanceQuoteBasePriceConstants.PRICE_SOURCE)
            .eq(FinanceBasePrice::getBusinessUnitType, businessUnitType));
  }

  private FinanceBasePrice newFinanceQuoteBasePrice(
      YearMonth month, BigDecimal pricePerKg, String businessUnitType) {
    FinanceBasePrice entity = new FinanceBasePrice();
    entity.setPriceMonth(month.toString());
    entity.setFactorName(FinanceQuoteBasePriceConstants.FACTOR_NAME);
    entity.setShortName(FinanceQuoteBasePriceConstants.SHORT_NAME);
    entity.setFactorCode(FinanceQuoteBasePriceConstants.FACTOR_CODE);
    entity.setPriceSource(FinanceQuoteBasePriceConstants.PRICE_SOURCE);
    entity.setPrice(pricePerKg);
    entity.setUnit(FinanceQuoteBasePriceConstants.UNIT);
    entity.setLinkType(FinanceQuoteBasePriceConstants.LINK_TYPE);
    entity.setBusinessUnitType(businessUnitType);
    return entity;
  }

  private FinanceQuoteBasePriceResponse validatedResponse(
      FinanceBasePrice entity, AuditSummary audit) {
    validateStoredValue(entity);
    return toResponse(entity, audit);
  }

  private FinanceQuoteBasePriceResponse toResponse(
      FinanceBasePrice entity, AuditSummary audit) {
    return new FinanceQuoteBasePriceResponse(
        entity.getId(), entity.getPriceMonth(), entity.getFactorCode(), entity.getPriceSource(),
        entity.getPrice(), entity.getPrice().multiply(KG_PER_TON), entity.getUnit(),
        entity.getBusinessUnitType(), entity.getUpdatedAt(),
        audit == null ? null : audit.operator(),
        audit == null ? null : audit.reason(),
        audit == null ? entity.getUpdatedAt() : audit.operTime());
  }

  private Map<String, AuditSummary> loadLatestAudits(
      List<FinanceBasePrice> entities, String businessUnitType) {
    List<String> targetIds = entities.stream()
        .map(FinanceBasePrice::getId)
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .toList();
    if (targetIds.isEmpty()) {
      return Map.of();
    }
    List<SysOperationLog> logs = operationLogMapper.selectList(
        Wrappers.lambdaQuery(SysOperationLog.class)
            .eq(SysOperationLog::getTitle, AUDIT_TITLE)
            .eq(SysOperationLog::getBusinessUnitType, businessUnitType)
            .eq(SysOperationLog::getStatus, 0)
            .in(SysOperationLog::getTargetId, targetIds)
            .orderByDesc(SysOperationLog::getOperTime)
            .orderByDesc(SysOperationLog::getOperId));
    Map<String, AuditSummary> result = new HashMap<>();
    for (SysOperationLog log : logs) {
      if (StringUtils.hasText(log.getTargetId())) {
        result.putIfAbsent(log.getTargetId(), toAuditSummary(log));
      }
    }
    return result;
  }

  private AuditSummary toAuditSummary(SysOperationLog log) {
    return new AuditSummary(
        log.getOperName(), extractChangeReason(log.getOperParam()), log.getOperTime());
  }

  private String extractChangeReason(String operParam) {
    if (!StringUtils.hasText(operParam)) {
      return null;
    }
    try {
      String reason = objectMapper.readTree(operParam).path("changeReason").asText(null);
      return StringUtils.hasText(reason) ? reason : null;
    } catch (JsonProcessingException ex) {
      return null;
    }
  }

  private void validateStoredValue(FinanceBasePrice entity) {
    if (!FinanceQuoteBasePriceConstants.UNIT.equals(entity.getUnit())) {
      throw new IllegalStateException(
          entity.getPriceMonth() + "财务报价Cu基准单位必须为公斤，当前为" + entity.getUnit());
    }
    if (entity.getPrice() == null || entity.getPrice().compareTo(BigDecimal.ZERO) <= 0
        || entity.getPrice().compareTo(MAX_PRICE_PER_TON.divide(KG_PER_TON)) > 0) {
      throw new IllegalStateException(entity.getPriceMonth() + "财务报价Cu基准价格超出有效范围");
    }
  }

  private BigDecimal toPricePerKg(BigDecimal pricePerTon) {
    if (pricePerTon == null) {
      throw new IllegalArgumentException("pricePerTon不能为空");
    }
    if (pricePerTon.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("pricePerTon必须大于0");
    }
    if (pricePerTon.compareTo(MAX_PRICE_PER_TON) > 0) {
      throw new IllegalArgumentException("pricePerTon不能超过" + MAX_PRICE_PER_TON.toPlainString());
    }
    BigDecimal converted = pricePerTon.divide(KG_PER_TON, 6, RoundingMode.HALF_UP);
    if (converted.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("pricePerTon换算为元/公斤后必须大于0");
    }
    return converted;
  }

  private YearMonth parseMonth(String value, String fieldName) {
    String normalized = requireText(value, fieldName);
    try {
      return YearMonth.parse(normalized);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException(fieldName + "必须为yyyy-MM格式", ex);
    }
  }

  private YearMonth parseOptionalMonth(String value, String fieldName) {
    return StringUtils.hasText(value) ? parseMonth(value, fieldName) : null;
  }

  private void validateMonthRange(YearMonth start, YearMonth end) {
    if (start != null && end != null && end.isBefore(start)) {
      throw new IllegalArgumentException("endMonth不能早于startMonth");
    }
  }

  private String requireCurrentBusinessUnit() {
    return requireText(BusinessUnitContext.getCurrentBusinessUnitType(), "当前业务单元");
  }

  private String requireText(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + "不能为空");
    }
    return value.trim();
  }

  private String currentOperator() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null || !StringUtils.hasText(authentication.getName())
        ? "system" : authentication.getName();
  }

  private SysOperationLog writeOperationLog(
      OperationType type,
      FinanceBasePrice entity,
      Map<String, Object> before,
      String reason,
      String operator) {
    SysOperationLog log = new SysOperationLog();
    boolean insert = type == OperationType.INSERT;
    log.setTitle(AUDIT_TITLE);
    log.setBusinessType(type.getCode());
    log.setMethod("FinanceQuoteBasePriceServiceImpl." + (insert ? "initialize" : "adjust"));
    log.setRequestMethod(insert ? "POST" : "PUT");
    log.setOperatorType(1);
    log.setOperName(operator);
    log.setOperUrl("/api/v1/finance-quote-base-prices/cu"
        + (insert ? "/initialize" : "/" + entity.getId()));
    log.setOperParam(toJson(Map.of("changeReason", reason)));
    log.setStatus(0);
    log.setOperTime(LocalDateTime.now());
    log.setBusinessUnitType(entity.getBusinessUnitType());
    log.setTargetId(String.valueOf(entity.getId()));
    log.setBeforeData(toJson(before));
    log.setAfterData(toJson(auditSnapshot(entity)));
    log.setJsonResult(log.getAfterData());
    if (operationLogMapper.insert(log) != 1) {
      throw new IllegalStateException("财务Cu基准操作日志写入失败");
    }
    return log;
  }

  private Map<String, Object> auditSnapshot(FinanceBasePrice entity) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", entity.getId());
    snapshot.put("priceMonth", entity.getPriceMonth());
    snapshot.put("factorCode", entity.getFactorCode());
    snapshot.put("priceSource", entity.getPriceSource());
    snapshot.put("pricePerKg", entity.getPrice());
    snapshot.put("pricePerTon", entity.getPrice().multiply(KG_PER_TON));
    snapshot.put("unit", entity.getUnit());
    snapshot.put("businessUnitType", entity.getBusinessUnitType());
    return snapshot;
  }

  private String toJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("财务Cu基准审计数据序列化失败", ex);
    }
  }

  private record AuditSummary(String operator, String reason, LocalDateTime operTime) {
  }
}

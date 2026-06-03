package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapRevokeRequest;
import com.sanhua.marketingcost.entity.MakePartNoScrapConfirmation;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.MakePartNoScrapConfirmationMapper;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class MakePartNoScrapConfirmationServiceImpl
    implements MakePartNoScrapConfirmationService {

  private static final Logger log = LoggerFactory.getLogger(MakePartNoScrapConfirmationServiceImpl.class);
  private static final int MIN_REASON_LENGTH = 5;

  private final MakePartNoScrapConfirmationMapper mapper;
  private final SysOperationLogMapper operationLogMapper;
  private final ObjectMapper objectMapper;

  public MakePartNoScrapConfirmationServiceImpl(
      MakePartNoScrapConfirmationMapper mapper,
      SysOperationLogMapper operationLogMapper,
      ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.operationLogMapper = operationLogMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public NoScrapConfirmResponse confirm(NoScrapConfirmRequest request, String operator) {
    ValidatedConfirm validated = validateConfirmRequest(request);
    List<MakePartNoScrapConfirmation> activeRows =
        activeRows(validated.businessUnitType(), validated.materialNo());
    if (activeRows.stream().anyMatch(row -> overlaps(row, validated.from(), validated.to()))) {
      throw new IllegalArgumentException("该料号在当前生效期间已存在人工确认无废料记录");
    }

    MakePartNoScrapConfirmation entity = new MakePartNoScrapConfirmation();
    entity.setBusinessUnitType(validated.businessUnitType());
    entity.setMaterialNo(validated.materialNo());
    entity.setMaterialName(trimToNull(request.getMaterialName()));
    entity.setEffectiveFromMonth(validated.from().toString());
    entity.setEffectiveToMonth(validated.to() == null ? null : validated.to().toString());
    entity.setStatus(MakePartNoScrapConfirmation.STATUS_ACTIVE);
    entity.setConfirmReason(validated.reason());
    entity.setSourceOaNo(trimToNull(request.getSourceOaNo()));
    entity.setSourceGapId(request.getSourceGapId());
    entity.setConfirmedBy(currentOperator(operator));
    entity.setConfirmedAt(LocalDateTime.now());
    try {
      mapper.insert(entity);
    } catch (DuplicateKeyException ex) {
      throw new IllegalArgumentException("该料号同一生效开始月已存在ACTIVE无废料确认", ex);
    }
    writeOperationLog("确认无废料按0处理", OperationType.INSERT, entity, currentOperator(operator));
    return NoScrapConfirmResponse.from(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public NoScrapConfirmResponse revoke(Long id, NoScrapRevokeRequest request, String operator) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException("确认记录ID不能为空");
    }
    MakePartNoScrapConfirmation entity = mapper.selectById(id);
    if (entity == null) {
      throw new IllegalArgumentException("无废料确认记录不存在: id=" + id);
    }
    if (!MakePartNoScrapConfirmation.STATUS_ACTIVE.equals(entity.getStatus())) {
      throw new IllegalArgumentException("只有ACTIVE状态的无废料确认记录允许撤销");
    }
    entity.setStatus(MakePartNoScrapConfirmation.STATUS_REVOKED);
    entity.setRevokedBy(currentOperator(operator));
    entity.setRevokedAt(LocalDateTime.now());
    entity.setRevokeReason(firstText(
        request == null ? null : request.getRevokeReason(),
        "撤销人工确认无废料"));
    mapper.updateById(entity);
    writeOperationLog("撤销无废料确认", OperationType.UPDATE, entity, currentOperator(operator));
    return NoScrapConfirmResponse.from(entity);
  }

  @Override
  public NoScrapConfirmationPageResponse page(NoScrapConfirmationPageRequest request) {
    NoScrapConfirmationPageRequest safe =
        request == null ? new NoScrapConfirmationPageRequest() : request;
    Page<MakePartNoScrapConfirmation> page =
        mapper.selectPage(
            new Page<>(normalizePage(safe.getPage()), normalizePageSize(safe.getPageSize())),
            buildPageQuery(safe));
    List<NoScrapConfirmResponse> records =
        page.getRecords().stream().map(NoScrapConfirmResponse::from).toList();
    return new NoScrapConfirmationPageResponse(page.getTotal(), records);
  }

  @Override
  public NoScrapConfirmResponse findEffective(
      String materialNo, String periodMonth, String businessUnitType) {
    String normalizedMaterial = requireText(materialNo, "materialNo");
    YearMonth period = parseMonth(periodMonth, "periodMonth");
    String normalizedBu = normalizeBusinessUnitType(businessUnitType);
    return activeRows(normalizedBu, normalizedMaterial).stream()
        .filter(row -> isEffective(row, period))
        .max(Comparator.comparing(MakePartNoScrapConfirmation::getEffectiveFromMonth))
        .map(NoScrapConfirmResponse::from)
        .orElse(null);
  }

  private LambdaQueryWrapper<MakePartNoScrapConfirmation> buildPageQuery(
      NoScrapConfirmationPageRequest request) {
    LambdaQueryWrapper<MakePartNoScrapConfirmation> query = Wrappers.lambdaQuery();
    eqIfText(query, MakePartNoScrapConfirmation::getBusinessUnitType, request.getBusinessUnitType());
    eqIfText(query, MakePartNoScrapConfirmation::getMaterialNo, request.getMaterialNo());
    eqIfText(query, MakePartNoScrapConfirmation::getStatus, request.getStatus());
    eqIfText(query, MakePartNoScrapConfirmation::getSourceOaNo, request.getSourceOaNo());
    query.orderByDesc(MakePartNoScrapConfirmation::getConfirmedAt)
        .orderByDesc(MakePartNoScrapConfirmation::getId);
    return query;
  }

  private void eqIfText(
      LambdaQueryWrapper<MakePartNoScrapConfirmation> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<
              MakePartNoScrapConfirmation, ?> column,
      String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private List<MakePartNoScrapConfirmation> activeRows(String businessUnitType, String materialNo) {
    return mapper.selectList(
        Wrappers.lambdaQuery(MakePartNoScrapConfirmation.class)
            .eq(MakePartNoScrapConfirmation::getBusinessUnitType, businessUnitType)
            .eq(MakePartNoScrapConfirmation::getMaterialNo, materialNo)
            .eq(MakePartNoScrapConfirmation::getStatus,
                MakePartNoScrapConfirmation.STATUS_ACTIVE));
  }

  private ValidatedConfirm validateConfirmRequest(NoScrapConfirmRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("确认请求不能为空");
    }
    String materialNo = requireText(request.getMaterialNo(), "materialNo");
    YearMonth from = parseMonth(request.getEffectiveFromMonth(), "effectiveFromMonth");
    YearMonth to = null;
    if (StringUtils.hasText(request.getEffectiveToMonth())) {
      to = parseMonth(request.getEffectiveToMonth(), "effectiveToMonth");
      if (to.isBefore(from)) {
        throw new IllegalArgumentException("effectiveToMonth 不能早于 effectiveFromMonth");
      }
    }
    String reason = requireText(request.getConfirmReason(), "confirmReason");
    if (reason.length() < MIN_REASON_LENGTH) {
      throw new IllegalArgumentException("confirmReason 去空格后不能少于5个字符");
    }
    return new ValidatedConfirm(
        normalizeBusinessUnitType(request.getBusinessUnitType()), materialNo, from, to, reason);
  }

  private boolean overlaps(
      MakePartNoScrapConfirmation existing, YearMonth requestedFrom, YearMonth requestedTo) {
    if (!MakePartNoScrapConfirmation.STATUS_ACTIVE.equals(existing.getStatus())) {
      return false;
    }
    YearMonth existingFrom = parseMonth(existing.getEffectiveFromMonth(), "effectiveFromMonth");
    YearMonth existingTo = StringUtils.hasText(existing.getEffectiveToMonth())
        ? parseMonth(existing.getEffectiveToMonth(), "effectiveToMonth")
        : null;
    return !requestedFrom.isAfter(maxOrInfinity(existingTo))
        && !existingFrom.isAfter(maxOrInfinity(requestedTo));
  }

  private boolean isEffective(MakePartNoScrapConfirmation row, YearMonth period) {
    if (!MakePartNoScrapConfirmation.STATUS_ACTIVE.equals(row.getStatus())) {
      return false;
    }
    YearMonth from = parseMonth(row.getEffectiveFromMonth(), "effectiveFromMonth");
    if (period.isBefore(from)) {
      return false;
    }
    if (!StringUtils.hasText(row.getEffectiveToMonth())) {
      return true;
    }
    return !period.isAfter(parseMonth(row.getEffectiveToMonth(), "effectiveToMonth"));
  }

  private YearMonth maxOrInfinity(YearMonth value) {
    return value == null ? YearMonth.of(9999, 12) : value;
  }

  private YearMonth parseMonth(String value, String fieldName) {
    String normalized = requireText(value, fieldName);
    try {
      return YearMonth.parse(normalized);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException(fieldName + " 必须为 yyyy-MM 格式", ex);
    }
  }

  private String normalizeBusinessUnitType(String requested) {
    return firstText(requested, BusinessUnitContext.getCurrentBusinessUnitType(), "");
  }

  private String requireText(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + " 不能为空");
    }
    return value.trim();
  }

  private String firstText(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return "";
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String currentOperator(String operator) {
    return StringUtils.hasText(operator) ? operator.trim() : "system";
  }

  private void writeOperationLog(
      String action,
      OperationType operationType,
      MakePartNoScrapConfirmation entity,
      String operator) {
    try {
      SysOperationLog record = new SysOperationLog();
      record.setTitle("价格准备无废料确认");
      record.setBusinessType(operationType.getCode());
      record.setMethod("MakePartNoScrapConfirmationServiceImpl." + action);
      record.setRequestMethod("POST");
      record.setOperatorType(1);
      record.setOperName(operator);
      record.setOperUrl("/api/v1/cost/price-prepare/no-scrap-confirmations");
      record.setOperParam(auditJson(action, entity));
      record.setStatus(0);
      record.setJsonResult(resultJson(entity));
      record.setOperTime(LocalDateTime.now());
      record.setBusinessUnitType(entity.getBusinessUnitType());
      record.setTargetId(String.valueOf(entity.getId() == null ? entity.getMaterialNo() : entity.getId()));
      operationLogMapper.insert(record);
    } catch (RuntimeException ex) {
      log.warn("无废料确认操作日志写入失败", ex);
    }
  }

  private String auditJson(String action, MakePartNoScrapConfirmation entity) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("confirmationId", entity.getId());
    data.put("businessUnitType", entity.getBusinessUnitType());
    data.put("materialNo", entity.getMaterialNo());
    data.put("materialName", entity.getMaterialName());
    data.put("effectiveFromMonth", entity.getEffectiveFromMonth());
    data.put("effectiveToMonth", entity.getEffectiveToMonth());
    data.put("status", entity.getStatus());
    data.put("sourceOaNo", entity.getSourceOaNo());
    data.put("sourceGapId", entity.getSourceGapId());
    data.put("confirmReason", entity.getConfirmReason());
    data.put("revokeReason", entity.getRevokeReason());
    data.put("confirmedBy", entity.getConfirmedBy());
    data.put("revokedBy", entity.getRevokedBy());
    return toJson(data);
  }

  private String resultJson(MakePartNoScrapConfirmation entity) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", entity.getId());
    data.put("materialNo", entity.getMaterialNo());
    data.put("status", entity.getStatus());
    return toJson(data);
  }

  private String toJson(Map<String, Object> data) {
    try {
      return objectMapper.writeValueAsString(data);
    } catch (JsonProcessingException ex) {
      return data.toString();
    }
  }

  private int normalizePage(Integer page) {
    return page == null || page < 1 ? 1 : page;
  }

  private int normalizePageSize(Integer pageSize) {
    if (pageSize == null || pageSize < 1) {
      return 20;
    }
    return Math.min(pageSize, 200);
  }

  private record ValidatedConfirm(
      String businessUnitType,
      String materialNo,
      YearMonth from,
      YearMonth to,
      String reason) {}
}

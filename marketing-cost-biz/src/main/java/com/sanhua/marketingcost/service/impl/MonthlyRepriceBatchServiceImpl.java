package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.MonthlyRepriceProperties;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateResponse;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.enums.MonthlyRepriceExecutionBackend;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MonthlyRepriceBatchService;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceBatchServiceImpl implements MonthlyRepriceBatchService {

  private static final String ROLE_BU_DIRECTOR = "BU_DIRECTOR";
  private static final String PERM_OPERATE = "price:monthly-reprice:operate";
  private static final String STATUS_CREATED = "CREATED";
  private static final String BOM_SOURCE_POLICY = CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM;
  private static final List<String> ACTIVE_STATUSES =
      List.of("CREATED", "PREPARING", "RUNNING", "WAIT_CONFIRM");
  private static final DateTimeFormatter REPRICE_NO_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

  private final MonthlyRepriceBatchMapper batchMapper;
  private final MonthlyRepriceAuditLogMapper auditLogMapper;
  private final PermissionService permissionService;
  private final MonthlyRepriceProperties monthlyRepriceProperties;
  private final ObjectMapper objectMapper;

  public MonthlyRepriceBatchServiceImpl(
      MonthlyRepriceBatchMapper batchMapper,
      MonthlyRepriceAuditLogMapper auditLogMapper,
      PermissionService permissionService,
      MonthlyRepriceProperties monthlyRepriceProperties,
      ObjectMapper objectMapper) {
    this.batchMapper = batchMapper;
    this.auditLogMapper = auditLogMapper;
    this.permissionService = permissionService;
    this.monthlyRepriceProperties = monthlyRepriceProperties;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceBatchCreateResponse createBatch(
      MonthlyRepriceBatchCreateRequest request, String operator) {
    assertBusinessDirector();
    String pricingMonth = normalizePricingMonth(required("pricingMonth", requestPricingMonth(request)));
    String businessUnitType = requestBusinessUnitType(request);
    assertMonthlyRepriceEnabled(businessUnitType);
    String executionBackend = currentExecutionBackend();

    // 服务层先做一次友好提示；V127 生成列唯一索引是最终并发兜底，防止多 API 实例同时发起。
    if (hasActiveBatch(businessUnitType)) {
      throw new IllegalArgumentException(
          "当前业务单元正在月度调价，不能重复发起：" + businessUnitType);
    }

    MonthlyRepriceBatch batch =
        buildBatch(request, pricingMonth, businessUnitType, executionBackend, operator);
    try {
      batchMapper.insert(batch);
    } catch (DuplicateKeyException ex) {
      throw new IllegalArgumentException(
          "当前业务单元正在月度调价，不能重复发起：" + businessUnitType, ex);
    }

    // 审计日志和批次创建在同一事务提交，避免出现“有批次无审计”的追溯断点。
    auditLogMapper.insert(buildCreateAuditLog(batch, operator));
    return MonthlyRepriceBatchCreateResponse.fromEntity(batch);
  }

  @Override
  public boolean hasActiveBatch(String businessUnitType) {
    String normalized = normalize(businessUnitType);
    if (!StringUtils.hasText(normalized)) {
      return false;
    }
    Long count = batchMapper.selectCount(Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
        .eq(MonthlyRepriceBatch::getBusinessUnitType, normalized)
        .in(MonthlyRepriceBatch::getStatus, ACTIVE_STATUSES));
    return count != null && count > 0;
  }

  private void assertBusinessDirector() {
    if (BusinessUnitContext.isAdmin()
        || permissionService.hasRole(ROLE_BU_DIRECTOR)
        || permissionService.hasPermi(PERM_OPERATE)) {
      return;
    }
    throw new AccessDeniedException("只有业务总监可以发起月度调价");
  }

  private MonthlyRepriceBatch buildBatch(
      MonthlyRepriceBatchCreateRequest request,
      String pricingMonth,
      String businessUnitType,
      String executionBackend,
      String operator) {
    LocalDateTime now = LocalDateTime.now();
    String normalizedOperator = normalizeOperator(operator);
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setRepriceNo(nextRepriceNo());
    batch.setPricingMonth(pricingMonth);
    batch.setPriceAsOfTime(now);
    batch.setBomSourcePolicy(BOM_SOURCE_POLICY);
    batch.setBusinessUnitType(businessUnitType);
    batch.setAdjustBatchId(null);
    batch.setExecutionBackend(executionBackend);
    batch.setStatus(STATUS_CREATED);
    batch.setTotalCount(0);
    batch.setSuccessCount(0);
    batch.setFailedCount(0);
    batch.setSkippedCount(0);
    batch.setCreatedBy(normalizedOperator);
    batch.setCreatedName(normalizedOperator);
    batch.setRemark(trimToNull(request == null ? null : request.getRemark()));
    batch.setCreatedAt(now);
    batch.setUpdatedAt(now);
    return batch;
  }

  private MonthlyRepriceAuditLog buildCreateAuditLog(MonthlyRepriceBatch batch, String operator) {
    MonthlyRepriceAuditLog log = new MonthlyRepriceAuditLog();
    log.setRepriceNo(batch.getRepriceNo());
    log.setPricingMonth(batch.getPricingMonth());
    log.setBusinessUnitType(batch.getBusinessUnitType());
    log.setOperationType("CREATE_BATCH");
    log.setOperationName("发起月度调价批次");
    log.setOperatorId(normalizeOperator(operator));
    log.setOperatorName(normalizeOperator(operator));
    log.setOperatorRole(operatorRole());
    log.setOperationTime(LocalDateTime.now());
    log.setTargetType("MONTHLY_REPRICE_BATCH");
    log.setTargetId(batch.getId() == null ? null : String.valueOf(batch.getId()));
    log.setTargetKey(batch.getRepriceNo());
    log.setBeforeJson(null);
    log.setAfterJson(afterJson(batch));
    log.setChangeSummary(
        "创建月度调价批次，固化取价时点 " + batch.getPriceAsOfTime()
            + "，按全价格源重算");
    log.setRemark(batch.getRemark());
    return log;
  }

  private String afterJson(MonthlyRepriceBatch batch) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("repriceNo", batch.getRepriceNo());
    snapshot.put("pricingMonth", batch.getPricingMonth());
    snapshot.put("priceAsOfTime", batch.getPriceAsOfTime() == null ? null : batch.getPriceAsOfTime().toString());
    snapshot.put("bomSourcePolicy", batch.getBomSourcePolicy());
    snapshot.put("businessUnitType", batch.getBusinessUnitType());
    snapshot.put("adjustBatchId", batch.getAdjustBatchId());
    snapshot.put("executionBackend", batch.getExecutionBackend());
    snapshot.put("status", batch.getStatus());
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("月度调价审计快照序列化失败", ex);
    }
  }

  private String operatorRole() {
    if (BusinessUnitContext.isAdmin()) {
      return "ADMIN";
    }
    if (permissionService.hasRole(ROLE_BU_DIRECTOR)) {
      return ROLE_BU_DIRECTOR;
    }
    if (permissionService.hasPermi(PERM_OPERATE)) {
      return "MONTHLY_REPRICE_OPERATOR";
    }
    return null;
  }

  private String requestBusinessUnitType(MonthlyRepriceBatchCreateRequest request) {
    String requested = normalize(request == null ? null : request.getBusinessUnitType());
    String current = normalize(BusinessUnitContext.getCurrentBusinessUnitType());
    if (StringUtils.hasText(current)
        && StringUtils.hasText(requested)
        && !current.equals(requested)
        && !BusinessUnitContext.isAdmin()) {
      throw new IllegalArgumentException("月度调价业务单元与当前登录业务单元不一致");
    }
    String businessUnitType = StringUtils.hasText(requested) ? requested : current;
    if (!StringUtils.hasText(businessUnitType)) {
      throw new IllegalArgumentException("请先选择业务单元后再发起月度调价");
    }
    return businessUnitType;
  }

  private void assertMonthlyRepriceEnabled(String businessUnitType) {
    if (!monthlyRepriceProperties.isEnabled()) {
      throw new IllegalArgumentException("月度调价入口未开启，请联系管理员开启灰度开关");
    }
    if (!monthlyRepriceProperties.isBusinessUnitAllowed(businessUnitType)) {
      throw new IllegalArgumentException("当前业务单元未纳入月度调价灰度范围：" + businessUnitType);
    }
  }

  private String currentExecutionBackend() {
    MonthlyRepriceExecutionBackend executionBackend =
        monthlyRepriceProperties.getExecutionBackendType();
    if (!executionBackend.isSupportedInCurrentPhase()) {
      throw new IllegalArgumentException("第一阶段月度调价只支持 LOCAL_WORKER 执行后端");
    }
    return executionBackend.getCode();
  }

  private String requestPricingMonth(MonthlyRepriceBatchCreateRequest request) {
    return request == null ? null : request.getPricingMonth();
  }

  private String normalizePricingMonth(String pricingMonth) {
    try {
      return YearMonth.parse(pricingMonth).toString();
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("pricingMonth 格式必须是 YYYY-MM", ex);
    }
  }

  private String required(String field, String value) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      throw new IllegalArgumentException(field + " 必填");
    }
    return normalized;
  }

  private String normalizeOperator(String operator) {
    String normalized = normalize(operator);
    return StringUtils.hasText(normalized) ? normalized : "system";
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String trimToNull(String value) {
    String normalized = normalize(value);
    return StringUtils.hasText(normalized) ? normalized : null;
  }

  private String nextRepriceNo() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    return "MRP" + LocalDateTime.now().format(REPRICE_NO_TIME_FORMAT) + suffix;
  }
}

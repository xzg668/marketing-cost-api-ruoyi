package com.sanhua.marketingcost.service.bomalternative;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 标准/替代选择的前后快照审计。 */
@Service
public class QuoteBomAlternativeAuditServiceImpl
    implements QuoteBomAlternativeAuditService {

  static final String AUDIT_TITLE = "报价BOM标准/替代选择";

  private final SysOperationLogMapper operationLogMapper;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public QuoteBomAlternativeAuditServiceImpl(
      SysOperationLogMapper operationLogMapper,
      ObjectMapper objectMapper) {
    this(operationLogMapper, objectMapper, Clock.systemDefaultZone());
  }

  public QuoteBomAlternativeAuditServiceImpl(
      SysOperationLogMapper operationLogMapper,
      ObjectMapper objectMapper,
      Clock clock) {
    this.operationLogMapper =
        Objects.requireNonNull(operationLogMapper, "operationLogMapper");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void recordSelectionChange(
      QuoteBomAlternativeSelectionScope scope,
      String groupKey,
      QuoteBomAlternativeSelection before,
      QuoteBomAlternativeSelectionResult after,
      String operator,
      String selectionRemark) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(after, "after");
    LocalDateTime now = LocalDateTime.now(clock);
    String normalizedOperator =
        StringUtils.hasText(operator) ? operator.trim() : "system";

    Map<String, Object> afterSnapshot =
        resultSnapshot(
            scope, groupKey, after, normalizedOperator, now);
    SysOperationLog log = new SysOperationLog();
    log.setTitle(AUDIT_TITLE);
    log.setBusinessType(OperationType.UPDATE.getCode());
    log.setMethod(
        "QuoteBomAlternativeApplicationServiceImpl.saveSelection");
    log.setRequestMethod("PUT");
    log.setOperatorType(1);
    log.setOperName(normalizedOperator);
    log.setOperUrl(
        "/api/v1/quote-requests/"
            + scope.oaNo()
            + "/items/"
            + scope.oaFormItemId()
            + "/costing-bom/alternative-groups/"
            + groupKey
            + "/selection");
    log.setOperParam(
        toJson(
            Map.of(
                "selectionRemark",
                StringUtils.hasText(selectionRemark)
                    ? selectionRemark.trim()
                    : "")));
    log.setStatus(0);
    log.setOperTime(now);
    log.setCostTime(0L);
    log.setBusinessUnitType(scope.businessUnitType());
    log.setTargetId(groupKey);
    log.setBeforeData(toJson(entitySnapshot(scope, before)));
    log.setAfterData(toJson(afterSnapshot));
    log.setJsonResult(log.getAfterData());
    if (operationLogMapper.insert(log) != 1) {
      throw new IllegalStateException(
          "报价BOM标准/替代选择操作日志写入失败");
    }
  }

  private Map<String, Object> entitySnapshot(
      QuoteBomAlternativeSelectionScope scope,
      QuoteBomAlternativeSelection row) {
    if (row == null) {
      return Map.of();
    }
    Map<String, Object> snapshot = baseSnapshot(scope);
    snapshot.put(
        "alternativeGroupKey", row.getAlternativeGroupKey());
    snapshot.put("selectionNo", row.getSelectionNo());
    snapshot.put(
        "standardMaterialCode", row.getStandardMaterialCode());
    snapshot.put(
        "selectedMaterialCode", row.getSelectedMaterialCode());
    snapshot.put("selectedChildType", row.getSelectedChildType());
    snapshot.put("selectionSource", row.getSelectionSource());
    snapshot.put("selectionVersion", row.getSelectionVersion());
    snapshot.put("selectionStatus", row.getSelectionStatus());
    snapshot.put(
        "sourceImportBatchId", row.getSourceImportBatchId());
    snapshot.put(
        "sourceBuildBatchId", row.getSourceBuildBatchId());
    snapshot.put("operator", row.getSelectedBy());
    snapshot.put(
        "operatedAt",
        row.getSelectedAt() == null
            ? null
            : row.getSelectedAt().toString());
    snapshot.put("selectionRemark", row.getSelectionRemark());
    return snapshot;
  }

  private Map<String, Object> resultSnapshot(
      QuoteBomAlternativeSelectionScope scope,
      String groupKey,
      QuoteBomAlternativeSelectionResult row,
      String operator,
      LocalDateTime operatedAt) {
    Map<String, Object> snapshot = baseSnapshot(scope);
    snapshot.put("alternativeGroupKey", groupKey);
    snapshot.put("selectionNo", row.selectionNo());
    snapshot.put(
        "standardMaterialCode", row.standardMaterialCode());
    snapshot.put(
        "selectedMaterialCode", row.selectedMaterialCode());
    snapshot.put(
        "selectedChildType",
        row.selectedChildType() == null
            ? null
            : row.selectedChildType().name());
    snapshot.put("selectionSource", row.selectionSource());
    snapshot.put("selectionVersion", row.selectionVersion());
    snapshot.put("selectionStatus", row.selectionStatus());
    snapshot.put(
        "sourceImportBatchId", row.sourceImportBatchId());
    snapshot.put(
        "sourceBuildBatchId", row.sourceBuildBatchId());
    snapshot.put("operator", operator);
    snapshot.put("operatedAt", operatedAt.toString());
    return snapshot;
  }

  private Map<String, Object> baseSnapshot(
      QuoteBomAlternativeSelectionScope scope) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("oaNo", scope.oaNo());
    snapshot.put("oaFormItemId", scope.oaFormItemId());
    snapshot.put("topProductCode", scope.topProductCode());
    snapshot.put("periodMonth", scope.periodMonth());
    snapshot.put("priceOrgCode", scope.priceOrgCode());
    snapshot.put(
        "businessUnitType", scope.businessUnitType());
    return snapshot;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "报价BOM标准/替代选择审计序列化失败", exception);
    }
  }
}

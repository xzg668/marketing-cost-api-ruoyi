package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class TechnicalDataAuditLogService {
  public static final String DOMAIN = "QUOTE_TECHNICAL_DATA";
  private final BusinessChangeLogMapper mapper;

  public TechnicalDataAuditLogService(BusinessChangeLogMapper mapper) {
    this.mapper = mapper;
  }

  public void record(
      QuoteTechTask task,
      QuoteTechProduct product,
      Long detailId,
      String eventType,
      String before,
      String after,
      String reason,
      TechnicalDataActor actor,
      String requestId) {
    record(task, product, detailId, eventType, before, after, reason, actor, requestId, null);
  }

  public void record(
      QuoteTechTask task,
      QuoteTechProduct product,
      Long detailId,
      String eventType,
      String before,
      String after,
      String reason,
      TechnicalDataActor actor,
      String requestId,
      String idempotencyKey) {
    BusinessChangeLog log = new BusinessChangeLog();
    log.setBizDomain(DOMAIN);
    log.setBizType("TECH_DATA_WORKFLOW_EVENT");
    log.setBizId(task.getId());
    log.setBizDetailId(detailId);
    log.setOaNo(task.getOaNo());
    log.setOaFormItemId(product == null ? null : product.getOaFormItemId());
    log.setTaskId(task.getId());
    log.setFieldName(eventType);
    log.setFieldLabel("技术资料补录审核");
    log.setBeforeValue(before);
    log.setAfterValue(after);
    log.setChangeReason(reason);
    log.setChangedBy(actor.userId());
    log.setChangedByName(actor.name());
    log.setChangedAt(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
    log.setChangeSource(actor.admin() ? "ADMIN_PROXY" : "TECHNICAL_DATA");
    log.setSubmitBatchNo("TD-R" + task.getReviewRound());
    log.setRequestId(requestId);
    log.setIdempotencyKey(idempotencyKey);
    if (mapper.insert(log) != 1) {
      throw new TechnicalDataTaskException(
          TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT, "技术资料审计日志保存失败");
    }
  }

  public void recordSystem(
      QuoteTechTask task,
      String eventType,
      String before,
      String after,
      String reason,
      String requestId,
      String idempotencyKey) {
    BusinessChangeLog log = new BusinessChangeLog();
    log.setBizDomain(DOMAIN);
    log.setBizType("TECH_DATA_INTEGRATION_EVENT");
    log.setBizId(task.getId());
    log.setTaskId(task.getId());
    log.setOaNo(task.getOaNo());
    log.setFieldName(eventType);
    log.setFieldLabel("技术资料OA协同");
    log.setBeforeValue(before);
    log.setAfterValue(after);
    log.setChangeReason(reason);
    log.setChangedAt(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
    log.setChangeSource("OA_INTEGRATION");
    log.setRequestId(requestId);
    log.setIdempotencyKey(idempotencyKey);
    if (mapper.insert(log) != 1) {
      throw new TechnicalDataTaskException(
          TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT, "技术资料OA审计日志保存失败");
    }
  }
}

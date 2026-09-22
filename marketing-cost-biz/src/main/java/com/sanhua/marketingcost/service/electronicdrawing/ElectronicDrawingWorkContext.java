package com.sanhua.marketingcost.service.electronicdrawing;

import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 电子图库处理所需的稳定业务上下文。
 *
 * <p>核心能力按报价产品和核算月份读取 BOM 准备记录及源版本，供补录、财务确认及核算调度共用。
 */
public record ElectronicDrawingWorkContext(
    Long workflowId,
    Integer revision,
    Long preparationId,
    Long sourceVersionId,
    Long oaFormId,
    Long oaFormItemId,
    String taskNo,
    String oaNo,
    String quoteProductCode,
    String temporaryProductKey,
    String productName,
    String productSpec,
    String productModel,
    String productType,
    String primaryScope,
    String accountingMonth,
    String priceOrgCode,
    String materialOrgCode,
    String businessUnitType,
    String applicableOrgCode,
    boolean active,
    boolean bomRequired,
    String workflowStatus,
    String workflowStage,
    Long assigneeUserId,
    String assigneeName,
    String compositionFingerprint) {

  private static final Set<String> PUBLISHED_STATUSES = Set.of(
      "READY_FOR_COSTING", "COSTING", "COMPLETED");

  public boolean published() {
    return PUBLISHED_STATUSES.contains(workflowStatus)
        && sourceVersionId != null
        && StringUtils.hasText(compositionFingerprint);
  }
}

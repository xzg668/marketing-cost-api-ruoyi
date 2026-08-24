package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** BOM 分支改变后的报价产品下游状态失效编排。 */
@Service
public class QuoteBomAlternativeWorkflowInvalidationServiceImpl
    implements QuoteBomAlternativeWorkflowInvalidationService {

  private final QuoteCostingWorkspaceService workspaceService;
  private final QuoteCostRunVersionInvalidationService
      costRunInvalidationService;

  public QuoteBomAlternativeWorkflowInvalidationServiceImpl(
      QuoteCostingWorkspaceService workspaceService,
      QuoteCostRunVersionInvalidationService
          costRunInvalidationService) {
    this.workspaceService = workspaceService;
    this.costRunInvalidationService = costRunInvalidationService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomAlternativeWorkflowInvalidationResult invalidate(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth) {
    String normalizedOaNo = required("oaNo", oaNo);
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException("oaFormItemId不能为空");
    }
    String normalizedProduct = required("productCode", productCode);
    String normalizedMonth = required("periodMonth", periodMonth);
    workspaceService.markItemStale(
        oaFormItemId, normalizedMonth, "BOM_ALTERNATIVE_CHANGED");
    int costRunCount =
        costRunInvalidationService.invalidateProduct(
            normalizedOaNo,
            oaFormItemId,
            normalizedProduct,
            normalizedMonth);
    return new QuoteBomAlternativeWorkflowInvalidationResult(
        0, 0, costRunCount);
  }

  private static String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }
}

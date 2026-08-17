package com.sanhua.marketingcost.service.collaboration.scan;

import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductForm;
import java.util.List;

/** 扫描结果不携带任何“已创建”语义，供后续发起命令和页面投影消费。 */
public record QuoteCollaborationScanResult(
    Long oaFormItemId,
    String oaNo,
    String accountingMonth,
    String productCode,
    String businessUnitType,
    String priceOrgCode,
    String materialOrganizationCode,
    ProductForm productForm,
    QuoteCollaborationScanStatus status,
    QuoteCollaborationScanAction action,
    PrimaryScope requiredScope,
    String authoritativeBomSource,
    String bomVersion,
    int bomLineCount,
    Long activeProductTaskId,
    String activeAssigneeName,
    Long approvedResultId,
    CollaborationPriceScanResult price,
    List<QuoteCollaborationScanStage> completedStages,
    QuoteCollaborationScanErrorCode errorCode,
    String message) {

  public QuoteCollaborationScanResult {
    completedStages = completedStages == null ? List.of() : List.copyOf(completedStages);
    price =
        price == null
            ? CollaborationPriceScanResult.error("价格扫描结果缺失")
            : price;
  }
}

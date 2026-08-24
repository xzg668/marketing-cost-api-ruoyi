package com.sanhua.marketingcost.service;

public interface ProductCostingStateService {

  String bindCurrentPriceFingerprint(
      String oaNo, Long oaFormItemId, String periodMonth, String prepareNo);

  void markBlocked(
      String oaNo,
      Long oaFormItemId,
      String periodMonth,
      String workspaceStatus,
      String currentStep,
      String errorCode,
      String message,
      int gapCount);

  void markSystemFailed(
      String oaNo,
      Long oaFormItemId,
      String periodMonth,
      String currentStep,
      String errorCode,
      String message);
}

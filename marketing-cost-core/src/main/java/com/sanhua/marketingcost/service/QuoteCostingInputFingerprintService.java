package com.sanhua.marketingcost.service;

import java.util.List;

/**
 * 计算报价产品一次核算所依赖输入的稳定指纹。
 *
 * <p>调用方只应传入会改变核算结果的稳定标识，不要传产品名称、状态文案等展示字段。
 */
public interface QuoteCostingInputFingerprintService {

  String calculate(Input input);

  record Input(
      Long oaFormItemId,
      String productCode,
      String periodMonth,
      String packageMethod,
      String packageComponentCode,
      String packageQuantity,
      String productAttribute,
      String businessType,
      String bomSourceFingerprint,
      String bomRuleFingerprint,
      List<String> alternativeSelections,
      List<PriceReference> priceReferences,
      List<String> configurationVersions) {

    public Input {
      alternativeSelections = alternativeSelections == null ? List.of() : List.copyOf(alternativeSelections);
      priceReferences = priceReferences == null ? List.of() : List.copyOf(priceReferences);
      configurationVersions = configurationVersions == null ? List.of() : List.copyOf(configurationVersions);
    }
  }

  record PriceReference(
      Long priceTypeRecordId,
      String priceType,
      Long priceSourceRecordId,
      String supplierCode,
      Long supplyRatioRecordId) {}
}

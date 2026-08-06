package com.sanhua.marketingcost.service.bomalternative;

import java.util.List;

/** 报价维度标准/替代选择、版本历史和失效服务。 */
public interface QuoteBomAlternativeSelectionService {

  QuoteBomAlternativeSelectionResult ensureDefault(
      QuoteBomAlternativeSelectionScope scope,
      BomAlternativeGroup group);

  QuoteBomAlternativeSelectionResult save(
      QuoteBomAlternativeSelectionCommand command,
      BomAlternativeGroup group);

  QuoteBomAlternativeSelectionResult reconcile(
      QuoteBomAlternativeSelectionScope scope,
      BomAlternativeGroup currentGroup);

  List<QuoteBomAlternativeSelectionResult> synchronize(
      QuoteBomAlternativeSelectionScope scope,
      List<BomAlternativeGroup> currentGroups);

  QuoteBomAlternativeSelectionResult findCurrent(
      QuoteBomAlternativeSelectionScope scope,
      String alternativeGroupKey);

  List<QuoteBomAlternativeSelectionResult> history(
      QuoteBomAlternativeSelectionScope scope,
      String alternativeGroupKey);
}

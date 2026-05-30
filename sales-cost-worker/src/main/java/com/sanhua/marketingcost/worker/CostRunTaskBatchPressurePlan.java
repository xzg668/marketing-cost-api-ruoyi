package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

public record CostRunTaskBatchPressurePlan(
    int taskCount,
    int sceneCount,
    int sourceCount,
    int oaGroupCount,
    int productCount,
    Set<MonthlyRepriceContextKey> monthlyRepriceContextKeys,
    Set<OaContextKey> oaContextKeys,
    Set<BomContextKey> bomContextKeys,
    List<OaTaskGroup> oaGroups,
    List<ContextViolation> contextViolations) {

  public static CostRunTaskBatchPressurePlan from(List<CostRunTask> tasks) {
    List<CostRunTask> safeTasks = tasks == null ? List.of() : tasks;
    Set<String> scenes = new LinkedHashSet<>();
    Set<String> sources = new LinkedHashSet<>();
    Set<String> products = new LinkedHashSet<>();
    Set<MonthlyRepriceContextKey> monthlyContexts = new LinkedHashSet<>();
    Set<OaContextKey> oaContexts = new LinkedHashSet<>();
    Set<BomContextKey> bomContexts = new LinkedHashSet<>();
    Map<OaGroupKey, MutableOaGroup> oaGroups = new LinkedHashMap<>();
    Map<MonthlyBatchKey, Set<MonthlyRepriceContextKey>> contextsByBatch = new LinkedHashMap<>();

    for (CostRunTask task : safeTasks) {
      String scene = normalized(task.getScene());
      String sourceNo = normalized(task.getSourceNo());
      String oaNo = normalized(task.getOaNo());
      String productCode = normalized(task.getProductCode());
      scenes.add(scene);
      sources.add(sourceNo);
      products.add(productCode);
      if (StringUtils.hasText(oaNo)) {
        oaContexts.add(new OaContextKey(oaNo));
        OaGroupKey oaGroupKey = new OaGroupKey(scene, sourceNo, oaNo);
        oaGroups.computeIfAbsent(oaGroupKey, key -> new MutableOaGroup(key))
            .add(productCode);
      }
      if (StringUtils.hasText(oaNo) && StringUtils.hasText(productCode)) {
        bomContexts.add(new BomContextKey(oaNo, productCode, normalized(task.getBomSourcePolicy())));
      }

      if (CostRunTaskScene.MONTHLY_REPRICE.name().equals(scene)) {
        MonthlyRepriceContextKey contextKey =
            new MonthlyRepriceContextKey(
                normalized(task.getBusinessUnitType()),
                normalized(task.getPricingMonth()),
                task.getPriceAsOfTime(),
                task.getAdjustBatchId(),
                normalized(task.getBomSourcePolicy()));
        monthlyContexts.add(contextKey);
        MonthlyBatchKey batchKey = new MonthlyBatchKey(normalized(task.getBatchNo()), sourceNo);
        contextsByBatch.computeIfAbsent(batchKey, ignored -> new LinkedHashSet<>()).add(contextKey);
      }
    }

    List<ContextViolation> violations = contextsByBatch.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .map(entry -> new ContextViolation(
            entry.getKey().batchNo(),
            entry.getKey().sourceNo(),
            entry.getValue().stream()
                .map(MonthlyRepriceContextKey::compact)
                .sorted()
                .toList()))
        .toList();

    List<OaTaskGroup> sortedGroups = oaGroups.values().stream()
        .map(MutableOaGroup::toGroup)
        .sorted(Comparator
            .comparing(OaTaskGroup::scene)
            .thenComparing(OaTaskGroup::sourceNo)
            .thenComparing(OaTaskGroup::oaNo))
        .toList();

    return new CostRunTaskBatchPressurePlan(
        safeTasks.size(),
        countMeaningful(scenes),
        countMeaningful(sources),
        sortedGroups.size(),
        countMeaningful(products),
        Set.copyOf(monthlyContexts),
        Set.copyOf(oaContexts),
        Set.copyOf(bomContexts),
        sortedGroups,
        violations);
  }

  public boolean hasContextViolations() {
    return !contextViolations.isEmpty();
  }

  public int recommendedClaimBatches(int claimBatchSize) {
    if (taskCount == 0) {
      return 0;
    }
    int safeBatchSize = claimBatchSize > 0 ? claimBatchSize : 100;
    return (int) Math.ceil((double) taskCount / safeBatchSize);
  }

  private static int countMeaningful(Set<String> values) {
    return (int) values.stream().filter(StringUtils::hasText).count();
  }

  private static String normalized(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  public record MonthlyRepriceContextKey(
      String businessUnitType,
      String pricingMonth,
      LocalDateTime priceAsOfTime,
      Long adjustBatchId,
      String bomSourcePolicy) {

    String compact() {
      return "businessUnitType=" + businessUnitType
          + ",pricingMonth=" + pricingMonth
          + ",priceAsOfTime=" + priceAsOfTime
          + ",adjustBatchId=" + adjustBatchId
          + ",bomSourcePolicy=" + bomSourcePolicy;
    }
  }

  public record OaContextKey(String oaNo) {
  }

  public record BomContextKey(String oaNo, String productCode, String bomSourcePolicy) {
  }

  public record OaTaskGroup(
      String scene,
      String sourceNo,
      String oaNo,
      int taskCount,
      int productCount) {
  }

  public record ContextViolation(String batchNo, String sourceNo, List<String> contexts) {
  }

  private record OaGroupKey(String scene, String sourceNo, String oaNo) {
  }

  private record MonthlyBatchKey(String batchNo, String sourceNo) {
  }

  private static final class MutableOaGroup {

    private final OaGroupKey key;
    private int taskCount;
    private final Set<String> productCodes = new LinkedHashSet<>();

    private MutableOaGroup(OaGroupKey key) {
      this.key = key;
    }

    private void add(String productCode) {
      taskCount++;
      if (StringUtils.hasText(productCode)) {
        productCodes.add(productCode);
      }
    }

    private OaTaskGroup toGroup() {
      return new OaTaskGroup(
          key.scene(),
          key.sourceNo(),
          key.oaNo(),
          taskCount,
          productCodes.size());
    }
  }
}

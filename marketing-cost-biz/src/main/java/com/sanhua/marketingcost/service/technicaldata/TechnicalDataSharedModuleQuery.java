package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSharedModuleInfo;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 先有明确的公共缺口，才查询原补录来源；不将别人草稿或批准标志当作本次核算结果。 */
@Service
@lombok.extern.slf4j.Slf4j
public class TechnicalDataSharedModuleQuery {
  private final TechnicalDataSharedModules shared;
  private final QuoteTechnicalDataRepository versions;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataDependencies dependencies;
  private final TechnicalDataTaskRepository tasks;

  public TechnicalDataSharedModuleQuery(TechnicalDataSharedModules shared, QuoteTechnicalDataRepository versions,
      TechnicalDataVersionContentCodec codec, TechnicalDataDependencies dependencies, TechnicalDataTaskRepository tasks) {
    this.tasks = tasks;
    this.shared = shared; this.versions = versions; this.codec = codec; this.dependencies = dependencies;
  }

  public List<TechnicalDataSharedModuleInfo> describe(TechnicalDataProductSource product, String month,
      List<TechnicalDataSourceFact> publicFacts) {
    List<TechnicalDataSharedModuleInfo> result = new ArrayList<>();
    for (var fact : publicFacts) {
      if (fact.availability() != TechnicalDataAvailability.MISSING || fact.moduleType() == TechnicalDataModuleType.PRICE) continue;
      String type = fact.moduleType().name();
      try {
        var owner = shared.find(product.materialNo(), product.oaFormItemId(), type);
        if (owner == null || "CANCELLED".equals(owner.taskStatus()) && !"APPROVED".equals(owner.versionStatus())) continue;
        if (owner.quoteItemId() == product.oaFormItemId() && Objects.equals(owner.accountingMonth(), month)) continue;
        if (!Objects.equals(owner.businessUnit(), product.businessUnitType())
            || !Objects.equals(owner.organization(), product.applicableOrgCode())) {
          result.add(new TechnicalDataSharedModuleInfo(type, "CONDITION_MISMATCH", null, null, null, null, null,
              "同产品已有其他业务单元或组织的补录资料，请核实适用范围，不能重复补录"));
          continue;
        }
        if (Set.of("SALARY", "NET_LOSS").contains(type)
            && YearMonth.parse(owner.accountingMonth()).getYear() != YearMonth.parse(month).getYear()) {
          result.add(info(owner, "CONDITION_MISMATCH", null, "原补录所属年度与本次不同，请核实原资料的年度适用性，不能重复补录"));
          continue;
        }
        if (!"APPROVED".equals(owner.moduleStatus()) || !"APPROVED".equals(owner.versionStatus())) {
          result.add(info(owner, "IN_PROGRESS", null, "已由" + name(owner.assigneeName()) + "办理，请等待原资料完成，不能重复补录"));
          continue;
        }
        var version = versions.findVersion(owner.versionId()).orElseThrow(() -> new IllegalStateException("原批准版本不存在"));
        if (!Objects.equals(version.getProductId(), owner.productId()) || !"APPROVED".equals(version.getVersionStatus())) {
          throw new IllegalStateException("原批准版本与办理来源不一致");
        }
        String fingerprint = codec.fingerprint(version, codec.readReferenceSnapshot(version.getReferenceSnapshotJson()),
            versions.findPackageItems(version.getId()), versions.findAuxItems(version.getId()), versions.findSalaryItems(version.getId()));
        if (version.getContentFingerprint() == null || !version.getContentFingerprint().equals(fingerprint)) {
          throw new IllegalStateException("原批准资料与冻结指纹不一致");
        }
        // 只检查当前共享模块的依赖，不让原任务其他人的无关问题影响本模块。
        var issues = dependencies.stale(version, tasks.findModules(owner.productId())).stream()
            .filter(issue -> type.equals(issue.moduleType())).toList();
        if (!issues.isEmpty()) {
          result.add(info(owner, "SOURCE_CHANGED", null, "原批准资料引用的依据已变化，请在原任务核实，不能重复补录"));
          continue;
        }
        result.add(info(owner, "APPROVED", fingerprint, "已有审批通过的补录资料，可查看原资料，无需重复分派"));
      } catch (RuntimeException exception) {
        log.warn("Shared technical source check failed: item={}, month={}, module={}", product.oaFormItemId(), month, type, exception);
        result.add(new TechnicalDataSharedModuleInfo(type, "ERROR", null, null, null, null, null,
            "原补录来源核验失败，请核实后重新检查，不能据此新建补录"));
      }
    }
    return List.copyOf(result);
  }

  private TechnicalDataSharedModuleInfo info(TechnicalDataSharedModuleRepository.Owner owner, String status,
      String fingerprint, String message) {
    return new TechnicalDataSharedModuleInfo(owner.moduleType(), status, owner.taskId(), owner.productId(),
        "APPROVED".equals(status) ? owner.versionId() : null, fingerprint, owner.assigneeName(), message);
  }

  private String name(String name) { return name == null || name.isBlank() ? "原办理人" : name; }
}

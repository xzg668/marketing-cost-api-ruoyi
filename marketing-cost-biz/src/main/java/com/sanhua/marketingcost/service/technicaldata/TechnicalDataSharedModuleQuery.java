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
  private final com.sanhua.marketingcost.mapper.OaFormItemMapper items;
  private final com.sanhua.marketingcost.mapper.OaFormMapper forms;
  private final com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver contexts;
  private final TechnicalDataPriceRequirements prices;

  public TechnicalDataSharedModuleQuery(TechnicalDataSharedModules shared, QuoteTechnicalDataRepository versions,
      TechnicalDataVersionContentCodec codec, TechnicalDataDependencies dependencies, TechnicalDataTaskRepository tasks,
      com.sanhua.marketingcost.mapper.OaFormItemMapper items, com.sanhua.marketingcost.mapper.OaFormMapper forms,
      com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver contexts, TechnicalDataPriceRequirements prices) {
    this.tasks = tasks;
    this.shared = shared; this.versions = versions; this.codec = codec; this.dependencies = dependencies;
    this.items = items; this.forms = forms; this.contexts = contexts;
    this.prices = prices;
  }

  /** 列表只刷新共享资料状态，不重新核算、不写任务，也不暴露他人的草稿内容。 */
  public List<TechnicalDataSharedModuleInfo> current(Long itemId, String month,
      List<TechnicalDataModuleRequirement> requirements) {
    if (requirements.stream().noneMatch(row -> row.required() || Objects.toString(row.reasonCode(), "").startsWith("SHARED_MODULE_")
        || "PRICE_SHARED_SAVED".equals(row.reasonCode()) || "PRICE_WAIT_ORIGINAL_OWNER".equals(row.reasonCode()))) return List.of();
    var item = items.selectById(itemId);
    var form = item == null ? null : forms.selectById(item.getOaFormId());
    if (form == null) return List.of();
    boolean priceNeeded = requirements.stream().anyMatch(row -> "PRICE".equals(row.moduleType())
        && (row.required() || Objects.toString(row.reasonCode(), "").startsWith("SHARED_MODULE_")
            || Set.of("PRICE_SHARED_SAVED", "PRICE_WAIT_ORIGINAL_OWNER").contains(Objects.toString(row.reasonCode(), ""))));
    boolean otherSource = requirements.stream().filter(row -> !"PRICE".equals(row.moduleType())
        && (row.required() || Objects.toString(row.reasonCode(), "").startsWith("SHARED_MODULE_"))).anyMatch(row -> {
          try {
            var owner = shared.find(item.getMaterialNo(), itemId, row.moduleType());
            return owner != null && (!Objects.equals(owner.quoteItemId(), itemId) || !Objects.equals(owner.accountingMonth(), month));
          } catch (RuntimeException inconsistent) { return true; }
        });
    if (!priceNeeded && !otherSource) return List.of();
    var organization = contexts.resolveOrganization(form, item);
    var product = new TechnicalDataProductSource(form.getId(), form.getOaNo(), itemId,
        item.getExternalLineId(), item.getSeq(), item.getMaterialNo(), item.getProductName(), item.getSunlModel(),
        item.getSpec(), item.getProductAttr(), null, item.getAnnualVolume(), null, item.getPackageMethod(),
        form.getBusinessUnitType(), organization.priceOrgCode(), organization.materialOrganizationCode());
    var facts = requirements.stream().map(row -> new TechnicalDataSourceFact(
        TechnicalDataModuleType.valueOf(row.moduleType()), Objects.toString(row.reasonCode(), "").startsWith("SHARED_MODULE_")
            ? TechnicalDataAvailability.MISSING : row.availability(), row.reasonCode(), row.reason(),
        row.sourceReference(), row.checkedAt())).toList();
    var result = new ArrayList<>(describe(product, month, facts));
    if (requirements.stream().anyMatch(row -> "PRICE".equals(row.moduleType()) && row.availability() != TechnicalDataAvailability.AVAILABLE)) {
      var price = prices.savedSharedSource(itemId, month, form.getBusinessUnitType(), organization.priceOrgCode());
      if (price != null) result.add(price);
    }
    return List.copyOf(result);
  }

  public List<TechnicalDataSharedModuleInfo> describe(TechnicalDataProductSource product, String month,
      List<TechnicalDataSourceFact> publicFacts) {
    List<TechnicalDataSharedModuleInfo> result = new ArrayList<>();
    for (var fact : publicFacts) {
      if (fact.availability() != TechnicalDataAvailability.MISSING || fact.moduleType() == TechnicalDataModuleType.PRICE) continue;
      String type = fact.moduleType().name();
      try {
        var owner = shared.find(product.materialNo(), product.oaFormItemId(), type);
        if (owner == null || "CANCELLED".equals(owner.taskStatus()) && !TechnicalDataSubmissionState.submitted(owner.versionStatus())) continue;
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
        if (!TechnicalDataSubmissionState.submitted(owner.moduleStatus()) || !TechnicalDataSubmissionState.submitted(owner.versionStatus())) {
          boolean saved = owner.versionId() != null;
          result.add(info(owner, saved ? "SAVED" : "IN_PROGRESS", null,
              (saved ? "已有资料" : "原任务办理中") + "，由" + name(owner.assigneeName()) + "维护，无需重复补录"));
          continue;
        }
        var version = versions.findVersion(owner.versionId()).orElseThrow(() -> new IllegalStateException("原提交版本不存在"));
        if (!Objects.equals(version.getProductId(), owner.productId()) || !TechnicalDataSubmissionState.submitted(version.getVersionStatus())) {
          throw new IllegalStateException("原提交版本与办理来源不一致");
        }
        String fingerprint = codec.fingerprint(version, codec.readReferenceSnapshot(version.getReferenceSnapshotJson()),
            versions.findPackageItems(version.getId()), versions.findAuxItems(version.getId()), versions.findSalaryItems(version.getId()));
        if (version.getContentFingerprint() == null || !version.getContentFingerprint().equals(fingerprint)) {
          throw new IllegalStateException("原提交资料与冻结指纹不一致");
        }
        // 只检查当前共享模块的依赖，不让原任务其他人的无关问题影响本模块。
        var issues = dependencies.stale(version, tasks.findModules(owner.productId())).stream()
            .filter(issue -> type.equals(issue.moduleType())).toList();
        if (!issues.isEmpty()) {
          result.add(info(owner, "SOURCE_CHANGED", null, "原提交资料引用的依据已变化，请在原任务核实，不能重复补录"));
          continue;
        }
        result.add(info(owner, "SUBMITTED", fingerprint, "已有提交的补录资料，可查看原资料，无需重复分派"));
      } catch (RuntimeException exception) {
        log.warn("Shared technical source check failed: item={}, month={}, module={}", product.oaFormItemId(), month, type, exception);
        result.add(new TechnicalDataSharedModuleInfo(type, "ERROR", null, null, null, null, null,
            "原补录来源核验失败，请核实后重新检查，不能据此新建补录"));
      }
    }
    return List.copyOf(result);
  }

  /** 共享只占用对应板块；其余真实缺口仍可补录，不新建第二份共享资料。 */
  public static List<TechnicalDataModuleRequirement> applySharedSources(
      List<TechnicalDataModuleRequirement> requirements, List<TechnicalDataSharedModuleInfo> shared) {
    return requirements.stream().map(requirement -> {
      var source = shared.stream().filter(row -> requirement.moduleType().equals(row.moduleType())).findFirst().orElse(null);
      if (source == null || !requirement.required() && !Objects.toString(requirement.reasonCode(), "").startsWith("SHARED_MODULE_")
          && !Set.of("PRICE_SHARED_SAVED", "PRICE_WAIT_ORIGINAL_OWNER").contains(Objects.toString(requirement.reasonCode(), ""))) return requirement;
      var availability = "SUBMITTED".equals(source.status()) ? TechnicalDataAvailability.AVAILABLE
          : "ERROR".equals(source.status()) ? TechnicalDataAvailability.ERROR : TechnicalDataAvailability.UNCONFIRMED;
      String reference = source.sourceTaskId() == null ? null
          : "TECH_SHARED:" + source.sourceTaskId() + ":" + Objects.toString(source.sourceVersionId(), "")
              + ":" + Objects.toString(source.sourceFingerprint(), "");
      return new TechnicalDataModuleRequirement(requirement.moduleType(), false,
          "SHARED_MODULE_" + source.status(), source.message(), availability, reference, requirement.checkedAt());
    }).toList();
  }

  public List<TechnicalDataModuleRequirement> currentRequirements(com.sanhua.marketingcost.entity.QuoteTechProduct product,
      List<com.sanhua.marketingcost.entity.QuoteTechModule> modules) {
    var requirements = modules.stream().map(module -> new TechnicalDataModuleRequirement(module.getModuleType(),
        Integer.valueOf(1).equals(module.getRequiredFlag()), Objects.toString(module.getRequirementReasonCode(), ""),
        module.getRequirementReason(), TechnicalDataAvailability.valueOf(Objects.toString(module.getSourceAvailability(), "UNCONFIRMED")),
        module.getSourceReference(), module.getSourceCheckedAt())).toList();
    return applySharedSources(requirements, current(product.getOaFormItemId(), product.getAccountingMonth(), requirements));
  }

  private TechnicalDataSharedModuleInfo info(TechnicalDataSharedModuleRepository.Owner owner, String status,
      String fingerprint, String message) {
    return new TechnicalDataSharedModuleInfo(owner.moduleType(), status, owner.taskId(), owner.productId(),
        "SUBMITTED".equals(status) ? owner.versionId() : null, fingerprint, owner.assigneeName(), message);
  }

  private String name(String name) { return name == null || name.isBlank() ? "原办理人" : name; }
}

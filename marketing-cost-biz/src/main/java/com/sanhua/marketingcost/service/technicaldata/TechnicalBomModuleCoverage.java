package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Material inputs now come from the complete BOM; existing OA participants still submit their own scope. */
@Component
public class TechnicalBomModuleCoverage {
  public static final Set<String> TYPES = Set.of("MANUFACTURING", "AUXILIARY", "SOLDER");
  public static final String SOURCE = "TECH_BOM_COVERED";
  private final QuoteTechnicalDataRepository repository;
  private final QuoteTechModuleMapper modules;
  private final TechnicalDataVersionContentCodec content;
  private final OaMessageCodec json;

  public TechnicalBomModuleCoverage(QuoteTechnicalDataRepository repository, QuoteTechModuleMapper modules,
      TechnicalDataVersionContentCodec content, OaMessageCodec json) {
    this.repository = repository;
    this.modules = modules;
    this.content = content;
    this.json = json;
  }

  public record Evidence(Long productId, String bomFingerprint) {}

  public static boolean covered(QuoteTechModule module) {
    return module != null && TYPES.contains(module.getModuleType()) && SOURCE.equals(module.getReferenceSourceType());
  }

  /** Caller holds task/product/module locks. Never changes an assignee or a frozen module. */
  public void saved(QuoteTechDataVersion draft, TechnicalBomContent bom, LocalDateTime now) {
    String fingerprint = json.dataFingerprint(bom.inputBasis());
    for (var module : repository.lockModules(draft.getProductId())) {
      if (!TYPES.contains(module.getModuleType())
          || !Set.of("PENDING", "NOT_REQUIRED", "EDITING", "READY", "RETURNED").contains(module.getModuleStatus())) continue;
      if (module.getAssigneeUserId() == null) {
        var requirement = new TechnicalDataModuleRequirement(module.getModuleType(), false,
            "TECH_BOM_MATERIALS_AVAILABLE", "已有（来自BOM），无需重复补录", TechnicalDataAvailability.AVAILABLE,
            fingerprint, now);
        if (modules.refreshRequirement(module.getId(), module.getRowVersion(), requirement, "NOT_REQUIRED") != 1)
          throw conflict("BOM相关资料已变化，请刷新后保存");
        continue;
      }
      if (!Integer.valueOf(1).equals(module.getRequiredFlag())) continue;
      module.setEntryMode("REFERENCE");
      module.setModuleStatus("READY");
      module.setCurrentVersionId(draft.getId());
      module.setReferenceSourceType(SOURCE);
      module.setReferenceSourceId(draft.getProductId().toString());
      module.setReferenceSourceVersion(draft.getId().toString());
      module.setReferenceFingerprint(fingerprint);
      module.setReferenceSnapshotJson(json.write(new Evidence(draft.getProductId(), fingerprint)));
      module.setReferencedAt(now);
      module.setLastValidationCode(SOURCE);
      module.setLastValidationMessage("已有（来自BOM）；保留本人原待办，提交时确认该BOM依据，无需再填材料明细");
      if (modules.coverByCompleteBom(module, now) != 1)
        throw conflict("原办理人的BOM资料已变化，请刷新后保存");
    }
  }

  public List<String> issues(QuoteTechModule covered, List<QuoteTechModule> all) {
    var source = all.stream().filter(row -> "DRAWING_BOM".equals(row.getModuleType())).findFirst().orElse(null);
    if (!covered(covered) || source == null || source.getCurrentVersionId() == null
        || !Set.of("READY", "RETURNED", "FROZEN", "SUBMITTED", "APPROVED").contains(source.getModuleStatus()))
      return List.of("完整BOM尚未校验保存，请先处理BOM补录");
    var version = repository.findVersion(source.getCurrentVersionId()).orElseThrow();
    var bom = content.technicalBom(version);
    if (!Objects.equals(version.getProductId(), covered.getProductId()) || bom == null
        || !Objects.equals(covered.getReferenceFingerprint(), json.dataFingerprint(bom.inputBasis()))
        || !content.sameSourceEvidence(json.read(covered.getReferenceSnapshotJson()),
            json.read(json.write(new Evidence(covered.getProductId(), covered.getReferenceFingerprint())))))
      return List.of("引用的完整BOM已变化，请先保存BOM并重新提交受影响板块");
    return List.of();
  }

  private TechnicalDataTaskException conflict(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message);
  }
}

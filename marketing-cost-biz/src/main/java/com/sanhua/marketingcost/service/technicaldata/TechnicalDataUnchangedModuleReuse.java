package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 退回后未修改的模块沿用其最后一次提交快照；公共来源刷新不改变这份报价资料。 */
@Service
public class TechnicalDataUnchangedModuleReuse {
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final QuoteTechDataVersionMapper versions;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataDependencies dependencies;
  private final ObjectMapper json;

  public TechnicalDataUnchangedModuleReuse(QuoteTechnicalDataRepository repository, TechnicalDataTaskRepository tasks,
      QuoteTechDataVersionMapper versions, TechnicalDataVersionContentCodec codec, TechnicalDataDependencies dependencies,
      ObjectMapper json) {
    this.repository = repository; this.tasks = tasks; this.versions = versions;
    this.codec = codec; this.dependencies = dependencies; this.json = json;
  }

  /** 调用方已完成权限、归属、产品/版本/模块加锁和并发版本检查。仅更新办理状态，不重写快照。 */
  public boolean save(QuoteTechProduct product, QuoteTechModule module, Object request, long actorId) {
    if (product.getCurrentEditVersionId() == null || !Integer.valueOf(1).equals(module.getRequiredFlag())
        || !"MISSING".equals(module.getSourceAvailability())
        || !Set.of("RETURNED", "READY").contains(Objects.toString(module.getModuleStatus(), ""))) return false;
    var draft = repository.findVersion(product.getCurrentEditVersionId()).orElseThrow();
    if (!Objects.equals(module.getCurrentVersionId(), draft.getId())
        || !TechnicalDataSavedModuleInput.matches(request, draft, repository, codec)
        || !unchanged(product, draft, module.getModuleType())) return false;
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    module.setModuleStatus("READY");
    module.setLastValidationCode("REUSED_SUBMITTED");
    module.setLastValidationMessage("本模块未修改，沿用原提交资料");
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict();
    product.setProductStatus("EDITING");
    if (repository.updateProductPointers(product, product.getRowVersion(), now) != 1) throw conflict();
    repository.markTaskInProgress(product.getTaskId(), actorId, now);
    return true;
  }

  public boolean unchanged(QuoteTechProduct product, QuoteTechDataVersion draft, String type) {
    if (draft == null || !"DRAFT".equals(draft.getVersionStatus())
        || !Objects.equals(product.getId(), draft.getProductId())
        || !Objects.equals(product.getCurrentEditVersionId(), draft.getId())) return false;
    Long id = versions.selectLatestSubmittedModuleVersion(product.getId(), type);
    if (id == null) return false;
    var submitted = repository.findVersion(id).orElseThrow();
    if (!codec.sameSourceEvidence(content(draft, type), content(submitted, type))) return false;
    // 上游资料真的修改（如新增包装料号）仍会影响价格等依赖模块，不能借未改输入绕过。
    return dependencies.stale(submitted, tasks.findModules(product.getId())).stream()
        .noneMatch(issue -> type.equals(issue.moduleType()));
  }

  private Object content(QuoteTechDataVersion version, String type) {
    var selected = TechnicalDataVersionCopies.scopedDraft(version, 0, null, null, Set.of(type));
    selected.setSourceFactsJson(null);
    try {
      return json.readTree(codec.versionContentJson(selected, List.of(),
          "PACKAGE".equals(type) ? repository.findPackageItems(version.getId()) : List.of(),
          "AUXILIARY".equals(type) ? repository.findAuxItems(version.getId()) : List.of(),
          "SALARY".equals(type) ? repository.findSalaryItems(version.getId()) : List.of()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalStateException("已保存的模块快照无法读取", exception);
    }
  }

  private TechnicalDataTaskException conflict() {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "资料已变化，请刷新后保存");
  }
}

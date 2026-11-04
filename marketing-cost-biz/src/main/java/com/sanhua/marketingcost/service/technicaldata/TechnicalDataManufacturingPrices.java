package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 保存原材料后，同一技术员继续处理实际缺价；不发布价格或创建第二次 OA 分派。 */
@Service
public class TechnicalDataManufacturingPrices {
  private static final String REASON = "MANUFACTURING_PRICE_MISSING";
  private final QuoteTechnicalDataRepository repository;
  private final QuoteTechModuleMapper modules;
  private final TechnicalDataPriceRequirements requirements;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TechnicalDataVersionContentCodec content;

  public TechnicalDataManufacturingPrices(QuoteTechnicalDataRepository repository, QuoteTechModuleMapper modules,
      TechnicalDataPriceRequirements requirements, TechnicalDataOaRecipientRepository recipients,
      TechnicalDataVersionContentCodec content) {
    this.repository = repository;
    this.modules = modules;
    this.requirements = requirements;
    this.recipients = recipients;
    this.content = content;
  }

  static boolean ownsRequirement(QuoteTechModule module) {
    return REASON.equals(module.getRequirementReasonCode());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void refresh(QuoteTechTask task, QuoteTechProduct product, TechnicalDataActor actor, LocalDateTime now) {
    var current = repository.lockModules(product.getId());
    var manufacturing = current.stream().filter(row -> "MANUFACTURING".equals(row.getModuleType())).findFirst().orElseThrow();
    if (!actor.canEditModule(task, manufacturing)) return;
    var price = current.stream().filter(row -> "PRICE".equals(row.getModuleType())).findFirst().orElseThrow();
    if (!Set.of("PENDING", "NOT_REQUIRED", "EDITING", "READY", "RETURNED").contains(price.getModuleStatus())) return;
    // 已由别人办理或普通 BOM 检查分派的价格模块维持原归属和范围。
    if (price.getAssigneeUserId() != null && !Objects.equals(price.getAssigneeUserId(), actor.userId())) return;
    if (Integer.valueOf(1).equals(price.getRequiredFlag()) && !ownsRequirement(price)) return;
    var checked = requirements.checkManufacturingReferences(task, product);
    boolean missing = checked.items().stream().anyMatch(row -> "MISSING".equals(row.status()));
    // 查询失败不能当成“缺价”或“已有价”，保留此前待补需求。
    if (!missing && !checked.issues().isEmpty()) return;
    if (!missing && !ownsRequirement(price)) return;
    boolean unassigned = "UNASSIGNED".equals(task.getTaskStatus()) && task.getAssigneeUserId() == null;
    if (!unassigned && task.getOaAssignmentVersion() != null && task.getOaAssignmentVersion() > 0) {
      recipients.manufacturingPriceScope(task.getId(), actor.userId(), missing);
    }
    String status = !missing ? "NOT_REQUIRED" : price.getCurrentVersionId() == null ? "PENDING"
        : savedPricesComplete(price, checked) ? "READY" : "EDITING";
    String reason = missing ? "原材料或对应废料缺少可用价格，请继续补价" : "已保存原材料及对应废料无需补价";
    if (modules.refreshManufacturingPrice(price.getId(), price.getRowVersion(), missing, status, REASON,
        reason, checked.fingerprint(), now, unassigned ? null : actor.userId(), unassigned ? null : actor.name()) != 1) {
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "价格补录状态已变化，请刷新后重试");
    }
  }

  private boolean savedPricesComplete(QuoteTechModule module,
      com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirementsResponse checked) {
    if (!checked.issues().isEmpty()) return false;
    var version = repository.findVersion(module.getCurrentVersionId()).orElseThrow();
    if (!Objects.equals(version.getProductId(), module.getProductId()) || !"DRAFT".equals(version.getVersionStatus())) return false;
    var saved = content.prices(version);
    return saved != null && saved.items() != null && checked.items().stream().filter(row -> row.requiresEntry())
        .allMatch(row -> saved.items().stream().anyMatch(item -> row.itemKey().equals(item.itemKey())
            && TechnicalDataPriceRules.validate(item).isEmpty()));
  }
}

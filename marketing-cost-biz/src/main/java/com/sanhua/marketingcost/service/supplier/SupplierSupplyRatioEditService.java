package com.sanhua.marketingcost.service.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioUpdateRequest;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.pricing.SupplierRatiosImported;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SupplierSupplyRatioEditService {
  private final SupplierSupplyRatioMapper mapper;
  private final BusinessChangeLogMapper changes;
  private final SupplierSupplyRatioImportLock lock;
  private final ApplicationEventPublisher events;
  private final ObjectMapper json;

  public SupplierSupplyRatioEditService(SupplierSupplyRatioMapper mapper, BusinessChangeLogMapper changes,
      SupplierSupplyRatioImportLock lock, ApplicationEventPublisher events, ObjectMapper json) {
    this.mapper = mapper;
    this.changes = changes;
    this.lock = lock;
    this.events = events;
    this.json = json.copy().findAndRegisterModules();
  }

  @Transactional(rollbackFor = Exception.class)
  public SupplierSupplyRatio update(long versionId, SupplierSupplyRatioUpdateRequest request,
      String businessUnitType, String operator) {
    String unit = SupplierSupplyRatioScope.require(businessUnitType);
    if (request == null) throw new IllegalArgumentException("请填写供货比例和取价规则");
    SupplierSupplyRatioNormalizeUtils.validateRatio(request.supplyRatio());
    String rule = StringUtils.hasText(request.selectionRule()) ? request.selectionRule().trim() : null;
    if (rule != null && rule.length() > 255) throw new IllegalArgumentException("取价规则最多255字符");

    // Serialize with imports too. An old version ID must never overwrite a newer correction.
    lock.acquire(unit);
    var previous = mapper.versionForUpdate(unit, versionId);
    if (previous == null || !Objects.equals(previous.getIsActive(), 1))
      throw new IllegalArgumentException("该供货关系不存在或已变更，请刷新列表后重新编辑");
    boolean sameRatio = previous.getSupplyRatio() == null || request.supplyRatio() == null
        ? previous.getSupplyRatio() == request.supplyRatio()
        : previous.getSupplyRatio().compareTo(request.supplyRatio()) == 0;
    if (sameRatio && Objects.equals(previous.getSelectionRule(), rule)) return previous;

    String actor = StringUtils.hasText(operator) ? operator.trim() : "system";
    LocalDateTime now = LocalDateTime.now();
    var next = new SupplierSupplyRatio();
    BeanUtils.copyProperties(previous, next);
    next.setId(null);
    next.setSupplyRatio(request.supplyRatio());
    next.setSelectionRule(rule);
    next.setSourceType("MANUAL");
    next.setSourceBatchNo("SSR-EDIT-" + UUID.randomUUID());
    next.setImportFileName(null);
    next.setImportedBy(null);
    next.setImportedAt(null);
    next.setCreatedBy(actor);
    next.setCreatedAt(now);
    next.setUpdatedBy(actor);
    next.setUpdatedAt(now);
    // Preserve identity, applicability and all other suppliers; only replace this version.
    if (mapper.retireVersion(unit, versionId, actor, now) != 1)
      throw new IllegalArgumentException("供货关系已变更，请刷新列表后重新编辑");
    if (mapper.insert(next) != 1 || next.getId() == null)
      throw new IllegalStateException("供货关系版本保存失败");
    if (changes.insert(SupplierSupplyRatioChanges.version(previous, next, actor, now,
        "FINANCE_EDIT", "手工修改供货比例或取价规则", json)) != 1)
      throw new IllegalStateException("供货关系变更日志保存失败");
    events.publishEvent(new SupplierRatiosImported(unit, Set.of(next.getMaterialCode())));
    return next;
  }
}

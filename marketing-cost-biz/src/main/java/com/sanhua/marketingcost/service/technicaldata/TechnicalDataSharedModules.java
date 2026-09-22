package com.sanhua.marketingcost.service.technicaldata;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 产品模块只有一个办理来源；其他报价复用这个来源，不再分派第二位办理人。 */
@Service
public class TechnicalDataSharedModules {
  private final TechnicalDataSharedModuleRepository repository;

  public TechnicalDataSharedModules(TechnicalDataSharedModuleRepository repository) { this.repository = repository; }

  public static String identity(String materialNo, long quoteItemId) {
    // OA 仅给型号时不能据此认定两张报价是同一产品；保留稳定来源行身份。
    return materialNo == null || materialNo.isBlank() ? "QUOTE_LINE:" + quoteItemId : "MATERIAL:" + materialNo.trim();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireOwnership(long productId, String type) {
    if ("PRICE".equals(type)) throw new IllegalArgumentException("价格必须按缺价料号占用，不能按成品模块占用");
    var current = repository.module(productId, type);
    if (current == null) throw conflict("产品缺少补录模块：" + type);
    String identity = identity(current.materialNo(), current.quoteItemId());
    long ownerId = repository.lockOrInsert(identity, type, current.moduleId());
    var existing = repository.existing(identity, type);
    if (existing.size() > 1) throw conflict("同一产品的" + label(type) + "已有重复办理记录，请核对原任务；不能自动选择或覆盖");
    // 首次接入唯一约束时，先登记已在办理的旧任务，不让新任务抢占。
    if (ownerId == current.moduleId() && !existing.isEmpty() && existing.getFirst().moduleId() != ownerId) {
      throw occupied(existing.getFirst(), type);
    }
    if (ownerId == current.moduleId()) return;
    var owner = repository.find(identity, type);
    if (owner == null) throw conflict("原补录来源不存在，请核对资料");
    if ("CANCELLED".equals(owner.taskStatus()) && !"APPROVED".equals(owner.versionStatus())) {
      repository.transfer(identity, type, ownerId, current.moduleId());
      return;
    }
    throw occupied(owner, type);
  }

  @Transactional(readOnly = true)
  public TechnicalDataSharedModuleRepository.Owner find(String materialNo, long quoteItemId, String type) {
    String identity = identity(materialNo, quoteItemId);
    var existing = repository.existing(identity, type);
    if (existing.size() > 1) throw conflict("同一产品的" + label(type) + "已有重复办理记录，请核对原任务");
    var owner = repository.find(identity, type);
    if (owner == null) return existing.isEmpty() ? null : existing.getFirst();
    if (!existing.isEmpty() && !Objects.equals(owner.moduleId(), existing.getFirst().moduleId())) {
      throw conflict("补录来源与原办理任务不一致，请核对资料");
    }
    return owner;
  }

  private TechnicalDataTaskException occupied(TechnicalDataSharedModuleRepository.Owner owner, String type) {
    String name = owner.assigneeName() == null || owner.assigneeName().isBlank() ? "原办理人" : owner.assigneeName();
    String state = "APPROVED".equals(owner.moduleStatus()) && "APPROVED".equals(owner.versionStatus())
        ? "已有审批通过的补录资料，请重新检查公共来源和这份补录的适用性，无需重复补录"
        : "已由" + name + "办理，请等待原资料完成，不能重复补录";
    return conflict(label(type) + state + "（原任务 " + owner.taskId() + "）");
  }

  static String label(String type) {
    return switch (type) {
      case "PROFILE" -> "产品资料"; case "DRAWING_BOM" -> "电子图库";
      case "MANUFACTURING" -> "制造件资料"; case "PACKAGE" -> "包装";
      case "AUXILIARY" -> "辅料"; case "SOLDER" -> "焊料";
      case "SALARY" -> "工资"; case "NET_LOSS" -> "净损失率";
      default -> throw new IllegalArgumentException("不支持的共享产品模块：" + type);
    };
  }

  private TechnicalDataTaskException conflict(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.SHARED_MODULE_CONFLICT, message);
  }
}

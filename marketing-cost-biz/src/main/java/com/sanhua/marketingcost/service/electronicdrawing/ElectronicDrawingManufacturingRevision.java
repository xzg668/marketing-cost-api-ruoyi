package com.sanhua.marketingcost.service.electronicdrawing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原材料修订重用已确认的图库与料号，另建组树草稿，保留已发布 BOM。 */
@Service
public class ElectronicDrawingManufacturingRevision {
  private final QuoteBomPreparationRecordMapper preparations;
  private final QuoteBomSupplementVersionMapper versions;
  private final ElectronicDrawingSourceNodeRepository nodes;
  private final ElectronicDrawingWorkflowContextPort contexts;

  public ElectronicDrawingManufacturingRevision(QuoteBomPreparationRecordMapper preparations,
      QuoteBomSupplementVersionMapper versions, ElectronicDrawingSourceNodeRepository nodes,
      ElectronicDrawingWorkflowContextPort contexts) {
    this.preparations = preparations;
    this.versions = versions;
    this.nodes = nodes;
    this.contexts = contexts;
  }

  /** 调用方已校验原材料编辑权限和输入；与草稿保存处于同一事务。 */
  @Transactional
  public Revision prepare(ElectronicDrawingWorkContext context) {
    var preparation = preparations.selectForElectronicDrawingImport(context.preparationId());
    if (preparation == null || !Objects.equals(preparation.getElectronicWorkflowVersion(), context.revision())
        || !Objects.equals(preparation.getElectronicSourceVersionId(), context.sourceVersionId())) {
      throw new IllegalArgumentException("图库来源已变化，请刷新后重新保存原材料");
    }
    var original = versions.selectById(context.sourceVersionId());
    if (original == null || !Objects.equals(original.getPreparationId(), context.preparationId())
        || !Objects.equals(original.getOaFormItemId(), context.oaFormItemId())
        || !Objects.equals(original.getQuoteProductCode(), context.quoteProductCode())
        || !Objects.equals(original.getTaskNo(), context.taskNo())
        || !Objects.equals(original.getPeriodMonth(), context.accountingMonth())
        || !Objects.equals(original.getMaterialOrgCode(), context.materialOrgCode())
        || !Objects.equals(original.getActiveFlag(), 1)
        || !"ELECTRONIC_DRAWING_EXCEL".equals(original.getBomSource())) {
      throw new IllegalArgumentException("制造件修订的图库来源归属不一致");
    }
    if ("DRAFT".equals(original.getVersionStatus())) return new Revision(context, Map.of());
    if (!"APPROVED".equals(original.getVersionStatus())) {
      throw new IllegalArgumentException("当前图库来源不能用于制造件修订");
    }
    var sourceNodes = nodes.findByVersionId(original.getId());
    if (sourceNodes.isEmpty()) throw new IllegalArgumentException("已发布图库没有原始节点，不能修订原材料");
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var draft = new QuoteBomSupplementVersion();
    BeanUtils.copyProperties(original, draft, "id", "compositionFingerprint", "versionNo",
        "versionStatus", "submittedBy", "submittedByName", "submittedAt", "reviewerUserId",
        "reviewerName", "reviewedAt", "reviewComment", "reuseValidUntil", "createdAt", "updatedAt");
    var latest = versions.selectList(Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
        .eq(QuoteBomSupplementVersion::getPreparationId, context.preparationId())
        .eq(QuoteBomSupplementVersion::getSupplementScope, original.getSupplementScope())
        .orderByDesc(QuoteBomSupplementVersion::getVersionNo).last("LIMIT 1"));
    draft.setVersionNo(Math.addExact(latest.getFirst().getVersionNo(), 1));
    draft.setVersionStatus("DRAFT");
    draft.setReusedFromVersionId(original.getId());
    draft.setCreatedAt(now);
    draft.setUpdatedAt(now);
    if (versions.insert(draft) != 1 || draft.getId() == null) throw new IllegalStateException("制造件修订来源创建失败");
    var copies = sourceNodes.stream().map(source -> {
      var copy = new ElectronicDrawingSourceNode();
      BeanUtils.copyProperties(source, copy, "id", "supplementVersionId", "createdAt", "updatedAt");
      copy.setSupplementVersionId(draft.getId());
      copy.setCreatedAt(now);
      copy.setUpdatedAt(now);
      return copy;
    }).toList();
    nodes.insertAll(draft.getId(), copies);
    var bySequence = new LinkedHashMap<String, Long>();
    for (var node : nodes.findByVersionId(draft.getId())) {
      if (node.getId() == null || bySequence.put(node.getSourceSequence(), node.getId()) != null) {
        throw new IllegalStateException("制造件修订来源节点标识重复或未保存");
      }
    }
    var replacements = new LinkedHashMap<Long, Long>();
    for (var source : sourceNodes) {
      var target = bySequence.get(source.getSourceSequence());
      if (target == null) throw new IllegalStateException("制造件修订来源节点不完整");
      replacements.put(source.getId(), target);
    }
    var next = contexts.attachSourceVersion(context, draft.getId(), now);
    next = contexts.updateStage(next, ElectronicDrawingWorkflowStage.MATCHED,
        context.assigneeUserId(), context.assigneeName(), now);
    contexts.record(next, "MANUFACTURING_REVISION", "原材料修订沿用图库来源 " + original.getId()
        + "，新组树来源 " + draft.getId() + "；保留原已发布资料");
    return new Revision(next, Map.copyOf(replacements));
  }

  public record Revision(ElectronicDrawingWorkContext context, Map<Long, Long> sourceNodeIds) {}
}

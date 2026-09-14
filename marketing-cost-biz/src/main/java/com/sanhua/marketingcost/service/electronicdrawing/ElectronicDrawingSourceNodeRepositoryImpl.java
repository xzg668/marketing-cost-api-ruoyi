package com.sanhua.marketingcost.service.electronicdrawing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.mapper.ElectronicDrawingSourceNodeMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ElectronicDrawingSourceNodeRepositoryImpl
    implements ElectronicDrawingSourceNodeRepository {

  private final ElectronicDrawingSourceNodeMapper mapper;

  public ElectronicDrawingSourceNodeRepositoryImpl(ElectronicDrawingSourceNodeMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public void insertAll(
      Long supplementVersionId, List<ElectronicDrawingSourceNode> sourceNodes) {
    Objects.requireNonNull(supplementVersionId, "supplementVersionId");
    Objects.requireNonNull(sourceNodes, "sourceNodes");
    for (ElectronicDrawingSourceNode sourceNode : sourceNodes) {
      if (sourceNode == null) {
        throw new IllegalArgumentException("sourceNodes 不能包含 null");
      }
      if (sourceNode.getSupplementVersionId() == null) {
        sourceNode.setSupplementVersionId(supplementVersionId);
      } else if (!supplementVersionId.equals(sourceNode.getSupplementVersionId())) {
        throw new IllegalArgumentException("源节点不属于指定补录版本");
      }
      if (sourceNode.getMatchStatus() == null) {
        sourceNode.setMatchStatus(ElectronicDrawingSourceNode.MATCH_UNMATCHED);
      }
    }
    if (!sourceNodes.isEmpty() && mapper.insertBatch(sourceNodes) != sourceNodes.size()) {
      throw new IllegalStateException("电子图库源节点批量写入数量不一致");
    }
  }

  @Override
  public List<ElectronicDrawingSourceNode> findByVersionId(Long supplementVersionId) {
    return mapper.selectList(
        Wrappers.<ElectronicDrawingSourceNode>lambdaQuery()
            .eq(ElectronicDrawingSourceNode::getSupplementVersionId, supplementVersionId)
            .orderByAsc(ElectronicDrawingSourceNode::getSourceRowNo)
            .orderByAsc(ElectronicDrawingSourceNode::getId));
  }

  @Override
  public List<ElectronicDrawingSourceNode> findPendingByVersionId(Long supplementVersionId) {
    return mapper.selectList(
        Wrappers.<ElectronicDrawingSourceNode>lambdaQuery()
            .eq(ElectronicDrawingSourceNode::getSupplementVersionId, supplementVersionId)
            .in(
                ElectronicDrawingSourceNode::getMatchStatus,
                ElectronicDrawingSourceNode.MATCH_UNMATCHED,
                ElectronicDrawingSourceNode.MATCH_AMBIGUOUS)
            .orderByAsc(ElectronicDrawingSourceNode::getSourceRowNo)
            .orderByAsc(ElectronicDrawingSourceNode::getId));
  }

  @Override
  public boolean updateResolution(
      Long sourceNodeId,
      String expectedStatus,
      String targetStatus,
      String resolvedMaterialCode,
      String resolvedBy,
      LocalDateTime resolvedAt) {
    boolean resolved = resolvedMaterialCode != null;
    if (resolved != (resolvedBy != null) || resolved != (resolvedAt != null)) {
      throw new IllegalArgumentException("resolvedMaterialCode、resolvedBy、resolvedAt 必须同时有值或同时为空");
    }
    return mapper.updateResolutionIfDraft(
        sourceNodeId,
        expectedStatus,
        targetStatus,
        resolvedMaterialCode,
        resolvedBy,
        resolvedAt,
        LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE)) == 1;
  }
}

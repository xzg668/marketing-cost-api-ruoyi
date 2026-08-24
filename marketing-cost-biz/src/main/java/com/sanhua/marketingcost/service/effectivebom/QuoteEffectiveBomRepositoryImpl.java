package com.sanhua.marketingcost.service.effectivebom;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.mapper.QuoteEffectiveBomNodeMapper;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.ibatis.executor.BatchResult;
import org.springframework.stereotype.Repository;

/** 最终节点仓储；构建写入后保持不可变。 */
@Repository
public class QuoteEffectiveBomRepositoryImpl
    implements QuoteEffectiveBomRepository {

  private final QuoteEffectiveBomNodeMapper mapper;

  public QuoteEffectiveBomRepositoryImpl(QuoteEffectiveBomNodeMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<String> findBuildBatchIdsByVariantHash(String variantHash) {
    List<QuoteEffectiveBomNode> rows =
        mapper.selectList(
            Wrappers.lambdaQuery(QuoteEffectiveBomNode.class)
                .select(QuoteEffectiveBomNode::getBuildBatchId)
                .eq(
                    QuoteEffectiveBomNode::getEffectiveVariantHash,
                    variantHash)
                .orderByAsc(QuoteEffectiveBomNode::getBuildBatchId));
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (QuoteEffectiveBomNode row : rows) {
      if (row.getBuildBatchId() != null) {
        result.add(row.getBuildBatchId());
      }
    }
    return List.copyOf(result);
  }

  @Override
  public List<QuoteEffectiveBomNode> findNodesByBuildBatchId(
      String buildBatchId) {
    return mapper.selectList(
        Wrappers.lambdaQuery(QuoteEffectiveBomNode.class)
            .eq(QuoteEffectiveBomNode::getBuildBatchId, buildBatchId)
            .orderByAsc(QuoteEffectiveBomNode::getNodeLevel)
            .orderByAsc(QuoteEffectiveBomNode::getSortSeq)
            .orderByAsc(QuoteEffectiveBomNode::getNodePath)
            .orderByAsc(QuoteEffectiveBomNode::getNodeKey));
  }

  @Override
  public boolean existsBuildBatchId(String buildBatchId) {
    return mapper.selectCount(
            Wrappers.lambdaQuery(QuoteEffectiveBomNode.class)
                .eq(QuoteEffectiveBomNode::getBuildBatchId, buildBatchId))
        > 0;
  }

  @Override
  public void insertAll(List<QuoteEffectiveBomNode> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      throw new IllegalArgumentException("最终有效BOM节点不能为空");
    }
    List<BatchResult> results = mapper.insert(nodes);
    if (results == null || results.isEmpty()) {
      throw new IllegalStateException("最终有效BOM批量写入没有返回执行结果");
    }
    int knownAffectedRows = 0;
    boolean containsUnknownSuccess = false;
    for (BatchResult result : results) {
      int[] updateCounts = result.getUpdateCounts();
      if (updateCounts == null || updateCounts.length == 0) {
        throw new IllegalStateException("最终有效BOM批量写入结果为空");
      }
      for (int count : updateCounts) {
        if (count == Statement.EXECUTE_FAILED || count == 0) {
          throw new IllegalStateException("最终有效BOM节点批量写入失败");
        }
        if (count == Statement.SUCCESS_NO_INFO) {
          containsUnknownSuccess = true;
        } else if (count > 0) {
          knownAffectedRows += count;
        }
      }
    }
    if (!containsUnknownSuccess && knownAffectedRows != nodes.size()) {
      throw new IllegalStateException(
          "最终有效BOM批量写入数量不一致: expected="
              + nodes.size()
              + ", actual="
              + knownAffectedRows);
    }
  }
}

package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.DrawingBom;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.DrawingEvidence;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.DrawingNode;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 送审和核算对比使用同一图库快照字段，防止原始明细修改后仍沿用旧审批。 */
public final class TechnicalDataDrawingSnapshot {
  private TechnicalDataDrawingSnapshot() {}
  public static DrawingBom from(ElectronicDrawingWorkContext context, QuoteBomSupplementVersion source,
      List<ElectronicDrawingSourceNode> rows) {
    if (source == null || rows.isEmpty()) throw new IllegalArgumentException("图库没有返回有效明细");
    Map<String,String> keys = rows.stream().collect(Collectors.toMap(ElectronicDrawingSourceNode::getSourceSequence,row -> row.getId().toString()));
    return new DrawingBom(source.getId(),source.getId(),rows.stream().allMatch(TechnicalDataDrawingSnapshot::matched) ? "MATCHED" : "MAPPING_PENDING",
        rows.stream().map(row -> new DrawingNode(row.getId().toString(),row.getParentSourceSequence()==null ? null : keys.get(row.getParentSourceSequence()),
            row.getId().toString(),row.getResolvedMaterialCode(),row.getDrawingCode(),row.getSourceName(),null,row.getQty(),null,row.getMatchStatus(),
            row.getReferenceWeight(),row.getReferenceWeightUnit(),row.getMaterial(),row.getSourceRemark())).toList(),
        new DrawingEvidence(context.oaFormItemId(),context.accountingMonth(),source.getElectronicDrawingNo(),source.getSourceFileSha256(),source.getSourceRequestId(),source.getSourceAcquiredAt()));
  }
  public static boolean matched(ElectronicDrawingSourceNode row) {
    return Set.of(ElectronicDrawingSourceNode.MATCH_AUTO,ElectronicDrawingSourceNode.MATCH_MANUAL)
        .contains(java.util.Objects.toString(row.getMatchStatus(),"")) && row.getResolvedMaterialCode()!=null && !row.getResolvedMaterialCode().isBlank();
  }
}

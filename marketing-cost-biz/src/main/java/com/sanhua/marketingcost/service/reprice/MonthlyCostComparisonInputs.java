package com.sanhua.marketingcost.service.reprice;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Batched saved evidence. Reading a report never looks up or adopts current public prices. */
@Service
@Transactional(readOnly=true)
public class MonthlyCostComparisonInputs {
  public record Loaded(MonthlyCostVersionService.Stored saved,MonthlyRepriceBomService.Input quotation,
      Map<Long,BomCostingRow> bomRows,
      List<QuoteEffectiveBomNode> roots,List<CostRunTraceSnapshot> traces,
      Map<Long,QuoteTechSalaryItem> salarySources) {
    public Loaded {
      bomRows=Map.copyOf(bomRows);roots=List.copyOf(roots);
      traces=List.copyOf(traces);salarySources=Map.copyOf(salarySources);
    }
  }
  private final MonthlyCostComparisonMapper query;
  private final CostRunPartItemMapper parts;
  private final CostRunCostItemMapper costs;
  private final BomCostingRowMapper boms;
  private final QuoteEffectiveBomNodeMapper nodes;
  private final CostRunTraceSnapshotMapper traces;
  private final QuoteTechSalaryItemMapper salaries;
  private final MonthlyPriceDocumentCodec codec;
  private final ObjectMapper json;
  public MonthlyCostComparisonInputs(MonthlyCostComparisonMapper query,CostRunPartItemMapper parts,
      CostRunCostItemMapper costs,BomCostingRowMapper boms,
      QuoteEffectiveBomNodeMapper nodes,CostRunTraceSnapshotMapper traces,QuoteTechSalaryItemMapper salaries,
      MonthlyPriceDocumentCodec codec,ObjectMapper json) {
    this.query=query;this.parts=parts;this.costs=costs;this.boms=boms;
    this.nodes=nodes;this.traces=traces;this.salaries=salaries;this.codec=codec;this.json=json;
  }
  public List<Loaded> load(String unit,List<QuoteCostRunVersion> versions) {
    MonthlyRepriceContract.unit(unit);
    if(versions.isEmpty()) return List.of();
    if(versions.size()>1000 || versions.stream().anyMatch(v->!unit.equals(v.getBusinessUnitType())))
      throw new IllegalArgumentException("保存版本超出本页范围或跨业务单元");
    var ids=versions.stream().map(QuoteCostRunVersion::getId).distinct().toList();
    if(ids.size()!=versions.size()) throw new IllegalArgumentException("比较版本重复");
    var partRows=parts.selectList(Wrappers.<CostRunPartItem>lambdaQuery().in(CostRunPartItem::getCostRunVersionId,ids).orderByAsc(CostRunPartItem::getId));
    var costRows=costs.selectList(Wrappers.<CostRunCostItem>lambdaQuery().in(CostRunCostItem::getCostRunVersionId,ids).orderByAsc(CostRunCostItem::getLineNo).orderByAsc(CostRunCostItem::getId));
    var snapshotRows=query.snapshots(ids).stream().collect(Collectors.toMap(MonthlyCostComparisonMapper.SavedSnapshot::id,Function.identity()));
    var inputs=query.taskInputs(ids).stream().collect(Collectors.toMap(MonthlyCostComparisonMapper.TaskInput::versionId,v->input(v.inputJson())));
    var bomIds=partRows.stream().map(CostRunPartItem::getBomRowId).filter(Objects::nonNull).distinct().toList();
    var bomRows=bomIds.isEmpty()?List.<BomCostingRow>of():boms.selectBatchIds(bomIds);
    var builds=bomRows.stream().map(BomCostingRow::getBuildBatchId).filter(Objects::nonNull).distinct().toList();
    var roots=builds.isEmpty()?List.<QuoteEffectiveBomNode>of():nodes.selectList(Wrappers.<QuoteEffectiveBomNode>lambdaQuery()
        .in(QuoteEffectiveBomNode::getBuildBatchId,builds).and(q->q.isNull(QuoteEffectiveBomNode::getParentNodeKey).or().eq(QuoteEffectiveBomNode::getParentNodeKey,"")));
    var savedTraces=traces.selectList(Wrappers.<CostRunTraceSnapshot>lambdaQuery().in(CostRunTraceSnapshot::getCostRunVersionId,ids).orderByAsc(CostRunTraceSnapshot::getId));
    var stored=new ArrayList<MonthlyCostVersionService.Stored>();var salaryIds=new HashSet<Long>();
    for(var version:versions) {
      var raw=snapshotRows.get(version.getId());
      if(raw==null) throw new IllegalStateException("所选成本版本已不存在");
      var snapshot=raw.json()==null?null:codec.decode(raw.json(),MonthlyCostVersionService.Snapshot.class);
      MonthlyCostVersionService.validateSavedSnapshot(version,snapshot);
      var p=partRows.stream().filter(r->version.getId().equals(r.getCostRunVersionId())).toList();
      var c=costRows.stream().filter(r->version.getId().equals(r.getCostRunVersionId())).toList();
      if(p.size()!=version.getPartItemCount() || c.size()!=version.getCostItemCount()
          || p.stream().anyMatch(r->!owned(version,r.getOaNo(),r.getOaFormItemId(),r.getProductCode(),r.getBusinessUnitType()))
          || c.stream().anyMatch(r->!owned(version,r.getOaNo(),r.getOaFormItemId(),r.getProductCode(),r.getBusinessUnitType())))
        throw new IllegalStateException("所选成本版本明细不完整或归属不一致");
      var saved=new MonthlyCostVersionService.Stored(version,snapshot,p,c);
      if(snapshot!=null) {
        MonthlyCostVersionService.validateStoredRows(saved);
        var technical=snapshot.calculation().technicalInput();
        if(technical!=null) technical.salaryItems().stream().map(v->v.id()).filter(Objects::nonNull).forEach(salaryIds::add);
      }
      stored.add(saved);
    }
    var salaryRows=salaryIds.isEmpty()?List.<QuoteTechSalaryItem>of():salaries.selectBatchIds(salaryIds);
    var salaryIndex=salaryRows.stream().collect(Collectors.toMap(QuoteTechSalaryItem::getId,Function.identity()));
    var result=new ArrayList<Loaded>();
    for(var saved:stored) {
      var ownBomIds=saved.parts().stream().map(CostRunPartItem::getBomRowId).collect(Collectors.toSet());
      var ownBoms=bomRows.stream().filter(r->ownBomIds.contains(r.getId())).collect(Collectors.toMap(BomCostingRow::getId,Function.identity()));
      var ownBuilds=ownBoms.values().stream().map(BomCostingRow::getBuildBatchId).collect(Collectors.toSet());
      result.add(new Loaded(saved,inputs.get(saved.version().getId()),ownBoms,
          roots.stream().filter(r->ownBuilds.contains(r.getBuildBatchId())).toList(),
          savedTraces.stream().filter(r->saved.version().getId().equals(r.getCostRunVersionId())).toList(),salaryIndex));
    }
    return List.copyOf(result);
  }
  private MonthlyRepriceBomService.Input input(String document) {
    try {return json.treeToValue(json.readTree(document).required("bomInput"),MonthlyRepriceBomService.Input.class);}
    catch(java.io.IOException|IllegalArgumentException failure){throw new IllegalStateException("保存的原报价依据不可读取",failure);}
  }
  private static boolean owned(QuoteCostRunVersion v,String oa,Long item,String product,String unit) {
    return v.getOaNo().equals(oa) && v.getOaFormItemId().equals(item) && v.getProductCode().equals(product) && v.getBusinessUnitType().equals(unit);
  }
}

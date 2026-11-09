package com.sanhua.marketingcost.service.jjb;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Single read seam for detail, workbench and trace; export consumes this same model. */
@Service
@Transactional(readOnly = true)
public class JjbReadService {
  public record View(JjbReadModel sheet, List<CostRunPartItemDto> parts, List<CostRunCostItemDto> costs) {}
  private final JjbVersionAccess access;
  private final MonthlyCostVersionService versions;
  private final JjbProjection projection;
  private final JjbMeasurementUnits units;
  private final com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper traces;
  private final com.sanhua.marketingcost.mapper.PricePrepareItemMapper preparations;
  public JjbReadService(JjbVersionAccess access, MonthlyCostVersionService versions, JjbProjection projection,
      com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper traces,
      com.sanhua.marketingcost.mapper.PricePrepareItemMapper preparations, JjbMeasurementUnits units) {
    this.units=units;
    this.preparations=preparations;
    this.traces=traces;
    this.access=access;this.versions=versions;this.projection=projection;
  }
  public View read(String oaNo, Long itemId, Long versionId) {return view(access.read(oaNo,itemId,versionId));}
  public View byRun(String runNo) {return view(access.byRun(runNo));}
  public View resolve(String oaNo,String product,Long itemId,Long versionId,String runNo) {
    return view(access.resolve(oaNo,product,itemId,versionId,runNo));
  }
  private View view(JjbVersionAccess.Owned owned) {
    var saved=versions.read(owned.businessUnit(),owned.version().getId());
    for(var row:saved.parts()) if(!matches(saved.version(),row.getOaNo(),row.getOaFormItemId(),row.getCostRunNo(),row.getProductCode(),row.getBusinessUnitType()))
      throw new IllegalStateException("保存部品的版本归属不一致");
    for(var row:saved.costs()) if(!matches(saved.version(),row.getOaNo(),row.getOaFormItemId(),row.getCostRunNo(),row.getProductCode(),row.getBusinessUnitType()))
      throw new IllegalStateException("保存费用的版本归属不一致");
    var frozenDetails = traces.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(CostRunTraceSnapshot.class)
        .eq(CostRunTraceSnapshot::getCostRunVersionId, saved.version().getId())
        .eq(CostRunTraceSnapshot::getBusinessUnitType, owned.businessUnit())
        .eq(CostRunTraceSnapshot::getTraceType, "PART_PRICE").orderByAsc(CostRunTraceSnapshot::getId));
    var ids=saved.parts().stream().map(CostRunPartItem::getPricePrepareItemId).filter(Objects::nonNull).distinct().toList();
    var frozenPreparations=new ArrayList<PricePrepareItem>();
    for(int start=0;start<ids.size();start+=256) {
      for(var row:preparations.selectBatchIds(ids.subList(start,Math.min(start+256,ids.size())))) {
        var version=saved.version();
        if(!Objects.equals(version.getBusinessUnitType(),row.getBusinessUnitType())
            || !Objects.equals(version.getOaNo(),row.getOaNo())
            || !Objects.equals(version.getOaFormItemId(),row.getOaFormItemId())
            || !Objects.equals(version.getPricingMonth(),row.getPeriodMonth())
            || !Objects.equals(version.getProductCode(),row.getTopProductCode()))
          throw new IllegalStateException("保存价格准备的版本归属不一致");
        frozenPreparations.add(row);
      }
    }
    return new View(units.complete(projection.project(saved,owned.form(),owned.item(),frozenDetails,frozenPreparations),saved.parts()),
        saved.parts().stream().map(row->{var dto=new CostRunPartItemDto();BeanUtils.copyProperties(row,dto);dto.setPartQty(row.getQty());return dto;}).toList(),
        saved.costs().stream().map(row->{var dto=new CostRunCostItemDto();BeanUtils.copyProperties(row,dto);return dto;}).toList());
  }
  private static boolean matches(QuoteCostRunVersion v,String oa,Long item,String run,String product,String unit) {
    return Objects.equals(v.getOaNo(),oa) && Objects.equals(v.getOaFormItemId(),item)
        && Objects.equals(v.getCostRunNo(),run) && Objects.equals(v.getProductCode(),product) && Objects.equals(v.getBusinessUnitType(),unit);
  }
}

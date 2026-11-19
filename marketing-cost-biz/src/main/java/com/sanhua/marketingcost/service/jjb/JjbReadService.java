package com.sanhua.marketingcost.service.jjb;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Single read seam for detail, workbench and trace; export consumes this same model. */
@Service
@Transactional(readOnly = true)
public class JjbReadService {
  public record View(JjbReadModel sheet, List<CostRunPartItemDto> parts, List<CostRunCostItemDto> costs) {}
  private final JjbVersionAccess access;
  private final MonthlyCostVersionService versions;
  private final JjbProjection projection;
  private final JjbMeasurementUnits units;
  private final MaterialMasterRawMapper materials;
  private final QuoteBomContextResolver contexts;
  @org.springframework.beans.factory.annotation.Autowired
  private com.sanhua.marketingcost.service.materialshape.QuoteMaterialShapeInputs materialShapes;
  private final com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper traces;
  private final com.sanhua.marketingcost.mapper.PricePrepareItemMapper preparations;
  public JjbReadService(JjbVersionAccess access, MonthlyCostVersionService versions, JjbProjection projection,
      com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper traces,
      com.sanhua.marketingcost.mapper.PricePrepareItemMapper preparations, JjbMeasurementUnits units,
      MaterialMasterRawMapper materials, QuoteBomContextResolver contexts) {
    this.units=units;
    this.materials=materials;
    this.contexts=contexts;
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
    var sheet=units.complete(projection.project(saved,owned.form(),owned.item(),frozenDetails,frozenPreparations,
        readBareCode(owned, saved)),saved.parts());
    if(materialShapes!=null) {
      var catalog=materialShapes.read(owned.businessUnit(),saved.version().getPricingMonth(),saved.version().getOaFormItemId());
      var codes=catalog.sources().stream().map(com.sanhua.marketingcost.service.materialshape.QuoteMaterialShapeInputs.Source::materialCode)
          .collect(java.util.stream.Collectors.toSet());
      if(!codes.isEmpty()) sheet=new JjbReadModel(sheet.identity(),sheet.header(),sheet.rows().stream()
          .map(row -> codes.contains(row.code()) ? row.toBuilder().multiPriceSource(true).build() : row).toList(),
          sheet.monthlyPrices(),sheet.totals(),sheet.warnings(),sheet.explanation());
    }
    return new View(sheet,
        saved.parts().stream().map(row->{var dto=new CostRunPartItemDto();BeanUtils.copyProperties(row,dto);dto.setPartQty(row.getQty());return dto;}).toList(),
        saved.costs().stream().map(row->{var dto=new CostRunCostItemDto();BeanUtils.copyProperties(row,dto);return dto;}).toList());
  }

  private String readBareCode(JjbVersionAccess.Owned owned, MonthlyCostVersionService.Stored saved) {
    String product = saved.version().getProductCode();
    if (!StringUtils.hasText(product)) return null;
    var bom = saved.snapshot() == null ? null : saved.snapshot().pricingBom();
    String organization = bom == null ? null : bom.materialOrganizationCode();
    if (!StringUtils.hasText(organization) && bom != null && StringUtils.hasText(bom.priceOrgCode())) {
      organization = com.sanhua.marketingcost.enums.MaterialOrganization.fromPriceOrgCode(bom.priceOrgCode()).getCode();
    }
    if (!StringUtils.hasText(organization)) {
      try {
        organization = contexts.resolveOrganization(owned.form(), owned.item(), product).materialOrganizationCode();
      } catch (QuoteIngestException unresolvedOrganization) {
        // Older costs remain readable even if the quote no longer identifies a material organization.
        return null;
      }
    }
    String bareCode = materials.selectActiveBareCode(product.trim(), organization);
    return StringUtils.hasText(bareCode) ? bareCode.trim() : null;
  }

  private static boolean matches(QuoteCostRunVersion v,String oa,Long item,String run,String product,String unit) {
    return Objects.equals(v.getOaNo(),oa) && Objects.equals(v.getOaFormItemId(),item)
        && Objects.equals(v.getCostRunNo(),run) && Objects.equals(v.getProductCode(),product) && Objects.equals(v.getBusinessUnitType(),unit);
  }
}

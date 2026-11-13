package com.sanhua.marketingcost.service.materialshape;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.*;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.settlement.*;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Existing ordinary byproduct pricing carries the negative recovery; weights retain separate evidence. */
@Service
public class OutsourceScrapPreparation {
  private final QuoteMaterialShapeInputs shapes;
  private final MaterialMasterRawMapper masters;
  private final MakePartScrapMappingService scraps;
  private final MonthlyQuotationSessions sessions;
  private final MonthlyPriceDocumentCodec codec;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public OutsourceScrapPreparation(QuoteMaterialShapeInputs shapes,MaterialMasterRawMapper masters,
      MakePartScrapMappingService scraps,MonthlyQuotationSessions sessions,MonthlyPriceDocumentCodec codec,
      JdbcTemplate jdbc,ObjectMapper json) {
    this.shapes=shapes;this.masters=masters;this.scraps=scraps;this.sessions=sessions;this.codec=codec;this.jdbc=jdbc;this.json=json;
  }

  public BomSettlementRowBuildResult add(QuoteBomPreparationRecord record,List<BomSettlementNode> nodes,
      BomSettlementRowBuildResult built,String unit,String month,LocalDateTime asOf) {
    var catalog=shapes.read(unit,month,record.getOaFormItemId());
    if(catalog.sources().isEmpty()) return built;
    var rows=new ArrayList<>(built.costingRows());
    var removed=new HashSet<String>();
    for(var parent:nodes) {
      if(!outsource(parent) || catalog.priceScope(parent.priceOrgCode(),parent.materialCode(),"OUTSOURCE")==null) continue;
      var children=nodes.stream().filter(n -> Objects.equals(parentPath(n.path()),path(parent.path()))).toList();
      if(children.size()!=1 || !purchase(children.getFirst()))
        throw missing(parent.materialCode(),parent.materialName(),"委外材料结构未明确，需要唯一直接采购材料来确定废料重量归属");
      var child=children.getFirst();
      var mappings=scraps.listMappings(child.materialCode(),unit);
      if(mappings==null || mappings.size()!=1)
        throw missing(child.materialCode(),child.materialName(),"缺少唯一的材料废料代码对应关系");
      var mapping=mappings.getFirst();
      var source=masters.selectByLatestBatchAndCodes(List.of(parent.materialCode()),null,
          MaterialOrganization.fromPriceOrgCode(parent.priceOrgCode()).getCode());
      if(source.size()!=1 || source.getFirst().getGlobalSeg3TheoreticalNetWeight()==null
          || source.getFirst().getGlobalSeg3TheoreticalNetWeight().isBlank())
        throw missing(parent.materialCode(),parent.materialName(),"缺少U9理论净重");
      BigDecimal net;
      try { net=new BigDecimal(source.getFirst().getGlobalSeg3TheoreticalNetWeight().trim().replace(",","")); }
      catch(NumberFormatException e) { throw missing(parent.materialCode(),parent.materialName(),"U9理论净重不是有效数字"); }
      if(net.signum()<0) throw missing(parent.materialCode(),parent.materialName(),"U9理论净重为负数");
      var rawMasters=masters.selectByLatestBatchAndCodes(List.of(child.materialCode()),null,
          MaterialOrganization.fromPriceOrgCode(child.priceOrgCode()).getCode());
      String pricingUnit=rawMasters.size()==1 ? rawMasters.getFirst().getUnit() : null;
      if(pricingUnit==null || pricingUnit.isBlank()) throw missing(child.materialCode(),child.materialName(),"缺少SRM取价计量单位");
      var scenario=PricePrepareScenarioContext.oaLocked(record.getOaFormItemId(),parent.priceOrgCode());
      var session=sessions.open(record.getOaNo(),unit,month,asOf,scenario);
      String weightScope=MaterialShapePriceScope.of("OUTSOURCE_WEIGHT",Set.of("LINKED","RANGE"),
          MonthlyPriceDocumentCodec.sha256(unit+"|"+month+"|"+child.priceOrgCode()+"|"+child.materialCode()+"|SRM_WEIGHT_V1"));
      var input=new QuotationPriceSession.Input(child.materialCode(),child.priceOrgCode(),pricingUnit,
          child.materialSpec(),null,null,session.conditions(child.priceOrgCode()).withMaterialShapeScope(weightScope),
          child.qtyPerTop().abs(),QuotationPriceSession.Purpose.MATERIAL);
      var materialInput=new QuotationPriceSession.Input(child.materialCode(),child.priceOrgCode(),pricingUnit,
          child.materialSpec(),null,null,session.conditions(child.priceOrgCode()),child.qtyPerTop().abs(),QuotationPriceSession.Purpose.MATERIAL);
      var value=session.read(List.of(materialInput)).values().get(materialInput);
      var weight=quotedWeight(value,child.materialCode());
      // Use the actual adopted material quote first. Only a non-SRM material price needs a separate weight quote.
      if(weight==null) {
        var read=session.read(List.of(input));value=read.values().get(input);
        if(value==null) throw missing(child.materialCode(),child.materialName(),"缺少可用的SRM联动／区间报价重量；"
            +(read.problems().get(input)==null ? "无重量来源" : read.problems().get(input).message()));
        weight=quotedWeight(value,child.materialCode());
      }
      if(weight==null) throw missing(child.materialCode(),child.materialName(),"SRM联动／区间价格缺少重量，无法取得毛重");
      BigDecimal recovered=weight.grams().subtract(net);
      if(recovered.signum()<0) throw missing(parent.materialCode(),parent.materialName(),"SRM毛重小于U9理论净重，无法计算废料重");
      String parentPath=path(parent.path());
      for(var row:rows) if("BYPRODUCT_EXTRA".equals(row.getSettlementRowType()) && parentPath.equals(parentPath(row.getPath())))
        removed.add(row.getPath());
      var evidence=new LinkedHashMap<String,Object>();
      evidence.put("kind","OUTSOURCE_SCRAP_V1");evidence.put("parentMaterialCode",parent.materialCode());
      evidence.put("parentMaterialName",Objects.toString(parent.materialName(),""));
      evidence.put("rawMaterialCode",child.materialCode());evidence.put("scrapCode",mapping.getScrapCode());
      evidence.put("priceOrganization",parent.priceOrgCode());evidence.put("grossWeightMaterialCode",child.materialCode());
      evidence.put("grossWeightG",weight.grams());evidence.put("grossWeightSource",weight.source());
      evidence.put("netWeightMaterialCode",parent.materialCode());evidence.put("netWeightG",net);
      evidence.put("netWeightSource","U9理论净重");evidence.put("netWeightSourceId",source.getFirst().getId());
      evidence.put("scrapWeightG",recovered);evidence.put("parentQuantity",parent.qtyPerTop());
      evidence.put("weightQuote",MonthlyPreparationPrice.read(value));
      evidence.put("formula","废料重=SRM报价净重作为毛重-U9理论净重；回收金额=废料单价×废料重(kg)×本件用量");
      var row=new BomCostingRow();
      row.setOaNo(record.getOaNo());row.setOaFormItemId(record.getOaFormItemId());row.setTopProductCode(record.getQuoteProductCode());
      row.setParentCode(parent.materialCode());row.setMaterialCode(mapping.getScrapCode());
      row.setMaterialName(Objects.toString(mapping.getScrapName(),mapping.getScrapCode())+"-废料回收");
      row.setUnit("KG");row.setShapeAttr("副产品");row.setLevel(parent.level()+1);
      row.setPath(parentPath+"/shape-recovery-"+mapping.getScrapCode());
      row.setQtyPerParent(recovered.movePointLeft(3).negate());
      row.setQtyPerTop(recovered.movePointLeft(3).multiply(parent.qtyPerTop()).negate());
      row.setPriceOrgCode(parent.priceOrgCode());row.setMaterialOrganizationCode(parent.materialOrganizationCode());
      row.setBusinessUnitType(unit);row.setPeriodMonth(month);row.setIsCostingRow(1);row.setSubtreeCostRequired(0);
      row.setSettlementRowType("BYPRODUCT_EXTRA");row.setBuildBatchId(rows.isEmpty()?null:rows.getFirst().getBuildBatchId());
      row.setBuiltAt(LocalDateTime.now());row.setAsOfDate(asOf.toLocalDate());row.setQuotationShapeEvidence(Collections.unmodifiableMap(evidence));
      row.setRawVersionEffectiveFrom(parent.rawVersionEffectiveFrom()!=null ? parent.rawVersionEffectiveFrom()
          : parent.effectiveFrom()==null ? asOf.toLocalDate() : parent.effectiveFrom());
      row.setEffectiveFrom(parent.effectiveFrom());row.setEffectiveTo(parent.effectiveTo());
      row.setBomPurpose(parent.bomPurpose());row.setBomVersion(parent.bomVersion());
      rows.add(row);
    }
    if(rows.size()==built.costingRows().size() && removed.isEmpty()) return built;
    rows.removeIf(r -> removed.contains(r.getPath()));
    var refs=built.sourceRefs().stream().filter(r -> !removed.contains(r.costingRowPath())).toList();
    var subs=built.subRefs().stream().filter(r -> !removed.contains(r.costingRowPath())).toList();
    var s=built.stats();
    return new BomSettlementRowBuildResult(rows,subs,refs,built.warnings(),new BomSettlementRowBuildStats(s.inputNodeCount(),
        rows.size(),subs.size(),refs.size(),s.warningCount(),s.stoppedPathCount(),s.consumedLeafPathCount(),s.rollupBucketCount(),
        (int)rows.stream().filter(r -> "BYPRODUCT_EXTRA".equals(r.getSettlementRowType())).count()));
  }

  public Set<String> selectedPricePaths(QuoteBomPreparationRecord record,String unit,String month,List<BomSettlementNode> nodes) {
    var catalog=shapes.read(unit,month,record.getOaFormItemId());
    var result=new HashSet<String>();
    for(var node:nodes) {
      var choice=catalog.choice(node.priceOrgCode(),node.materialCode());
      String shape=outsource(node)?"OUTSOURCE":purchase(node)?"PURCHASE":null;
      if(shape==null || (choice==null && catalog.priceScope(node.priceOrgCode(),node.materialCode(),shape)==null)) continue;
      result.add(path(node.path()));
      if("OUTSOURCE".equals(shape)) nodes.stream()
          .filter(child -> path(node.path()).equals(parentPath(child.path())) && purchase(child))
          .forEach(child -> result.add(path(child.path())));
    }
    return Set.copyOf(result);
  }

  public void save(BomCostingRow row) {
    if(row.getQuotationShapeEvidence()==null) return;
    jdbc.update("INSERT INTO lp_quote_outsource_scrap_basis(costing_row_id,business_unit_type,oa_form_item_id,period_month,evidence_json) VALUES(?,?,?,?,?)",
        row.getId(),row.getBusinessUnitType(),row.getOaFormItemId(),row.getPeriodMonth(),encode(row.getQuotationShapeEvidence()));
  }
  private MakePartQuotedWeight quotedWeight(QuotationPriceSession.Value value,String code) {
    if(value==null) return null;
    var resolved=com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult.ok(code,"SRM_WEIGHT",
        value.financialPrice(),"委外毛重取本次选定SRM报价",MonthlyPreparationPrice.KIND);
    resolved.setEvidence(new PriceResolveEvidence(value.selection().monthlyPriceId(),null,null,null,null,null,null,null,
        false,null,codec.encode(MonthlyPreparationPrice.read(value))));
    return MakePartQuotedWeight.read(resolved,codec);
  }
  public Map<String,Object> evidence(BomCostingRow row) {
    if(row.getQuotationShapeEvidence()!=null) return row.getQuotationShapeEvidence();
    if(row.getId()==null || !path(row.getPath()).contains("/shape-recovery-") || !shapes.installed()) return null;
    var rows=jdbc.queryForList("SELECT evidence_json FROM lp_quote_outsource_scrap_basis WHERE costing_row_id=? AND business_unit_type=? AND oa_form_item_id=? AND period_month=?",
        String.class,row.getId(),row.getBusinessUnitType(),row.getOaFormItemId(),row.getPeriodMonth());
    if(rows.size()!=1) throw missing(row.getMaterialCode(),row.getMaterialName(),"缺少委外废料重量计算底稿");
    try { return json.readValue(rows.getFirst(),new TypeReference<Map<String,Object>>(){}); }
    catch(java.io.IOException e) { throw new IllegalStateException("委外废料重量计算底稿损坏",e); }
  }
  private String encode(Object value) {
    try { return json.writeValueAsString(value); }
    catch(java.io.IOException e) { throw new IllegalStateException("委外废料重量底稿无法保存",e); }
  }
  private static boolean outsource(BomSettlementNode n) { return "委外加工件".equals(n.shapeAttr()) || "OUTSOURCE".equals(n.shapeAttr()); }
  private static boolean purchase(BomSettlementNode n) { return "采购件".equals(n.shapeAttr()) || "PURCHASE".equals(n.shapeAttr()); }
  private static String path(String value) { return MonthlyBomPricingScope.pathKey(value); }
  private static String parentPath(String value) { String p=path(value);int slash=p.lastIndexOf('/');return slash>0?p.substring(0,slash):null; }
  private static QuotationMaterialShapeGap missing(String code,String name,String message) {
    return new QuotationMaterialShapeGap(code,name,message);
  }
}

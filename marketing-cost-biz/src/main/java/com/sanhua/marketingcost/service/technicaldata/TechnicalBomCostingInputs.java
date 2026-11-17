package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Adapts complete-BOM quantities/weights to the existing calculator, without creating gallery records. */
@Component
public class TechnicalBomCostingInputs {
  private final TechnicalDataVersionContentCodec codec;
  private final MakePartScrapMappingService scraps;
  private final MakePartNoScrapConfirmationService noScrap;
  public TechnicalBomCostingInputs(TechnicalDataVersionContentCodec codec,MakePartScrapMappingService scraps,MakePartNoScrapConfirmationService noScrap) {
    this.codec=codec; this.scraps=scraps; this.noScrap=noScrap;
  }
  public Map<String,TechnicalManufacturingInputs.Input> read(TechnicalDataCostingSources.Source source) {
    var bom=source==null?null:codec.technicalBom(source.version()); if(bom==null) return Map.of();
    var paths=paths(bom,source.version().getId()); var result=new LinkedHashMap<String,TechnicalManufacturingInputs.Input>();
    for(var parent:bom.nodes()) {
      if(!"制造件".equals(parent.input().attribute()) || parent.quantityPerTop().signum() == 0) continue;
      var children=children(bom,parent.input().hierarchy());
      if(children.size()!=1||!"采购件".equals(children.getFirst().input().attribute())) continue;
      var raw=children.getFirst(); String unit=TechnicalBomValidation.unit(raw.purchasingUnit());
      if(!Set.of("g","kg").contains(Objects.toString(unit,""))||parent.input().netWeight()==null) continue;
      BigDecimal net=TechnicalDataManufacturingUnits.sourceWeightG(parent.input().netWeight(),parent.input().netWeightUnit());
      BigDecimal gross=TechnicalBomValidation.convert(raw.quantityPerParent(),unit,"g");
      if(gross.compareTo(net)<0) throw new IllegalArgumentException("完整BOM制造件原料用量小于单件净重："+parent.resolvedMaterialCode());
      var mappings=scraps.listMappings(raw.resolvedMaterialCode(),bom.scope().businessUnit()).stream()
          .map(row->new ManufacturingScrap(row.getScrapCode(),row.getScrapName(),row.getScrapUnit())).toList();
      var confirmation=mappings.isEmpty()?noScrap.findEffective(raw.resolvedMaterialCode(),bom.scope().accountingMonth(),bom.scope().businessUnit()):null;
      String state=mappings.size()==1?"MATCHED":mappings.size()>1?"AMBIGUOUS":confirmation!=null&&"ACTIVE".equals(confirmation.getStatus())?"NO_SCRAP_CONFIRMED":"MISSING";
      var evidence=new ManufacturingNodeEvidence(parent.input().name(),parent.input().model(),parent.input().quantity(),parent.input().netWeight(),parent.input().netWeightUnit(),
          raw.input().name(),raw.input().specification(),state,mappings);
      var input=new RawMaterial("TECH_BOM:"+parent.input().hierarchy(),parent.input().hierarchy(),null,parent.resolvedMaterialCode(),raw.resolvedMaterialCode(),null,
          net.movePointLeft(3),raw.quantityPerParent(),raw.purchasingUnit(),"TECH_"+bom.entryMode()+":"+source.version().getId(),gross.movePointLeft(3),null,evidence);
      String path=paths.get(parent.input().hierarchy()); result.put(path,new TechnicalManufacturingInputs.Input(source.version().getId(),path,input));
    }
    return Map.copyOf(result);
  }
  /** Null means this is not a complete technical BOM; an empty list is an actual leaf. */
  public List<BomU9Source> children(TechnicalDataCostingSources.Source source,String parentPath) {
    var bom=source==null?null:codec.technicalBom(source.version()); if(bom==null) return null;
    var paths=paths(bom,source.version().getId());
    var parent=bom.nodes().stream().filter(node->Objects.equals(paths.get(node.input().hierarchy()),parentPath)).findFirst().orElse(null);
    if(parent==null) return List.of();
    return children(bom,parent.input().hierarchy()).stream().map(node->{
      var row=new BomU9Source(); row.setParentMaterialNo(parent.resolvedMaterialCode()); row.setChildMaterialNo(node.resolvedMaterialCode());
      row.setChildMaterialName(node.input().name()); row.setChildMaterialSpec(node.input().specification()); row.setStockUnit(node.purchasingUnit());
      row.setQtyPerParent(node.quantityPerParent()); row.setParentBaseQty(BigDecimal.ONE); row.setPriceOrgCode(node.reference()==null?bom.scope().priceOrgCode():Objects.toString(node.reference().priceOrgCode(),bom.scope().priceOrgCode()));
      row.setBomVersion("TB-"+source.version().getId()); return row;
    }).toList();
  }
  private static List<TechnicalBomContent.Node> children(TechnicalBomContent bom,String hierarchy) {
    return bom.nodes().stream().filter(node->Objects.equals(hierarchy,TechnicalBomRows.parent(node.input().hierarchy()))).toList();
  }
  private static Map<String,String> paths(TechnicalBomContent bom,Long versionId) {
    var rows=TechnicalBomPreparationSource.rows(bom,versionId,"TECH_INPUT",bom.checkedAt()); var result=new HashMap<String,String>();
    for(int index=0;index<bom.nodes().size();index++) result.put(bom.nodes().get(index).input().hierarchy(),rows.get(index+1).getPath()); return result;
  }
}

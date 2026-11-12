package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.dto.CostAmountComposition;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Explain the saved packaging preparation; never consult today's BOM or package prices. */
@Service
public class PackageAmountCompositionReader {
  private final PricePrepareItemMapper preparations;
  private final PackageComponentSnapshotDetailMapper names;
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;
  public PackageAmountCompositionReader(PricePrepareItemMapper preparations,
      PackageComponentSnapshotDetailMapper names,ObjectMapper json,MonthlyPriceDocumentCodec codec) {
    this.preparations=preparations;this.names=names;this.json=json;this.codec=codec;
  }

  public CostAmountComposition read(MonthlyCostVersionService.Stored saved, CostRunCostItem fee,
      CostAmountComposition captured) {
    var v=saved.version();var rows=new ArrayList<Map<String,String>>();var notes=new ArrayList<String>();
    if(captured!=null) rows.addAll(captured.rows());
    var metadata=rows.stream().filter(row->"PACKAGE_FEE_PARAMETERS".equals(row.get("kind"))).findFirst().orElse(Map.of());
    if("TECHNICAL".equals(metadata.get("mode")) || "lp_quote_tech_data_version".equals(fee.getSourceTable())) {
      var technical=saved.snapshot()==null?null:saved.snapshot().calculation().technicalInput();
      if(technical!=null)for(var line:technical.packageItems()) {
        var row=new LinkedHashMap<String,String>();row.put("kind","PACKAGE_COMPONENT");
        row.put("materialCode",line.materialNo());row.put("materialName",line.name());row.put("quantity",number(line.quantity()));
        row.put("unit",line.unit());row.put("unitPrice",number(line.unitPrice()));row.put("amountYuan",number(line.amount()));
        row.put("sourceTable","lp_quote_tech_data_version");row.put("sourceId",String.valueOf(fee.getSourceId()));
        row.put("sourceRow",String.valueOf(line.lineNo()));row.put("note","保存的技术包装分项；不采用包装BOM母件底数或组件上浮系数");rows.add(row);
      }
      return new CostAmountComposition("SAVED_ROWS","本次包装费直接采用保存的技术包装资料金额，不再乘包装组件系数。",
          number(fee.getBaseAmount()),"0",fee.getRemark(),List.of(),rows);
    }
    var ids=new LinkedHashSet<Long>();
    for(var part:saved.parts()) {
      if(!Objects.equals(v.getId(),part.getCostRunVersionId()) || !Objects.equals(v.getBusinessUnitType(),part.getBusinessUnitType())
          || !Objects.equals(v.getCostRunNo(),part.getCostRunNo()) || !Objects.equals(v.getProductCode(),part.getProductCode()))
        throw new AccessDeniedException("包装部品不属于此保存版本");
      if(part.getPricePrepareItemId()!=null)ids.add(part.getPricePrepareItemId());
    }
    var byId=new HashMap<Long,PricePrepareItem>();var list=new ArrayList<>(ids);
    for(int i=0;i<list.size();i+=256)for(var p:preparations.selectBatchIds(list.subList(i,Math.min(i+256,list.size()))))byId.put(p.getId(),p);
    var validator=new PackageCalculationEvidence(json); var base=BigDecimal.ZERO;var found=0;
    for(var part:saved.parts()) {
      var prepared=byId.get(part.getPricePrepareItemId());
      if(prepared==null || prepared.getResultRefType()==null || !prepared.getResultRefType().startsWith("PACKAGE"))continue;
      own(v,part,prepared);
      // Older wrappers are restored before invoking the existing immutable PACK validator.
      var input=prepared.getInputSnapshotJson();
      if(input!=null && jsonTree(input).has("document")) {
        var copy=new PricePrepareItem();org.springframework.beans.BeanUtils.copyProperties(prepared,copy);
        copy.setInputSnapshotJson(codec.unwrap(input));prepared=copy;
      }
      var snapshot=validator.trace(prepared);
      if(snapshot==null) {notes.add("包装组件 "+part.getPartCode()+" 未保存子件计算底稿，不能用当前结构代替历史结构。");continue;}
      found++;base=base.add(Objects.requireNonNull(part.getAmount(),"保存包装组件缺少金额"));
      var childNames=names(snapshot,part,v.getBusinessUnitType());
      var components=validator.components(snapshot);
      var packageBase=snapshot.path("parentBaseQty").isNumber()?snapshot.path("parentBaseQty").decimalValue():null;
      var childTotal=BigDecimal.ZERO;
      for(var component:components.values()) {
        var row=new LinkedHashMap<String,String>();
        row.put("kind","PACKAGE_COMPONENT");row.put("parentCode",part.getPartCode());row.put("materialCode",component.path("materialCode").asText());
        row.put("materialName",childNames.getOrDefault(component.path("snapshotDetailId").asLong(),""));
        row.put("sourceTable","lp_price_prepare_item");row.put("sourceId",prepared.getId().toString());
        row.put("period",v.getPricingMonth());row.put("parentBaseQty",number(packageBase));
        row.put("childBaseQty",component.path("parentBaseQty").isNumber()?number(component.path("parentBaseQty").decimalValue()):"");
        row.put("quantity",component.path("quantity").isNumber()?number(component.path("quantity").decimalValue()):"");
        row.put("packageQuantity",number(part.getQty()));row.put("path",component.path("sourcePath").asText(""));
        if(validator.household(component)!=null) {
          row.put("note","家用询价已独立计价，不进入包装费，不作为0元包装材料");rows.add(row);continue;
        }
        var unitPrice=component.path("unitPrice").decimalValue();var q=validator.componentQuantity(component);
        var childAmount=component.path("amount").decimalValue();var normalized=validator.parentPrice(childAmount,snapshot);
        childTotal=childTotal.add(childAmount);
        row.put("unitPrice",number(unitPrice));row.put("childAmount",number(childAmount));
        row.put("effectiveQuantity",number(validator.parentPrice(q,snapshot).multiply(part.getQty())));
        row.put("amountYuan",number(normalized.multiply(part.getQty())));
        row.put("priceSource","MONTHLY_PRICE".equals(component.path("priceSource").asText())?"已采用月价":component.path("priceSource").asText());
        boolean original="PACKAGE_PARENT".equals(component.path("quantityBasis").asText());
        row.put("quantityBasis",original?"子件原始用量，统一除包装母件底数":"旧保存算法，子件先除自身底数");
        row.put("calculation",number(unitPrice)+" × "+row.get("quantity")
            +(original||!component.hasNonNull("parentBaseQty")?"":" ÷ "+row.get("childBaseQty"))
            +" = "+number(childAmount)+"；"+number(childAmount)
            +(packageBase!=null&&packageBase.signum()!=0?" ÷ "+number(packageBase):"（保存算法未除包装母件底数）")
            +" × "+number(part.getQty())+" = "+row.get("amountYuan")+" 元（乘包装系数前）");
        rows.add(row);
      }
      var parent=new LinkedHashMap<String,String>();parent.put("kind","PACKAGE_PARENT");parent.put("parentCode",part.getPartCode());
      parent.put("parentBaseQty",number(packageBase));parent.put("unitPrice",number(part.getUnitPrice()));
      parent.put("packageQuantity",number(part.getQty()));parent.put("amountYuan",number(part.getAmount()));
      parent.put("childAmount",number(childTotal));
      parent.put("calculation","子件金额合计 "+number(childTotal)
          +(packageBase!=null&&packageBase.signum()!=0?" ÷ 包装母件底数 "+number(packageBase):"（保存算法未除包装母件底数）")
          +" = 组件单价 "+number(validator.parentPrice(childTotal,snapshot))+"；保存组件单价 "+number(part.getUnitPrice())
          +" × 本次BOM用量 "+number(part.getQty())+" → 保存组件金额 "+number(part.getAmount())+" 元");
      rows.add(parent);
    }
    var coefficient=metadata.get("coefficient");
    if(coefficient!=null&&!coefficient.isBlank())coefficient=new BigDecimal(coefficient).stripTrailingZeros().toPlainString();
    if(coefficient==null || coefficient.isBlank()) {
      if(found>0 && base.signum()!=0 && fee.getAmount()!=null) {
        coefficient=fee.getAmount().divide(base,12,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        notes.add("旧版本未单独记录包装系数；核对系数 "+coefficient+" 由保存包装费÷保存组件金额反推，不代表读取了当时配置。");
      } else notes.add("此版本未保存完整包装子件或系数，缺少的历史参数不推造。");
    }
    if(coefficient!=null&&!coefficient.isBlank()) {
      var finalRow=new LinkedHashMap<String,String>();finalRow.put("kind","PACKAGE_TOTAL");
      finalRow.put("baseAmount",found>0?number(base):number(fee.getBaseAmount()));finalRow.put("coefficient",coefficient);
      finalRow.put("amountYuan",number(fee.getAmount()));
      finalRow.put("coefficientBasis",metadata.containsKey("coefficient")?"本次保存的包装系数":"由保存金额反推的核对系数");
      finalRow.put("calculation","包装组件金额合计 "+finalRow.get("baseAmount")+" × 包装系数 "+coefficient+" → 保存包装费 "+number(fee.getAmount())+" 元（按原核算精度保留）");rows.add(finalRow);
    }
    if(found==0)notes.add("未找到此版本保存的包装组件明细；不读取当前包装价格重算历史。");
    return new CostAmountComposition("SAVED_ROWS","子件金额＝子件单价×保存用量；组件单价＝子件金额合计÷包装母件底数；"
        +"组件金额＝组件单价×本次BOM用量；包装费用＝组件金额合计×本次包装系数。旧底稿按各行已保存的底数算法列示，家用询价独立行不重复计入。",
        found>0?number(base):number(fee.getBaseAmount()),"0",String.join("；",notes),List.of(),rows);
  }
  private Map<Long,String> names(JsonNode snapshot,CostRunPartItem part,String unit) {
    var expected=new HashMap<Long,String>();snapshot.path("components").forEach(row->{if(row.path("snapshotDetailId").asLong()>0)expected.put(row.path("snapshotDetailId").asLong(),row.path("materialCode").asText());});
    var ids=new ArrayList<>(expected.keySet());
    var result=new HashMap<Long,String>();
    for(int i=0;i<ids.size();i+=256)for(var row:names.selectBatchIds(ids.subList(i,Math.min(i+256,ids.size()))))
      if(Objects.equals(row.getSnapshotId(),snapshot.path("snapshotId").asLong()) && Objects.equals(row.getBusinessUnitType(),unit)
          && Objects.equals(row.getPackageMaterialCode(),part.getPartCode())
          && Objects.equals(row.getChildMaterialCode(),expected.get(row.getId())) && row.getChildMaterialName()!=null)result.put(row.getId(),row.getChildMaterialName());
    return result;
  }
  private static void own(QuoteCostRunVersion v,CostRunPartItem part,PricePrepareItem p) {
    if(!Objects.equals(v.getOaNo(),p.getOaNo())||!Objects.equals(v.getOaFormItemId(),p.getOaFormItemId())
        ||!Objects.equals(v.getProductCode(),p.getTopProductCode())||!Objects.equals(v.getBusinessUnitType(),p.getBusinessUnitType())
        ||!Objects.equals(v.getPricingMonth(),p.getPeriodMonth())||!Objects.equals(part.getPartCode(),p.getMaterialCode())
        ||!Objects.equals(part.getPriceOrgCode(),p.getPriceOrgCode()))throw new AccessDeniedException("包装准备不属于此保存版本");
  }
  private JsonNode jsonTree(String value) {try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("保存包装底稿无法读取",e);}}
  private static String number(BigDecimal value) {return value==null?"":value.stripTrailingZeros().toPlainString();}
}

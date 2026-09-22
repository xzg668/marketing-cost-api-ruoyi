package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPricePublicationStatus;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceItem;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.impl.TechnicalPriceSourceResolverImpl;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 审批只确认技术内容；固定价和可计算参考公式发布，自行公式继续等待财务处理。 */
@Service
public class TechnicalDataPricePublication {
  private static final Logger log = LoggerFactory.getLogger(TechnicalDataPricePublication.class);
  private final PriceFixedItemMapper fixed;
  private final PriceLinkedItemMapper linked;
  private final PriceVariableBindingMapper bindings;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataPriceOwnership ownership;
  private final TechnicalPriceSourceResolverImpl calculator;
  private final QuoteTechnicalDataRepository repository;
  public TechnicalDataPricePublication(PriceFixedItemMapper fixed, PriceLinkedItemMapper linked, TechnicalDataVersionContentCodec codec,
      TechnicalDataPriceOwnership ownership, TechnicalPriceSourceResolverImpl calculator, QuoteTechnicalDataRepository repository, PriceVariableBindingMapper bindings) {
    this.fixed=fixed; this.linked=linked; this.codec=codec; this.ownership=ownership; this.calculator=calculator;
    this.repository=repository; this.bindings=bindings;
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void publish(QuoteTechTask task, QuoteTechProduct product, QuoteTechDataVersion version) {
    if (!"APPROVED".equals(version.getVersionStatus()) || !Objects.equals(product.getId(),version.getProductId())) throw new IllegalArgumentException("价格发布须使用本产品已审批版本");
    String actual=codec.fingerprint(version,codec.readReferenceSnapshot(version.getReferenceSnapshotJson()),
        repository.findPackageItems(version.getId()),repository.findAuxItems(version.getId()),repository.findSalaryItems(version.getId()));
    if(!Objects.equals(actual,version.getContentFingerprint())) throw new IllegalStateException("价格审批内容与冻结指纹不一致");
    var content=codec.prices(version);
    if (content==null || content.items()==null) return;
    for (var item:content.items()) {
      var owner=ownership.find(item.materialNo());
      if(owner==null || !Objects.equals(owner.productId(),product.getId())) throw new IllegalStateException(item.materialNo()+" 价格办理来源不一致");
      var issues=TechnicalDataPriceRules.validate(item);
      if(!issues.isEmpty()) throw new IllegalStateException(String.join("；",issues));
      if("FIXED".equals(item.entryMode())) publishFixed(task,version,item);
      else if("REFERENCE".equals(item.entryMode())) publishReference(task,product,version,item);
      // MANUAL 不建立虚假可用记录，TW-17 由财务修正导入后关联原审批版本。
    }
  }

  private void publishFixed(QuoteTechTask task,QuoteTechDataVersion version,PriceItem item) {
    var existing=fixed.selectList(Wrappers.lambdaQuery(PriceFixedItem.class).eq(PriceFixedItem::getTechnicalVersionId,version.getId()).eq(PriceFixedItem::getTechnicalItemKey,item.itemKey()));
    if(!existing.isEmpty()) return;
    var row=new PriceFixedItem(); row.setSourceKind("TECH_SUPPLEMENTAL"); row.setTechnicalVersionId(version.getId()); row.setTechnicalItemKey(item.itemKey());
    row.setTechnicalPublicationStatus("AVAILABLE"); row.setTechnicalPublicationMessage("技术审批通过，固定不含税价可用");
    row.setMaterialCode(item.materialNo()); row.setOrgCode(item.organizationCode()); row.setUnit(item.unit()); row.setBusinessUnitType(task.getBusinessUnitType());
    row.setSourceType("PURCHASE_FIXED"); row.setSourceName("技术补录"); row.setSourceSystem("TECH_SUPPLEMENTAL");
    row.setSourceBatchNo("TECH:"+version.getId()); row.setFixedPrice(item.unitPrice()); row.setTaxIncluded(0);
    row.setEffectiveFrom(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE).toLocalDate()); row.setEffectiveTo(null);
    fixed.insert(row);
  }

  private void publishReference(QuoteTechTask task,QuoteTechProduct product,QuoteTechDataVersion version,PriceItem item) {
    var existing=linked.selectList(Wrappers.lambdaQuery(PriceLinkedItem.class).eq(PriceLinkedItem::getTechnicalVersionId,version.getId()).eq(PriceLinkedItem::getTechnicalItemKey,item.itemKey()));
    if(!existing.isEmpty() && "AVAILABLE".equals(existing.getFirst().getTechnicalPublicationStatus())) return;
    var row=new PriceLinkedItem(); row.setSourceKind("TECH_SUPPLEMENTAL"); row.setTechnicalVersionId(version.getId()); row.setTechnicalItemKey(item.itemKey());
    row.setTechnicalPublicationStatus("FAILED"); row.setMaterialCode(item.materialNo()); row.setOrgCode(item.organizationCode()); row.setUnit(item.unit());
    row.setBusinessUnitType(task.getBusinessUnitType()); row.setPricingMonth(product.getAccountingMonth()); row.setSourceName("技术补录");
    row.setFormulaExpr(item.formula()); row.setFormulaExprCn(item.reference().formulaText()); row.setTaxIncluded(item.reference().taxIncluded());
    row.setEffectiveFrom(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE).toLocalDate()); row.setEffectiveTo(null); row.setDeleted(0);
    if(item.parameters()!=null) {
      var p=item.parameters();
      // 既有价格变量读取 linked_item 的克值；显式 kg 输入先换为克，公式仍用原规范表达式。
      BigDecimal multiplier="千克".equals(p.weightUnit())?new BigDecimal("1000"):BigDecimal.ONE;
      row.setBlankWeight(p.blankWeight()==null?null:p.blankWeight().multiply(multiplier));
      row.setNetWeight(p.netWeight()==null?null:p.netWeight().multiply(multiplier));
      row.setProcessFee(p.processFee()); row.setAgentFee(p.agentFee());
    }
    if(existing.isEmpty()) {
      linked.insert(row);
      for (var source : item.reference().bindings()) {
        var binding = new PriceVariableBinding();
        binding.setLinkedItemId(row.getId()); binding.setTokenName(source.tokenName());
        binding.setFactorCode(source.factorCode()); binding.setPriceSource(source.priceSource());
        binding.setFactorIdentityId(source.factorIdentityId()); binding.setBuScoped(source.buScoped());
        binding.setEffectiveDate(row.getEffectiveFrom()); binding.setSource("TECH_SUPPLEMENTAL"); binding.setDeleted(0);
        bindings.insert(binding);
      }
    }
    else row=existing.getFirst();
    try {
      String targetUnit = priceUnit(item.unit());
      if (!Objects.equals(targetUnit, priceUnit(item.reference().unit()))) {
        throw new IllegalArgumentException("参考公式计价单位与本次采购单位不一致，请核实换算依据");
      }
      var parameters = item.parameters();
      if (parameters != null && (parameters.processFee() != null || parameters.agentFee() != null)
          && !Objects.equals("元/" + targetUnit, parameters.feeUnit())) {
        throw new IllegalArgumentException("费用单位与公式计价单位不一致，缺少可靠换算依据");
      }
      var context=CostRunContext.quote(task.getOaNo(),product.getOaFormItemId(),product.getMaterialNo(),null,null,task.getBusinessUnitType(),
          product.getAccountingMonth(),LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE),null);
      context.setPriceOrgCode(task.getApplicableOrgCode()); context.setPriceCheckOnly(true);
      var result=calculator.calculate(row,context);
      boolean usable="OK".equals(result.getCalcStatus()) && result.getPartUnitPrice()!=null && result.getPartUnitPrice().signum()>0;
      row.setTechnicalPublicationStatus(usable?"AVAILABLE":"FAILED");
      row.setTechnicalPublicationMessage(usable?"技术审批通过，参考公式已通过实际计算":Objects.toString(result.getCalcMessage(),"参考公式未取得有效正数单价"));
    } catch(RuntimeException exception) {
      log.warn("technical reference price publication failed: version={} material={}",version.getId(),item.materialNo(),exception);
      row.setTechnicalPublicationMessage(Objects.toString(exception.getMessage(),"参考公式计算失败"));
    }
    if(row.getTechnicalPublicationMessage().length()>1000) row.setTechnicalPublicationMessage(row.getTechnicalPublicationMessage().substring(0,1000));
    linked.updateById(row);
  }

  private static String priceUnit(String value) {
    if (value == null) return null;
    String unit = value.trim().replace('，', ',');
    return unit.substring(unit.lastIndexOf(',') + 1).trim();
  }

  public List<TechnicalDataPricePublicationStatus> statuses(QuoteTechDataVersion version) {
    var content=codec.prices(version);
    if(version==null || content==null || content.items()==null) return List.of();
    Map<String,TechnicalDataPricePublicationStatus> published=new HashMap<>();
    fixed.selectList(Wrappers.lambdaQuery(PriceFixedItem.class).eq(PriceFixedItem::getTechnicalVersionId,version.getId()))
        .forEach(row -> published.put(row.getTechnicalItemKey(),new TechnicalDataPricePublicationStatus(row.getTechnicalItemKey(),row.getMaterialCode(),row.getTechnicalPublicationStatus(),row.getTechnicalPublicationMessage())));
    linked.selectList(Wrappers.lambdaQuery(PriceLinkedItem.class).eq(PriceLinkedItem::getTechnicalVersionId,version.getId()))
        .forEach(row -> {
          var previous=published.get(row.getTechnicalItemKey());
          if(previous==null || !"AVAILABLE".equals(previous.status()))published.put(row.getTechnicalItemKey(),new TechnicalDataPricePublicationStatus(row.getTechnicalItemKey(),row.getMaterialCode(),row.getTechnicalPublicationStatus(),row.getTechnicalPublicationMessage()));
        });
    return content.items().stream().map(item -> {
      if(!"APPROVED".equals(version.getVersionStatus())) return new TechnicalDataPricePublicationStatus(item.itemKey(),item.materialNo(),version.getVersionStatus(),"尚未形成可用补录价格");
      if("MANUAL".equals(item.entryMode()) && published.containsKey(item.itemKey()))return published.get(item.itemKey());
      if("MANUAL".equals(item.entryMode())) return new TechnicalDataPricePublicationStatus(item.itemKey(),item.materialNo(),"WAIT_FINANCE","技术审批已通过，等待报价员修正、导入并检查取价");
      return published.getOrDefault(item.itemKey(),new TechnicalDataPricePublicationStatus(item.itemKey(),item.materialNo(),"UNPUBLISHED","已审批，尚未形成可用价格，请重新检查"));
    }).toList();
  }
}

package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ManufacturingNodeEvidence;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ManufacturingScrap;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.RawMaterial;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 将已确认图库中的一件一原料关系及原始重量适配为现有制造件计算输入。 */
@Service
public class ElectronicDrawingManufacturingInputs {
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final ElectronicDrawingSourceNodeRepository sourceNodes;
  private final QuoteBomSupplementDetailMapper details;
  private final MakePartScrapMappingService scraps;
  private final MakePartNoScrapConfirmationService noScrap;

  public ElectronicDrawingManufacturingInputs(ElectronicDrawingWorkflowContextPort contexts,
      ElectronicDrawingSourceNodeRepository sourceNodes, QuoteBomSupplementDetailMapper details,
      MakePartScrapMappingService scraps, MakePartNoScrapConfirmationService noScrap) {
    this.contexts = contexts; this.sourceNodes = sourceNodes; this.details = details;
    this.scraps = scraps; this.noScrap = noScrap;
  }

  public Map<String, TechnicalManufacturingInputs.Input> read(TechnicalDataCostingSources.Source drawing) {
    if (drawing == null) return Map.of();
    var context = contexts.load(drawing.product().getOaFormItemId(), drawing.task().getBusinessUnitType(),
        drawing.task().getApplicableOrgCode(), drawing.product().getAccountingMonth());
    if (context.sourceVersionId() == null) return Map.of();
    var source = sourceNodes.findByVersionId(context.sourceVersionId()).stream()
        .collect(Collectors.toMap(com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode::getId, Function.identity()));
    var rows = details.selectList(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, context.sourceVersionId()));
    var result = new LinkedHashMap<String, TechnicalManufacturingInputs.Input>();
    for (var parent : rows) {
      if (!"制造件".equals(parent.getShapeAttr()) || !"E_DRAWING".equals(parent.getNodeSourceType())) continue;
      var original = source.get(parent.getSourceElectronicNodeId());
      if (original == null) continue;
      var children = rows.stream().filter(row -> row.getLevel() != null && parent.getLevel() != null
          && row.getLevel() == parent.getLevel() + 1 && row.getPath().startsWith(parent.getPath())).toList();
      if (children.size() != 1 || !"采购件".equals(children.getFirst().getShapeAttr())
          || !"E_DRAWING".equals(children.getFirst().getNodeSourceType())) continue;
      var raw = children.getFirst();
      if (raw.getUnit() == null || !WEIGHT_UNITS.contains(raw.getUnit())) continue;
      BigDecimal netG = TechnicalDataManufacturingUnits.sourceWeightG(original.getReferenceWeight(), original.getReferenceWeightUnit());
      BigDecimal grossG = switch (raw.getUnit()) {
        case "kg", "KG", "千克", "公斤" -> raw.getQtyPerParent().movePointRight(3);
        default -> raw.getQtyPerParent();
      };
      if (netG.signum() <= 0 || grossG.compareTo(netG) < 0)
        throw new IllegalArgumentException("图库制造件重量不完整或毛重小于净重：" + original.getDrawingCode());
      var mapping = scraps.listMappings(raw.getMaterialCode(), context.businessUnitType()).stream()
          .map(row -> new ManufacturingScrap(row.getScrapCode(), row.getScrapName(), row.getScrapUnit())).toList();
      var confirmation = mapping.isEmpty()
          ? noScrap.findEffective(raw.getMaterialCode(), context.accountingMonth(), context.businessUnitType()) : null;
      String state = mapping.size() == 1 ? "MATCHED" : mapping.size() > 1 ? "AMBIGUOUS"
          : confirmation != null && "ACTIVE".equals(confirmation.getStatus()) ? "NO_SCRAP_CONFIRMED" : "MISSING";
      var evidence = new ManufacturingNodeEvidence(original.getSourceName(), original.getDrawingCode(),
          original.getQty(), original.getReferenceWeight(), original.getReferenceWeightUnit(),
          raw.getMaterialName(), raw.getMaterialSpec(), state, mapping);
      var material = new RawMaterial("DRAWING_RAW:" + original.getId(), original.getId().toString(),
          context.sourceVersionId(), parent.getMaterialCode(), raw.getMaterialCode(), raw.getDrawingNo(),
          netG.movePointLeft(3), raw.getQtyPerParent(), raw.getUnit(), null, grossG.movePointLeft(3), null, evidence);
      result.put(parent.getPath(), new TechnicalManufacturingInputs.Input(drawing.version().getId(), parent.getPath(), material));
    }
    return Map.copyOf(result);
  }

  private static final java.util.Set<String> WEIGHT_UNITS = java.util.Set.of("kg", "KG", "千克", "公斤", "g", "G", "克");
}

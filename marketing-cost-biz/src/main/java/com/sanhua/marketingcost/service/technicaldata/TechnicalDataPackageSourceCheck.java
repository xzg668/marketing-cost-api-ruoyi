package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 只有确实缺少包装关系才分派；未取得图库或组树失败不伪装成包装缺失。 */
@Service
public class TechnicalDataPackageSourceCheck {
  private final TechnicalDataPackageSourceQuery sources;
  private final QuoteProductTypeResolveService productTypes;
  private final ElectronicDrawingWorkflowContextPort drawings;
  private final QuoteBomSupplementDetailMapper details;
  private final MaterialMasterRawMapper materials;
  private final OaMessageCodec json;

  public TechnicalDataPackageSourceCheck(TechnicalDataPackageSourceQuery sources, QuoteProductTypeResolveService productTypes,
      ElectronicDrawingWorkflowContextPort drawings, QuoteBomSupplementDetailMapper details,
      MaterialMasterRawMapper materials, OaMessageCodec json) {
    this.sources = sources; this.productTypes = productTypes; this.drawings = drawings;
    this.details = details; this.materials = materials; this.json = json;
  }

  public TechnicalDataSourceFact check(QuoteBomReadContext context, TechnicalDataAvailability original) {
    try {
      if (original == TechnicalDataAvailability.ERROR) return fact(context, TechnicalDataAvailability.ERROR, "U9 原始资料查询失败，请重查", null);
      if (original == TechnicalDataAvailability.AVAILABLE) {
        var packaging = sources.inspectProduct(context.productCode(), context.accountingMonth(), context.businessUnitType(), context.priceOrgCode());
        boolean available = !packaging.sources().isEmpty() && packaging.missingParents().isEmpty();
        return fact(context, available ? TechnicalDataAvailability.AVAILABLE : TechnicalDataAvailability.MISSING,
            available ? "沿用本产品正式 BOM 包装关系" : missingMessage(packaging.missingParents()),
            json.write(packaging.sources().stream().map(row -> row.evidence()).toList()));
      }
      var type = productTypes.resolve(context.productCode(), context.materialOrganizationCode());
      var drawing = drawings.load(context.oaFormItemId(), context.businessUnitType(), context.priceOrgCode(), context.accountingMonth());
      if (drawing == null || drawing.sourceVersionId() == null || drawing.compositionFingerprint() == null) {
        if (type != null && type.productType() == QuoteProductType.BARE) {
          return fact(context, TechnicalDataAvailability.MISSING, "本产品为裸品，需要补录本次包装", "BARE:" + context.productCode());
        }
        return fact(context, TechnicalDataAvailability.UNCONFIRMED, "先核实本产品 BOM，再判断是否缺包装；已有包装草稿保留", null);
      }
      var rows = details.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.sanhua.marketingcost.entity.QuoteBomSupplementDetail>lambdaQuery()
          .eq(com.sanhua.marketingcost.entity.QuoteBomSupplementDetail::getSupplementVersionId, drawing.sourceVersionId()));
      if (rows.isEmpty()) return fact(context, TechnicalDataAvailability.ERROR, "图库 BOM 明细为空，请重新检查", null);
      // 只识别本棵树涉及的当前组织料号，避免每次资料检查都扫描整份料品主档。
      var drawingCodes = rows.stream().map(row -> row.getMaterialCode())
          .filter(StringUtils::hasText).collect(Collectors.toSet());
      Set<String> codes = drawingCodes.isEmpty() ? Set.of()
          : materials.selectByLatestBatchAndCodes(drawingCodes, null, context.materialOrganizationCode()).stream()
              .filter(material -> "包装组件".equals(material.getMainCategoryName()))
              .map(MaterialMasterRaw::getMaterialCode).collect(Collectors.toSet());
      var nodes = rows.stream().map(row -> new TechnicalDataPackageStructure.Node(row.getMaterialCode(),
          row.getParentCode(), row.getPath(), row.getLevel(), context.priceOrgCode())).toList();
      var missing = TechnicalDataPackageStructure.missingParents(nodes, codes);
      boolean available = !codes.isEmpty() && missing.isEmpty();
      return fact(context, available ? TechnicalDataAvailability.AVAILABLE : TechnicalDataAvailability.MISSING,
          available ? "沿用本产品已核实 BOM 中的包装关系" : missingMessage(missing.stream().map(TechnicalDataPackageStructure.Node::code).toList()), "DRAWING:" + drawing.sourceVersionId());
    } catch (RuntimeException exception) {
      return fact(context, TechnicalDataAvailability.ERROR, "包装来源查询失败，请核实正式 BOM 后重查", null);
    }
  }

  private String missingMessage(java.util.List<String> codes) {
    return codes.isEmpty() ? "本产品 BOM 中没有包装关系，请补录包装"
        : "包装组件缺少下级：" + String.join("、", codes.stream().distinct().toList()) + "；请补录这些缺口的包装";
  }

  private TechnicalDataSourceFact fact(QuoteBomReadContext context, TechnicalDataAvailability availability, String message, String reference) {
    return new TechnicalDataSourceFact(TechnicalDataModuleType.PACKAGE, availability, "PACKAGE_SOURCE_" + availability,
        message, reference, context.scanAt());
  }
}

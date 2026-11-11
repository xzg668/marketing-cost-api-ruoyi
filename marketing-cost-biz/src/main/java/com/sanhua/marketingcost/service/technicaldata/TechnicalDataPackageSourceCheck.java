package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 无 U9 BOM 时按实际补录包装检查；查询或组树失败不伪装成包装缺失。 */
@Service
public class TechnicalDataPackageSourceCheck {
  private final TechnicalDataPackageSourceQuery sources;
  private final ElectronicDrawingWorkflowContextPort drawings;
  private final QuoteBomSupplementDetailMapper details;
  private final MaterialMasterRawMapper materials;
  private final OaMessageCodec json;
  private final TechnicalDataSharedModules shared;
  private final com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope repriceBom;
  private final com.sanhua.marketingcost.service.quotebom.MonthlyBomSnapshotDetailService monthlyDetails;
  private final com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper monthlySnapshots;

  public TechnicalDataPackageSourceCheck(TechnicalDataPackageSourceQuery sources,
      ElectronicDrawingWorkflowContextPort drawings, QuoteBomSupplementDetailMapper details,
      MaterialMasterRawMapper materials, OaMessageCodec json, TechnicalDataSharedModules shared,
      com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope repriceBom,
      com.sanhua.marketingcost.service.quotebom.MonthlyBomSnapshotDetailService monthlyDetails,
      com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper monthlySnapshots) {
    this.sources = sources; this.drawings = drawings;
    this.details = details; this.materials = materials; this.json = json; this.shared = shared;
    this.repriceBom = repriceBom;
    this.monthlyDetails = monthlyDetails;
    this.monthlySnapshots = monthlySnapshots;
  }

  public TechnicalDataSourceFact check(QuoteBomReadContext context, TechnicalDataAvailability original) {
    try {
      if (original == TechnicalDataAvailability.ERROR) return fact(context, TechnicalDataAvailability.ERROR, "U9 原始资料查询失败，请重查", null);
      if (original == TechnicalDataAvailability.AVAILABLE) {
        Long snapshotId = repriceBom.snapshotId(context);
        if (snapshotId == null) {
          var saved = monthlySnapshots.selectU9MonthlyByIdentity(
              com.sanhua.marketingcost.service.quotebom.U9MonthlySnapshotIdentity.from(context).identityKey());
          if (saved != null && "SUCCESS".equals(saved.getSyncStatus())
              && Objects.equals(saved.getActiveFlag(), 1)) snapshotId = saved.getId();
        }
        if (snapshotId != null) return checkSavedPackage(context, snapshotId);
        var packaging = sources.inspectProduct(context.productCode(), context.accountingMonth(), context.businessUnitType(), context.priceOrgCode());
        boolean available = !packaging.sources().isEmpty() && packaging.missingParents().isEmpty();
        return fact(context, available ? TechnicalDataAvailability.AVAILABLE : TechnicalDataAvailability.MISSING,
            available ? "沿用本产品正式 BOM 包装关系" : missingMessage(packaging.missingParents()),
            json.write(packaging.sources().stream().map(row -> row.evidence()).toList()));
      }
      if (original != TechnicalDataAvailability.MISSING) {
        return fact(context, TechnicalDataAvailability.UNCONFIRMED, "U9 原始 BOM 尚未核实，请先检查来源", null);
      }
      var owner = shared.find(context.productCode(), context.oaFormItemId(), "DRAWING_BOM");
      boolean sharedSource = owner != null && (!Objects.equals(owner.quoteItemId(), context.oaFormItemId())
          || !Objects.equals(owner.accountingMonth(), context.accountingMonth()));
      if (sharedSource && (!Objects.equals(owner.organization(), context.priceOrgCode())
          || !Objects.equals(owner.businessUnit(), context.businessUnitType()))) {
        return fact(context, TechnicalDataAvailability.ERROR, "原图库组织与本报价不一致，请核实来源", null);
      }
      // 包装关系属于实际采用的图库。共享办理时新报价行没有独立图库准备记录，
      // 必须沿原报价行/月检查；共享版本是否可消费仍由核算来源校验统一保证。
      var drawing = repriceBom.drawing(context, () -> drawings.load(sharedSource ? owner.quoteItemId() : context.oaFormItemId(),
          context.businessUnitType(), context.priceOrgCode(),
          sharedSource ? owner.accountingMonth() : context.accountingMonth()));
      if (drawing == null || drawing.sourceVersionId() == null || drawing.compositionFingerprint() == null) {
        return fact(context, TechnicalDataAvailability.MISSING,
            "本产品无 U9 原始 BOM，需要补录本次包装", "U9_NOT_FOUND:" + context.productCode());
      }
      var rows = repriceBom.drawingRows(context);
      if (rows == null) rows = details.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.sanhua.marketingcost.entity.QuoteBomSupplementDetail>lambdaQuery()
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

  /** 调价已有明确月度结构，包装检查直接读取这一份，不再选取原始 U9 BOM。 */
  private TechnicalDataSourceFact checkSavedPackage(QuoteBomReadContext context, Long snapshotId) {
    var rows = monthlyDetails.load(snapshotId);
    if (rows.isEmpty()) return fact(context, TechnicalDataAvailability.ERROR, "已选定的月度 BOM 明细为空", null);
    var codes = rows.stream().filter(row -> Objects.equals(row.getPriceOrgCode(), context.priceOrgCode()))
        .map(row -> row.getMaterialCode()).filter(StringUtils::hasText).collect(Collectors.toSet());
    var packageCodes = codes.isEmpty() ? Set.<String>of() : materials.selectByLatestBatchAndCodes(
        codes, null, context.materialOrganizationCode()).stream()
        .filter(row -> "包装组件".equals(row.getMainCategoryName())).map(MaterialMasterRaw::getMaterialCode)
        .collect(Collectors.toSet());
    var nodes = rows.stream().map(row -> new TechnicalDataPackageStructure.Node(row.getMaterialCode(),
        row.getParentCode(), row.getPath(), row.getLevel(), row.getPriceOrgCode())).toList();
    var missing = TechnicalDataPackageStructure.missingParents(nodes, packageCodes);
    boolean available = !packageCodes.isEmpty() && missing.isEmpty();
    return fact(context, available ? TechnicalDataAvailability.AVAILABLE : TechnicalDataAvailability.MISSING,
        available ? "沿用本次已选定 BOM 中的包装关系"
            : missingMessage(missing.stream().map(TechnicalDataPackageStructure.Node::code).toList()),
        "MONTHLY_BOM:" + snapshotId);
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

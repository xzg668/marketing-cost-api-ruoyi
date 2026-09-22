package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 只查公共 BOM、工资、包装和制造资料；不依赖价格生成，组树阶段可安全复用。 */
@Service
public class TechnicalDataPublicSourceCheck {
  private final TechnicalDataSharedModules shared;
  private final CurrentU9BomGateway u9;
  private final TechnicalDataSalarySourceQuery salary;
  private final OaMessageCodec codec;
  private final ElectronicDrawingWorkflowContextPort drawingContexts;
  private final TechnicalDataManufacturingSourceQuery manufacturing;
  private final TechnicalDataPackageSourceCheck packaging;
  private final com.sanhua.marketingcost.service.NetLossRateQuery netLoss;

  public TechnicalDataPublicSourceCheck(
      CurrentU9BomGateway u9,
      TechnicalDataSalarySourceQuery salary,
      OaMessageCodec codec,
      ElectronicDrawingWorkflowContextPort drawingContexts,
      TechnicalDataManufacturingSourceQuery manufacturing,
      TechnicalDataPackageSourceCheck packaging,
      com.sanhua.marketingcost.service.NetLossRateQuery netLoss, TechnicalDataSharedModules shared) {
    this.shared = shared;
    this.u9 = u9;
    this.salary = salary;
    this.codec = codec;
    this.drawingContexts = drawingContexts;
    this.manufacturing = manufacturing;
    this.packaging = packaging;
    this.netLoss = netLoss;
  }

  /** BOM 组装和成本输入共同读取公共资料，价格在组树后的统一价格准备中检查。 */
  public List<TechnicalDataSourceFact> checkDataSources(QuoteBomReadContext context) {
    List<TechnicalDataSourceFact> facts = new ArrayList<>();
    var originalBom = checkOriginalBom(context, facts);
    facts.add(packaging.check(context, originalBom));
    facts.add(checkManufacturing(context, originalBom));
    facts.add(checkSalary(context));
    facts.add(checkNetLoss(context));
    return List.copyOf(facts);
  }

  private TechnicalDataAvailability checkOriginalBom(
      QuoteBomReadContext context, List<TechnicalDataSourceFact> facts) {
    CurrentU9BomResult result;
    try {
      result = u9.read(context);
    } catch (RuntimeException exception) {
      result = CurrentU9BomResult.error("U9 原始 BOM 查询失败，请核实来源后重查");
    }
    var status = result == null ? CurrentU9BomResult.Status.ERROR : result.status();
    var availability =
        switch (status) {
          case AVAILABLE -> TechnicalDataAvailability.AVAILABLE;
          case NOT_FOUND -> TechnicalDataAvailability.MISSING;
          default -> TechnicalDataAvailability.ERROR;
        };
    String reference =
        result == null
            ? null
            : codec.write(
                Map.of(
                    "source",
                    "U9",
                    "status",
                    status.name(),
                    "snapshotId",
                    Objects.toString(result.monthlySnapshotId(), ""),
                    "structureFingerprint",
                    Objects.toString(result.structureFingerprint(), "")));
    for (var type :
        List.of(
            TechnicalDataModuleType.PROFILE,
            TechnicalDataModuleType.DRAWING_BOM,
            TechnicalDataModuleType.AUXILIARY,
            TechnicalDataModuleType.SOLDER)) {
      String reason =
          switch (availability) {
            case AVAILABLE -> "本产品 U9 原始 BOM 可用，无需按无 BOM 条件补录";
            case MISSING ->
                type == TechnicalDataModuleType.DRAWING_BOM
                    ? "本产品无 U9 原始 BOM，需要选取并确认电子图库明细"
                    : "本产品无 U9 原始 BOM，需要补录本项资料";
            default -> result == null ? "U9 查询未返回结论，请重新检查" : result.message();
          };
      facts.add(fact(context, type, availability, "U9_ORIGINAL_" + status, reason, reference));
    }
    return availability;
  }

  private TechnicalDataSourceFact checkManufacturing(
      QuoteBomReadContext context, TechnicalDataAvailability originalBom) {
    if (originalBom == TechnicalDataAvailability.AVAILABLE)
      return fact(
          context,
          TechnicalDataModuleType.MANUFACTURING,
          TechnicalDataAvailability.AVAILABLE,
          "MANUFACTURING_U9_SOURCE",
          "沿用本产品正式 U9 下级关系",
          "U9:" + context.productCode());
    if (originalBom == TechnicalDataAvailability.ERROR)
      return fact(
          context,
          TechnicalDataModuleType.MANUFACTURING,
          TechnicalDataAvailability.ERROR,
          "MANUFACTURING_SOURCE_ERROR",
          "U9 原始来源尚未核实，不能判定缺原材料",
          null);
    try {
      var owner = shared.find(context.productCode(), context.oaFormItemId(), "DRAWING_BOM");
      boolean sharedSource = owner != null && (!Objects.equals(owner.quoteItemId(), context.oaFormItemId())
          || !Objects.equals(owner.accountingMonth(), context.accountingMonth()));
      if (sharedSource && (!Objects.equals(owner.organization(), context.priceOrgCode())
          || !Objects.equals(owner.businessUnit(), context.businessUnitType()))) {
        return fact(context, TechnicalDataModuleType.MANUFACTURING, TechnicalDataAvailability.ERROR,
            "MANUFACTURING_SOURCE_SCOPE_MISMATCH", "原图库组织与本报价不一致，请核实来源", null);
      }
      var drawing = drawingContexts.load(sharedSource ? owner.quoteItemId() : context.oaFormItemId(),
          context.businessUnitType(), context.priceOrgCode(), sharedSource ? owner.accountingMonth() : context.accountingMonth());
      if (drawing == null || drawing.sourceVersionId() == null)
        return fact(
            context,
            TechnicalDataModuleType.MANUFACTURING,
            TechnicalDataAvailability.UNCONFIRMED,
            "MANUFACTURING_WAIT_DRAWING",
            "先取得图库明细，再检查制造件原材料关系",
            null);
      var result = manufacturing.inspect(drawing);
      String reference =
          codec.write(
              Map.of(
                  "sourceVersionId",
                  drawing.sourceVersionId(),
                  "fingerprint",
                  result.fingerprint()));
      if (result.hasMissing())
        return fact(
            context,
            TechnicalDataModuleType.MANUFACTURING,
            TechnicalDataAvailability.MISSING,
            "MANUFACTURING_RAW_MISSING",
            "存在明确缺少原材料关系的制造件，请按缺失节点补录",
            reference);
      if (result.hasUnresolved())
        return fact(
            context,
            TechnicalDataModuleType.MANUFACTURING,
            TechnicalDataAvailability.UNCONFIRMED,
            "MANUFACTURING_WAIT_SOURCE",
            "仍有料号待财务确认或下级查询未成功，暂不判定为缺原材料",
            reference);
      return fact(
          context,
          TechnicalDataModuleType.MANUFACTURING,
          TechnicalDataAvailability.AVAILABLE,
          "MANUFACTURING_SOURCE_AVAILABLE",
          "当前制造件已有下级关系，或本产品没有需要补原料的制造件",
          reference);
    } catch (RuntimeException exception) {
      return fact(
          context,
          TechnicalDataModuleType.MANUFACTURING,
          TechnicalDataAvailability.ERROR,
          "MANUFACTURING_QUERY_FAILED",
          "制造件下级关系检查失败，请核实后重新检查",
          null);
    }
  }

  private TechnicalDataSourceFact checkSalary(QuoteBomReadContext context) {
    try {
      var source =
          salary.read(
              context.productCode(),
              YearMonth.parse(context.accountingMonth()).getYear(),
              context.businessUnitType());
      var rows = source.rows();
      boolean invalid =
          rows.stream()
                  .anyMatch(row -> row.getAmountYuan() == null || row.getAmountYuan().signum() < 0)
              || rows.stream().map(row -> row.getSourceType()).distinct().count() != rows.size();
      String reference =
          codec.write(
              Map.of(
                  "source",
                  "cms_cost_source_effective",
                  "publishedBatchId",
                  Objects.toString(source.confirmedBatchId(), ""),
                  "rows",
                  rows.stream()
                      .map(
                          row ->
                              Map.of(
                                  "id",
                                  row.getId(),
                                  "type",
                                  row.getSourceType(),
                                  "amount",
                                  Objects.toString(row.getAmountYuan(), ""),
                                  "period",
                                  Objects.toString(row.getPeriod(), "")))
                      .toList()));
      if (invalid)
        return fact(
            context,
            TechnicalDataModuleType.SALARY,
            TechnicalDataAvailability.ERROR,
            "SALARY_SOURCE_INVALID",
            "工资来源金额异常或同类重复，请财务核实",
            reference);
      if (rows.size() == 2 || (!rows.isEmpty() && source.confirmedBatchId() != null)) {
        return fact(
            context,
            TechnicalDataModuleType.SALARY,
            TechnicalDataAvailability.AVAILABLE,
            "SALARY_SOURCE_AVAILABLE",
            rows.size() == 1 ? "沿用已有单项工资，CMS 已确认另一项不存在" : "沿用本产品已有直接、辅助人工工资",
            reference);
      }
      if (source.confirmedBatchId() == null)
        return fact(
            context,
            TechnicalDataModuleType.SALARY,
            TechnicalDataAvailability.UNCONFIRMED,
            "SALARY_SOURCE_UNCONFIRMED",
            "尚无完整 CMS 发布证据，不能把未查到的工资当作确实不存在",
            reference);
      return fact(
          context,
          TechnicalDataModuleType.SALARY,
          TechnicalDataAvailability.MISSING,
          "SALARY_SOURCE_MISSING",
          "CMS 已完成核实，本产品两项工资均不存在",
          reference);
    } catch (RuntimeException exception) {
      return fact(
          context,
          TechnicalDataModuleType.SALARY,
          TechnicalDataAvailability.ERROR,
          "SALARY_QUERY_FAILED",
          "工资来源查询失败，请重新检查",
          null);
    }
  }

  private TechnicalDataSourceFact checkNetLoss(QuoteBomReadContext context) {
    try {
      var source =
          netLoss.lookup(
              context.productCode(),
              context.productModel(),
              context.materialOrganizationCode(),
              YearMonth.parse(context.accountingMonth()).getYear(),
              context.businessUnitType());
      return fact(
          context,
          TechnicalDataModuleType.NET_LOSS,
          TechnicalDataAvailability.valueOf(source.status()),
          source.reasonCode(),
          source.message(),
          codec.write(source));
    } catch (RuntimeException exception) {
      return fact(
          context,
          TechnicalDataModuleType.NET_LOSS,
          TechnicalDataAvailability.ERROR,
          "NET_LOSS_QUERY_FAILED",
          "净损失率查询失败，请核实后重查",
          null);
    }
  }

  private TechnicalDataSourceFact fact(
      QuoteBomReadContext context,
      TechnicalDataModuleType type,
      TechnicalDataAvailability availability,
      String code,
      String reason,
      String reference) {
    return new TechnicalDataSourceFact(
        type,
        availability,
        code,
        reason == null || reason.isBlank() ? "来源检查尚未取得有效结论" : reason,
        reference,
        context.scanAt());
  }
}

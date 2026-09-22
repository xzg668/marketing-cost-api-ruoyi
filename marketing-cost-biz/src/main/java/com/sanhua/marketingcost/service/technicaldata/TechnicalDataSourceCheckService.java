package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 只把实际来源转换为补录前提，不创建待办、不把查询错误当作缺资料。 */
@Service
public class TechnicalDataSourceCheckService {
  public record Check(List<TechnicalDataSourceFact> facts, Map<String, Object> evidence) {}
  private final QuoteTechnicalDataRepository technicalData;
  private final TechnicalDataPriceRequirements priceRequirements;
  private final TechnicalDataPriceOwnership priceOwnership;
  private final TechnicalDataPublicSourceCheck publicSources;
  private final PricePrepareService prices;
  private final OaMessageCodec codec;

  public TechnicalDataSourceCheckService(TechnicalDataPublicSourceCheck publicSources,
      PricePrepareService prices, OaMessageCodec codec, QuoteTechnicalDataRepository technicalData,
      TechnicalDataPriceRequirements priceRequirements, TechnicalDataPriceOwnership priceOwnership) {
    this.publicSources=publicSources; this.prices=prices; this.codec=codec;
    this.technicalData=technicalData; this.priceRequirements=priceRequirements; this.priceOwnership=priceOwnership;
  }

  public Check check(QuoteBomReadContext context, QuoteCostingWorkspace workspace) {
    List<TechnicalDataSourceFact> facts = new ArrayList<>(publicSources.checkDataSources(context));
    Map<String, Object> evidence = new LinkedHashMap<>();
    facts.add(checkPrice(context, workspace, evidence));
    return new Check(List.copyOf(facts), Map.copyOf(evidence));
  }

  private TechnicalDataSourceFact checkPrice(QuoteBomReadContext context, QuoteCostingWorkspace workspace, Map<String, Object> evidence) {
    if (workspace == null || workspace.getCurrentBomBuildBatchId() == null) {
      return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.UNCONFIRMED,
          "PRICE_BOM_NOT_READY", "先确定本产品实际 BOM 及计价物料，再检查价格", null);
    }
    // 核算已确认价格类型有缺口，取价前提尚未成立；提前试算会把未配置的路线误报为查询失败。
    if ("WAIT_PRICE_TYPE".equals(workspace.getWorkspaceStatus())) {
      return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.UNCONFIRMED,
          "PRICE_BASIS_UNCONFIRMED", "请先在价格类型识别页确认缺失的价格类型，再重新核算本产品", null);
    }
    try {
      var request = new PricePrepareGenerateRequest();
      request.setOaNo(context.oaNo());
      request.setOaFormItemId(context.oaFormItemId());
      request.setTopProductCode(context.productCode());
      request.setTopProductCodes(List.of(context.productCode()));
      request.setPeriodMonth(context.accountingMonth());
      request.setBusinessUnitType(context.businessUnitType());
      request.setScenarioType(QuotePriceScenarioType.OA_LOCKED);
      var result = prices.calculate(request);
      if (result == null || result.getSummary() == null || "FAILED".equals(result.getSummary().getStatus())
          || result.getItems().stream().anyMatch(row -> "FAILED".equals(row.getStatus()))) {
        return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.ERROR,
            "PRICE_CHECK_FAILED", "价格检查失败，不能据此分派缺价任务", null);
      }
      var gaps = result.getGaps();
      var product = technicalData.findActiveProduct(context.oaFormItemId(), context.accountingMonth());
      var additional = product.map(value -> priceRequirements.checkModuleReferences(
          technicalData.findTask(value.getTaskId()).orElseThrow(), value)).orElse(null);
      if (additional != null) evidence.put("PRICE_MODULE_REFERENCES", additional);
      if (additional != null && !additional.issues().isEmpty()) {
        return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.ERROR,
            "PRICE_MODULE_CHECK_FAILED", String.join("；", additional.issues()), additional.fingerprint());
      }
      var priceEvidence = Map.of("source", "PricePrepareService", "bomBatch", workspace.getCurrentBomBuildBatchId(),
          "items", result.getItems().stream().map(row -> List.of(Objects.toString(row.getSettlementKey(), ""),
              Objects.toString(row.getMaterialCode(), ""), Objects.toString(row.getUnitPrice(), ""), Objects.toString(row.getPriceSource(), ""))).toList(),
          "gaps", gaps.stream().map(row -> List.of(Objects.toString(row.getGapMaterialCode(), ""),
              Objects.toString(row.getGapType(), ""), Objects.toString(row.getReasonCode(), ""))).toList());
      evidence.put("PRICE", priceEvidence);
      String reference = "PricePrepareService:" + codec.canonicalHash(priceEvidence);
      boolean unresolved = gaps.stream().anyMatch(gap -> !"MISSING_PRICE".equals(gap.getGapType())
          || Objects.toString(gap.getReasonCode(), "").contains("CONFLICT"));
      if (unresolved) return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.UNCONFIRMED,
          "PRICE_BASIS_UNCONFIRMED", "仍有价格类型、结构或来源冲突，请先在价格检查页处理", reference);
      var missingCodes = new java.util.HashSet<String>();
      gaps.forEach(gap -> missingCodes.add(gap.getGapMaterialCode()));
      if (additional != null) additional.items().stream().filter(row -> "MISSING".equals(row.status()))
          .forEach(row -> missingCodes.add(row.materialNo()));
      if (!missingCodes.isEmpty()) {
        var otherOwners = missingCodes.stream().map(priceOwnership::find).filter(Objects::nonNull)
            .filter(owner -> product.isEmpty() || !Objects.equals(owner.productId(), product.get().getId()))
            .filter(owner -> !"CANCELLED".equals(owner.taskStatus()) || "APPROVED".equals(owner.moduleStatus())).toList();
        if (!otherOwners.isEmpty()) evidence.put("PRICE_OWNERS", otherOwners);
        if (otherOwners.size() == missingCodes.size()) {
          String owners = otherOwners.stream().map(owner -> owner.materialNo() + " 已由" + Objects.toString(owner.assigneeName(), "原技术员")
              + "办理（任务 " + owner.taskId() + "）").collect(java.util.stream.Collectors.joining("；"));
          return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.UNCONFIRMED,
              "PRICE_WAIT_ORIGINAL_OWNER", owners + "，等待原资料形成可用价格，无需重复分派", reference);
        }
      }
      if (!missingCodes.isEmpty()) return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.MISSING,
          "PRICE_SOURCE_MISSING", "统一取价后仍有 " + missingCodes.size() + " 项物料缺少可用价格", reference);
      if (result.getItems().isEmpty()) return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.UNCONFIRMED,
          "PRICE_ITEMS_NOT_READY", "尚无已确认的计价物料清单", reference);
      return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.AVAILABLE,
          "PRICE_SOURCE_AVAILABLE", "本次计价物料已有可用价格，无需补价", reference);
    } catch (RuntimeException exception) {
      return fact(context, TechnicalDataModuleType.PRICE, TechnicalDataAvailability.ERROR,
          "PRICE_QUERY_FAILED", "价格来源查询失败，请核实后重新检查", null);
    }
  }

  private TechnicalDataSourceFact fact(QuoteBomReadContext context, TechnicalDataModuleType type,
      TechnicalDataAvailability availability, String code, String reason, String reference) {
    return new TechnicalDataSourceFact(type, availability, code,
        reason == null || reason.isBlank() ? "来源检查尚未取得有效结论" : reason, reference, context.scanAt());
  }
}

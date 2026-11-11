package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Projects saved OA inputs into P0 without opening a price session, reading sources or adopting. */
final class MonthlyPreparationFinanceProjection {
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPreparationEvidence evidence;

  MonthlyPreparationFinanceProjection(ObjectMapper json, MonthlyPriceDocumentCodec codec) {
    this.json = json; this.codec = codec; this.evidence = new MonthlyPreparationEvidence(codec);
  }

  /** The caller assigns the new preparation identity and audit fields. The source is never mutated. */
  PricePrepareItem project(PricePrepareItem source) {
    Objects.requireNonNull(source, "财务投影缺少已保存的OA准备");
    var result = new PricePrepareItem(); org.springframework.beans.BeanUtils.copyProperties(source, result);
    if (source.getQuantity() != null && source.getQuantity().signum() == 0
        && source.getAmount() != null && source.getAmount().signum() == 0) return result;
    if (!"READY".equals(source.getStatus()) || source.getQuantity() == null)
      throw new IllegalArgumentException("财务投影只接受已就绪且有实际用量的OA准备");
    var inputs = evidence.read(source);
    if (inputs == null || inputs.uses().isEmpty()) throw new IllegalArgumentException("财务投影缺少已采用月价与实际用量");
    BigDecimal unitPrice;
    BigDecimal customerUnitPrice = source.getUnitPrice();
    if (MonthlyPreparationPrice.KIND.equals(source.getResultRefType())) {
      var captured = codec.decode(source.getInputSnapshotJson(), MonthlyPreparationPrice.class);
      if (!Objects.equals(source.getMonthlyPriceId(), captured.selection().monthlyPriceId())
          || !Objects.equals(source.getMonthlyPricingType(), captured.selection().fact().type().name())
          || captured.path() == null || !MonthlyBomPricingScope.pathKey(captured.path()).equals(inputs.rootPath()))
        throw new IllegalArgumentException("保存OA的月价引用或路径与底稿不一致");
      amount(source.getQuantity(), captured.quantity(), "保存OA实际用量");
      unitPrice = base(source, inputs, captured, source.getMaterialCode(), source.getUnitPrice(),
          source.getQuantity(), false);
      // Amounts were calculated before DECIMAL(20,8) rounded the saved unit price.
      // Validate against the original captured price, preserving the calculation's precision.
      customerUnitPrice = captured.customerPrice().multiply(captured.unitPriceMultiplier());
    } else if ("MAKE_PART_PRICE".equals(source.getResultRefType())) {
      var make = new MakePartCalculationEvidence(json);
      var rows = make.rows(source);
      if (rows == null) throw new IllegalArgumentException("财务投影缺少保存的制造计算行");
      for (var row : rows) {
        BigDecimal raw = com.sanhua.marketingcost.service.MakePartConsumption.rawQuantity(row);
        row.setRawUnitPrice(base(source, inputs, codec.decode(row.getRawInputSnapshotJson(), MonthlyPreparationPrice.class),
            row.getChildMaterialNo(), row.getRawUnitPrice(), raw.multiply(source.getQuantity()), true));
        if (!Boolean.TRUE.equals(row.getNoScrapConfirmed())) {
          BigDecimal scrap = com.sanhua.marketingcost.service.MakePartConsumption.scrapQuantityKg(row);
          row.setScrapUnitPrice(base(source, inputs, codec.decode(row.getScrapInputSnapshotJson(), MonthlyPreparationPrice.class),
              row.getScrapCode(), row.getScrapUnitPrice(), scrap.multiply(source.getQuantity()), true));
        }
        row.setPriceScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
      }
      var projected = new MakePartPriceCalculator().calculate(rows);
      if (projected.stream().anyMatch(row -> !"OK".equals(row.getStatus()) || row.getParentTotalCostPrice() == null))
        throw new IllegalArgumentException("保存制造输入无法形成财务价格");
      unitPrice = projected.getFirst().getParentTotalCostPrice();
      result.setInputSnapshotJson(make.withRows(source.getInputSnapshotJson(), projected));
    } else if (source.getResultRefType() != null && source.getResultRefType().startsWith("PACKAGE")) {
      var pack = new PackageCalculationEvidence(json);
      var original = pack.trace(source);
      if (original == null) throw new IllegalArgumentException("财务投影缺少保存的包装计算行");
      ObjectNode snapshot = original.deepCopy();
      BigDecimal total = BigDecimal.ZERO;
      for (JsonNode component : pack.components(snapshot).values()) {
        BigDecimal quantity = pack.parentPrice(pack.componentQuantity(component), snapshot).multiply(source.getQuantity());
        var household = pack.household(component);
        if (household != null) {
          if (!MonthlyBomPricingScope.pathKey(household.path()).startsWith(inputs.rootPath() + "/")
              || !MonthlyBomPricingScope.pathKey(household.path()).equals(
                  MonthlyBomPricingScope.pathKey(component.path("sourcePath").asText())))
            throw new IllegalArgumentException("保存包装排除路径不属于本次母件");
          amount(quantity.abs(), household.cumulativeQuantity(), "保存包装独立家用用量");
          continue;
        }
        if (quantity.signum() == 0) continue;
        if (!MonthlyPreparationPrice.KIND.equals(component.path("priceSource").asText()))
          throw new IllegalArgumentException("保存包装子件未接入月价");
        var p0 = base(source, inputs, codec.decode(component.path("inputs").toString(), MonthlyPreparationPrice.class),
            component.path("materialCode").asText(), number(component, "unitPrice"), quantity, true);
        var componentAmount = pack.componentAmount(component, p0);
        ((ObjectNode) component).put("unitPrice", p0); ((ObjectNode) component).put("amount", componentAmount);
        total = total.add(componentAmount);
      }
      unitPrice = pack.parentPrice(total, snapshot);
      result.setInputSnapshotJson(snapshot.toString());
    } else throw new IllegalArgumentException("财务投影不接受缺少月价依据的历史准备类型");
    amount(source.getAmount(), customerUnitPrice == null ? null
        : customerUnitPrice.multiply(source.getQuantity()), "保存OA金额");
    result.setUnitPrice(unitPrice); result.setAmount(unitPrice.multiply(source.getQuantity()));
    // Re-run the same saved-evidence validators on the projected numbers.
    if ("MAKE_PART_PRICE".equals(result.getResultRefType())) new MakePartCalculationEvidence(json).rows(result);
    else if (result.getResultRefType().startsWith("PACKAGE")) new PackageCalculationEvidence(json).trace(result);
    return result;
  }

  private BigDecimal base(PricePrepareItem source, MonthlyPreparationInputs inputs, MonthlyPreparationPrice value,
      String code, BigDecimal p1, BigDecimal physicalQuantity, boolean ordinary) {
    var request = value.request(); var selected = value.selection();
    if (!request.matchesBomMaterialCode(code)
        || !Objects.equals(source.getBusinessUnitType(), request.family().businessUnitType())
        || (!ordinary && !Objects.equals(source.getPriceOrgCode(), request.priceOrgCode()))
        || !Objects.equals(source.getPeriodMonth(), request.family().month().toString())
        || !selected.applicability().matches(request)
        || value.financialPrice().compareTo(selected.fact().price()) != 0
        || value.customerPrice().signum() < 0
        || (ordinary && selected.fact().type() != MonthlyAdoptionPlan.PricingType.NORMAL))
      throw new IllegalArgumentException("保存基础价格身份或P0与月价事实不一致");
    amount(p1, value.customerPrice().multiply(value.unitPriceMultiplier()), "保存基础P1");
    if (physicalQuantity.signum() != 0) {
      if (selected.monthlyPriceId() == null || selected.monthlyPriceId() <= 0
          || !selected.equals(inputs.plan().selections().get(request)))
        throw new IllegalArgumentException("保存基础价格不是本次已采用月价");
    }
    return value.financialPrice().multiply(value.unitPriceMultiplier());
  }

  private static BigDecimal number(JsonNode value, String field) {
    if (!value.path(field).isNumber()) throw new IllegalArgumentException("保存计算行缺少数值：" + field);
    return value.path(field).decimalValue();
  }

  private static void amount(BigDecimal actual, BigDecimal expected, String field) {
    if (actual == null || expected == null || actual.setScale(8, RoundingMode.HALF_UP)
        .compareTo(expected.setScale(8, RoundingMode.HALF_UP)) != 0)
      throw new IllegalArgumentException(field + "不一致");
  }
}

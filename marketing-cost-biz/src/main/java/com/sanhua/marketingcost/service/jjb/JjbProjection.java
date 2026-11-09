package com.sanhua.marketingcost.service.jjb;

import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.dto.costing.ProductFeeCode;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.Stored;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost;
import com.sanhua.marketingcost.service.costing.ProductFeeContract;
import java.math.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Presentation of saved facts only. No calculator, current price, or current PROFILE dependency. */
@Component
public class JjbProjection {
  private final ObjectMapper json;
  public JjbProjection(ObjectMapper json) { this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS); }
  private static final Map<String, String> LABELS = Map.ofEntries(
      Map.entry("MATERIAL", "材料费"), Map.entry("DIRECT_LABOR", "直接人工工资"),
      Map.entry("INDIRECT_LABOR", "辅助人工工资"), Map.entry("LOSS", "净损失"),
      Map.entry("MANUFACTURE", "制造费用"), Map.entry("MANUFACTURE_COST", "制造成本"),
      Map.entry("ADJUSTED_MANUFACTURE_COST", "属性调整后制造成本"),
      Map.entry("MGMT_EXP", "管理费用"), Map.entry("SALES_EXP", "营业费用"), Map.entry("FIN_EXP", "财务费用"),
      Map.entry("OTHER_INDEPENDENT", "其他独立费用"),
      Map.entry("BOM_BUCKET_PACKAGE", "包装"));
  private static final List<String> ORDINARY = List.of("MATERIAL", "DIRECT_LABOR", "INDIRECT_LABOR",
      "LOSS", "MANUFACTURE", "MANUFACTURE_COST", "ADJUSTED_MANUFACTURE_COST");
  private static final List<String> THREE = List.of("MGMT_EXP", "SALES_EXP", "FIN_EXP");

  public JjbReadModel project(Stored stored, OaForm form, OaFormItem item,
      List<CostRunTraceSnapshot> traces, List<PricePrepareItem> preparations) {
    var v = stored.version(); var snapshot = stored.snapshot();
    var calc = snapshot == null ? null : snapshot.calculation();
    var warnings = new ArrayList<String>(); var rows = new ArrayList<Row>();
    var costMap = stored.costs().stream().collect(Collectors.groupingBy(CostRunCostItem::getCostCode, LinkedHashMap::new, Collectors.toList()));
    var consumed = new HashSet<Long>();
    var partsByPrepare = stored.parts().stream().filter(p -> p.getPricePrepareItemId() != null)
        .collect(Collectors.groupingBy(CostRunPartItem::getPricePrepareItemId));
    if (calc != null) {
      for (var line : calc.ordinaryInputs().materials()) if (line.explanationOf() == null) {
        var binding = snapshot.preparations().get(line.path());
        var part = bound(partsByPrepare, binding.financialItemId());
        verifyPart(part, line.materialCode(), line.quantity(), line.financeAmount());
        consumed.add(part.getId());
        rows.add(part(part).monthlyPriceIds(line.monthlyPriceIds()).paths(List.of(line.path())).build());
      }
    } else for (var part : stored.parts()) rows.add(part(part).build());

    rows = manufacturingDetails(rows, preparations);
    rows = explainSavedChildren(rows, traces);
    if (calc != null) for (var line : calc.ordinaryInputs().materials()) if (line.explanationOf() != null) {
      rows.add(Row.builder().key("EXPLANATION:"+line.path()).section("ORDINARY_PARTS").kind("EXPLANATION")
          .code(line.materialCode()).name("基础料件说明").quantity(decimal(line.quantity())).amount(money(line.financeAmount()))
          .monthlyPriceIds(line.monthlyPriceIds()).paths(List.of(line.path()))
          .remark("已计入母件 "+line.explanationOf()+"，本行不重复汇总").build());
    }
    Set<Long> feeIds = new HashSet<>();
    Map<ProductFeeCode,List<CostRunCostItem>> feeCosts = new EnumMap<>(ProductFeeCode.class);
    for (var fee : ProductFeeCode.values()) feeCosts.put(fee, new ArrayList<>());
    for (var cost : stored.costs()) {
      var fee = feeCode(cost);
      if (fee != null) { feeCosts.get(fee).add(cost); feeIds.add(cost.getId()); }
    }
    Set<String> reserved = new HashSet<>(ORDINARY); reserved.addAll(THREE);
    reserved.addAll(List.of("TOTAL", "FINAL_QUOTE", "CU_MATERIAL_ADJUSTMENT", "HOUSEHOLD_SUBTOTAL", "HOUSEHOLD_CONTRIBUTION"));
    // Welding already appears as individual parts. Its classification subtotal must not be another sheet row.
    reserved.add("BOM_BUCKET_WELD");
    // Keep every legitimate ordinary detail, including historical public non-product expenses.
    for (var cost : stored.costs()) if (!reserved.contains(cost.getCostCode()) && !feeIds.contains(cost.getId())) {
      if ("OTHER_INDEPENDENT".equals(cost.getCostCode()) && cost.getAmount() != null && BigDecimal.ZERO.compareTo(cost.getAmount()) == 0) continue;
      rows.add(cost(cost, "MATERIAL_DETAIL").build());
    }
    rows = packagingDetails(rows, preparations, traces, warnings);
    for (String code : ORDINARY) addCost(rows, costMap, code, "ORDINARY", calc, warnings);
    for (String code : THREE) addCost(rows, costMap, code, "THREE_EXPENSES", calc, warnings);
    rows.add(subtotal("THREE_SUBTOTAL", "THREE_EXPENSES", "三项费用小计", THREE.stream()
        .flatMap(code -> costMap.getOrDefault(code, List.of()).stream()).toList(), THREE.stream().allMatch(costMap::containsKey)));

    if (calc != null && !calc.household().isEmpty()) {
      for (var line : calc.household()) {
        String path = com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.pathKey(line.paths().getFirst());
        var part = bound(partsByPrepare, snapshot.preparations().get(path).financialItemId());
        verifyPart(part, line.materialCode(), line.quantity(), line.amount()); equal(part.getUnitPrice(), line.price(), "家用 P");
        consumed.add(part.getId());
        rows.add(part(part).section("HOUSEHOLD").kind("HOUSEHOLD").key("HOUSEHOLD:"+line.monthlyPriceId())
            .unitPrice(money(line.price())).quantity(decimal(line.quantity())).unit(line.pricingUnit()).amount(money(line.amount()))
            .monthlyPriceIds(List.of(line.monthlyPriceId())).paths(line.paths())
            .householdRate(decimal(line.householdRate())).salesRate(decimal(line.salesRate()))
            .coefficient(decimal(calc.ordinaryInputs().coefficient()))
            .householdCalculation(householdCalculation(snapshot,line))
            .remark("P÷(1+H)×(1+H+S)×Q；本行金额未乘产品属性系数").build());
      }
      rows.add(Row.builder().key("HOUSEHOLD_SUBTOTAL").section("HOUSEHOLD").kind("SUBTOTAL")
          .name("家用部件小计").amount(money(calc.householdSubtotal())).build());
      rows.add(Row.builder().key("HOUSEHOLD_CONTRIBUTION").section("HOUSEHOLD").kind("CONTRIBUTION")
          .name("家用部件计入总成本金额").amount(money(calc.householdContribution()))
          .coefficient(decimal(calc.ordinaryInputs().coefficient())).remark("家用小计乘一次产品属性系数；不再计提普通三项费用").build());
    }
    if (calc != null && consumed.size() != stored.parts().size()) throw new IllegalStateException("成本部品与保存快照的范围不一致");
    var tech = tree(v.getTechDataInputJson());
    for (var code : ProductFeeCode.values()) {
      var originals = feeCosts.get(code);
      String amount = sum(originals); String state = amount == null ? "MISSING" : "LEGACY";
      Long profileId = null;
      if (calc != null) {
        var fee = calc.fees().stream().filter(f -> f.code() == code).findFirst().orElseThrow();
        if (originals.size() != 1) throw new IllegalStateException("保存的四费行不完整");
        equalNullable(originals.getFirst().getAmount(), fee.amount(), "四费");
        if (!Objects.equals(originals.getFirst().getSourceId(), fee.profileVersionId())) throw new IllegalStateException("四费来源与保存快照不一致");
        amount = money(fee.amount()); state = fee.state().name(); profileId = fee.profileVersionId();
      } else if (ProductFeeContract.POLICY_VERSION.equals(v.getFeePolicyVersion())) {
        if (originals.size() > 1) warnings.add(code.displayName()+"存在多条保存行，按原金额展示并保留全部来源");
        if (originals.stream().allMatch(c -> code.costCode().equals(c.getCostCode())) && originals.size() == 1) {
          var encoding = tech.path("productFees").get("encodingVersion");
          state = ProductFeeContract.state(originals.getFirst().getAmount(), encoding == null || encoding.isNull() ? null : encoding.intValue()).name();
          profileId = originals.getFirst().getSourceId();
        }
      }
      if (!originals.isEmpty() && originals.stream().anyMatch(c -> c.getAmount() == null)) {
        if (originals.size() > 1) warnings.add(code.displayName()+"历史明细含未记录金额，未将其当作零汇总");
        for (var original : originals) if (originals.size() > 1) rows.add(cost(original,"HISTORICAL_DETAIL").build());
      }
      rows.add(Row.builder().key(code.costCode()).section("PRODUCT_FEES").kind("FEE").code(code.costCode())
          .name(code.displayName()).amount(amount).feeState(state)
          .sources(originals.stream().map(this::source).toList()).costItemIds(originals.stream().map(CostRunCostItem::getId).toList())
          .remark(profileId == null ? calc != null ? "未填写单件费用"
              : originals.isEmpty() ? "未记录有效费用金额" : "历史保存费用" : "有效产品信息补录版本 " + profileId).build());
    }
    var filledFees = rows.stream().filter(r -> "FEE".equals(r.kind())).map(Row::amount)
        .filter(Objects::nonNull).map(BigDecimal::new).toList();
    rows.add(Row.builder().key("PRODUCT_FEES_SUBTOTAL").section("PRODUCT_FEES").kind("SUBTOTAL")
        .name("新品费用小计").amount(filledFees.isEmpty() ? null : money(filledFees.stream().reduce(BigDecimal.ZERO, BigDecimal::add))).build());
    String coefficient = calc == null ? costMap.getOrDefault("ADJUSTED_MANUFACTURE_COST",List.of()).stream()
        .map(CostRunCostItem::getRate).filter(Objects::nonNull).map(JjbProjection::decimal).findFirst().orElse(null)
        : decimal(calc.ordinaryInputs().coefficient());
    String property = calc == null ? text(tech,"productProperty") : calc.technicalInput() == null ? null : calc.technicalInput().productProperty();
    rows.add(Row.builder().key("PRODUCT_PROPERTY").section("PROPERTY").kind("PROPERTY").name("产品属性")
        .remark(property).coefficient(coefficient).build());
    BigDecimal t1 = v.getTotalCost();
    var totals = costMap.getOrDefault("TOTAL",List.of());
    if (calc != null) {
      validateMonthlyCosts(calc, costMap);
      if (totals.size()!=1) throw new IllegalStateException("新版本总额保存行不唯一");
      equal(totals.getFirst().getAmount(),t1,"总成本1");
    } else {
      if (t1 == null || v.getCuMaterialAdjustment()==null || v.getFinalQuoteAmount()==null)
        warnings.add("历史版本未记录的总额字段留空，不补算材料差异或总成本2");
      if (totals.size()!=1 || t1 == null || totals.getFirst().getAmount()==null || totals.getFirst().getAmount().compareTo(t1)!=0)
      { warnings.add("历史总额主表与明细存在缺失或不一致，保留各自原值");
        for (var total : totals) rows.add(cost(total,"HISTORICAL_DETAIL").name("历史明细总额（原保存值）").build());
      }
      if (t1!=null && v.getCuMaterialAdjustment()!=null && v.getFinalQuoteAmount()!=null
          && t1.add(v.getCuMaterialAdjustment()).compareTo(v.getFinalQuoteAmount())!=0)
        warnings.add("历史总成本1、材料差异与总成本2不一致，未调整原金额");
    }
    if (!"SUCCESS".equals(v.getStatus()) && !"CONFIRMED".equals(v.getStatus())) warnings.add("该版本状态为"+v.getStatus()+"，请按历史状态使用保存结果");
    if ("CONTRACT_SAMPLE".equals(v.getDataQualityStatus())) warnings.add("隔离验收样本：普通输入为接口测试数据");
    var identity = new Identity(v.getId(),v.getCostRunNo(),v.getVersionNo(),v.getOaNo(),v.getOaFormItemId(),v.getProductCode(),
        v.getBusinessUnitType(),v.getPricingMonth(),v.getAlgorithmVersion(),v.getFeePolicyVersion(),v.getStatus(),calc!=null);
    Map<String,String> quotation = new LinkedHashMap<>();
    if (v.getConfirmedBy() != null && !v.getConfirmedBy().isBlank()) quotation.put("quoterName",v.getConfirmedBy());
    String[][] metadata = {{"applicantName",form.getApplicantName()},{"applicantDept",form.getApplicantDept()},
        {"applicantOffice",form.getApplicantOffice()},{"overseasSalesMode",form.getOverseasSalesMode()},
        {"priceLinkMode",form.getPriceLinkMode()},{"businessType",item.getBusinessType()},
        {"division",form.getSourceBusinessDivision()},{"spec",item.getSpec()}};
    for (var pair : metadata) if (pair[1] != null) quotation.put(pair[0],pair[1]);
    BigDecimal[] metals = {form.getSus316lPrice(),form.getSus304Price(),form.getCopperPrice(),
        form.getAluminumPrice(),form.getZincPrice(),form.getSteelPrice(),form.getSilverPrice(),form.getGoldPrice()};
    String[] metalKeys = {"sus316lPrice","sus304Price","copperPrice","aluminumPrice","zincPrice","steelPrice","silverPrice","goldPrice"};
    for (int i=0;i<metals.length;i++) if (metals[i] != null) quotation.put(metalKeys[i],money(metals[i]));
    if (form.getCopperPrice() != null) quotation.put("customerCuPrice",money(form.getCopperPrice().movePointLeft(3)));
    var header = new Header(item.getProductName(),item.getSunlModel(),form.getCustomer(),item.getCustomerCode(),item.getCustomerDrawing(),
        "表头取报价需求单；核算金额、费率及财务Cu取所选保存版本",money(v.getFinanceCuPrice()),money(v.getOaCuPrice()),"元/公斤",property,coefficient,quotation);
    rows = new JjbPricePresentation(json).apply(rows, calc, preparations);
    return new JjbReadModel(identity,header,rows,calc==null?List.of():monthly(calc),
        new Totals(money(t1),money(v.getCuMaterialAdjustment()),money(v.getFinalQuoteAmount())),warnings);
  }

  public JjbReadModel project(Stored stored, OaForm form, OaFormItem item, List<CostRunTraceSnapshot> traces) {
    return project(stored,form,item,traces,List.of());
  }

  /** Monthly costs already captured MAKE rows, even when no legacy trace rows were generated. */
  private ArrayList<Row> manufacturingDetails(List<Row> rows,List<PricePrepareItem> preparations) {
    var byId=new HashMap<Long,PricePrepareItem>();
    preparations.forEach(row->byId.put(row.getId(),row));
    var result=new ArrayList<Row>();
    for(var row:rows) {
      var prepared=row.sources().stream().filter(source->"lp_price_prepare_item".equals(source.table()))
          .map(source->byId.get(source.id())).filter(Objects::nonNull).findFirst().orElse(null);
      if(prepared==null || !"MAKE_PART_PRICE".equals(prepared.getResultRefType())) {result.add(row);continue;}
      var snapshot=tree(prepared.getInputSnapshotJson());
      if(snapshot.has("document")) snapshot=tree(new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(json)
          .unwrap(prepared.getInputSnapshotJson()));
      if(!"MAKE_PART_V1".equals(snapshot.path("calculatorVersion").asText())
          || !Objects.equals(prepared.getMaterialCode(),snapshot.path("materialCode").asText())
          || !snapshot.path("rows").isArray()) {result.add(row);continue;}
      var children=snapshot.path("rows");
      var manufacturing=new ArrayList<Map<String,String>>();
      children.forEach(child->manufacturing.add(JjbManufacturingEvidence.describe(child)));
      row=row.toBuilder().manufacturing(manufacturing).build();
      if(children.size()==1) {
        result.add(weights(row.toBuilder(),children.get(0)).build());
      } else {
        result.add(row);int index=0;
        for(var child:children) {
          String code=text(child,"childMaterialNo");if(code==null)continue;
          result.add(weights(Row.builder(),child).key(row.key()+":MAKE:"+(++index))
              .manufacturing(List.of(JjbManufacturingEvidence.describe(child)))
              .section("ORDINARY_PARTS").kind("EXPLANATION").code(row.code()+"\n【"+code+"】")
              .name(first(text(child,"childMaterialName"),code)).drawingNo(text(child,"childMaterialSpec"))
              .unitPrice(number(child,"costPrice")).partItemIds(row.partItemIds()).sources(row.sources())
              .remark("母件 "+row.code()+" 的制造明细；金额已计入母件，不重复汇总").build());
        }
      }
    }
    return result;
  }

  private Row.RowBuilder weights(Row.RowBuilder row,JsonNode saved) {
    return row.grossWeight(decimalNumber(saved,"grossWeightG")).netWeight(decimalNumber(saved,"netWeightG"))
        .rawUnitPrice(number(saved,"rawUnitPrice")).scrapUnitPrice(number(saved,"scrapUnitPrice"));
  }

  /** 包装母件是取价依据，包装费才是含系数后的材料贡献；读取保存依据，不重算历史。 */
  private ArrayList<Row> packagingDetails(List<Row> rows, List<PricePrepareItem> preparations,
      List<CostRunTraceSnapshot> traces, List<String> warnings) {
    var fees = rows.stream().filter(row -> "OTHER_EXP_PACKAGE".equals(row.code())).toList();
    var buckets = rows.stream().filter(row -> "BOM_BUCKET_PACKAGE".equals(row.code())).toList();
    Row fee = fees.size() == 1 ? fees.getFirst() : fees.isEmpty() && buckets.size() == 1 ? buckets.getFirst() : null;
    if (fee == null || fee.amount() == null) return new ArrayList<>(rows);
    Row duplicate = fees.size() == 1 && buckets.size() == 1
        && Objects.equals(fee.amount(), buckets.getFirst().amount()) ? buckets.getFirst() : null;
    var packagePreparations = new HashSet<Long>();
    for (var prepared : preparations) {
      var snapshot = tree(prepared.getInputSnapshotJson());
      if (snapshot.has("document")) snapshot = tree(new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(json)
          .unwrap(prepared.getInputSnapshotJson()));
      if ("PACKAGE_PRICE_V1".equals(snapshot.path("calculatorVersion").asText())) packagePreparations.add(prepared.getId());
    }
    var packageParts = traces.stream().filter(trace -> "PACKAGE_COMPONENT".equals(trace.getSourceType()))
        .map(CostRunTraceSnapshot::getPartItemId).filter(Objects::nonNull).collect(Collectors.toSet());
    var componentRows = rows.stream().filter(row -> "PART".equals(row.kind())
        && (row.partItemIds().stream().anyMatch(packageParts::contains)
            || row.sources().stream().anyMatch(source -> "lp_price_prepare_item".equals(source.table())
                && packagePreparations.contains(source.id())))).toList();
    // 旧保存结果的包装基数混入了独立材料，但最终包装金额只保留组件；提示重算，不能篡改历史金额。
    if (duplicate != null && fee.baseAmount() != null && !componentRows.isEmpty()
        && componentRows.stream().allMatch(row -> row.amount() != null)
        && fee.sources().stream().noneMatch(source -> "lp_quote_tech_data_version".equals(source.table()))) {
      BigDecimal componentBase = componentRows.stream().map(row -> new BigDecimal(row.amount()))
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal roundingTolerance = new BigDecimal("0.000001").multiply(BigDecimal.valueOf(componentRows.size() + 1));
      if (new BigDecimal(fee.baseAmount()).subtract(componentBase).compareTo(roundingTolerance) > 0)
        warnings.add("该历史版本的包装归集遗漏了组件外的独立包装材料；当前保留原保存金额，请重新整单核算生成修正版本。");
    }
    var componentIds = componentRows.stream().flatMap(row -> row.partItemIds().stream()).collect(Collectors.toSet());
    var componentPaths = componentRows.stream().flatMap(row -> row.paths().stream()).toList();
    var hidden = rows.stream().filter(row -> row == duplicate || componentRows.contains(row)
        || "EXPLANATION".equals(row.kind()) && (row.partItemIds().stream().anyMatch(componentIds::contains)
            || row.paths().stream().anyMatch(path -> componentPaths.stream().anyMatch(parent -> path.startsWith(parent + "/")))))
        .toList();
    var evidence = new ArrayList<Row>(hidden); evidence.add(fee);
    var result = new ArrayList<Row>();
    for (var row : rows) {
      if (hidden.contains(row)) continue;
      if (row == fee) {
        result.add(row.toBuilder().name("包装费用")
            .costItemIds(evidence.stream().flatMap(value -> value.costItemIds().stream()).distinct().sorted().toList())
            .partItemIds(evidence.stream().flatMap(value -> value.partItemIds().stream()).distinct().toList())
            .monthlyPriceIds(evidence.stream().flatMap(value -> value.monthlyPriceIds().stream()).distinct().toList())
            .paths(evidence.stream().flatMap(value -> value.paths().stream()).distinct().toList())
            .sources(evidence.stream().flatMap(value -> value.sources().stream()).distinct().toList())
            .remark(joinRemark(row.remark(), "已计入材料费；包装组件依据见核算底稿")).build());
      } else result.add(row);
    }
    return result;
  }

  private static String joinRemark(String previous, String explanation) {
    return previous == null || previous.isBlank() ? explanation : previous + "；" + explanation;
  }

  /** Old rollup/raw details are explanations from immutable trace JSON, never today's BOM. */
  private ArrayList<Row> explainSavedChildren(List<Row> rows, List<CostRunTraceSnapshot> traces) {
    var byPart = new HashMap<Long, CostRunTraceSnapshot>();
    for (var trace : traces) if (trace.getPartItemId()!=null) byPart.putIfAbsent(trace.getPartItemId(), trace);
    var result = new ArrayList<Row>();
    for (var row : rows) {
      result.add(row);
      if (row.partItemIds().size()!=1) continue;
      var trace = byPart.get(row.partItemIds().getFirst());
      if (trace == null) continue;
      var source = tree(trace.getSourceSnapshotJson());
      var children = source.path("rollupDisplayComponents");
      boolean rollup = children.isArray() && !children.isEmpty();
      if (!rollup && "MAKE_PART".equals(trace.getSourceType())) children=tree(trace.getChildrenJson());
      if (!children.isArray()) continue;
      int index=0;
      for (var child : children) {
        String code = text(child,rollup?"childMaterialCode":"childMaterialNo");
        if (code == null) continue;
        result.add(Row.builder().key(row.key()+":EXPLANATION:"+(++index)).section("ORDINARY_PARTS").kind("EXPLANATION")
            .code(row.code()+"\n【"+code+"】").name(first(text(child,"childMaterialName"),code))
            .drawingNo(text(child,"childMaterialSpec")).unitPrice(number(child,rollup?"childUnitCost":"costPrice"))
            .quantity(rollup?text(child,"childQtyPerTop"):null)
            .grossWeight(decimalNumber(child,"grossWeightG")).netWeight(decimalNumber(child,"netWeightG"))
            .rawUnitPrice(number(child,"rawUnitPrice")).scrapUnitPrice(number(child,"scrapUnitPrice"))
            .priceSource(text(child,rollup?"childRawPriceType":"rawPriceType"))
            .partItemIds(row.partItemIds()).sources(List.of(new Source("lp_cost_run_trace_snapshot",trace.getId(),"保存的母件／子件依据")))
            .remark("母件 "+row.code()+" 的子件说明；金额已包含在母件，不重复汇总").build());
      }
    }
    return result;
  }
  private static String number(JsonNode node,String field) {
    String value=text(node,field);return value==null?null:money(new BigDecimal(value));
  }
  private static String decimalNumber(JsonNode node,String field) {
    String value=text(node,field);return value==null?null:decimal(new BigDecimal(value));
  }

  private Map<String,String> householdCalculation(com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.Snapshot snapshot,
      MonthlyProductCost.HouseholdLine line) {
    var pair=snapshot.priceScenarios().stream().filter(value->value.monthlyPriceId()==line.monthlyPriceId()).findFirst().orElse(null);
    if(pair==null)return Map.of();
    var evidence=tree(pair.customerEvidenceJson());var calculation=evidence.path("householdCalculation");
    if(!calculation.isObject())return Map.of();
    var values=new LinkedHashMap<String,String>();
    calculation.fields().forEachRemaining(entry->{
      var value=entry.getValue();
      if(!value.isNull())values.put(entry.getKey(),value.isNumber()?decimal(value.decimalValue()):value.asText());
    });
    values.put("inquirySourceMonth",evidence.path("inquirySourceMonth").asText());
    values.put("pricingMonth",snapshot.pricingMonth());
    if(evidence.path("householdMatch").isObject()) {
      values.put("matchKind","型号/规格");values.put("matchedSpecification",evidence.path("householdMatch").path("matchedValue").asText());
    } else values.put("matchKind","料号");
    for(String field:List.of("formula","value"))if(!evidence.path("originalW").path(field).isMissingNode() && !evidence.path("originalW").path(field).isNull())
      values.put(field.equals("formula")?"originalWFormula":"originalWValue",evidence.path("originalW").path(field).asText());
    values.put("householdRate",decimal(line.householdRate()));values.put("salesRate",decimal(line.salesRate()));
    values.put("quantity",decimal(line.quantity()));values.put("financialAmount",decimal(line.amount()));
    values.put("customerAmount",decimal(line.effectiveCustomerAmount()));
    values.put("difference",decimal(line.effectiveCustomerAmount().subtract(line.amount())));
    return Map.copyOf(values);
  }

  private List<MonthlyPrice> monthly(MonthlyProductCost.Result calc) {
    var groups = calc.adoptedPrices().stream().collect(Collectors.groupingBy(p -> p.selection().monthlyPriceId(),TreeMap::new,Collectors.toList()));
    var result = new ArrayList<MonthlyPrice>();
    groups.forEach((id, group) -> {
      var p=group.getFirst();var fact=p.selection().fact();var family=p.request().family();var evidence=tree(fact.evidence().json());
      var src=evidence.path("source");var selected=src.has("selected")?src.path("selected"):src;
      result.add(new MonthlyPrice(id,family.materialCode(),fact.type().name(),family.unit(),family.currency(),family.taxBasis().name(),
          decimal(fact.price()),decimal(fact.householdRate()),fact.evidence().sha256(),
          p.selection().applicability().fragments().stream().flatMap(f -> f.organizations().stream()).distinct().sorted().toList(),
          group.stream().map(a -> a.request().priceOrgCode()).distinct().sorted().toList(),p.selection().applicability().canonical(),
          text(evidence,"sourceKind"),first(text(evidence,"householdSourceId"),text(selected,"id"),text(src,"versionId")),
          first(text(src,"revisionNo"),text(selected,"ruleRevision"),text(selected,"technicalVersionId")),
          first(text(src,"importBatchId"),text(selected,"sourceBatchNo")),first(text(selected,"supplierName"),text(selected,"supplierCode")),
          JjbSourceEvidence.describe(evidence)));
    });return result;
  }
  private void addCost(List<Row> rows,Map<String,List<CostRunCostItem>> map,String code,String section,MonthlyProductCost.Result calc,List<String> warnings) {
    var values=map.getOrDefault(code,List.of());
    if (values.isEmpty()) { rows.add(Row.builder().key(code).section(section).kind("COST").code(code).name(LABELS.get(code)).build());return; }
    if(values.size()>1) warnings.add("历史科目 "+code+" 存在多行，逐行保留");
    for(var c:values) {
      var row=cost(c,section);
      if ("MATERIAL".equals(code)) row.remark(joinRemark(c.getRemark(),
          "含部品、辅料、包装和部门经费；直接、辅助人工另计；按保存的核算口径汇总，六位小数明细加总可能存在舍入尾差"));
      if(calc!=null) {
        BigDecimal rate=switch(code) {
          case "LOSS" -> calc.ordinaryInputs().lossRate();case "MANUFACTURE" -> calc.ordinaryInputs().manufactureRate();
          case "ADJUSTED_MANUFACTURE_COST" -> calc.ordinaryInputs().coefficient();case "MGMT_EXP" -> calc.ordinaryInputs().managementRate();
          case "SALES_EXP" -> calc.ordinaryInputs().salesRate();case "FIN_EXP" -> calc.ordinaryInputs().financeRate();default -> c.getRate();};
        row.rate(decimal(rate));
      }
      rows.add(row.build());
    }
  }
  private static void validateMonthlyCosts(MonthlyProductCost.Result c,Map<String,List<CostRunCostItem>> costs) {
    var expected=new LinkedHashMap<String,BigDecimal>();
    expected.put("MATERIAL",c.financeMaterialCost()); expected.put("DIRECT_LABOR",c.ordinaryInputs().directLabor());
    expected.put("INDIRECT_LABOR",c.ordinaryInputs().indirectLabor());expected.put("LOSS",c.ordinary().loss());
    expected.put("MANUFACTURE",c.ordinary().manufactureFee());expected.put("MANUFACTURE_COST",c.ordinary().manufactureCost());
    expected.put("ADJUSTED_MANUFACTURE_COST",c.ordinary().adjustedManufactureCost());expected.put("MGMT_EXP",c.ordinary().management());
    expected.put("SALES_EXP",c.ordinary().sales());expected.put("FIN_EXP",c.ordinary().finance());
    expected.put("HOUSEHOLD_SUBTOTAL",c.householdSubtotal());expected.put("HOUSEHOLD_CONTRIBUTION",c.householdContribution());
    expected.put("OTHER_INDEPENDENT",c.ordinaryInputs().otherIndependentFees());
    c.ordinaryInputs().details().forEach(d -> expected.putIfAbsent(d.costCode(),d.amount()));
    var allowed = new HashSet<>(expected.keySet());
    allowed.add("TOTAL");
    for (var fee : ProductFeeCode.values()) allowed.add(fee.costCode());
    if (!allowed.equals(costs.keySet())) throw new IllegalStateException("新版本费用范围与保存快照不一致");
    expected.forEach((code,amount)-> {var stored=costs.getOrDefault(code,List.of());if(stored.size()!=1) throw new IllegalStateException("新版本科目不完整或重复："+code);equalNullable(stored.getFirst().getAmount(),amount,code);});
  }
  private Row.RowBuilder part(CostRunPartItem p) { return Row.builder().key("PART:"+p.getId()).section("ORDINARY_PARTS").kind("PART")
      .code(p.getPartCode()).name(p.getPartName()).drawingNo(p.getPartDrawingNo()).unitPrice(money(p.getUnitPrice()))
      .quantity(decimal(p.getQty())).amount(money(p.getAmount())).material(p.getMaterial()).shape(p.getShapeAttr())
      .priceSource(p.getPriceSource()).remark(p.getRemark()).partItemIds(List.of(p.getId()))
      .sources(p.getPricePrepareItemId()==null?List.of():List.of(new Source("lp_price_prepare_item",p.getPricePrepareItemId(),"本版本价格准备"))); }
  private Row.RowBuilder cost(CostRunCostItem c,String section) { return Row.builder().key("COST:"+c.getId()).section(section).kind("COST")
      .code(c.getCostCode()).name(LABELS.getOrDefault(c.getCostCode(),c.getCostName())).amount(money(c.getAmount()))
      .baseAmount(money(c.getBaseAmount())).rate(decimal(c.getRate())).priceSource(c.getSourceTable()).remark(c.getRemark())
      .sources(List.of(source(c))).costItemIds(List.of(c.getId())); }
  private Source source(CostRunCostItem c) {return new Source(c.getSourceTable(),c.getSourceId(),c.getRemark());}
  private static Row subtotal(String key,String section,String name,List<CostRunCostItem> values,boolean complete) {
    return Row.builder().key(key).section(section).kind("SUBTOTAL").name(name).amount(complete?sum(values):null)
        .costItemIds(values.stream().map(CostRunCostItem::getId).toList()).remark("保存的费用行金额合计").build();
  }
  private static ProductFeeCode feeCode(CostRunCostItem c) {
    for(var code:ProductFeeCode.values()) if(code.costCode().equals(c.getCostCode()))return code;
    if(c.getCostCode()==null || !c.getCostCode().startsWith("OTHER_EXP_") || c.getCostName()==null)return null;
    return switch(c.getCostName().trim()) {case "模具费"->ProductFeeCode.MOULD;case "认证费"->ProductFeeCode.CERTIFICATION;
      case "折旧费"->ProductFeeCode.DEPRECIATION;case "工装费","工装夹具费"->ProductFeeCode.TOOLING;default->null;};
  }
  private static CostRunPartItem bound(Map<Long,List<CostRunPartItem>> parts,long preparation) {
    var found=parts.getOrDefault(preparation,List.of());if(found.size()!=1)throw new IllegalStateException("保存部品准备引用不完整或重复");return found.getFirst();
  }
  private static void verifyPart(CostRunPartItem part,String code,BigDecimal q,BigDecimal amount) {
    if(!code.equals(part.getPartCode()))throw new IllegalStateException("保存部品料号不一致");equal(part.getQty(),q,"部品数量");
    equal(part.getAmount(),amount.setScale(6,RoundingMode.HALF_UP),"部品金额");
  }
  private static void equalNullable(BigDecimal a,BigDecimal b,String field) {if(a==null && b==null)return;equal(a,b,field);}
  private static void equal(BigDecimal a,BigDecimal b,String field) {if(a==null || b==null || a.compareTo(b)!=0)throw new IllegalStateException(field+"与保存快照不一致");}
  private static String sum(List<CostRunCostItem> values) {if(values.isEmpty() || values.stream().anyMatch(v->v.getAmount()==null))return null;
    return money(values.stream().map(CostRunCostItem::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add));}
  public static String money(BigDecimal value) {return value==null?null:value.setScale(6,RoundingMode.HALF_UP).toPlainString();}
  public static String decimal(BigDecimal value) {return value==null?null:value.stripTrailingZeros().toPlainString();}
  private JsonNode tree(String value) {try{return value==null?json.createObjectNode():json.readTree(value);}catch(java.io.IOException ex){throw new IllegalStateException("保存的来源内容无法读取",ex);}}
  private static String text(JsonNode node,String field) {var value=node.get(field);return value==null||value.isNull()?null:value.asText();}
  private static String first(String... values){for(String value:values)if(value!=null&&!value.isBlank())return value;return null;}
}

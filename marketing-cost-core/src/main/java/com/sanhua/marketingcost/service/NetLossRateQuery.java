package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunFeeQuery;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.service.costing.CostRunFeeSources;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.springframework.stereotype.Service;

/** 核算与补录共用的公共取率：档案缺裸品时从正式 BOM 识别，再按裸品、报价料号查率。 */
@Service
public class NetLossRateQuery {
  public static final String BOM_SOURCE_AVAILABLE = "NET_LOSS_BOM_SOURCE_AVAILABLE";
  private static final String MISSING_RATE_MESSAGE = "未取到净损失率，无法计算成本。请补齐净损失率后重新核算。";

  public record Source(String status, String reasonCode, String message, Long materialId,
      String materialNo, String name, String model, String bareMaterialNo, String organizationCode,
      int year, String businessUnitType, Long configurationId, BigDecimal rate) {}

  /** 只接受调用方已核验审批版本的补录值；公共查询失败不能解释成缺少公共配置。 */
  public record ApplicableRate(String status, String sourceType, BigDecimal rate, String message) {}

  public static boolean validPublicRate(BigDecimal rate) {
    return rate != null && rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) < 0;
  }

  public static ApplicableRate select(Source publicSource, BigDecimal approvedSupplementalRate) {
    if (publicSource == null) throw new IllegalArgumentException("缺少公共净损失率查询结果");
    if ("AVAILABLE".equals(publicSource.status())) {
      if (!validPublicRate(publicSource.rate())) return new ApplicableRate("ERROR", "PUBLIC", null,
          "公共净损失率必须大于等于 0% 且小于 100%，请核实配置");
      return new ApplicableRate("AVAILABLE", "PUBLIC", publicSource.rate(), "采用公共净损失率");
    }
    if (!"MISSING".equals(publicSource.status())) {
      return new ApplicableRate(publicSource.status(), "PUBLIC", null, publicSource.message());
    }
    if (approvedSupplementalRate == null) {
      return new ApplicableRate("MISSING", null, null, publicSource.message());
    }
    if (approvedSupplementalRate.signum() < 0 || approvedSupplementalRate.compareTo(BigDecimal.ONE) >= 0) {
      throw new IllegalArgumentException("已审批补录净损失率不在有效范围内");
    }
    return new ApplicableRate("AVAILABLE", "TECH_SUPPLEMENTAL", approvedSupplementalRate, "采用已审批补录净损失率");
  }

  private final MaterialMasterRawMapper materials;
  private final QualityLossRateMapper rates;
  private final BomRawHierarchyMapper hierarchy;

  public NetLossRateQuery(MaterialMasterRawMapper materials, QualityLossRateMapper rates,
      BomRawHierarchyMapper hierarchy) {
    this.materials = materials;
    this.rates = rates;
    this.hierarchy = hierarchy;
  }

  public Source lookup(String code, String model, String organization, int year, String businessUnit) {
    requireContext(organization, year, businessUnit);
    var matches = blank(code) ? List.<MaterialMasterRaw>of()
        : materials.selectByLatestBatchAndCodes(List.of(code.trim()), null, organization);
    if (matches.isEmpty() && !blank(model)) {
      matches = materials.selectNetLossProducts("MODEL", model.trim(), true, organization, 2);
    }
    if (matches.size() != 1) return missingProduct(matches.isEmpty(), code, organization, year, businessUnit);
    return resolveProducts(List.of(new Lookup(code, model, organization, year, businessUnit)), matches).getFirst();
  }

  public record Lookup(String code, String model, String organization, int year, String businessUnit) {}

  /** Resolve the same public selection for a bounded batch; supplemental values remain consumer-owned. */
  public List<Source> lookupBatch(List<Lookup> requests,
      CostRunFeeSources sources) {
    var stable = List.copyOf(requests);
    if (stable.size() > 64) throw new IllegalArgumentException("净损失率每批最多64个产品对象");
    stable.forEach(q -> requireContext(q.organization(), q.year(), q.businessUnit()));
    var matches = new ArrayList<List<MaterialMasterRaw>>();
    stable.forEach(q -> matches.add(List.of()));
    var codePositions = new ArrayList<Integer>();
    var codeQueries = new ArrayList<CostRunFeeQuery.Material>();
    for (int i = 0; i < stable.size(); i++) {
      var q = stable.get(i);
      if (!blank(q.code())) {
        codePositions.add(i);
        codeQueries.add(new CostRunFeeQuery.Material(q.code().trim(), q.organization()));
      }
    }
    var byCode = sources.readMaterial(codeQueries);
    for (int i = 0; i < codePositions.size(); i++) matches.set(codePositions.get(i), byCode.get(i));
    var modelPositions = new ArrayList<Integer>();
    var modelQueries = new ArrayList<CostRunFeeQuery.NetLossModel>();
    for (int i = 0; i < stable.size(); i++) {
      var q = stable.get(i);
      if (matches.get(i).isEmpty() && !blank(q.model())) {
        modelPositions.add(i);
        modelQueries.add(new CostRunFeeQuery.NetLossModel(q.model().trim(), q.organization()));
      }
    }
    var byModel = sources.readNetLossModel(modelQueries);
    for (int i = 0; i < modelPositions.size(); i++) matches.set(modelPositions.get(i), byModel.get(i));
    var bomProducts = new ArrayList<BomProduct>();
    for (int i = 0; i < stable.size(); i++) {
      var found = matches.get(i);
      if (found.size() == 1) addBomProduct(bomProducts, found.getFirst(), stable.get(i).organization());
    }
    var bareByScope = readBomBodies(bomProducts);
    var bareMatches = new ArrayList<BareMatch>();
    for (int i = 0; i < stable.size(); i++) {
      var found = matches.get(i);
      bareMatches.add(found.size() == 1 ? bareMatch(found.getFirst(), stable.get(i).organization(), bareByScope) : null);
    }
    var ratePositions = new ArrayList<Integer>();
    var rateQueries = new ArrayList<CostRunFeeQuery.QualityLoss>();
    for (int i = 0; i < stable.size(); i++) {
      var q = stable.get(i); var bare = bareMatches.get(i);
      if (bare != null && bare.code() != null) {
        ratePositions.add(i);
        rateQueries.add(new CostRunFeeQuery.QualityLoss(q.year(), bare.code(), q.businessUnit()));
      }
    }
    var selectedRates = new HashMap<Integer, QualityLossRate>();
    readRates(sources, ratePositions, rateQueries, selectedRates);
    var matchedBareRates = Set.copyOf(selectedRates.keySet());
    ratePositions.clear();
    rateQueries.clear();
    for (int i = 0; i < stable.size(); i++) {
      var q = stable.get(i);
      var bare = bareMatches.get(i);
      if (bare != null && !matchedBareRates.contains(i) && canUseQuoteCode(q.code(), bare.code())) {
        ratePositions.add(i);
        rateQueries.add(new CostRunFeeQuery.QualityLoss(q.year(), q.code().trim(), q.businessUnit()));
      }
    }
    readRates(sources, ratePositions, rateQueries, selectedRates);
    var result = new ArrayList<Source>();
    for (int i = 0; i < stable.size(); i++) {
      var q = stable.get(i); var found = matches.get(i);
      result.add(found.size() == 1
          ? resolve(found.getFirst(), q, bareMatches.get(i), selectedRates.get(i), matchedBareRates.contains(i))
          : missingProduct(found.isEmpty(), q.code(), q.organization(), q.year(), q.businessUnit()));
    }
    return List.copyOf(result);
  }

  private static void readRates(CostRunFeeSources sources, List<Integer> positions,
      List<CostRunFeeQuery.QualityLoss> queries, HashMap<Integer, QualityLossRate> selectedRates) {
    if (queries.isEmpty()) return;
    var ratesByScope = sources.readQualityLoss(queries);
    for (int i = 0; i < positions.size(); i++) {
      var rows = ratesByScope.get(i);
      if (rows.size() > 1) {
        throw new TooManyResultsException("同一年度、料号和业务单元存在多条净损失率");
      }
      if (!rows.isEmpty()) selectedRates.put(positions.get(i), rows.getFirst());
    }
  }

  public List<Source> search(String searchBy, String keyword, String organization, int year, String businessUnit) {
    requireContext(organization, year, businessUnit);
    if (!("CODE".equals(searchBy) || "MODEL".equals(searchBy)) || blank(keyword) || keyword.trim().length() > 100) {
      throw new IllegalArgumentException("请选择成品料号或型号，并填写 1—100 字的查询条件");
    }
    var matches = materials.selectNetLossProducts(searchBy, keyword.trim(), false, organization, 101);
    if (matches.size() > 100) throw new IllegalArgumentException("匹配的成品较多，请填写更具体的查询条件");
    if ("MODEL".equals(searchBy)) {
      var exact = matches.stream().filter(row -> keyword.trim().equalsIgnoreCase(row.getMaterialModel())).toList();
      if (!exact.isEmpty()) matches = exact;
    }
    return resolveProducts(matches.stream()
        .map(row -> new Lookup(row.getMaterialCode(), null, organization, year, businessUnit)).toList(), matches);
  }

  private List<Source> resolveProducts(List<Lookup> requests, List<MaterialMasterRaw> products) {
    var bomProducts = new ArrayList<BomProduct>();
    for (int i = 0; i < products.size(); i++) addBomProduct(bomProducts, products.get(i), requests.get(i).organization());
    var bareByScope = readBomBodies(bomProducts);
    var result = new ArrayList<Source>();
    for (int i = 0; i < products.size(); i++) {
      var product = products.get(i);
      var q = requests.get(i);
      var bare = bareMatch(product, q.organization(), bareByScope);
      var rate = bare.code() == null ? null : findRate(bare.code(), q.year(), q.businessUnit());
      boolean matchedBareRate = rate != null;
      if (rate == null && canUseQuoteCode(q.code(), bare.code())) rate = findRate(q.code(), q.year(), q.businessUnit());
      result.add(resolve(product, q, bare, rate, matchedBareRate));
    }
    return List.copyOf(result);
  }

  private QualityLossRate findRate(String code, int year, String businessUnit) {
    return rates.selectOne(Wrappers.lambdaQuery(QualityLossRate.class)
        .eq(QualityLossRate::getRateYear, year).eq(QualityLossRate::getBareProductCode, code.trim())
        .eq(QualityLossRate::getBusinessUnitType, businessUnit));
  }

  private static boolean canUseQuoteCode(String quoteCode, String bareCode) {
    return !blank(quoteCode) && (blank(bareCode) || !quoteCode.trim().equals(bareCode.trim()));
  }

  private static String bareCode(MaterialMasterRaw material) {
    return !blank(material.getMainCategoryCode()) && material.getMainCategoryCode().trim().startsWith("11")
        ? material.getMaterialCode() : material.getBareCode();
  }

  private record BomProduct(String code, String organization) {}

  private record BareMatch(String code, boolean fromBom, boolean ambiguous) {}

  private static void addBomProduct(List<BomProduct> products, MaterialMasterRaw material, String organization) {
    if (blank(bareCode(material)) && !blank(material.getMaterialCode())) {
      products.add(new BomProduct(material.getMaterialCode(), organization));
    }
  }

  private Map<BomProduct, BareMatch> readBomBodies(List<BomProduct> products) {
    var byOrg = products.stream().collect(Collectors.groupingBy(BomProduct::organization,
        Collectors.mapping(BomProduct::code, Collectors.toSet())));
    var result = new HashMap<BomProduct, BareMatch>();
    var asOf = CostPricingPeriodUtils.currentPricingDate();
    byOrg.forEach((organization, codes) -> {
      var priceOrg = MaterialOrganization.fromCode(organization).getPriceOrgCode();
      var lines = hierarchy.selectNetLossDirectChildren(priceOrg, codes, asOf);
      var byParent = lines.stream().collect(Collectors.groupingBy(BomRawHierarchy::getTopProductCode));
      var childCodes = lines.stream().map(BomRawHierarchy::getMaterialCode).collect(Collectors.toSet());
      if (childCodes.isEmpty()) return;
      var childMasters = materials.selectByLatestBatchAndCodes(childCodes, null, organization).stream()
          .collect(Collectors.toMap(MaterialMasterRaw::getMaterialCode, row -> row));
      byParent.forEach((code, children) -> result.put(new BomProduct(code, organization),
          identifyBody(code, children, childMasters)));
    });
    return result;
  }

  private static BareMatch bareMatch(MaterialMasterRaw product, String organization, Map<BomProduct, BareMatch> bodies) {
    String explicit = bareCode(product);
    if (!blank(explicit)) return new BareMatch(explicit.trim(), false, false);
    return bodies.getOrDefault(new BomProduct(product.getMaterialCode(), organization),
        new BareMatch(null, false, false));
  }

  private static BareMatch identifyBody(String product, List<BomRawHierarchy> lines,
      Map<String, MaterialMasterRaw> masters) {
    var remaining = new LinkedHashSet<String>();
    boolean packaging = false;
    boolean bareCategory = false;
    for (var line : lines) {
      String code = line.getMaterialCode();
      if (blank(code) || product.equals(code)) continue;
      var master = masters.get(code);
      String category = master == null ? null : master.getMainCategoryCode();
      if ("No104".equals(line.getCostElementCode()) || "包装组件".equals(line.getMaterialCategory2())
          || startsWith(line.getMaterialCategory1(), "15155") || startsWith(category, "15155")
          || master != null && ("包装组件".equals(master.getMainCategoryName())
              || "主要材料-包装材料".equals(master.getCostElement()))) {
        packaging = true;
        continue;
      }
      remaining.add(code);
      bareCategory |= startsWith(line.getMaterialCategory1(), "11") || startsWith(category, "11");
    }
    if (remaining.size() == 1 && (packaging || bareCategory)) return new BareMatch(remaining.getFirst(), true, false);
    return new BareMatch(null, false, remaining.size() > 1 && (packaging || bareCategory));
  }

  private static boolean startsWith(String value, String prefix) {
    return value != null && value.trim().startsWith(prefix);
  }

  private Source resolve(MaterialMasterRaw material, Lookup query, BareMatch body,
      QualityLossRate rate, boolean matchedBareRate) {
    String organization = query.organization();
    int year = query.year();
    String businessUnit = query.businessUnit();
    String bare = body.fromBom() && rate != null && !matchedBareRate ? null : body.code();
    if (rate == null) {
      String reason = body.ambiguous() ? "NET_LOSS_BOM_BARE_AMBIGUOUS"
          : bare == null ? "NET_LOSS_BARE_MISSING" : "NET_LOSS_RATE_MISSING";
      return source(material, bare, organization, year, businessUnit, null, "MISSING", reason, MISSING_RATE_MESSAGE);
    }
    if (!validPublicRate(rate.getLossRate())) {
      return source(material, bare, organization, year, businessUnit, rate,
          "ERROR", "NET_LOSS_RATE_INVALID", "公共净损失率必须大于等于 0% 且小于 100%，请核实配置");
    }
    if (body.fromBom() && matchedBareRate) return source(material, bare, organization, year, businessUnit, rate,
        "AVAILABLE", BOM_SOURCE_AVAILABLE, "采用 BOM 去除包装后的裸品 " + bare + " 的净损失率");
    return source(material, bare, organization, year, businessUnit, rate,
        "AVAILABLE", "NET_LOSS_SOURCE_AVAILABLE", "沿用本产品已有净损失率");
  }

  private static Source source(MaterialMasterRaw material, String bare, String organization, int year, String businessUnit,
      QualityLossRate rate, String status, String code, String message) {
    return new Source(status, code, message, material.getId(), material.getMaterialCode(), material.getMaterialName(),
        material.getMaterialModel(), bare, organization, year, businessUnit,
        rate == null ? null : rate.getId(), rate == null ? null : rate.getLossRate());
  }

  private static Source missingProduct(boolean noMatch, String code, String organization, int year, String businessUnit) {
    String reason = noMatch ? "NET_LOSS_PRODUCT_MISSING" : "NET_LOSS_PRODUCT_AMBIGUOUS";
    return new Source("MISSING", reason, MISSING_RATE_MESSAGE, null, code, null, null, null, organization, year,
        businessUnit, null, null);
  }
  private static void requireContext(String organization, int year, String businessUnit) {
    if (blank(organization) || blank(businessUnit) || year < 1900 || year > 9999) throw new IllegalArgumentException("净损失率缺少有效组织、核算年度或业务单元");
  }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
}

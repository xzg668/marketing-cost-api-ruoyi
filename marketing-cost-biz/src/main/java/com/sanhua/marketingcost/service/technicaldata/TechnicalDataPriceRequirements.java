package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirement;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirementsResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 缺价依据来自正式价格准备和当前模块引用；不把整棵 BOM 或辅料直接当成缺价清单。 */
@Service
public class TechnicalDataPriceRequirements {
  private static final Logger log = LoggerFactory.getLogger(TechnicalDataPriceRequirements.class);
  private final PricePrepareService preparation;
  private final MakePartMaterialPriceResolveService prices;
  private final MaterialMasterRawMapper materials;
  private final QuoteTechnicalDataRepository versions;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataVersionContentCodec content;
  private final OaMessageCodec json;
  private final JdbcTemplate jdbc;

  public TechnicalDataPriceRequirements(PricePrepareService preparation, MakePartMaterialPriceResolveService prices,
      MaterialMasterRawMapper materials, QuoteTechnicalDataRepository versions, TechnicalDataTaskRepository tasks,
      TechnicalDataVersionContentCodec content, OaMessageCodec json, JdbcTemplate jdbc) {
    this.preparation = preparation; this.prices = prices; this.materials = materials; this.versions = versions;
    this.tasks = tasks; this.content = content; this.json = json; this.jdbc = jdbc;
  }

  public TechnicalDataPriceRequirementsResponse check(QuoteTechTask task, QuoteTechProduct product) {
    var demands = new TreeMap<String, Set<String>>();
    List<String> issues = new ArrayList<>();
    addBom(task, product, demands, issues);
    addModules(product, demands);
    return resolve(task, product, demands, issues);
  }

  public TechnicalDataPriceRequirementsResponse checkModuleReferences(QuoteTechTask task, QuoteTechProduct product) {
    var demands = new TreeMap<String, Set<String>>();
    addModules(product, demands);
    return resolve(task, product, demands, new ArrayList<>());
  }

  private void addModules(QuoteTechProduct product, Map<String, Set<String>> demands) {
    for (var module : tasks.findModules(product.getId())) {
      Long versionId = module.getCurrentVersionId();
      if (versionId == null) continue;
      var version = versions.findVersion(versionId).orElseThrow();
      if (!Objects.equals(version.getProductId(), product.getId())) throw new IllegalStateException("价格依据版本不属于本产品");
      switch (module.getModuleType()) {
        case "MANUFACTURING" -> {
          var value = content.manufacturing(version);
          if (value != null && value.items() != null) for (var row : value.items()) {
            add(demands, row.rawMaterialNo(), "RAW");
            if (row.evidence() != null && "MATCHED".equals(row.evidence().scrapStatus())) {
              for (var scrap : row.evidence().scrapMappings()) add(demands, scrap.materialNo(), "SCRAP");
            }
          }
        }
        case "SOLDER" -> {
          var value = content.solder(version);
          if (value != null && value.items() != null) value.items().forEach(row -> add(demands, row.materialNo(), "SOLDER"));
        }
        case "PACKAGE" -> versions.findPackageItems(versionId).forEach(row -> add(demands, row.getComponentMaterialNo(), "PACKAGE"));
        default -> { }
      }
    }
  }

  private TechnicalDataPriceRequirementsResponse resolve(QuoteTechTask task, QuoteTechProduct product, Map<String, Set<String>> demands, List<String> issues) {
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    List<TechnicalDataPriceRequirement> result = new ArrayList<>();
    for (var demand : demands.entrySet()) {
      String code = demand.getKey();
      String org = MaterialOrganization.fromPriceOrgCode(task.getApplicableOrgCode()).getCode();
      var masters = materials.selectByLatestBatchAndCodes(List.of(code), null, org);
      if (masters.size() != 1 || masters.getFirst().getUnit() == null || masters.getFirst().getUnit().isBlank()) {
        issues.add(code + " 的组织或采购单位尚未核实，请先核对料品档案");
        continue;
      }
      var master = masters.getFirst();
      String unit = master.getUnit().trim();
      String key = json.canonicalHash(List.of(code, task.getApplicableOrgCode(), task.getBusinessUnitType(), unit, "CNY"));
      String status, message;
      BigDecimal price = null;
      try {
        var resolved = prices.calculateMaterialUnitPrice(code, product.getAccountingMonth(), now.toLocalDate(), now,
            task.getOaNo(), task.getBusinessUnitType(), null);
        if (resolved == null) throw new IllegalStateException("取价未返回结果");
        price = resolved.getUnitPrice();
        if ("OK".equals(resolved.getStatus()) && price != null && price.signum() > 0) {
          status = "AVAILABLE";
        } else if (price == null && Set.of("MISSING_PRICE", "MISSING_ROUTE").contains(resolved.getStatus())) {
          status = "MISSING";
        } else {
          status = "ERROR";
        }
        message = resolved.getRemark();
      } catch (RuntimeException exception) {
        log.warn("technical price requirement failed: task={} product={} material={}", task.getId(), product.getId(), code, exception);
        status = "ERROR"; message = "取价检查失败，请重试";
      }
      result.add(new TechnicalDataPriceRequirement(key, code, master.getMaterialName(), master.getMaterialModel(), task.getApplicableOrgCode(),
          unit, "CNY", List.copyOf(demand.getValue()), status, price, message));
      if ("ERROR".equals(status)) issues.add(code + "：" + message);
    }
    return new TechnicalDataPriceRequirementsResponse(List.copyOf(result), List.copyOf(issues), json.canonicalHash(List.of(result, issues)));
  }

  private void addBom(QuoteTechTask task, QuoteTechProduct product, Map<String, Set<String>> demands, List<String> issues) {
    var batches = jdbc.queryForList("SELECT current_bom_build_batch_id FROM lp_quote_costing_workspace WHERE oa_form_item_id=? AND period_month=?",
        product.getOaFormItemId(), product.getAccountingMonth());
    if (batches.isEmpty() || batches.getFirst().get("current_bom_build_batch_id") == null) {
      issues.add("请先完成本产品 BOM 及计价对象检查");
      return;
    }
    var request = new PricePrepareGenerateRequest();
    request.setOaNo(task.getOaNo()); request.setOaFormItemId(product.getOaFormItemId());
    request.setTopProductCode(product.getMaterialNo()); request.setTopProductCodes(List.of(product.getMaterialNo()));
    request.setPeriodMonth(product.getAccountingMonth()); request.setBusinessUnitType(task.getBusinessUnitType());
    request.setScenarioType(QuotePriceScenarioType.OA_LOCKED);
    try {
      var prepared = preparation.calculate(request);
      if (prepared == null || prepared.getSummary() == null || "FAILED".equals(prepared.getSummary().getStatus())) {
        issues.add("BOM 价格检查失败，请核对后重新检查"); return;
      }
      for (var gap : prepared.getGaps()) {
        if (!"MISSING_PRICE".equals(gap.getGapType()) || Objects.toString(gap.getReasonCode(), "").contains("CONFLICT")) {
          issues.add(Objects.toString(gap.getMessage(), "尚有结构或价格来源问题"));
        } else {
          add(demands, gap.getGapMaterialCode(), "BOM");
        }
      }
    } catch (RuntimeException exception) {
      log.warn("technical BOM price requirements failed: product={}", product.getId(), exception);
      issues.add("BOM 价格检查失败，请重试");
    }
  }

  private static void add(Map<String, Set<String>> demands, String code, String role) {
    if (code == null || code.isBlank()) throw new IllegalStateException("价格依据缺少真实料号");
    demands.computeIfAbsent(code.trim(), ignored -> new TreeSet<>()).add(role);
  }
}

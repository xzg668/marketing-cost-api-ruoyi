package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirement;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirementsResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
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
  private final TechnicalDataPriceOwnership ownership;

  public TechnicalDataPriceRequirements(PricePrepareService preparation, MakePartMaterialPriceResolveService prices,
      MaterialMasterRawMapper materials, QuoteTechnicalDataRepository versions, TechnicalDataTaskRepository tasks,
      TechnicalDataVersionContentCodec content, OaMessageCodec json, JdbcTemplate jdbc, TechnicalDataPriceOwnership ownership) {
    this.preparation = preparation; this.prices = prices; this.materials = materials; this.versions = versions;
    this.tasks = tasks; this.content = content; this.json = json; this.jdbc = jdbc;
    this.ownership = ownership;
  }

  public TechnicalDataPriceRequirementsResponse check(QuoteTechTask task, QuoteTechProduct product) {
    var failed = tasks.findModules(product.getId()).stream().filter(module ->
        "PRICE".equals(module.getModuleType()) && "TECHNICAL_BOM_PRICE_FAILED".equals(module.getRequirementReasonCode())).findFirst();
    if (failed.isPresent()) return new TechnicalDataPriceRequirementsResponse(List.of(),
        List.of(failed.get().getRequirementReason()), Objects.toString(failed.get().getSourceReference(), "FAILED"));
    var demands = new TreeMap<String, Set<String>>();
    List<String> issues = new ArrayList<>();
    // 制造件保存产生的补价先处理已知原料/废料，不以整棵 BOM 完成为前置条件。
    // 核算发现的普通 PRICE 需求仍按正式计价 BOM 检查。
    boolean manufacturingPrices = tasks.findModules(product.getId()).stream().anyMatch(module ->
        "PRICE".equals(module.getModuleType()) && TechnicalDataManufacturingPrices.ownsRequirement(module));
    boolean composed = tasks.findModules(product.getId()).stream().anyMatch(module ->
        "PRICE".equals(module.getModuleType()) && isComposedCheck(module));
    if (!composed) addModules(product, demands, manufacturingPrices ? Set.of("MANUFACTURING") : Set.of("MANUFACTURING", "SOLDER", "PACKAGE"));
    if (!manufacturingPrices) addBom(task, product, demands, issues);
    var revisions = ownedRevisions(task, product);
    revisions.values().forEach(row -> add(demands, row.materialNo(), "SOURCE_REVISION"));
    return resolve(task, product, demands, issues, revisions.keySet());
  }

  static boolean isComposedCheck(QuoteTechModule module) {
    return Objects.toString(module.getRequirementReasonCode(), "").startsWith("TECHNICAL_BOM_PRICE_");
  }

  public TechnicalDataPriceRequirementsResponse checkComposedBom(QuoteTechTask task, QuoteTechProduct product) {
    var demands = new TreeMap<String, Set<String>>();
    var issues = new ArrayList<String>();
    addBom(task, product, demands, issues);
    var revisions = ownedRevisions(task, product);
    revisions.values().forEach(row -> add(demands, row.materialNo(), "SOURCE_REVISION"));
    return resolve(task, product, demands, issues, revisions.keySet());
  }

  public TechnicalDataPriceRequirementsResponse checkModuleReferences(QuoteTechTask task, QuoteTechProduct product) {
    var demands = new TreeMap<String, Set<String>>();
    addModules(product, demands);
    return resolve(task, product, demands, new ArrayList<>(), Set.of());
  }

  public TechnicalDataPriceRequirementsResponse checkManufacturingReferences(QuoteTechTask task, QuoteTechProduct product) {
    var demands = new TreeMap<String, Set<String>>();
    addModules(product, demands, Set.of("MANUFACTURING"));
    return resolve(task, product, demands, new ArrayList<>(), Set.of());
  }

  /** 列表使用已检查的需求和当前保存资料，不为刷新页面重新执行价格准备。 */
  public com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSharedModuleInfo savedSharedSource(
      Long itemId, String month, String businessUnit, String organization) {
    var snapshots = jdbc.queryForList("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=? AND period_month=?",
        String.class, itemId, month);
    if (snapshots.isEmpty() || snapshots.getFirst() == null) return null;
    var evidence = json.read(snapshots.getFirst()).path("evidence");
    var demands = new TreeMap<String, Set<String>>();
    for (var gap : evidence.path("PRICE").path("gaps")) {
      if (gap.size() < 3 || !"MISSING_PRICE".equals(gap.path(1).asText()) || gap.path(2).asText().contains("CONFLICT")) return null;
      add(demands, gap.path(0).asText(), "BOM");
    }
    var product = versions.findActiveProduct(itemId, month).orElse(null);
    if (product != null) addModules(product, demands);
    if (demands.isEmpty()) return null;
    var owners = new ArrayList<com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceOwner>();
    var sourceEvidence = new ArrayList<Object>();
    for (String code : demands.keySet()) {
      var owner = ownership.find(code);
      if (owner == null || owner.versionId() == null || "CANCELLED".equals(owner.taskStatus())
          || product != null && Objects.equals(product.getId(), owner.productId())
          || !Objects.equals(owner.businessUnit(), businessUnit) || !Objects.equals(owner.organizationCode(), organization)
          || !"CNY".equals(owner.currency())) return null;
      var masters = materials.selectByLatestBatchAndCodes(List.of(code), null, MaterialOrganization.fromPriceOrgCode(organization).getCode());
      if (masters.size() != 1 || !Objects.equals(masters.getFirst().getUnit(), owner.unit())) return null;
      var version = versions.findVersion(owner.versionId()).orElse(null);
      var saved = version == null || !Objects.equals(version.getProductId(), owner.productId()) ? null : content.prices(version);
      var row = saved == null || saved.items() == null ? null : saved.items().stream().filter(value ->
          code.equals(value.materialNo()) && organization.equals(value.organizationCode())
              && owner.unit().equals(value.unit()) && "CNY".equals(value.currency())).findFirst().orElse(null);
      if (row == null || !TechnicalDataPriceRules.validate(row).isEmpty()) return null;
      owners.add(owner); sourceEvidence.add(List.of(owner, row));
    }
    var first = owners.getFirst();
    boolean oneSource = owners.stream().allMatch(owner -> Objects.equals(owner.taskId(), first.taskId()));
    boolean submitted = owners.stream().allMatch(owner -> TechnicalDataSubmissionState.submitted(owner.moduleStatus()));
    return new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSharedModuleInfo("PRICE", submitted ? "SUBMITTED" : "SAVED",
        oneSource ? first.taskId() : null, oneSource ? first.productId() : null,
        submitted && oneSource ? first.versionId() : null, json.canonicalHash(sourceEvidence), oneSource ? first.assigneeName() : null,
        "已有资料，" + demands.size() + " 项物料沿用原补录，无需重复填写");
  }

  /** Reopening an owned source does not make an already adopted quotation price missing. */
  private Map<String, com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceItem>
      ownedRevisions(QuoteTechTask task, QuoteTechProduct product) {
    var result = new LinkedHashMap<String, com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceItem>();
    if (product.getLatestSubmittedVersionId() == null) return result;
    var submitted = versions.findVersion(product.getLatestSubmittedVersionId()).orElseThrow();
    if (!Objects.equals(submitted.getProductId(), product.getId())) throw new IllegalStateException("原价格版本不属于本产品");
    var original = content.prices(submitted);
    if (original == null || original.items() == null) return result;
    for (var module : tasks.findModules(product.getId())) {
      if (!"PRICE".equals(module.getModuleType())
          || !Set.of("RETURNED", "EDITING", "READY").contains(Objects.toString(module.getModuleStatus(), ""))) continue;
      for (var row : original.items()) {
        Integer owned = jdbc.queryForObject("""
            SELECT COUNT(*) FROM lp_quote_tech_price_claim
            WHERE material_code=? AND owner_module_id=? AND organization_code=?
              AND business_unit_type=? AND price_unit=? AND currency=?
            """, Integer.class, row.materialNo(), module.getId(), row.organizationCode(),
            task.getBusinessUnitType(), row.unit(), row.currency());
        if (Integer.valueOf(1).equals(owned)) result.put(row.itemKey(), row);
      }
    }
    return result;
  }

  private void addModules(QuoteTechProduct product, Map<String, Set<String>> demands) {
    addModules(product, demands, Set.of("MANUFACTURING", "SOLDER", "PACKAGE"));
  }

  private void addModules(QuoteTechProduct product, Map<String, Set<String>> demands, Set<String> types) {
    for (var module : tasks.findModules(product.getId())) {
      if (!types.contains(module.getModuleType())) continue;
      Long versionId = module.getCurrentVersionId();
      if (versionId == null) continue;
      var version = versions.findVersion(versionId).orElseThrow();
      if (!Objects.equals(version.getProductId(), product.getId()))
        throw new IllegalStateException("价格依据版本不属于本产品");
      switch (module.getModuleType()) {
        case "MANUFACTURING" -> {
          var value = content.manufacturing(version);
          if (value != null && value.items() != null) for (var row : value.items()) {
            add(demands, row.rawMaterialNo(), "RAW");
            if (row.evidence() != null && "MATCHED".equals(row.evidence().scrapStatus())) {
                for (var scrap : row.evidence().scrapMappings())
                  add(demands, scrap.materialNo(), "SCRAP");
            }
          }
        }
        case "SOLDER" -> {
          var value = content.solder(version);
          if (value != null && value.items() != null)
            value.items().forEach(row -> add(demands, row.materialNo(), "SOLDER"));
        }
        case "PACKAGE" ->
            versions
                .findPackageItems(versionId)
                .forEach(row -> add(demands, row.getComponentMaterialNo(), "PACKAGE"));
        default -> { }
      }
    }
  }

  private TechnicalDataPriceRequirementsResponse resolve(QuoteTechTask task, QuoteTechProduct product, Map<String, Set<String>> demands, List<String> issues, Set<String> revisionKeys) {
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var scenario = PricePrepareScenarioContext.oaLocked(product.getOaFormItemId(), task.getApplicableOrgCode());
    List<TechnicalDataPriceRequirement> result = new ArrayList<>();
    List<Object> sharedEvidence = new ArrayList<>();
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
      String key =
          json.canonicalHash(
              List.of(code, task.getApplicableOrgCode(), task.getBusinessUnitType(), unit, "CNY"));
      String status, message;
      BigDecimal price = null;
      boolean waiting = false;
      try {
        var resolved =
            prices.calculateMaterialUnitPrice(
                code,
                product.getAccountingMonth(),
                now.toLocalDate(),
                now,
                task.getOaNo(),
                task.getBusinessUnitType(),
                scenario,
                demand.getValue().contains("SCRAP")
                    ? MakePartMaterialPriceResolveService.Purpose.SCRAP
                    : demand.getValue().contains("PACKAGE")
                        ? MakePartMaterialPriceResolveService.Purpose.PACKAGE
                        : MakePartMaterialPriceResolveService.Purpose.MATERIAL);
        if (resolved == null) throw new IllegalStateException("取价未返回结果");
        price = resolved.getUnitPrice();
        waiting = "WAITING".equals(resolved.getFailureReason());
        if ("OK".equals(resolved.getStatus()) && price != null && price.signum() > 0) {
          status = "AVAILABLE";
        } else if (price == null && "MISSING_PRICE".equals(resolved.getStatus())) {
          status = "MISSING";
        } else {
          status = "ERROR";
        }
        message = resolved.getRemark();
      } catch (RuntimeException exception) {
        log.warn(
            "technical price requirement failed: task={} product={} material={}",
            task.getId(),
            product.getId(),
            code,
            exception);
        status = "ERROR"; message = "取价检查失败，请重试";
      }
      if (!"ERROR".equals(status) && revisionKeys.contains(key)) {
        status = "REVISION";
        message = "修改原补录来源；已采用的月价保持不变";
      }
      if ("MISSING".equals(status) || waiting) {
        var owner = ownership.find(code);
        if (owner != null
            && !"CANCELLED".equals(owner.taskStatus())
            && Objects.equals(owner.organizationCode(), task.getApplicableOrgCode())
            && Objects.equals(owner.businessUnit(), task.getBusinessUnitType())
            && Objects.equals(owner.unit(), unit) && "CNY".equals(owner.currency())) {
          var original = owner.versionId() == null ? null : versions.findVersion(owner.versionId()).orElse(null);
          var saved = original == null || !Objects.equals(original.getProductId(), owner.productId())
              ? null : content.prices(original);
          var entry = saved == null || saved.items() == null ? null : saved.items().stream()
              .filter(row -> code.equals(row.materialNo()) && task.getApplicableOrgCode().equals(row.organizationCode())
                  && unit.equals(row.unit()) && "CNY".equals(row.currency()))
              .findFirst().orElse(null);
          if (!Objects.equals(owner.productId(), product.getId())) {
            boolean complete = entry != null && TechnicalDataPriceRules.validate(entry).isEmpty();
            status = complete ? "SHARED" : "ASSIGNED";
            message = (complete ? "已引用原产品 " : "请在原产品 ")
                + Objects.toString(owner.productMaterialNo(), "任务 " + owner.taskId())
                + (complete ? " 填好的价格，本产品无需重复补录" : " 补齐价格，本产品无需重复填写");
            sharedEvidence.add(List.of(owner, entry == null ? "" : entry));
          } else if (waiting && entry != null && "DRAFT".equals(original.getVersionStatus())
              && tasks.findModules(product.getId()).stream().anyMatch(module ->
                  Objects.equals(module.getId(), owner.moduleId()) && "PRICE".equals(module.getModuleType())
                      && Objects.equals(module.getCurrentVersionId(), owner.versionId())
                      && "TECHNICAL_BOM_PRICE_FAILED".equals(module.getRequirementReasonCode())
                      && Set.of("PENDING", "EDITING", "READY", "RETURNED").contains(Objects.toString(module.getModuleStatus(), "")))) {
            // 检查异常会临时撤下 required 标记；重试时恢复本人的可编辑草稿需求，不能把草稿当作已生效价格。
            status = "MISSING";
            price = null;
            message = "继续使用已保存的补录资料，提交后生效";
          }
        }
      }
      result.add(new TechnicalDataPriceRequirement(key, code, master.getMaterialName(), master.getMaterialModel(), task.getApplicableOrgCode(),
          unit, "CNY", List.copyOf(demand.getValue()), status, price, message));
      if ("ERROR".equals(status)) issues.add(code + "：" + message);
    }
    return new TechnicalDataPriceRequirementsResponse(List.copyOf(result), List.copyOf(issues), json.canonicalHash(List.of(result, issues, sharedEvidence)));
  }

  private void addBom(QuoteTechTask task, QuoteTechProduct product, Map<String, Set<String>> demands, List<String> issues) {
    var batches =
        jdbc.queryForList(
            "SELECT current_bom_build_batch_id FROM lp_quote_costing_workspace WHERE"
                + " oa_form_item_id=? AND period_month=?",
            product.getOaFormItemId(),
            product.getAccountingMonth());
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
      var prepared = preparation.calculateForTechnical(request);
      if (prepared == null || prepared.getSummary() == null || "FAILED".equals(prepared.getSummary().getStatus())) {
        issues.add("BOM 价格检查失败，请核对后重新检查"); return;
      }
      if (prepared.getItems().isEmpty() && prepared.getGaps().isEmpty()) {
        issues.add("尚未生成实际计价物料，不能判定价格已齐全"); return;
      }
      for (var gap : technicalGaps(prepared)) {
        // 制造件汇总把基础料件的待审批价格包装为来源异常；按该基础料件重新核对已填资料。
        // resolve 仍区分 WAITING、真实来源错误和冲突，不把草稿用于正式成本计算。
        boolean manufacturingPrice = "PRICE_SOURCE_ERROR".equals(gap.getGapType())
            && Set.of("RAW_PRICE_ERROR", "SCRAP_PRICE_ERROR").contains(Objects.toString(gap.getReasonCode(), ""));
        if ((!"MISSING_PRICE".equals(gap.getGapType()) && !manufacturingPrice)
            || Objects.toString(gap.getReasonCode(), "").contains("CONFLICT")) {
          issues.add(Objects.toString(gap.getMessage(), "尚有结构或价格来源问题"));
        } else {
          add(demands, gap.getGapMaterialCode(),
              Set.of("MISSING_SCRAP_PRICE", "SCRAP_PRICE_ERROR").contains(Objects.toString(gap.getReasonCode(), "")) ? "SCRAP" : "BOM");
        }
      }
    } catch (RuntimeException exception) {
      log.warn("technical BOM price requirements failed: product={}", product.getId(), exception);
      issues.add("BOM 价格检查失败，请重试");
    }
  }

  static List<PricePrepareGap> technicalGaps(
      com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult prepared) {
    // Supplier review remains in the finance pricing workflow; it is not a missing price
    // for the technician to enter and must not prevent dispatching other missing materials.
    var reviews = prepared.getSupplierPriceReviews();
    return prepared.getGaps().stream().filter(gap -> !("MISSING_PRICE".equals(gap.getGapType())
        && "WAITING".equals(gap.getReasonCode()) && reviews != null && reviews.stream().anyMatch(review ->
            Set.of("PENDING", "WAIT_IMPORT").contains(review.status())
                && Objects.equals(gap.getGapMaterialCode(), review.scope().materialCode())))).toList();
  }

  private static void add(Map<String, Set<String>> demands, String code, String role) {
    if (code == null || code.isBlank()) throw new IllegalStateException("价格依据缺少真实料号");
    demands.computeIfAbsent(code.trim(), ignored -> new TreeSet<>()).add(role);
  }
}

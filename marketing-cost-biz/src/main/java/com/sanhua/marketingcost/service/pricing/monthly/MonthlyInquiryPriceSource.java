package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.monthly.MonthlySupplierInputMapper;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.inquiry.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Actual BOM material -> exact code or model/specification -> supplier identity -> existing ratio
 * policy.
 */
@Component
public final class MonthlyInquiryPriceSource {
  public record Selected(
      long id,
      String supplierCode,
      String supplierName,
      String originalSupplierName,
      String supplierMatch,
      BigDecimal price,
      InquiryPriceRepository.Source original,
      Map<String, Object> calculation) {}

  public record Source(
      Selected selected,
      List<Selected> candidates,
      List<SupplierSupplyRatio> ratios,
      String selectionRule,
      String materialMatch,
      Set<String> archiveModelsAndSpecifications) {}

  public record Snapshot(int snapshotSchema, String sourceKind, Source source) {}

  private final JdbcTemplate jdbc;
  private final InquiryPriceRepository repository;
  private final MonthlySupplierInputMapper supplierInputs;
  private final SupplierPriceSelectionService suppliers;
  private final MonthlyPriceDocumentCodec codec;

  public MonthlyInquiryPriceSource(
      JdbcTemplate jdbc,
      InquiryPriceRepository repository,
      MonthlySupplierInputMapper supplierInputs,
      SupplierPriceSelectionService suppliers,
      MonthlyPriceDocumentCodec codec) {
    this.jdbc = jdbc;
    this.repository = repository;
    this.supplierInputs = supplierInputs;
    this.suppliers = suppliers;
    this.codec = codec;
  }

  public Candidates read(Context context, Set<MonthlyPriceRequest> requests) {
    return read(context,requests,null, false);
  }

  public Candidates readSpecial(Context context, Set<MonthlyPriceRequest> requests, boolean currentMonthOnly) {
    return read(context,requests,currentMonthOnly, false);
  }

  Candidates readModelFallback(Context context, Set<MonthlyPriceRequest> requests) {
    return read(context, requests, null, true);
  }

  private Candidates read(Context context, Set<MonthlyPriceRequest> requests, Boolean currentMonthOnly, boolean modelFallback) {
    var values = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
    var problems = new LinkedHashMap<MonthlyPriceRequest, Problem>();
    var organizations = new TreeMap<String, List<MonthlyPriceRequest>>();
    for (var request : requests) {
      if (!context.businessUnitType().equals(request.family().businessUnitType())
          || !context.month().equals(request.family().month()))
        throw new MonthlyPriceProblem(Failure.INVALID_INPUT, "询价范围与本次核算不一致");
      organizations
          .computeIfAbsent(request.priceOrgCode(), ignored -> new ArrayList<>())
          .add(request);
    }
    List<InquirySupplierNames.Supplier> supplierCatalog = null;
    for (var group : organizations.entrySet()) {
      var codes = group.getValue().stream().map(r -> r.materialCode()).distinct().toList();
      for (int start = 0; start < codes.size(); start += 256) {
        var chunk = new LinkedHashSet<>(codes.subList(start, Math.min(start + 256, codes.size())));
        var scoped =
            group.getValue().stream()
                .filter(r -> chunk.contains(r.materialCode()))
                .toList();
        var specifications = new TreeSet<String>();
        scoped.stream().filter(request -> request.family().specificationOnly())
            .map(request -> InquiryNames.normalize(request.family().specification()))
            .filter(spec -> !spec.isBlank()).forEach(specifications::add);
        var aliases = modelFallback ? modelAliases(group.getKey(), chunk) : Map.<String, Set<String>>of();
        aliases.values().forEach(specifications::addAll);
        var keys = new TreeSet<String>();
        if (!modelFallback) chunk.forEach(code -> keys.add(InquiryNames.normalize(code)));
        var found =
            currentMonthOnly==null ? repository.matching(
                context.businessUnitType(), context.month().toString(), keys, specifications)
                : repository.matchingPolicy(context.businessUnitType(),context.month().toString(),keys,currentMonthOnly);
        if (found.isEmpty()) {
          scoped.forEach(request -> problems.put(request, new Problem(Failure.MISSING, "没有匹配的询价")));
          continue;
        }
        if (supplierCatalog == null)
          supplierCatalog =
              jdbc.query(
                  "SELECT DISTINCT supplier_code,supplier_name FROM lp_supplier_supply_ratio WHERE"
                      + " business_unit_type=? AND deleted=0 AND is_active=1 AND supplier_code IS"
                      + " NOT NULL ORDER BY supplier_code,supplier_name",
                  (row, n) -> new InquirySupplierNames.Supplier(row.getString(1), row.getString(2)),
                  context.businessUnitType());
        var effective =
            repository.effective(
                context.businessUnitType(),
                context.month().toString(),
                found.stream().map(InquiryPriceRepository.Source::id).toList());
        var ratios = new HashMap<String, List<SupplierSupplyRatio>>();
        supplierInputs
            .ratios(context.businessUnitType(), context.priceAsOfTime().toLocalDate(), chunk)
            .forEach(
                row ->
                    ratios
                        .computeIfAbsent(
                            row.getRequestedMaterialCode(), ignored -> new ArrayList<>())
                        .add(row));
        var scopeKeys =
            scoped.stream()
                .map(request -> SupplierPriceSelectionService.scopeKey(scope(context, request)))
                .collect(java.util.stream.Collectors.toSet());
        var decisions = new HashMap<String, SupplierPriceDecision>();
        supplierInputs.decisions(scopeKeys).forEach(row -> decisions.put(row.getScopeKey(), row));
        for (var request : scoped) {
          try {
            String material = InquiryNames.normalize(request.materialCode());
            var candidates =
                found.stream()
                    .filter(
                        row -> material.equals(InquiryNames.normalize(row.rule().materialCode())))
                    .toList();
            String match = "MATERIAL_CODE";
            if (modelFallback) {
              var terms = aliases.getOrDefault(request.materialCode(), Set.of());
              candidates = found.stream()
                  .filter(row -> InquiryNames.normalize(row.rule().materialCode()).isBlank())
                  .filter(row -> terms.contains(InquiryNames.normalize(row.rule().specification())))
                  .filter(row -> compatibleUnit(request.family().unit(), row.rule().unit()))
                  .toList();
              if (!candidates.isEmpty()) {
                var matched = candidates.stream().map(row -> InquiryNames.normalize(row.rule().specification()))
                    .collect(java.util.stream.Collectors.toSet());
                requireUnambiguousModel(group.getKey(), matched);
                match = "MODEL_FALLBACK";
              }
            } else if (request.family().specificationOnly()) {
              match = "SPECIFICATION";
              candidates =
                  found.stream()
                      .filter(
                          row ->
                              request.matchesSpecification(row.rule().specification()))
                      .toList();
            }
            if (candidates.isEmpty()
                || !"CNY".equals(request.family().currency())
                || request.family().taxBasis() != TaxBasis.EXCLUDED) {
              problems.put(request, new Problem(Failure.MISSING, "没有匹配的人民币不含税询价"));
              continue;
            }
            var actualRatios = ratios.getOrDefault(request.materialCode(), List.of());
            var materialSuppliers =
                actualRatios.stream()
                    .map(
                        row ->
                            new InquirySupplierNames.Supplier(
                                row.getSupplierCode(), row.getSupplierName()))
                    .toList();
            var heads =
                latestSuppliers(
                    candidates,
                    materialSuppliers,
                    supplierCatalog,
                    request.family().requestedSupplierCode());
            var prices = new LinkedHashMap<String, Selected>();
            for (var head : heads.entrySet()) {
              Selected highest = null;
              for (var entry : head.getValue()) {
                var source = entry.source();
                var result = effective.get(source.id());
                BigDecimal price;
                Map<String, Object> calculation;
                if (result == null && source.rule().priceType().equals("固定价")) {
                  price = source.rule().originalPrice();
                  calculation = Map.of("priceType", "固定价");
                } else if (result == null || "WAITING_BASE".equals(result.get("status")))
                  throw new MonthlyPriceProblem(
                      Failure.WAITING, "询价等待" + context.month() + "商用基价刷新");
                else if (!"READY".equals(result.get("status")))
                  throw new IllegalArgumentException("询价刷新失败：" + result.get("message"));
                else {
                  price = (BigDecimal) result.get("unit_price");
                  calculation = repository.calculation(result);
                }
                if (price == null) throw new IllegalArgumentException("询价没有已刷新的价格");
                var selected =
                    new Selected(
                        source.id(),
                        entry.supplier().code(),
                        entry.supplier().name(),
                        source.rule().supplierName(),
                        entry.supplier().basis(),
                        price,
                        source,
                        calculation);
                if (highest == null || price.compareTo(highest.price()) > 0) highest = selected;
              }
              if (highest != null) prices.put(Long.toString(highest.id()), highest);
            }
            if (prices.isEmpty()) {
              problems.put(request, new Problem(Failure.MISSING, "询价没有适用的指定供应商"));
              continue;
            }
            var priceCandidates =
                prices.entrySet().stream()
                    .map(
                        entry ->
                            new SupplierPriceCandidate(
                                entry.getKey(),
                                entry.getValue().supplierCode(),
                                entry.getValue().supplierName(),
                                entry.getValue().price(),
                                null,
                                null))
                    .toList();
            var scope = scope(context, request);
            var chosen =
                suppliers.selectPrepared(
                    scope,
                    priceCandidates,
                    actualRatios,
                    decisions.get(SupplierPriceSelectionService.scopeKey(scope)),
                    context.supplierReviews().packagingHighestFallback());
            if (chosen.review() != null)
              context
                  .supplierReviews()
                  .forOrganization(request.priceOrgCode())
                  .add(chosen.review());
            if (chosen.candidate() == null) {
              problems.put(request, new Problem(SupplierPriceSelectionService.NO_PRIMARY_SUPPLIER_PRICE.equals(chosen.rule()) ? Failure.MISSING : Failure.WAITING, chosen.message()));
              continue;
            }
            var selected = prices.get(chosen.candidate().key());
            var snapshot =
                new Snapshot(
                    1,
                    "INQUIRY",
                    new Source(
                        selected,
                        List.copyOf(prices.values()),
                        actualRatios,
                        chosen.rule(),
                        match,
                        modelFallback ? aliases.getOrDefault(request.materialCode(), Set.of())
                            : request.family().specificationOnly() ? Set.of(request.family().specification()) : Set.of()));
            var fact =
                PriceFact.normal(
                    selected.price(),
                    SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),
                    Set.of());
            if (chosen.review() != null)
              context
                  .supplierReviews()
                  .forOrganization(request.priceOrgCode())
                  .add(chosen.review(), fact.evidence().sha256());
            // The formula source is shared; each consumer keeps its actual supplier review and
            // adopted scope.
            var applicability =
                MonthlyPriceApplicability.of(
                    new MonthlyPriceCoverage(
                        Set.of(request.priceOrgCode()),
                        QuantityInterval.all(),
                        ConditionDomain.any()));
            values.put(request, new Candidate(new Selection(null, applicability, fact), null));
          } catch (MonthlyPriceProblem failure) {
            problems.put(request, new Problem(failure.reason(), failure.getMessage()));
          } catch (IllegalArgumentException | IllegalStateException invalid) {
            problems.put(request, new Problem(Failure.INVALID_INPUT, invalid.getMessage()));
          }
        }
      }
    }
    return new Candidates(values, problems);
  }

  private Map<String, Set<String>> modelAliases(String priceOrg, Set<String> codes) {
    var args = new ArrayList<Object>();
    args.add(MaterialOrganization.fromPriceOrgCode(priceOrg).getCode());
    args.addAll(codes);
    var result = new HashMap<String, Set<String>>();
    jdbc.query("SELECT material_code,material_model,drawing_no FROM lp_material_master_raw"
        + " WHERE organization_code=? AND active_flag=1 AND material_code IN ("
        + String.join(",", Collections.nCopies(codes.size(), "?")) + ")", row -> {
          var terms = result.computeIfAbsent(row.getString("material_code"), ignored -> new TreeSet<>());
          for (String column : List.of("material_model", "drawing_no")) {
            String term = InquiryNames.normalize(row.getString(column));
            if (!term.isBlank()) terms.add(term);
          }
        }, args.toArray());
    return result;
  }

  private void requireUnambiguousModel(String priceOrg, Set<String> terms) {
    if (terms.size() != 1) throw new IllegalArgumentException("型号和图号分别匹配到不同询价，请确认采用依据");
    String identifier = terms.iterator().next();
    var specifications = jdbc.queryForList(
        "SELECT DISTINCT UPPER(TRIM(COALESCE(material_spec,''))) FROM lp_material_master_raw"
            + " WHERE organization_code=? AND active_flag=1 AND"
            + " (UPPER(TRIM(material_model))=? OR UPPER(TRIM(drawing_no))=?) LIMIT 2",
        String.class, MaterialOrganization.fromPriceOrgCode(priceOrg).getCode(), identifier, identifier);
    if (specifications.size() > 1) throw new IllegalArgumentException("型号或图号对应多个物料规格，请确认型号价适用范围");
  }

  private static boolean compatibleUnit(String target, String source) {
    if (source == null || source.isBlank()) return true;
    return inquiryUnit(target).equals(inquiryUnit(source));
  }
  private static String inquiryUnit(String unit) {
    return switch (unit.strip().toUpperCase(Locale.ROOT)) {
      case "只", "件", "个", "PC", "PCS", "EA" -> "PCS";
      case "公斤", "千克", "KG" -> "KG";
      case "克", "G" -> "G";
      case "米", "M" -> "M";
      default -> unit.strip().toUpperCase(Locale.ROOT);
    };
  }

  private record SupplierSource(
      InquiryPriceRepository.Source source, InquirySupplierNames.Match supplier) {}

  private static Map<String, List<SupplierSource>> latestSuppliers(
      List<InquiryPriceRepository.Source> sources,
      List<InquirySupplierNames.Supplier> local,
      List<InquirySupplierNames.Supplier> catalog,
      String explicit) {
    var result = new LinkedHashMap<String, List<SupplierSource>>();
    var batches = new HashMap<String, Long>();
    for (var source : sources) {
      var match = source.rule().supplierCode().isBlank()
          ? InquirySupplierNames.resolve(source.rule().supplierName(), local, catalog)
          : new InquirySupplierNames.Match(source.rule().supplierCode(), source.rule().supplierName(), "EXPLICIT_CODE");
      if (explicit != null && !explicit.equals(match.code())) continue;
      String key =
          match.code() != null
              ? "CODE:" + match.code()
              : match.name().isBlank()
                  ? "EMPTY:" + source.identityKey()
                  : "NAME:" + InquiryNames.normalize(match.name());
      long previous = batches.getOrDefault(key, 0L);
      if (source.batchId() < previous) continue;
      if (source.batchId() > previous) {
        batches.put(key, source.batchId());
        result.put(key, new ArrayList<>());
      }
      result.get(key).add(new SupplierSource(source, match));
    }
    return result;
  }

  private static SupplierPriceScope scope(Context context, MonthlyPriceRequest request) {
    return new SupplierPriceScope(
        context.businessUnitType(),
        context.oaNo(),
        context.oaFormItemId(),
        context.month().toString(),
        context.priceAsOfTime().toLocalDate(),
        request.priceOrgCode(),
        request.materialCode(),
        "INQUIRY");
  }
}

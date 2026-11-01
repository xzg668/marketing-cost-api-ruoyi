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
      var codes = group.getValue().stream().map(r -> r.family().materialCode()).distinct().toList();
      for (int start = 0; start < codes.size(); start += 256) {
        var chunk = new LinkedHashSet<>(codes.subList(start, Math.min(start + 256, codes.size())));
        var scoped =
            group.getValue().stream()
                .filter(r -> chunk.contains(r.family().materialCode()))
                .toList();
        var archive = archive(group.getKey(), chunk);
        for (var request : scoped) {
          String spec = InquiryNames.normalize(request.family().specification());
          if (!spec.isBlank())
            archive
                .computeIfAbsent(request.family().materialCode(), ignored -> new LinkedHashSet<>())
                .add(spec);
        }
        var specifications = new TreeSet<String>();
        archive.values().forEach(specifications::addAll);
        var keys = new TreeSet<String>();
        chunk.forEach(code -> keys.add(InquiryNames.normalize(code)));
        var found =
            repository.matching(
                context.businessUnitType(), context.month().toString(), keys, specifications);
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
            String material = InquiryNames.normalize(request.family().materialCode());
            var candidates =
                found.stream()
                    .filter(
                        row -> material.equals(InquiryNames.normalize(row.rule().materialCode())))
                    .toList();
            String match = "MATERIAL_CODE";
            var models = archive.getOrDefault(request.family().materialCode(), Set.of());
            if (candidates.isEmpty()) {
              match = "MODEL_OR_SPECIFICATION";
              candidates =
                  found.stream()
                      .filter(
                          row ->
                              models.contains(InquiryNames.normalize(row.rule().specification())))
                      .toList();
            }
            if (candidates.isEmpty()
                || !"CNY".equals(request.family().currency())
                || request.family().taxBasis() != TaxBasis.EXCLUDED) {
              problems.put(request, new Problem(Failure.MISSING, "没有匹配的人民币不含税询价"));
              continue;
            }
            var actualRatios = ratios.getOrDefault(request.family().materialCode(), List.of());
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
              problems.put(request, new Problem(Failure.WAITING, chosen.message()));
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
                        Set.copyOf(models)));
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
      var match = InquirySupplierNames.resolve(source.rule().supplierName(), local, catalog);
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

  private Map<String, Set<String>> archive(String org, Set<String> codes) {
    var args = new ArrayList<Object>();
    args.add(MaterialOrganization.fromPriceOrgCode(org).getCode());
    args.addAll(codes);
    var rows =
        jdbc.queryForList(
            "SELECT material_code,material_model,material_spec FROM lp_material_master_raw WHERE"
                + " active_flag=1 AND organization_code=? AND material_code IN ("
                + String.join(",", Collections.nCopies(codes.size(), "?"))
                + ")",
            args.toArray());
    var result = new HashMap<String, Set<String>>();
    for (var row : rows)
      for (String field : List.of("material_model", "material_spec")) {
        String value = InquiryNames.normalize(Objects.toString(row.get(field), ""));
        if (!value.isBlank())
          result
              .computeIfAbsent(
                  row.get("material_code").toString(), ignored -> new LinkedHashSet<>())
              .add(value);
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
        request.family().materialCode(),
        "INQUIRY");
  }
}

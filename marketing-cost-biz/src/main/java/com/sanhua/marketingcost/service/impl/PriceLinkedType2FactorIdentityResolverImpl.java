package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2FactorIdentityCandidate;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorIdentityResolution;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.enums.PriceLinkedType2FactorIdentityResolutionStatus;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityReadRepository;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityResolver;
import com.sanhua.marketingcost.service.PriceLinkedType2TextNormalizer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedType2FactorIdentityResolverImpl
    implements PriceLinkedType2FactorIdentityResolver {

  private final FactorCanonicalKeyService canonicalKeyService;
  private final PriceLinkedType2TextNormalizer textNormalizer;
  private final PriceLinkedType2FactorIdentityReadRepository readRepository;

  public PriceLinkedType2FactorIdentityResolverImpl(
      FactorCanonicalKeyService canonicalKeyService,
      PriceLinkedType2TextNormalizer textNormalizer,
      PriceLinkedType2FactorIdentityReadRepository readRepository) {
    this.canonicalKeyService = canonicalKeyService;
    this.textNormalizer = textNormalizer;
    this.readRepository = readRepository;
  }

  @Override
  public PriceLinkedType2FactorIdentityResolution resolve(
      PriceLinkedType2FactorRow factorRow,
      String businessUnitType,
      String priceMonth) {
    String normalizedBusinessUnit = textNormalizer.normalize(businessUnitType);
    String normalizedMonth = textNormalizer.normalize(priceMonth);
    String canonicalKey = factorRow == null
        ? ""
        : canonicalKeyService.build(factorRow.getPriceSource(), factorRow.getShortName());
    String validationError = validate(
        factorRow, normalizedBusinessUnit, normalizedMonth, canonicalKey);
    if (validationError != null) {
      return unselectedResult(
          factorRow,
          normalizedBusinessUnit,
          normalizedMonth,
          canonicalKey,
          PriceLinkedType2FactorIdentityResolutionStatus.INVALID_REQUEST,
          null,
          null,
          List.of(),
          List.of(),
          validationError);
    }

    List<FactorIdentity> allIdentities =
        safe(readRepository.findActiveIdentities(normalizedBusinessUnit)).stream()
            .filter(Objects::nonNull)
            .filter(identity -> identity.getId() != null)
            .filter(identity -> sameBusinessUnit(identity, normalizedBusinessUnit))
            .toList();
    List<FactorIdentity> canonicalCandidates = allIdentities.stream()
        .filter(identity -> canonicalKey.equals(canonicalKey(identity)))
        .toList();
    if (canonicalCandidates.isEmpty()) {
      return unselectedResult(
          factorRow,
          normalizedBusinessUnit,
          normalizedMonth,
          canonicalKey,
          PriceLinkedType2FactorIdentityResolutionStatus.CREATE_REQUIRED,
          null,
          null,
          List.of(),
          List.of(),
          "系统中不存在统一因素 " + canonicalKey + "，需要在确认阶段创建稳定身份");
    }

    List<FactorIdentity> exactCandidates = canonicalCandidates.stream()
        .filter(identity -> exactMatch(identity, factorRow))
        .toList();
    boolean exactMatched = !exactCandidates.isEmpty();
    List<FactorIdentity> selectionCandidates =
        exactMatched ? exactCandidates : canonicalCandidates;
    List<Long> candidateIds = canonicalCandidates.stream()
        .map(FactorIdentity::getId)
        .distinct()
        .toList();
    List<FactorMonthlyPrice> monthlyPrices = safe(
        readRepository.findActiveMonthlyPrices(candidateIds, normalizedMonth));
    Map<Long, Long> bindingCounts = safeMap(
        readRepository.countActiveLegacyBindings(candidateIds));
    Map<Long, List<BigDecimal>> pricesByIdentity =
        pricesByIdentity(monthlyPrices, candidateIds);
    List<PriceLinkedType2FactorIdentityCandidate> snapshots = candidateSnapshots(
        canonicalCandidates, exactCandidates, pricesByIdentity, bindingCounts);

    MasterDecision masterDecision = establishedMaster(
        canonicalCandidates, allIdentities, canonicalKey);
    if (masterDecision.conflictMessage() != null) {
      return unselectedResult(
          factorRow,
          normalizedBusinessUnit,
          normalizedMonth,
          canonicalKey,
          PriceLinkedType2FactorIdentityResolutionStatus.CANONICAL_MASTER_CONFLICT,
          null,
          null,
          snapshots,
          List.of(),
          masterDecision.conflictMessage());
    }

    FactorIdentity selected = masterDecision.identity();
    if (selected == null) {
      selected = selectionCandidates.stream()
          .sorted(
              Comparator
                  .<FactorIdentity>comparingLong(
                      identity -> bindingCounts.getOrDefault(identity.getId(), 0L))
                  .reversed()
                  .thenComparing(FactorIdentity::getId))
          .findFirst()
          .orElseThrow();
    }
    Long canonicalMasterId = selected.getCanonicalFactorIdentityId() == null
        ? selected.getId()
        : selected.getCanonicalFactorIdentityId();
    Set<BigDecimal> systemPrices = distinctPrices(null, pricesByIdentity);
    Set<BigDecimal> distinctPrices = distinctPrices(
        factorRow.getPrice(), pricesByIdentity);
    if (distinctPrices.size() > 1) {
      boolean overwriteAllowed = systemPrices.size() <= 1;
      return conflictResult(
          factorRow,
          normalizedBusinessUnit,
          normalizedMonth,
          canonicalKey,
          selected,
          canonicalMasterId,
          overwriteAllowed,
          snapshots,
          metadataUpdates(canonicalCandidates, canonicalKey, canonicalMasterId),
          "统一因素 " + canonicalKey + " 在 " + normalizedMonth
              + " 的系统候选价与本次 Excel 价格不一致："
              + distinctPrices.stream().map(BigDecimal::toPlainString).toList()
              + (overwriteAllowed
                  ? "；可在用户明确确认后覆盖"
                  : "；候选内部已不一致，禁止自动覆盖"));
    }
    BigDecimal selectedPrice = onePrice(pricesByIdentity.get(selected.getId()));
    PriceLinkedType2FactorIdentityResolutionStatus resolvedStatus = exactMatched
        ? PriceLinkedType2FactorIdentityResolutionStatus.EXACT_MATCH
        : PriceLinkedType2FactorIdentityResolutionStatus.CANONICAL_MATCH;
    return selectedResult(
        factorRow,
        normalizedBusinessUnit,
        normalizedMonth,
        canonicalKey,
        resolvedStatus,
        selected,
        selectedPrice,
        snapshots,
        metadataUpdates(canonicalCandidates, canonicalKey, canonicalMasterId),
        exactMatched
            ? "完整名称、简称和取价来源命中已有身份"
            : "完整名称未命中，已按统一因素键复用已有身份");
  }

  private String validate(
      PriceLinkedType2FactorRow row,
      String businessUnitType,
      String priceMonth,
      String canonicalKey) {
    if (row == null) {
      return "影响因素行不能为空";
    }
    if (!StringUtils.hasText(businessUnitType)) {
      return "businessUnitType 不能为空";
    }
    if (!StringUtils.hasText(priceMonth)) {
      return "priceMonth 不能为空";
    }
    if (!priceMonth.matches("\\d{4}-\\d{2}")) {
      return "priceMonth 必须为 YYYY-MM";
    }
    if (!StringUtils.hasText(row.getFactorName())) {
      return "影响因素完整名称不能为空";
    }
    if (!StringUtils.hasText(canonicalKey)) {
      return "简称和取价来源不能为空";
    }
    if (row.getPrice() == null) {
      return "影响因素价格不能为空";
    }
    return null;
  }

  private boolean sameBusinessUnit(
      FactorIdentity identity, String normalizedBusinessUnit) {
    return normalizedBusinessUnit.equals(
        textNormalizer.normalize(identity.getBusinessUnitType()));
  }

  private boolean exactMatch(
      FactorIdentity identity, PriceLinkedType2FactorRow factorRow) {
    return textNormalizer.normalize(identity.getFactorName())
            .equals(textNormalizer.normalize(factorRow.getFactorName()))
        && textNormalizer.normalize(identity.getShortName())
            .equals(textNormalizer.normalize(factorRow.getShortName()))
        && textNormalizer.normalize(identity.getPriceSource())
            .equals(textNormalizer.normalize(factorRow.getPriceSource()));
  }

  private String canonicalKey(FactorIdentity identity) {
    if (StringUtils.hasText(identity.getCanonicalFactorKey())) {
      return canonicalKeyService.normalizeExistingKey(identity.getCanonicalFactorKey());
    }
    return canonicalKeyService.build(identity.getPriceSource(), identity.getShortName());
  }

  private Map<Long, List<BigDecimal>> pricesByIdentity(
      List<FactorMonthlyPrice> monthlyPrices, List<Long> candidateIds) {
    Set<Long> usableIds = new LinkedHashSet<>(candidateIds);
    Map<Long, List<BigDecimal>> prices = new LinkedHashMap<>();
    for (FactorMonthlyPrice monthlyPrice : monthlyPrices) {
      if (monthlyPrice == null
          || !usableIds.contains(monthlyPrice.getFactorIdentityId())
          || monthlyPrice.getPrice() == null) {
        continue;
      }
      prices.computeIfAbsent(monthlyPrice.getFactorIdentityId(), ignored -> new ArrayList<>())
          .add(normalizePrice(monthlyPrice.getPrice()));
    }
    return prices;
  }

  private Set<BigDecimal> distinctPrices(
      BigDecimal incomingPrice, Map<Long, List<BigDecimal>> pricesByIdentity) {
    Map<String, BigDecimal> distinct = new LinkedHashMap<>();
    if (incomingPrice != null) {
      BigDecimal normalized = normalizePrice(incomingPrice);
      distinct.put(normalized.toPlainString(), normalized);
    }
    pricesByIdentity.values().stream()
        .flatMap(List::stream)
        .map(this::normalizePrice)
        .forEach(price -> distinct.put(price.toPlainString(), price));
    return new LinkedHashSet<>(distinct.values());
  }

  private MasterDecision establishedMaster(
      List<FactorIdentity> candidates,
      List<FactorIdentity> allIdentities,
      String canonicalKey) {
    Set<Long> masterIds = candidates.stream()
        .map(FactorIdentity::getCanonicalFactorIdentityId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (masterIds.size() > 1) {
      return new MasterDecision(
          null,
          "统一因素 " + canonicalKey + " 已配置多个主身份 " + masterIds + "，必须人工处理");
    }
    if (masterIds.isEmpty()) {
      return new MasterDecision(null, null);
    }
    Long masterId = masterIds.iterator().next();
    Map<Long, FactorIdentity> identitiesById = allIdentities.stream()
        .collect(Collectors.toMap(
            FactorIdentity::getId,
            Function.identity(),
            (left, right) -> left,
            LinkedHashMap::new));
    FactorIdentity master = identitiesById.get(masterId);
    if (master == null || !canonicalKey.equals(canonicalKey(master))) {
      return new MasterDecision(
          null,
          "统一因素 " + canonicalKey + " 指向的主身份 " + masterId
              + " 不存在或统一因素键不一致");
    }
    return new MasterDecision(master, null);
  }

  private List<PriceLinkedType2FactorIdentityCandidate> candidateSnapshots(
      List<FactorIdentity> candidates,
      List<FactorIdentity> exactCandidates,
      Map<Long, List<BigDecimal>> pricesByIdentity,
      Map<Long, Long> bindingCounts) {
    Set<Long> exactIds = exactCandidates.stream()
        .map(FactorIdentity::getId)
        .collect(Collectors.toSet());
    return candidates.stream()
        .sorted(Comparator.comparing(FactorIdentity::getId))
        .map(identity -> new PriceLinkedType2FactorIdentityCandidate(
            identity.getId(),
            identity.getCanonicalFactorIdentityId(),
            identity.getFactorName(),
            identity.getShortName(),
            identity.getPriceSource(),
            canonicalKey(identity),
            onePrice(pricesByIdentity.get(identity.getId())),
            bindingCounts.getOrDefault(identity.getId(), 0L),
            exactIds.contains(identity.getId())))
        .toList();
  }

  private List<Long> metadataUpdates(
      List<FactorIdentity> candidates,
      String canonicalKey,
      Long canonicalMasterId) {
    return candidates.stream()
        .filter(identity ->
            !canonicalKey.equals(canonicalKeyService.normalizeExistingKey(
                identity.getCanonicalFactorKey()))
                || (canonicalMasterId != null
                    && !canonicalMasterId.equals(identity.getCanonicalFactorIdentityId())))
        .map(FactorIdentity::getId)
        .toList();
  }

  private BigDecimal onePrice(List<BigDecimal> prices) {
    if (prices == null || prices.isEmpty()) {
      return null;
    }
    return prices.stream()
        .filter(Objects::nonNull)
        .min(BigDecimal::compareTo)
        .orElse(null);
  }

  private BigDecimal normalizePrice(BigDecimal price) {
    return price.stripTrailingZeros();
  }

  private PriceLinkedType2FactorIdentityResolution selectedResult(
      PriceLinkedType2FactorRow factorRow,
      String businessUnitType,
      String priceMonth,
      String canonicalKey,
      PriceLinkedType2FactorIdentityResolutionStatus status,
      FactorIdentity selected,
      BigDecimal selectedPrice,
      List<PriceLinkedType2FactorIdentityCandidate> candidates,
      List<Long> metadataUpdates,
      String message) {
    Long selectedId = selected == null ? null : selected.getId();
    Long canonicalMasterId = selected == null
        ? null
        : selected.getCanonicalFactorIdentityId() == null
            ? selected.getId()
            : selected.getCanonicalFactorIdentityId();
    return new PriceLinkedType2FactorIdentityResolution(
        factorRow,
        businessUnitType,
        priceMonth,
        canonicalKey,
        status,
        selectedId,
        canonicalMasterId,
        selectedPrice,
        selectedId,
        canonicalMasterId,
        false,
        metadataUpdates,
        candidates,
        message);
  }

  private PriceLinkedType2FactorIdentityResolution conflictResult(
      PriceLinkedType2FactorRow factorRow,
      String businessUnitType,
      String priceMonth,
      String canonicalKey,
      FactorIdentity recommended,
      Long canonicalMasterId,
      boolean overwriteAllowed,
      List<PriceLinkedType2FactorIdentityCandidate> candidates,
      List<Long> metadataUpdates,
      String message) {
    return new PriceLinkedType2FactorIdentityResolution(
        factorRow,
        businessUnitType,
        priceMonth,
        canonicalKey,
        PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT,
        null,
        null,
        null,
        recommended == null ? null : recommended.getId(),
        recommended == null ? null : canonicalMasterId,
        overwriteAllowed,
        metadataUpdates,
        candidates,
        message);
  }

  private PriceLinkedType2FactorIdentityResolution unselectedResult(
      PriceLinkedType2FactorRow factorRow,
      String businessUnitType,
      String priceMonth,
      String canonicalKey,
      PriceLinkedType2FactorIdentityResolutionStatus status,
      Long selectedId,
      Long canonicalMasterId,
      List<PriceLinkedType2FactorIdentityCandidate> candidates,
      List<Long> metadataUpdates,
      String message) {
    return new PriceLinkedType2FactorIdentityResolution(
        factorRow,
        businessUnitType,
        priceMonth,
        canonicalKey,
        status,
        selectedId,
        canonicalMasterId,
        null,
        null,
        null,
        false,
        metadataUpdates,
        candidates,
        message);
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private <K, V> Map<K, V> safeMap(Map<K, V> values) {
    return values == null ? Map.of() : values;
  }

  private record MasterDecision(FactorIdentity identity, String conflictMessage) {
  }
}

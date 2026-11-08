package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.service.ingest.ResolvedCustomerKey;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Monthly customer/product membership, separate from the choice of representative source. */
public final class MonthlyRepriceObjectScope {
  private MonthlyRepriceObjectScope() {}

  public record Identity(String businessUnitType, YearMonth month, String productCode,
      ResolvedCustomerKey.Source customerSource, String customerValue) {
    public Identity {
      MonthlyRepriceContract.unit(businessUnitType);
      Objects.requireNonNull(month, "目标月份必填");
      if (!month.toString().matches("[0-9]{4}-(0[1-9]|1[0-2])"))
        throw new IllegalArgumentException("目标月份格式无效");
      productCode = requiredText(productCode, "产品料号", 64);
      customerValue = requiredText(customerValue, "客户身份", 255);
      if (customerSource == null || customerSource == ResolvedCustomerKey.Source.OA_NUMBER_FALLBACK)
        throw new IllegalArgumentException("客户身份缺失，不能形成跨 OA 月度对象");
    }

    /** Digest is a lookup aid. Persist and compare the complete identity before accepting a hit. */
    public String key() {
      return MonthlyRepriceContract.hash(List.of("CUSTOMER_PRODUCT_V1", businessUnitType,
          month.toString(), productCode, customerSource.name(), customerValue));
    }
  }

  public record Candidate(MonthlyRepriceContract.ObjectIdentity source, long costVersionId,
      String productCode, ResolvedCustomerKey customer, LocalDateTime completedAt) {
    public Candidate {
      Objects.requireNonNull(source, "原报价产品身份必填");
      if (costVersionId <= 0) throw new IllegalArgumentException("成功成本版本必填");
      productCode = requiredText(productCode, "产品料号", 64);
      Objects.requireNonNull(customer, "必须经过权威客户解析");
      Objects.requireNonNull(completedAt, "成功核算完成时间必填");
    }
  }

  public record Group(Identity identity, List<Candidate> sources) {
    public Group {
      Objects.requireNonNull(identity);
      sources = List.copyOf(sources);
      if (sources.isEmpty()) throw new IllegalArgumentException("月度对象必须保留原始来源");
      var versions = new HashSet<Long>();
      var objects = new HashSet<MonthlyRepriceContract.ObjectIdentity>();
      for (var source : sources) {
        if (!identity.businessUnitType().equals(source.source().businessUnitType())
            || !identity.equals(MonthlyRepriceObjectScope.identity(identity.businessUnitType(), identity.month(), source))
            || !versions.add(source.costVersionId()) || !objects.add(source.source()))
          throw new IllegalArgumentException("归并来源身份不一致或重复");
      }
    }

    /** R-01: newest successful completion across OA sources; version id breaks equal timestamps. */
    public Candidate selected() {
      return sources.stream().max(Comparator.comparing(Candidate::completedAt)
          .thenComparingLong(Candidate::costVersionId)).orElseThrow();
    }
  }

  public record Unresolved(Candidate source, String reason) {}
  public record Discovery(int sourceCount, List<Group> groups, List<Unresolved> unresolved) {
    public Discovery {
      groups = List.copyOf(groups);
      unresolved = List.copyOf(unresolved);
      if (sourceCount != groups.stream().mapToInt(group -> group.sources().size()).sum() + unresolved.size())
        throw new IllegalArgumentException("原始来源存在遗漏或重复");
    }
  }

  /** Inputs have already been collapsed to the latest success within each original OA product line. */
  public static Discovery discover(String unit, YearMonth month, List<Candidate> candidates) {
    MonthlyRepriceContract.unit(unit);
    Objects.requireNonNull(month);
    candidates = List.copyOf(candidates);
    var groups = new LinkedHashMap<Identity, List<Candidate>>();
    var unresolved = new ArrayList<Unresolved>();
    var sourceObjects = new HashSet<MonthlyRepriceContract.ObjectIdentity>();
    var sourceVersions = new HashSet<Long>();
    for (var candidate : candidates) {
      if (!unit.equals(candidate.source().businessUnitType()))
        throw new IllegalArgumentException("发现了其他业务单元的报价来源");
      if (!sourceObjects.add(candidate.source()) || !sourceVersions.add(candidate.costVersionId()))
        throw new IllegalArgumentException("同一 OA 产品行只能保留一份最近成功版本");
      if (candidate.customer().source() == ResolvedCustomerKey.Source.OA_NUMBER_FALLBACK) {
        unresolved.add(new Unresolved(candidate, "客户身份缺失，请完善该原报价客户后重试"));
        continue;
      }
      groups.computeIfAbsent(identity(unit, month, candidate), ignored -> new ArrayList<>()).add(candidate);
    }
    return new Discovery(candidates.size(), groups.entrySet().stream()
        .map(entry -> new Group(entry.getKey(), entry.getValue())).toList(), unresolved);
  }

  private static Identity identity(String unit, YearMonth month, Candidate candidate) {
    return new Identity(unit, month, candidate.productCode(), candidate.customer().source(),
        candidate.customer().value());
  }

  private static String requiredText(String value, String label, int maximumLength) {
    if (value == null || value.isBlank() || value.trim().length() > maximumLength || "/".equals(value.trim()))
      throw new IllegalArgumentException(label + "缺失或超长");
    // Match QuoteBomContextResolver: trim only; never case-fold, parse numbers or infer aliases.
    return value.trim();
  }
}

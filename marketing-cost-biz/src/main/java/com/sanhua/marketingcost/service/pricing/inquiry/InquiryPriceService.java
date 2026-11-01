package com.sanhua.marketingcost.service.pricing.inquiry;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Import orchestration; parsing has no writes and confirmation retains the complete original. */
@Service
public final class InquiryPriceService {
  public record Published(long batchId, int rows, InquiryPriceRefreshService.Result refresh) {}

  public record Current(
      InquiryPriceRepository.Source source,
      String priceMonth,
      String status,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          BigDecimal price,
      String message,
      Map<String, Object> calculation) {}

  public record Page<T>(List<T> records, long total) {}

  private final InquiryPriceRepository repository;
  private final InquiryWorkbookParser parser;
  private final InquiryPriceRefreshService refresh;
  private final TransactionTemplate transaction;

  public InquiryPriceService(
      InquiryPriceRepository repository,
      InquiryWorkbookParser parser,
      InquiryPriceRefreshService refresh,
      PlatformTransactionManager manager) {
    this.repository = repository;
    this.parser = parser;
    this.refresh = refresh;
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
    transaction.setTimeout(90);
  }

  public InquiryWorkbookParser.Parsed preview(
      String unit, String month, String name, byte[] bytes) {
    scope(unit, month);
    return parser.parse(bytes, name, repository.catalog(unit));
  }

  public Published publish(
      String unit, String month, String actor, String requestId, String name, byte[] bytes) {
    scope(unit, month);
    if (actor == null || actor.isBlank() || actor.length() > 64)
      throw new IllegalArgumentException("缺少有效导入人");
    if (requestId == null || !UUID.fromString(requestId).toString().equalsIgnoreCase(requestId))
      throw new IllegalArgumentException("无效导入请求编号");
    String file = Objects.toString(name, "询价.xlsx").replace('\\', '/');
    file = file.substring(file.lastIndexOf('/') + 1);
    if (file.isBlank() || file.length() > 255) throw new IllegalArgumentException("文件名无效或过长");
    var parsed = preview(unit, month, file, bytes);
    String filename = file, hash = hash(bytes);
    for (int attempt = 0; ; attempt++) {
      try {
        return Objects.requireNonNull(
            transaction.execute(
                tx -> {
                  repository.lock(unit);
                  var previous = repository.replay(unit, requestId);
                  if (previous != null) {
                    if (!month.equals(previous.get("price_month"))
                        || !hash.equals(previous.get("file_hash"))
                        || !actor.equals(previous.get("created_by")))
                      throw new IllegalArgumentException("导入请求已用于不同月份、文件或账号");
                    return new Published(
                        ((Number) previous.get("id")).longValue(),
                        ((Number) previous.get("row_count")).intValue(),
                        null);
                  }
                  long batch =
                      repository.insertBatch(
                          unit, month, actor, requestId, filename, bytes, hash, parsed.rows());
                  return new Published(
                      batch,
                      parsed.rows().size(),
                      refresh.refreshInTransaction(unit, month, "IMPORT", actor));
                }));
      } catch (org.springframework.dao.TransientDataAccessException conflict) {
        if (attempt >= 2) throw conflict;
      }
    }
  }

  public Page<Current> current(String unit, String month, String search, int page, int size) {
    scope(unit, month);
    pagination(page, size);
    String key = InquiryNames.normalize(search);
    var sources =
        repository.current(unit, month, false).stream()
            .filter(
                source ->
                    key.isBlank()
                        || InquiryNames.normalize(source.rule().materialCode()).contains(key)
                        || InquiryNames.normalize(source.rule().specification()).contains(key)
                        || InquiryNames.normalize(source.rule().materialName()).contains(key))
            .toList();
    int from = Math.min(sources.size(), (page - 1) * size);
    var selected = sources.subList(from, Math.min(sources.size(), from + size));
    var results =
        repository.effective(
            unit, month, selected.stream().map(InquiryPriceRepository.Source::id).toList());
    var current = new ArrayList<Current>();
    for (var source : selected) {
      var result = results.get(source.id());
      if (result == null && source.rule().priceType().equals("固定价"))
        current.add(
            new Current(source, month, "READY", source.rule().originalPrice(), null, Map.of()));
      else if (result == null)
        current.add(new Current(source, month, "WAITING_BASE", null, "等待发布该月商用基价后刷新", Map.of()));
      else
        current.add(
            new Current(
                source,
                month,
                result.get("status").toString(),
                (BigDecimal) result.get("unit_price"),
                (String) result.get("message"),
                repository.calculation(result)));
    }
    return new Page<>(List.copyOf(current), sources.size());
  }

  public Page<Map<String, Object>> history(String unit, int page, int size) {
    unit(unit);
    pagination(page, size);
    return new Page<>(repository.history(unit, page, size), repository.historyCount(unit));
  }

  public List<InquiryPriceRepository.Source> batch(String unit, long id) {
    unit(unit);
    return repository.batch(unit, id);
  }

  public InquiryPriceRepository.Original original(String unit, long id) {
    unit(unit);
    return repository.original(unit, id);
  }

  static void scope(String unit, String month) {
    unit(unit);
    if (month == null
        || !month.matches("[0-9]{4}-[0-9]{2}")
        || !YearMonth.parse(month).toString().equals(month))
      throw new IllegalArgumentException("请选择有效价格月份");
  }

  private static void unit(String unit) {
    if (!Set.of("COMMERCIAL", "HOUSEHOLD").contains(Objects.toString(unit, "")))
      throw new IllegalArgumentException("缺少有效业务单元");
  }

  private static void pagination(int page, int size) {
    if (page < 1 || page > 1_000_000 || size < 1 || size > 100)
      throw new IllegalArgumentException("分页参数无效");
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}

package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceProblem;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/** Durable, bounded recovery of an entire customer attempt after its transaction has rolled back. */
public final class MonthlyRepriceFailures {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_AUTOMATIC_RETRIES = 3;

  private MonthlyRepriceFailures() {}

  public record Failure(String code, boolean retryable, int automaticRetries,
      boolean exhausted, String message,
      java.util.List<com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue> issues) {
    public Failure {issues=java.util.List.copyOf(issues);}
  }
  public record Decision(Failure failure, int delaySeconds) {
    public boolean retry() { return delaySeconds > 0; }
    public String json() {
      try { return JSON.writeValueAsString(failure); }
      catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }
  }

  public static Decision decide(Throwable failure, String previousSummary, Integer maximumRetries) {
    Objects.requireNonNull(failure);
    int completed;
    try { completed = automaticRetries(previousSummary); }
    catch (IllegalStateException invalid) {
      return new Decision(new Failure("RETRY_STATE_INVALID", false, 0, false,
          "调价重试记录无效，请联系管理员核对；" + message(failure, 970),issues(failure)), 0);
    }
    int limit = maximumRetries == null ? MAX_AUTOMATIC_RETRIES
        : Math.max(0, Math.min(MAX_AUTOMATIC_RETRIES, maximumRetries));
    String code = classify(failure);
    boolean retryable = switch (code) {
      case "SHARED_GROUP_BUSY", "GROUP_LEASE_LOST", "TRANSIENT_DATABASE", "PRICE_REPLAN" -> true;
      default -> false;
    };
    boolean retry = retryable && completed < limit;
    int count = completed + (retry ? 1 : 0);
    return new Decision(new Failure(code, retryable, count, retryable && !retry, message(failure, 1000),issues(failure)),
        retry ? 1 << count : 0);
  }

  private static java.util.List<com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue> issues(Throwable failure) {
    var visited=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
    for(var cause=failure;cause!=null && visited.add(cause);cause=cause.getCause())
      if(cause instanceof MonthlyRepriceInputNotReady input)return input.issues();
    return java.util.List.of();
  }

  private static String message(Throwable failure, int limit) {
    String value = failure.getMessage();
    if (value == null || value.isBlank()) value = "调价执行失败，请检查资料后重试";
    int end = Math.min(limit, value.length());
    if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
    return value.substring(0, end);
  }

  private static int automaticRetries(String summary) {
    if (summary == null || summary.isBlank()) return 0;
    try {
      var document = JSON.readTree(summary);
      if (document == null || !document.isObject()) throw new IllegalStateException("调价自动重试记录格式无效");
      var value = document.path("failure").path("automaticRetries");
      if (value.isMissingNode()) return 0;
      if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0
          || value.intValue() > MAX_AUTOMATIC_RETRIES)
        throw new IllegalStateException("调价自动重试记录无效");
      return value.intValue();
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("调价自动重试记录不可读取", invalid);
    }
  }

  private static String classify(Throwable failure) {
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (var cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof MonthlyRepriceSharedNumbers.GroupBusy) return "SHARED_GROUP_BUSY";
      if (cause instanceof MonthlyRepriceGroupLeases.LeaseLost) return "GROUP_LEASE_LOST";
      if (cause instanceof MonthlyRepriceInputNotReady || cause instanceof EffectiveTechnicalDataException)
        return "INPUT_NOT_READY";
      if (cause instanceof MonthlyPriceProblem price) return switch (price.reason()) {
        case REPLAN_REQUIRED, TRANSIENT_CONCURRENCY_FAILURE, COMMIT_OUTCOME_UNKNOWN -> "PRICE_REPLAN";
        case MISSING, WAITING, INVALID_INPUT, EVIDENCE_UNAVAILABLE -> "INPUT_NOT_READY";
        case INVALID_PLAN, PERSISTENCE_INCOMPLETE -> "REQUIRES_ATTENTION";
      };
      if (cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
          || cause instanceof SQLTransientException || cause instanceof SQLRecoverableException)
        return "TRANSIENT_DATABASE";
      if (cause instanceof SQLException sql && (sql.getSQLState() != null
          && (sql.getSQLState().startsWith("08") || sql.getSQLState().equals("40001"))
          || sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213))
        return "TRANSIENT_DATABASE";
    }
    return "REQUIRES_ATTENTION";
  }
}

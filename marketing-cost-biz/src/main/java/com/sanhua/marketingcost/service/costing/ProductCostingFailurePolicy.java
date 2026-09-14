package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowRetryException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLTransientException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将异常统一分类为业务资料缺口或系统失败；不执行数据库写入。 */
@Component
public class ProductCostingFailurePolicy {
  public Failure classify(String stage, RuntimeException exception) {
    if (exception instanceof EffectiveTechnicalDataException technical) {
      return new Failure("WAIT_TECH_DATA", "TECHNICAL_DATA", technical.errorCode(),
          message(technical), Math.max(1, technical.modules().size()), false);
    }
    // 网络、数据库等暂时故障不应因错误文案含“价格”等词而被当成业务缺口。
    boolean retryable = isRetryableSystemFailure(exception);
    if (!retryable) {
      if ("QUOTE_BOM".equals(stage) && isBomBusinessGap(exception)) {
        return new Failure("WAIT_BOM", stage, "BOM_MISSING", message(exception), 1, false);
      }
      if ("PRICE_TYPE_CONFIRMATION".equals(stage) && isPriceTypeGap(exception)) {
        return new Failure("WAIT_PRICE_TYPE", stage, "PRICE_TYPE_MISSING", message(exception), 1, false);
      }
      if ("PRICE_PREPARE".equals(stage) && isPriceBusinessGap(exception)) {
        return new Failure("WAIT_PRICE", stage, priceErrorCode(exception), message(exception), 1, false);
      }
    }
    return new Failure("SYSTEM_FAILED", stage, systemErrorCode(stage), message(exception), 0, retryable);
  }

  public record Failure(String status, String step, String errorCode,
      String message, int gapCount, boolean retryable) {
    public boolean blocked() { return !"SYSTEM_FAILED".equals(status); }
  }

  private boolean isPriceTypeGap(RuntimeException exception) {
    String text = message(exception);
    return text.contains("价格类型") || text.contains("无法识别");
  }

  private boolean isBomBusinessGap(RuntimeException exception) {
    if (!(exception instanceof QuoteIngestException)) {
      return false;
    }
    String text = message(exception);
    return text.contains("BOM 准备结果")
        || text.contains("正式 BOM 准备结果为空")
        || text.contains("完整 BOM 准备结果为空")
        || text.contains("没有可用于核算的 BOM")
        || text.contains("补录任务");
  }

  private boolean isPriceBusinessGap(RuntimeException exception) {
    String text = message(exception);
    return (exception instanceof QuoteIngestException
            || exception instanceof IllegalArgumentException)
        && (text.contains("价格")
            || text.contains("基准")
            || text.contains("供应商")
            || text.contains("供货"));
  }

  private String priceErrorCode(RuntimeException exception) {
    String text = message(exception);
    return text.contains("财务") && text.contains("基准")
        ? "FINANCE_BASE_PRICE_MISSING"
        : "PRICE_MISSING";
  }

  private String systemErrorCode(String stage) {
    return switch (stage) {
      case "QUOTE_BOM" -> "BOM_SYSTEM_ERROR";
      case "PRICE_TYPE_CONFIRMATION" -> "PRICE_TYPE_SYSTEM_ERROR";
      case "PRICE_PREPARE" -> "PRICE_PREPARE_SYSTEM_ERROR";
      case "INPUT_CHECK" -> "INPUT_CHECK_SYSTEM_ERROR";
      default -> "COST_RUN_SYSTEM_ERROR";
    };
  }

  private boolean isRetryableSystemFailure(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof TransientDataAccessException
          || current instanceof SQLTransientException
          || current instanceof SocketTimeoutException
          || current instanceof ConnectException
          || current instanceof ElectronicDrawingWorkflowRetryException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private String message(Throwable throwable) {
    return firstText(throwable == null ? null : throwable.getMessage(), "产品核算失败");
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

}

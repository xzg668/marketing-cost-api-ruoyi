package com.sanhua.marketingcost.service.collaboration;

import java.util.Arrays;
import java.util.List;

/** 协作持久化使用的稳定数据库编码；状态迁移规则由 QCBP-03 领域层实现。 */
public final class CollaborationCodes {

  private CollaborationCodes() {}

  public interface DatabaseCode {
    String code();
  }

  public enum MasterStatus implements DatabaseCode {
    WAIT_TECH,
    WAIT_FINANCE,
    PARTIAL_RETURN,
    PUBLISHING,
    PUBLISH_FAILED,
    READY_FOR_COSTING,
    COMPLETED,
    CANCELLED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ProductTaskStatus implements DatabaseCode {
    WAIT_TECH,
    BOM_IN_PROGRESS,
    PACKAGE_IN_PROGRESS,
    PRICE_IN_PROGRESS,
    TECH_VALIDATION_FAILED,
    TECH_SUBMITTED,
    WAIT_FINANCE,
    RETURNED_TO_TECH,
    APPROVED_PUBLISHING,
    PUBLISH_OR_REPRICE_FAILED,
    READY_FOR_COSTING,
    COSTING,
    COMPLETED,
    CANCELLED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum PriceType implements DatabaseCode {
    FIXED_PURCHASE,
    LINKED,
    RANGE,
    SETTLE_FIXED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ProductForm implements DatabaseCode {
    NORMAL,
    BARE,
    UNKNOWN;

    @Override
    public String code() {
      return name();
    }
  }

  public enum PrimaryScope implements DatabaseCode {
    FULL_BOM,
    BARE_PACKAGE,
    PRICE_ONLY;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ValidationStatus implements DatabaseCode {
    NOT_CHECKED,
    PASSED,
    FAILED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum QuoteLinkType implements DatabaseCode {
    OWNER,
    ACTIVE_TASK_LINK,
    APPROVED_RESULT_REUSE;

    @Override
    public String code() {
      return name();
    }
  }

  public enum QuoteLinkStatus implements DatabaseCode {
    WAIT_SOURCE,
    RECHECKING,
    READY,
    FAILED,
    CANCELLED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum GapCategory implements DatabaseCode {
    BOM,
    PACKAGE,
    PRICE;

    @Override
    public String code() {
      return name();
    }
  }

  public enum MaterialRole implements DatabaseCode {
    NORMAL,
    RAW,
    SCRAP,
    PACKAGE;

    @Override
    public String code() {
      return name();
    }
  }

  public enum GapStatus implements DatabaseCode {
    OPEN,
    DRAFT_READY,
    RESOLVED,
    WAIVED,
    OBSOLETE;

    @Override
    public String code() {
      return name();
    }
  }

  public enum DraftStatus implements DatabaseCode {
    EDITING,
    VALIDATED,
    SUBMITTED,
    APPROVED,
    REJECTED,
    PUBLISHED,
    VOIDED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum SourceMode implements DatabaseCode {
    COPY,
    DIRECT;

    @Override
    public String code() {
      return name();
    }
  }

  public enum DraftFieldSection implements DatabaseCode {
    COMMON,
    FORMULA,
    VARIABLE,
    RANGE_ROW;

    @Override
    public String code() {
      return name();
    }
  }

  public enum DraftFieldValueType implements DatabaseCode {
    TEXT,
    DECIMAL,
    DATE,
    BOOLEAN,
    JSON;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ReviewStatus implements DatabaseCode {
    PENDING,
    PARTIAL,
    REJECTED,
    APPROVED,
    PUBLISHING,
    EFFECTIVE,
    FAILED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ReviewItemType implements DatabaseCode {
    PRODUCT,
    BOM,
    PACKAGE,
    PRICE_DRAFT;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ReviewDecision implements DatabaseCode {
    PENDING,
    PASSED,
    REJECTED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ResultType implements DatabaseCode {
    FULL_BOM,
    BARE_PACKAGE;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ResultSourceObjectType implements DatabaseCode {
    SUPPLEMENT_VERSION,
    PACKAGE_REFERENCE;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ResultSourceSystem implements DatabaseCode {
    ELECTRONIC_DRAWING,
    QUOTE_PACKAGE;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ResultStatus implements DatabaseCode {
    ACTIVE,
    EXPIRED,
    REVOKED,
    INVALIDATED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ExternalTaskKind implements DatabaseCode {
    TECH,
    TECH_REWORK,
    FINANCE,
    NOTICE;

    @Override
    public String code() {
      return name();
    }
  }

  public enum ExternalStatus implements DatabaseCode {
    NOT_CREATED,
    HOLD,
    OPEN,
    DONE,
    CLOSED,
    FAILED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum SendPolicy implements DatabaseCode {
    AUTO,
    HOLD;

    @Override
    public String code() {
      return name();
    }
  }

  public enum SendStatus implements DatabaseCode {
    HOLD,
    PENDING,
    SENDING,
    SENT,
    FAILED,
    DEAD;

    @Override
    public String code() {
      return name();
    }
  }

  public enum SignatureStatus implements DatabaseCode {
    NOT_CHECKED,
    PASSED,
    FAILED;

    @Override
    public String code() {
      return name();
    }
  }

  public enum InboxProcessStatus implements DatabaseCode {
    RECEIVED,
    PROCESSED,
    REJECTED,
    FAILED;

    @Override
    public String code() {
      return name();
    }
  }

  public static <E extends Enum<E> & DatabaseCode> List<String> codes(Class<E> type) {
    return Arrays.stream(type.getEnumConstants()).map(DatabaseCode::code).toList();
  }
}

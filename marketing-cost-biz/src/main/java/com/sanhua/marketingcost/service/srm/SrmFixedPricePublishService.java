package com.sanhua.marketingcost.service.srm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 将 EasyData 落地的 SRM 全量固定价快照发布到系统正式价格表。 */
@Service
public class SrmFixedPricePublishService {

  private static final String COMPANY_210 = "浙江三花商用制冷有限公司";
  private static final String COMPANY_220 = "浙江三花板换科技有限公司";

  private static final String TARGET_ROW_CONDITION = """
      TRIM(r.`source`) LIKE '固定价%'
      AND TRIM(r.company) IN ('浙江三花商用制冷有限公司', '浙江三花板换科技有限公司')
      """;

  private static final String VALID_ROW_CONDITION = """
      NULLIF(TRIM(r.material_code), '') IS NOT NULL
      AND (NULLIF(TRIM(r.sup_code), '') IS NOT NULL
           OR NULLIF(TRIM(r.sup_name), '') IS NOT NULL)
      AND r.price IS NOT NULL
      AND TRIM(r.eff_date) REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
      AND DATE_FORMAT(STR_TO_DATE(TRIM(r.eff_date), '%Y-%m-%d'), '%Y-%m-%d') = TRIM(r.eff_date)
      AND (
          r.exp_date IS NULL
          OR TRIM(r.exp_date) = ''
          OR (
              TRIM(r.exp_date) REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
              AND DATE_FORMAT(STR_TO_DATE(TRIM(r.exp_date), '%Y-%m-%d'), '%Y-%m-%d') = TRIM(r.exp_date)
              AND STR_TO_DATE(TRIM(r.exp_date), '%Y-%m-%d')
                  >= STR_TO_DATE(TRIM(r.eff_date), '%Y-%m-%d')
          )
      )
      """;

  private static final String SUMMARY_SQL = ("""
      SELECT
          COUNT(*) AS raw_count,
          COALESCE(SUM(CASE WHEN %s THEN 1 ELSE 0 END), 0) AS valid_count,
          COALESCE(SUM(CASE WHEN (%s) AND TRIM(r.company) = '%s' THEN 1 ELSE 0 END), 0)
              AS org_210_count,
          COALESCE(SUM(CASE WHEN (%s) AND TRIM(r.company) = '%s' THEN 1 ELSE 0 END), 0)
              AS org_220_count,
          MAX(r.synced_at) AS last_synced_at,
          TIMESTAMPDIFF(SECOND, MAX(r.synced_at), NOW()) AS quiet_seconds
      FROM lp_price_fixed_item_srm_raw r
      WHERE r.dt = ?
        AND %s
      """).formatted(
          VALID_ROW_CONDITION,
          VALID_ROW_CONDITION,
          COMPANY_210,
          VALID_ROW_CONDITION,
          COMPANY_220,
          TARGET_ROW_CONDITION);

  private static final String PREVIOUS_BATCH_SQL = """
      SELECT MAX(r.dt)
      FROM lp_price_fixed_item_srm_raw r
      WHERE r.dt < ?
        AND %s
      """.formatted(TARGET_ROW_CONDITION);

  private static final String REPLACEABLE_FIXED_PRICE_CONDITION = """
      source_kind = 'PUBLIC'
      AND source_type IN ('PURCHASE', 'PURCHASE_FIXED')
      AND COALESCE(NULLIF(TRIM(business_unit_type), ''), 'COMMERCIAL') = 'COMMERCIAL'
      """;

  private static final String DELETE_FORMAL_SQL = """
      DELETE FROM lp_price_fixed_item
      WHERE %s
      """.formatted(REPLACEABLE_FIXED_PRICE_CONDITION);

  private static final String COUNT_FORMAL_SQL = """
      SELECT COUNT(*)
      FROM lp_price_fixed_item
      WHERE %s
      """.formatted(REPLACEABLE_FIXED_PRICE_CONDITION);

  private static final String PUBLISHED_BATCH_SQL = """
      SELECT
          COUNT(*) AS total_count,
          COALESCE(SUM(CASE
              WHEN source_system = 'SRM' AND source_batch_no = ? THEN 1 ELSE 0 END), 0)
              AS current_batch_count,
          MAX(CASE
              WHEN source_system = 'SRM' AND source_batch_no = ? THEN imported_at ELSE NULL END)
              AS imported_at
      FROM lp_price_fixed_item
      WHERE %s
      """.formatted(REPLACEABLE_FIXED_PRICE_CONDITION);

  private static final String INSERT_FORMAL_SQL = """
      INSERT INTO lp_price_fixed_item (
          org_code,
          source_name,
          supplier_name,
          supplier_code,
          material_name,
          material_code,
          business_unit_type,
          spec_model,
          unit,
          fixed_price,
          tax_included,
          effective_from,
          effective_to,
          created_at,
          updated_at,
          source_type,
          remark,
          pricing_month,
          source_system,
          source_batch_no,
          imported_by,
          imported_at,
          external_row_id,
          current_supplier_name,
          source_kind
      )
      SELECT
          ranked.org_code,
          '供管部',
          ranked.supplier_name,
          ranked.supplier_code,
          ranked.material_name,
          ranked.material_code,
          'COMMERCIAL',
          ranked.spec_model,
          ranked.unit,
          ranked.fixed_price,
          0,
          ranked.effective_from,
          ranked.effective_to,
          NOW(),
          NOW(),
          'PURCHASE_FIXED',
          ranked.source_text,
          '',
          'SRM',
          ranked.dt,
          'SYSTEM_SRM_SYNC',
          NOW(),
          LOWER(SHA2(CONCAT_WS(
              '|',
              ranked.org_code,
              ranked.material_code,
              ranked.supplier_key,
              DATE_FORMAT(ranked.effective_from, '%Y-%m-%d'),
              COALESCE(DATE_FORMAT(ranked.effective_to, '%Y-%m-%d'), 'NO_EXP_DATE')
          ), 256)),
          ranked.supplier_name,
          'PUBLIC'
      FROM (
          SELECT
              CASE TRIM(r.company)
                  WHEN '浙江三花商用制冷有限公司' THEN '210'
                  WHEN '浙江三花板换科技有限公司' THEN '220'
              END AS org_code,
              TRIM(r.material_code) AS material_code,
              NULLIF(TRIM(r.material_name), '') AS material_name,
              NULLIF(TRIM(r.sup_code), '') AS supplier_code,
              NULLIF(TRIM(r.sup_name), '') AS supplier_name,
              CASE
                  WHEN NULLIF(TRIM(r.sup_code), '') IS NOT NULL
                      THEN CONCAT('CODE:', TRIM(r.sup_code))
                  ELSE CONCAT('NAME:', TRIM(r.sup_name))
              END AS supplier_key,
              LEFT(NULLIF(TRIM(r.spec), ''), 64) AS spec_model,
              NULLIF(TRIM(r.unit), '') AS unit,
              r.price AS fixed_price,
              STR_TO_DATE(TRIM(r.eff_date), '%Y-%m-%d') AS effective_from,
              CASE
                  WHEN r.exp_date IS NULL OR TRIM(r.exp_date) = '' THEN NULL
                  ELSE STR_TO_DATE(TRIM(r.exp_date), '%Y-%m-%d')
              END AS effective_to,
              LEFT(TRIM(r.`source`), 512) AS source_text,
              r.dt,
              ROW_NUMBER() OVER (
                  PARTITION BY
                      CASE TRIM(r.company)
                          WHEN '浙江三花商用制冷有限公司' THEN '210'
                          WHEN '浙江三花板换科技有限公司' THEN '220'
                      END,
                      TRIM(r.material_code),
                      CASE
                          WHEN NULLIF(TRIM(r.sup_code), '') IS NOT NULL
                              THEN CONCAT('CODE:', TRIM(r.sup_code))
                          ELSE CONCAT('NAME:', TRIM(r.sup_name))
                      END,
                      STR_TO_DATE(TRIM(r.eff_date), '%Y-%m-%d'),
                      CASE
                          WHEN r.exp_date IS NULL OR TRIM(r.exp_date) = '' THEN NULL
                          ELSE STR_TO_DATE(TRIM(r.exp_date), '%Y-%m-%d')
                      END
                  ORDER BY r.price DESC, r.id DESC
              ) AS row_rank
          FROM lp_price_fixed_item_srm_raw r
          WHERE r.dt = ?
            AND ${TARGET_ROW_CONDITION}
            AND ${VALID_ROW_CONDITION}
      ) ranked
      WHERE ranked.row_rank = 1
      """
      .replace("${TARGET_ROW_CONDITION}", TARGET_ROW_CONDITION)
      .replace("${VALID_ROW_CONDITION}", VALID_ROW_CONDITION);

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final long minimumValidRows;
  private final BigDecimal maximumChangeRatio;
  private final long quietPeriodSeconds;
  private final ReentrantLock publishLock = new ReentrantLock();

  public SrmFixedPricePublishService(
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      @Value("${srm.fixed-price.minimum-valid-rows:70000}") long minimumValidRows,
      @Value("${srm.fixed-price.maximum-change-ratio:0.10}") BigDecimal maximumChangeRatio,
      @Value("${srm.fixed-price.quiet-period-minutes:5}") long quietPeriodMinutes) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.minimumValidRows = minimumValidRows;
    this.maximumChangeRatio = maximumChangeRatio;
    this.quietPeriodSeconds = quietPeriodMinutes * 60;
  }

  public PublishResult publishIfReady(LocalDate batchDate) {
    Objects.requireNonNull(batchDate, "SRM固定价批次日期不能为空");
    if (!publishLock.tryLock()) {
      return PublishResult.skipped(batchDate, "已有SRM固定价发布任务正在执行");
    }
    try {
      BatchSummary current = loadSummary(batchDate);
      PublishResult validation = validateCurrentBatch(current);
      if (validation != null) {
        return validation;
      }

      LocalDate previousBatchDate = loadPreviousBatchDate(batchDate);
      BatchSummary previous = previousBatchDate == null ? null : loadSummary(previousBatchDate);
      validation = validateChangeRatio(current, previous);
      if (validation != null) {
        return validation;
      }

      if (alreadyPublished(current)) {
        return PublishResult.skipped(
            batchDate,
            "该批次已经发布，且原始表在发布后没有重新写入",
            current);
      }

      int insertedRows = replaceFormalFixedPrices(batchDate);
      return PublishResult.published(
          batchDate,
          current,
          insertedRows,
          previousBatchDate);
    } finally {
      publishLock.unlock();
    }
  }

  private BatchSummary loadSummary(LocalDate batchDate) {
    return jdbcTemplate.queryForObject(
        SUMMARY_SQL,
        (resultSet, rowNum) ->
            new BatchSummary(
                batchDate,
                resultSet.getLong("raw_count"),
                resultSet.getLong("valid_count"),
                resultSet.getLong("org_210_count"),
                resultSet.getLong("org_220_count"),
                timestamp(resultSet.getTimestamp("last_synced_at")),
                nullableLong(resultSet, "quiet_seconds")),
        batchDate.toString());
  }

  private LocalDate loadPreviousBatchDate(LocalDate batchDate) {
    String value = jdbcTemplate.queryForObject(
        PREVIOUS_BATCH_SQL, String.class, batchDate.toString());
    return value == null || value.isBlank() ? null : LocalDate.parse(value);
  }

  private PublishResult validateCurrentBatch(BatchSummary current) {
    if (current.validRows() < minimumValidRows) {
      return PublishResult.rejected(
          current.batchDate(),
          "有效数据量不足：实际=" + current.validRows() + "，要求至少=" + minimumValidRows,
          current);
    }
    if (current.org210Rows() == 0 || current.org220Rows() == 0) {
      return PublishResult.rejected(
          current.batchDate(),
          "210或220公司没有有效数据",
          current);
    }
    if (current.lastSyncedAt() == null
        || current.quietSeconds() == null
        || current.quietSeconds() < quietPeriodSeconds) {
      return PublishResult.waiting(
          current.batchDate(),
          "EasyData仍可能写入中，等待连续"
              + (quietPeriodSeconds / 60)
              + "分钟无新增后再发布",
          current);
    }
    return null;
  }

  private PublishResult validateChangeRatio(BatchSummary current, BatchSummary previous) {
    if (previous == null || previous.validRows() == 0) {
      return null;
    }
    String totalError = ratioError("总数", current.validRows(), previous.validRows());
    String org210Error = ratioError("210公司", current.org210Rows(), previous.org210Rows());
    String org220Error = ratioError("220公司", current.org220Rows(), previous.org220Rows());
    String message = firstNonNull(totalError, org210Error, org220Error);
    return message == null
        ? null
        : PublishResult.rejected(current.batchDate(), message, current);
  }

  private String ratioError(String label, long current, long previous) {
    if (previous <= 0) {
      return label + "上一批次没有有效数据，无法核对变化比例";
    }
    BigDecimal ratio = BigDecimal.valueOf(Math.abs(current - previous))
        .divide(BigDecimal.valueOf(previous), 6, RoundingMode.HALF_UP);
    if (ratio.compareTo(maximumChangeRatio) <= 0) {
      return null;
    }
    return label
        + "与上一批次变化"
        + ratio.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
        + "%（当前="
        + current
        + "，上一批次="
        + previous
        + "），超过允许的"
        + maximumChangeRatio.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
        + "%";
  }

  private boolean alreadyPublished(BatchSummary current) {
    PublishedBatch published = jdbcTemplate.queryForObject(
        PUBLISHED_BATCH_SQL,
        (resultSet, rowNum) ->
            new PublishedBatch(
                resultSet.getLong("total_count"),
                resultSet.getLong("current_batch_count"),
                timestamp(resultSet.getTimestamp("imported_at"))),
        current.batchDate().toString(),
        current.batchDate().toString());
    return published != null
        && published.totalRows() > 0
        && published.totalRows() == published.currentBatchRows()
        && published.importedAt() != null
        && current.lastSyncedAt() != null
        && !published.importedAt().isBefore(current.lastSyncedAt());
  }

  private int replaceFormalFixedPrices(LocalDate batchDate) {
    Integer inserted = transactionTemplate.execute(status -> {
      jdbcTemplate.update(DELETE_FORMAL_SQL);
      int insertedRows = jdbcTemplate.update(INSERT_FORMAL_SQL, batchDate.toString());
      Long formalRows = jdbcTemplate.queryForObject(COUNT_FORMAL_SQL, Long.class);
      if (insertedRows <= 0 || formalRows == null || formalRows != insertedRows) {
        throw new IllegalStateException(
            "SRM固定价发布后条数不一致：插入=" + insertedRows + "，正式表=" + formalRows);
      }
      return insertedRows;
    });
    if (inserted == null) {
      throw new IllegalStateException("SRM固定价发布事务没有返回结果");
    }
    return inserted;
  }

  private LocalDateTime timestamp(Timestamp value) {
    return value == null ? null : value.toLocalDateTime();
  }

  private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  private String firstNonNull(String... values) {
    for (String value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  public enum Status {
    PUBLISHED,
    WAITING,
    REJECTED,
    SKIPPED
  }

  public record PublishResult(
      Status status,
      LocalDate batchDate,
      long rawRows,
      long validRows,
      long skippedRows,
      long insertedRows,
      String message) {

    private static PublishResult published(
        LocalDate batchDate,
        BatchSummary summary,
        long insertedRows,
        LocalDate previousBatchDate) {
      String comparison = previousBatchDate == null
          ? "，没有更早的原始批次，本次仅执行最低条数校验"
          : "，对比批次=" + previousBatchDate;
      return new PublishResult(
          Status.PUBLISHED,
          batchDate,
          summary.rawRows(),
          summary.validRows(),
          summary.skippedRows(),
          insertedRows,
          "发布成功" + comparison);
    }

    private static PublishResult waiting(
        LocalDate batchDate, String message, BatchSummary summary) {
      return result(Status.WAITING, batchDate, message, summary);
    }

    private static PublishResult rejected(
        LocalDate batchDate, String message, BatchSummary summary) {
      return result(Status.REJECTED, batchDate, message, summary);
    }

    private static PublishResult skipped(LocalDate batchDate, String message) {
      return new PublishResult(Status.SKIPPED, batchDate, 0, 0, 0, 0, message);
    }

    private static PublishResult skipped(
        LocalDate batchDate, String message, BatchSummary summary) {
      return result(Status.SKIPPED, batchDate, message, summary);
    }

    private static PublishResult result(
        Status status, LocalDate batchDate, String message, BatchSummary summary) {
      return new PublishResult(
          status,
          batchDate,
          summary.rawRows(),
          summary.validRows(),
          summary.skippedRows(),
          0,
          message);
    }
  }

  private record BatchSummary(
      LocalDate batchDate,
      long rawRows,
      long validRows,
      long org210Rows,
      long org220Rows,
      LocalDateTime lastSyncedAt,
      Long quietSeconds) {

    private long skippedRows() {
      return rawRows - validRows;
    }
  }

  private record PublishedBatch(
      long totalRows,
      long currentBatchRows,
      LocalDateTime importedAt) {}
}

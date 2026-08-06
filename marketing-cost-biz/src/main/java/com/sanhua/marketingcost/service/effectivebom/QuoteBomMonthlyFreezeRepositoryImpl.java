package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 使用 MySQL 真实行锁保护同一客户场景月度卡片的首次冻结。 */
@Repository
public class QuoteBomMonthlyFreezeRepositoryImpl
    implements QuoteBomMonthlyFreezeRepository {

  private static final String LOCK_SNAPSHOT_SQL =
      """
      SELECT id, product_code, price_org_code, customer_code, package_method,
             cost_period_month, source_oa_form_item_id, bom_batch_id,
             freeze_status, effective_build_batch_id, effective_variant_hash,
             frozen_at, frozen_by
        FROM lp_quote_bom_monthly_snapshot
       WHERE cost_period_month = ?
         AND product_code = ?
         AND customer_code = ?
         AND package_method = ?
         AND price_org_code = ?
         AND sync_status = 'SUCCESS'
         AND active_flag = 1
       ORDER BY sync_at DESC, id DESC
       LIMIT 1
       FOR UPDATE
      """;

  private static final String LOCK_STATUS_SQL =
      """
      SELECT id, oa_form_item_id, product_code, customer_code, package_method,
             cost_period_month, sync_record_id, costing_build_batch_id
        FROM lp_quote_bom_status
       WHERE oa_form_item_id = ?
       LIMIT 1
       FOR UPDATE
      """;

  private final JdbcTemplate jdbcTemplate;

  public QuoteBomMonthlyFreezeRepositoryImpl(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<QuoteBomMonthlySnapshot> findActiveSuccessForUpdate(
      QuoteBomMonthlyFreezeKey key) {
    List<QuoteBomMonthlySnapshot> rows =
        jdbcTemplate.query(
            LOCK_SNAPSHOT_SQL,
            this::mapSnapshot,
            key.costPeriodMonth(),
            key.productCode(),
            key.resolvedCustomerKey(),
            key.packageMethod(),
            key.priceOrgCode());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<QuoteBomStatus> findStatusForUpdate(Long oaFormItemId) {
    List<QuoteBomStatus> rows =
        jdbcTemplate.query(LOCK_STATUS_SQL, this::mapStatus, oaFormItemId);
    return rows.stream().findFirst();
  }

  @Override
  public int freezeDraft(
      Long snapshotId,
      String buildBatchId,
      String variantHash,
      Long frozenBy,
      LocalDateTime frozenAt) {
    return jdbcTemplate.update(
        """
        UPDATE lp_quote_bom_monthly_snapshot
           SET freeze_status = 'FROZEN',
               effective_build_batch_id = ?,
               effective_variant_hash = ?,
               frozen_by = ?,
               frozen_at = ?,
               updated_at = ?
         WHERE id = ?
           AND active_flag = 1
           AND sync_status = 'SUCCESS'
           AND COALESCE(freeze_status, 'DRAFT') = 'DRAFT'
        """,
        buildBatchId,
        variantHash,
        frozenBy,
        frozenAt,
        frozenAt,
        snapshotId);
  }

  @Override
  public int stageDraft(
      Long snapshotId,
      String buildBatchId,
      String variantHash,
      LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        """
        UPDATE lp_quote_bom_monthly_snapshot
           SET freeze_status = 'DRAFT',
               effective_build_batch_id = ?,
               effective_variant_hash = ?,
               frozen_by = NULL,
               frozen_at = NULL,
               updated_at = ?
         WHERE id = ?
           AND active_flag = 1
           AND sync_status = 'SUCCESS'
           AND COALESCE(freeze_status, 'DRAFT') = 'DRAFT'
        """,
        buildBatchId,
        variantHash,
        updatedAt,
        snapshotId);
  }

  @Override
  public int bindStatus(
      Long statusId,
      Long oaFormItemId,
      Long snapshotId,
      String buildBatchId,
      LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        """
        UPDATE lp_quote_bom_status
           SET sync_record_id = ?,
               costing_build_batch_id = ?,
               updated_at = ?
         WHERE id = ?
           AND oa_form_item_id = ?
        """,
        snapshotId,
        buildBatchId,
        updatedAt,
        statusId,
        oaFormItemId);
  }

  @Override
  public boolean hasActiveConfirmationForBuild(String buildBatchId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM lp_quote_bom_confirmation
             WHERE costing_build_batch_id = ?
               AND confirm_status = 'CONFIRMED'
            """,
            Integer.class,
            buildBatchId);
    return count != null && count > 0;
  }

  @Override
  public boolean hasActiveConfirmation(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM lp_quote_bom_confirmation
             WHERE oa_no = ?
               AND oa_form_item_id = ?
               AND top_product_code = ?
               AND period_month = ?
               AND confirm_status = 'CONFIRMED'
            """,
            Integer.class,
            oaNo,
            oaFormItemId,
            topProductCode,
            periodMonth);
    return count != null && count > 0;
  }

  @Override
  public int releaseProvisional(
      Long snapshotId,
      String expectedBuildBatchId,
      LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        """
        UPDATE lp_quote_bom_monthly_snapshot
           SET freeze_status = 'DRAFT',
               effective_build_batch_id = NULL,
               effective_variant_hash = NULL,
               frozen_by = NULL,
               frozen_at = NULL,
               updated_at = ?
         WHERE id = ?
           AND active_flag = 1
           AND sync_status = 'SUCCESS'
           AND freeze_status = 'FROZEN'
           AND effective_build_batch_id = ?
        """,
        updatedAt,
        snapshotId,
        expectedBuildBatchId);
  }

  @Override
  public int clearStatusBindings(
      String buildBatchId,
      LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        """
        UPDATE lp_quote_bom_status
           SET costing_build_batch_id = NULL,
               updated_at = ?
         WHERE costing_build_batch_id = ?
        """,
        updatedAt,
        buildBatchId);
  }

  private QuoteBomMonthlySnapshot mapSnapshot(ResultSet result, int rowNum)
      throws SQLException {
    QuoteBomMonthlySnapshot snapshot = new QuoteBomMonthlySnapshot();
    snapshot.setId(result.getLong("id"));
    snapshot.setProductCode(result.getString("product_code"));
    snapshot.setPriceOrgCode(result.getString("price_org_code"));
    snapshot.setCustomerCode(result.getString("customer_code"));
    snapshot.setPackageMethod(result.getString("package_method"));
    snapshot.setCostPeriodMonth(result.getString("cost_period_month"));
    snapshot.setSourceOaFormItemId(
        nullableLong(result, "source_oa_form_item_id"));
    snapshot.setBomBatchId(result.getString("bom_batch_id"));
    snapshot.setFreezeStatus(result.getString("freeze_status"));
    snapshot.setEffectiveBuildBatchId(
        result.getString("effective_build_batch_id"));
    snapshot.setEffectiveVariantHash(
        result.getString("effective_variant_hash"));
    snapshot.setFrozenAt(
        result.getObject("frozen_at", LocalDateTime.class));
    snapshot.setFrozenBy(nullableLong(result, "frozen_by"));
    return snapshot;
  }

  private QuoteBomStatus mapStatus(ResultSet result, int rowNum)
      throws SQLException {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(result.getLong("id"));
    status.setOaFormItemId(result.getLong("oa_form_item_id"));
    status.setProductCode(result.getString("product_code"));
    status.setCustomerCode(result.getString("customer_code"));
    status.setPackageMethod(result.getString("package_method"));
    status.setCostPeriodMonth(result.getString("cost_period_month"));
    status.setSyncRecordId(nullableLong(result, "sync_record_id"));
    status.setCostingBuildBatchId(
        result.getString("costing_build_batch_id"));
    return status;
  }

  private static Long nullableLong(ResultSet result, String column)
      throws SQLException {
    long value = result.getLong(column);
    return result.wasNull() ? null : value;
  }
}

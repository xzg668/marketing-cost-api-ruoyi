package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmationSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummaryResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuoteCostingWorkbenchSummaryMapper {

  @Select(
      "SELECT id, confirm_no AS confirmNo, oa_no AS oaNo, oa_form_item_id AS oaFormItemId, "
          + "top_product_code AS topProductCode, period_month AS periodMonth, "
          + "confirm_status AS confirmStatus, confirm_version AS confirmVersion, "
          + "row_count AS rowCount, manual_modified_count AS manualModifiedCount, "
          + "replace_count AS replaceCount, usage_adjust_count AS usageAdjustCount, "
          + "confirmed_by AS confirmedBy, confirmed_at AS confirmedAt, confirm_remark AS confirmRemark "
          + "FROM lp_quote_bom_confirmation "
          + "WHERE oa_no=#{oaNo} "
          + "AND oa_form_item_id=#{oaFormItemId} "
          + "AND top_product_code=#{productCode} "
          + "AND period_month=#{periodMonth} "
          + "ORDER BY confirmed_at DESC, id DESC "
          + "LIMIT 1")
  QuoteBomConfirmationSummaryResponse selectLatestBomConfirmation(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("productCode") String productCode,
      @Param("periodMonth") String periodMonth);

  @Select(
      "SELECT id, confirm_no AS confirmNo, oa_no AS oaNo, oa_form_item_id AS oaFormItemId, "
          + "product_code AS productCode, period_month AS periodMonth, bom_confirm_no AS bomConfirmNo, "
          + "status, total_count AS totalCount, confirmed_count AS confirmedCount, "
          + "gap_count AS gapCount, reference_price_count AS referencePriceCount, "
          + "confirmed_by AS confirmedBy, confirmed_at AS confirmedAt, message "
          + "FROM lp_quote_price_type_confirm_batch "
          + "WHERE oa_no=#{oaNo} "
          + "AND oa_form_item_id=#{oaFormItemId} "
          + "AND product_code=#{productCode} "
          + "AND period_month=#{periodMonth} "
          + "ORDER BY confirmed_at DESC, id DESC "
          + "LIMIT 1")
  QuotePriceTypeConfirmationSummaryResponse selectLatestPriceTypeConfirmation(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("productCode") String productCode,
      @Param("periodMonth") String periodMonth);

  @Select(
      "SELECT id, prepare_no AS prepareNo, oa_no AS oaNo, oa_form_item_id AS oaFormItemId, "
          + "top_product_code AS topProductCode, price_type_confirm_no AS priceTypeConfirmNo, "
          + "period_month AS periodMonth, status, total_count AS totalCount, "
          + "success_count AS successCount, warning_count AS warningCount, gap_count AS gapCount, "
          + "started_at AS startedAt, finished_at AS finishedAt, message "
          + "FROM lp_price_prepare_batch "
          + "WHERE oa_no=#{oaNo} "
          + "AND oa_form_item_id=#{oaFormItemId} "
          + "AND top_product_code=#{productCode} "
          + "AND period_month=#{periodMonth} "
          + "ORDER BY started_at DESC, id DESC "
          + "LIMIT 1")
  QuotePricePrepareSummaryResponse selectLatestPricePrepare(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("productCode") String productCode,
      @Param("periodMonth") String periodMonth);

  @Select(
      "SELECT id, cost_run_no AS costRunNo, version_no AS versionNo, oa_no AS oaNo, "
          + "oa_form_item_id AS oaFormItemId, product_code AS productCode, "
          + "pricing_month AS pricingMonth, result_period AS resultPeriod, "
          + "bom_confirm_no AS bomConfirmNo, price_type_confirm_no AS priceTypeConfirmNo, "
          + "price_prepare_no AS pricePrepareNo, status, total_cost AS totalCost, "
          + "part_item_count AS partItemCount, cost_item_count AS costItemCount, "
          + "trial_started_at AS trialStartedAt, trial_finished_at AS trialFinishedAt, "
          + "confirmed_by AS confirmedBy, confirmed_at AS confirmedAt, confirm_message AS confirmMessage "
          + "FROM lp_quote_cost_run_version "
          + "WHERE oa_no=#{oaNo} "
          + "AND oa_form_item_id=#{oaFormItemId} "
          + "AND product_code=#{productCode} "
          + "AND result_period=#{periodMonth} "
          + "ORDER BY confirmed_at DESC, trial_finished_at DESC, id DESC "
          + "LIMIT 1")
  QuoteCostRunSummaryResponse selectLatestCostRun(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("productCode") String productCode,
      @Param("periodMonth") String periodMonth);
}

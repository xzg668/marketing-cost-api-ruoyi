package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareSummaryResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuoteCostingWorkbenchSummaryMapper {

  @Select(
      "SELECT id, prepare_no AS prepareNo, oa_no AS oaNo, oa_form_item_id AS oaFormItemId, "
          + "top_product_code AS topProductCode, "
          + "period_month AS periodMonth, status, total_count AS totalCount, "
          + "success_count AS successCount, warning_count AS warningCount, gap_count AS gapCount, "
          + "price_as_of_time AS priceAsOfTime, price_as_of_source AS priceAsOfSource, "
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
      "SELECT id, prepare_no AS prepareNo, oa_no AS oaNo, oa_form_item_id AS oaFormItemId, "
          + "top_product_code AS topProductCode, "
          + "period_month AS periodMonth, status, total_count AS totalCount, "
          + "success_count AS successCount, warning_count AS warningCount, gap_count AS gapCount, "
          + "price_as_of_time AS priceAsOfTime, price_as_of_source AS priceAsOfSource, "
          + "started_at AS startedAt, finished_at AS finishedAt, message "
          + "FROM lp_price_prepare_batch WHERE prepare_no=#{prepareNo} LIMIT 1")
  QuotePricePrepareSummaryResponse selectPricePrepareByNo(
      @Param("prepareNo") String prepareNo);

  @Select(
      "SELECT id, cost_run_no AS costRunNo, version_no AS versionNo, oa_no AS oaNo, "
          + "oa_form_item_id AS oaFormItemId, product_code AS productCode, "
          + "pricing_month AS pricingMonth, result_period AS resultPeriod, "
          + "price_prepare_no AS pricePrepareNo, oa_price_prepare_no AS oaPricePrepareNo, "
          + "finance_price_prepare_no AS financePricePrepareNo, "
          + "finance_cu_price AS financeCuPrice, oa_cu_price AS oaCuPrice, "
          + "finance_base_price_id AS financeBasePriceId, "
          + "status, total_cost AS totalCost, "
          + "finance_material_cost AS financeMaterialCost, oa_material_cost AS oaMaterialCost, "
          + "cu_material_adjustment AS cuMaterialAdjustment, final_quote_amount AS finalQuoteAmount, "
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

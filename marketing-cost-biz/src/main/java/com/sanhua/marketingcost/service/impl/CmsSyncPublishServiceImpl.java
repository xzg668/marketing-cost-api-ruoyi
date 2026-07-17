package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.dto.CmsSyncPublishRunResponse;
import com.sanhua.marketingcost.entity.CmsSyncPublishSignal;
import com.sanhua.marketingcost.mapper.CmsSyncPublishSignalMapper;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsSyncPublishService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class CmsSyncPublishServiceImpl implements CmsSyncPublishService {
  private static final String STATUS_READY = "READY";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_SKIPPED = "SKIPPED";
  private static final String OPERATOR = "SYSTEM_CMS_SYNC";

  private final CmsSyncPublishSignalMapper signalMapper;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final CmsSalaryCostSourceEffectiveService salaryEffectiveService;
  private final CmsAuxSubjectSourceEffectiveService auxEffectiveService;
  private final ReentrantLock publishLock = new ReentrantLock();
  private final long runningTimeoutMinutes;

  public CmsSyncPublishServiceImpl(
      CmsSyncPublishSignalMapper signalMapper,
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      CmsSalaryCostSourceEffectiveService salaryEffectiveService,
      CmsAuxSubjectSourceEffectiveService auxEffectiveService,
      @Value("${cms.sync-publish.running-timeout-minutes:360}") long runningTimeoutMinutes) {
    this.signalMapper = signalMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.salaryEffectiveService = salaryEffectiveService;
    this.auxEffectiveService = auxEffectiveService;
    this.runningTimeoutMinutes = runningTimeoutMinutes;
  }

  @Override
  public CmsSyncPublishRunResponse runNextReady() {
    if (!publishLock.tryLock()) {
      return skippedResponse("已有 CMS 同步发布任务正在执行");
    }
    try {
      markStaleRunningSignals();
      if (hasRunningSignal()) {
        return skippedResponse("已有 CMS 同步发布信号正在处理");
      }
      CmsSyncPublishSignal signal =
          signalMapper.selectOne(
              new QueryWrapper<CmsSyncPublishSignal>()
                  .eq("status", STATUS_READY)
                  .orderByDesc("ready_at")
                  .orderByDesc("id")
                  .last("LIMIT 1"));
      if (signal == null) {
        return skippedResponse("没有待发布的 CMS 同步信号");
      }
      return runSignal(signal);
    } finally {
      publishLock.unlock();
    }
  }

  private CmsSyncPublishRunResponse skippedResponse(String message) {
    CmsSyncPublishRunResponse response = new CmsSyncPublishRunResponse();
    response.setExecuted(false);
    response.setStatus(STATUS_SKIPPED);
    response.setMessage(message);
    return response;
  }

  private void markStaleRunningSignals() {
    if (runningTimeoutMinutes <= 0) {
      return;
    }
    LocalDateTime cutoff = LocalDateTime.now().minusMinutes(runningTimeoutMinutes);
    signalMapper.update(
        null,
        new UpdateWrapper<CmsSyncPublishSignal>()
            .eq("status", STATUS_RUNNING)
            .and(wrapper -> wrapper.isNull("started_at").or().lt("started_at", cutoff))
            .set("status", STATUS_FAILED)
            .set("message", "CMS 同步发布任务超时，已释放 RUNNING 状态")
            .set("finished_at", LocalDateTime.now()));
  }

  private boolean hasRunningSignal() {
    Long count =
        signalMapper.selectCount(
            new QueryWrapper<CmsSyncPublishSignal>().eq("status", STATUS_RUNNING));
    return count != null && count > 0;
  }

  private CmsSyncPublishRunResponse runSignal(CmsSyncPublishSignal signal) {
    CmsSyncPublishRunResponse response = baseResponse(signal);
    int claimed =
        signalMapper.update(
            null,
            new UpdateWrapper<CmsSyncPublishSignal>()
                .eq("id", signal.getId())
                .eq("status", STATUS_READY)
                .set("status", STATUS_RUNNING)
                .set("started_at", LocalDateTime.now())
                .set("message", null));
    if (claimed == 0) {
      response.setExecuted(false);
      response.setStatus(STATUS_SKIPPED);
      response.setMessage("该信号已被其他任务处理");
      return response;
    }
    skipOlderReadySignals(signal);

    try {
      CmsSyncPublishRunResponse published =
          transactionTemplate.execute(
              ignored -> {
                CmsSyncPublishRunResponse current = baseResponse(signal);
                current.setExecuted(true);
                publishTmpTables(signal, current);
                refreshEffectiveSources(signal, current);
                current.setStatus(STATUS_SUCCESS);
                current.setMessage(successMessage(current));
                return current;
              });
      markSignal(signal.getId(), STATUS_SUCCESS, published.getMessage());
      return published;
    } catch (RuntimeException e) {
      String message = trimMessage(e.getMessage());
      markSignal(signal.getId(), STATUS_FAILED, message);
      response.setExecuted(true);
      response.setStatus(STATUS_FAILED);
      response.setMessage(message);
      return response;
    }
  }

  private CmsSyncPublishRunResponse baseResponse(CmsSyncPublishSignal signal) {
    CmsSyncPublishRunResponse response = new CmsSyncPublishRunResponse();
    response.setSignalId(signal.getId());
    response.setBatchNo(signal.getBatchNo());
    response.setCostYear(signal.getCostYear());
    response.setBusinessUnitType(normalizeBusinessUnit(signal.getBusinessUnitType()));
    return response;
  }

  private void skipOlderReadySignals(CmsSyncPublishSignal signal) {
    signalMapper.update(
        null,
        new UpdateWrapper<CmsSyncPublishSignal>()
            .eq("status", STATUS_READY)
            .eq("cost_year", signal.getCostYear())
            .eq("business_unit_type", normalizeBusinessUnit(signal.getBusinessUnitType()))
            .ne("id", signal.getId())
            .set("status", STATUS_SKIPPED)
            .set(
                "message",
                "已有更新的 CMS 同步发布信号 "
                    + signal.getBatchNo()
                    + "，旧 READY 已跳过")
            .set("finished_at", LocalDateTime.now()));
  }

  private void publishTmpTables(CmsSyncPublishSignal signal, CmsSyncPublishRunResponse response) {
    String businessUnitType = normalizeBusinessUnit(signal.getBusinessUnitType());
    List<String> tmpTables =
        List.of(
            "tmp_cms_plan_cost_raw",
            "tmp_cms_workshop_labor_raw",
            "tmp_cms_product_subject_cost_raw",
            "tmp_cms_subject_setting_raw",
            "tmp_lp_material_scrap_ref");
    for (String table : tmpTables) {
      long count = countTmpTable(table, businessUnitType);
      response.getTmpCounts().put(table, count);
      if (count <= 0) {
        throw new IllegalStateException(table + " 没有数据，停止覆盖正式表");
      }
    }

    deleteByBusinessUnit("cms_plan_cost_raw", businessUnitType, "");
    response
        .getPublishedCounts()
        .put(
            "cms_plan_cost_raw",
            updateWithBusinessUnit(insertPlanCostSql(businessUnitType), businessUnitType));

    deleteByBusinessUnit("cms_workshop_labor_raw", businessUnitType, "");
    response
        .getPublishedCounts()
        .put(
            "cms_workshop_labor_raw",
            updateWithBusinessUnit(insertWorkshopLaborSql(businessUnitType), businessUnitType));

    deleteByBusinessUnit("cms_product_subject_cost_raw", businessUnitType, "");
    response
        .getPublishedCounts()
        .put(
            "cms_product_subject_cost_raw",
            updateWithBusinessUnit(insertProductSubjectSql(businessUnitType), businessUnitType));

    jdbcTemplate.update("DELETE FROM cms_subject_setting_raw");
    response
        .getPublishedCounts()
        .put(
            "cms_subject_setting_raw",
            updateWithBusinessUnit(insertSubjectSettingSql(businessUnitType), businessUnitType));

    preserveNewerMaterialScrapMappings(businessUnitType);
    deleteByBusinessUnit("lp_material_scrap_ref", businessUnitType, "COMMERCIAL");
    response
        .getPublishedCounts()
        .put(
            "lp_material_scrap_ref",
            updateWithBusinessUnit(insertMaterialScrapSql(businessUnitType), businessUnitType));
  }

  private void refreshEffectiveSources(
      CmsSyncPublishSignal signal, CmsSyncPublishRunResponse response) {
    if (signal.getCostYear() == null) {
      throw new IllegalArgumentException("cost_year 不能为空");
    }
    String businessUnitType = normalizeBusinessUnit(signal.getBusinessUnitType());
    deleteDefaultEffectiveSources(signal.getCostYear(), businessUnitType);
    CmsEffectiveSourceGenerateResponse salary =
        salaryEffectiveService.generateDefaultSources(signal.getCostYear(), OPERATOR, businessUnitType);
    CmsEffectiveSourceGenerateResponse aux =
        auxEffectiveService.generateDefaultSources(signal.getCostYear(), OPERATOR, businessUnitType);
    response.setEffectiveInsertedCount(salary.getInsertedCount() + aux.getInsertedCount());
    response.setEffectiveUpdatedCount(salary.getUpdatedCount() + aux.getUpdatedCount());
    response.setEffectiveSkippedCount(salary.getSkippedCount() + aux.getSkippedCount());
    response.setEffectiveBlockedCount(salary.getBlockedCount() + aux.getBlockedCount());
    response.setEffectiveErrorCount(salary.getErrorCount() + aux.getErrorCount());
  }

  private long countTmpTable(String tableName, String businessUnitType) {
    if (!StringUtils.hasText(businessUnitType)) {
      Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
      return count == null ? 0L : count;
    }
    String defaultBusinessUnit = "tmp_lp_material_scrap_ref".equals(tableName) ? "COMMERCIAL" : "";
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM "
                + tableName
                + " WHERE COALESCE(business_unit_type, ?) = ?",
            Long.class,
            defaultBusinessUnit,
            businessUnitType);
    return count == null ? 0L : count;
  }

  private void deleteByBusinessUnit(
      String tableName, String businessUnitType, String defaultBusinessUnit) {
    if (!StringUtils.hasText(businessUnitType)) {
      jdbcTemplate.update("DELETE FROM " + tableName);
      return;
    }
    jdbcTemplate.update(
        "DELETE FROM " + tableName + " WHERE COALESCE(business_unit_type, ?) = ?",
        defaultBusinessUnit,
        businessUnitType);
  }

  private int updateWithBusinessUnit(String sql, String businessUnitType) {
    if (!StringUtils.hasText(businessUnitType)) {
      return jdbcTemplate.update(sql);
    }
    return jdbcTemplate.update(sql, businessUnitType);
  }

  private void preserveNewerMaterialScrapMappings(String businessUnitType) {
    String sql = preserveNewerMaterialScrapSql(businessUnitType);
    if (!StringUtils.hasText(businessUnitType)) {
      jdbcTemplate.update(sql);
      return;
    }
    jdbcTemplate.update(sql, businessUnitType);
  }

  String preserveNewerMaterialScrapSql(String businessUnitType) {
    return """
        INSERT INTO tmp_lp_material_scrap_ref (
          material_code, material_name, material_spec, material_unit, scrap_code, scrap_name,
          scrap_spec, scrap_unit, ratio, effective_from, effective_to, business_unit_type,
          source_type, source_doc_no, cms_record_id, link_detail_id, cms_posting_period,
          cms_effective_date, approval_time, sync_time, remark, created_at, updated_at
        )
        SELECT
          live.material_code, live.material_name, live.material_spec, live.material_unit,
          live.scrap_code, live.scrap_name, live.scrap_spec, live.scrap_unit, live.ratio,
          live.effective_from, live.effective_to, live.business_unit_type, live.source_type,
          live.source_doc_no, live.cms_record_id, live.link_detail_id, live.cms_posting_period,
          live.cms_effective_date, live.approval_time, live.sync_time, live.remark,
          live.created_at, live.updated_at
        FROM lp_material_scrap_ref live
        WHERE live.material_code IS NOT NULL
          AND TRIM(live.material_code) <> ''
          AND live.scrap_code IS NOT NULL
          AND TRIM(live.scrap_code) <> ''
          AND EXISTS (
            SELECT 1
            FROM tmp_lp_material_scrap_ref incoming
            WHERE COALESCE(incoming.business_unit_type, 'COMMERCIAL') =
                    COALESCE(live.business_unit_type, 'COMMERCIAL')
              AND incoming.material_code = live.material_code
          )
          AND COALESCE(NULLIF(TRIM(live.cms_posting_period), ''), '') >
              COALESCE((
                SELECT MAX(NULLIF(TRIM(candidate.cms_posting_period), ''))
                FROM tmp_lp_material_scrap_ref candidate
                WHERE COALESCE(candidate.business_unit_type, 'COMMERCIAL') =
                        COALESCE(live.business_unit_type, 'COMMERCIAL')
                  AND candidate.material_code = live.material_code
              ), '')
          AND NOT EXISTS (
            SELECT 1
            FROM tmp_lp_material_scrap_ref duplicate_row
            WHERE COALESCE(duplicate_row.business_unit_type, 'COMMERCIAL') =
                    COALESCE(live.business_unit_type, 'COMMERCIAL')
              AND duplicate_row.material_code = live.material_code
              AND duplicate_row.scrap_code = live.scrap_code
              AND COALESCE(NULLIF(TRIM(duplicate_row.cms_posting_period), ''), '') =
                    COALESCE(NULLIF(TRIM(live.cms_posting_period), ''), '')
          )
        """
        + businessUnitLiveFilter(businessUnitType, "COMMERCIAL");
  }

  private void deleteDefaultEffectiveSources(int costYear, String businessUnitType) {
    if (!StringUtils.hasText(businessUnitType)) {
      jdbcTemplate.update(
          "DELETE FROM cms_cost_source_effective WHERE cost_year = ? AND default_flag = 1",
          costYear);
      return;
    }
    jdbcTemplate.update(
        "DELETE FROM cms_cost_source_effective "
            + "WHERE cost_year = ? AND business_unit_type = ? AND default_flag = 1",
        costYear,
        businessUnitType);
  }

  private void markSignal(Long signalId, String status, String message) {
    signalMapper.update(
        null,
        new UpdateWrapper<CmsSyncPublishSignal>()
            .eq("id", signalId)
            .set("status", status)
            .set("message", trimMessage(message))
            .set("finished_at", LocalDateTime.now()));
  }

  private String successMessage(CmsSyncPublishRunResponse response) {
    return "发布成功，正式表覆盖完成，CMS 公共生效来源已刷新：inserted="
        + response.getEffectiveInsertedCount()
        + ", updated="
        + response.getEffectiveUpdatedCount()
        + ", skipped="
        + response.getEffectiveSkippedCount()
        + ", blocked="
        + response.getEffectiveBlockedCount();
  }

  private String trimMessage(String message) {
    if (!StringUtils.hasText(message)) {
      return "CMS 同步发布失败";
    }
    return message.length() > 1000 ? message.substring(0, 1000) : message;
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    return businessUnitType == null ? "" : businessUnitType.trim();
  }

  private String insertPlanCostSql(String businessUnitType) {
    return """
        INSERT INTO cms_plan_cost_raw (
          import_batch_id, row_no, first_unit_code, first_unit_name, parent_code, parent_name,
          parent_spec, parent_type, unit, working_hours, effective_date, effective_period,
          main_material_cost, aux_material_cost, salary_cost, fund_cost, loss_cost,
          total_plan_cost, business_status, unapproved_items, description, oa_no,
          business_unit_type, created_at
        )
        SELECT
          import_batch_id, row_no, first_unit_code, first_unit_name, parent_code, parent_name,
          parent_spec, parent_type, unit, working_hours, effective_date, effective_period,
          main_material_cost, aux_material_cost, salary_cost, fund_cost, loss_cost,
          total_plan_cost, business_status, unapproved_items, description, oa_no,
          COALESCE(business_unit_type, ''), created_at
        FROM tmp_cms_plan_cost_raw
        """
        + businessUnitFilter(businessUnitType, "");
  }

  private String insertWorkshopLaborSql(String businessUnitType) {
    return """
        INSERT INTO cms_workshop_labor_raw (
          import_batch_id, row_no, period, first_unit_code, first_unit_name, parent_code,
          parent_name, parent_spec, parent_type, last_unit_name, last_unit_code, working_hours,
          funding, working_cost_cent, working_cost_yuan, build_flag, path, source_row_id,
          sequence_no, sequence_status, material_price, first_subject_code, first_subject_name,
          second_subject_code, second_subject_name, third_subject_code, third_subject_name,
          business_unit_type, created_at
        )
        SELECT
          import_batch_id, row_no, period, first_unit_code, first_unit_name, parent_code,
          parent_name, parent_spec, parent_type, last_unit_name, last_unit_code, working_hours,
          funding, working_cost_cent, working_cost_yuan, build_flag, path, source_row_id,
          sequence_no, sequence_status, material_price, first_subject_code, first_subject_name,
          second_subject_code, second_subject_name, third_subject_code, third_subject_name,
          COALESCE(business_unit_type, ''), created_at
        FROM tmp_cms_workshop_labor_raw
        """
        + businessUnitFilter(businessUnitType, "");
  }

  private String insertProductSubjectSql(String businessUnitType) {
    return """
        INSERT INTO cms_product_subject_cost_raw (
          import_batch_id, row_no, period, first_unit_code, first_unit_name, parent_code,
          parent_name, parent_spec, parent_type, last_subject_code, last_subject_name,
          last_subject_level, material_price, material_price_yuan, build_flag, path,
          first_subject_code, first_subject_name, second_subject_code, second_subject_name,
          third_subject_code, third_subject_name, source_row_id, sequence_no, sequence_status,
          business_unit_type, created_at
        )
        SELECT
          import_batch_id, row_no, period, first_unit_code, first_unit_name, parent_code,
          parent_name, parent_spec, parent_type, last_subject_code, last_subject_name,
          last_subject_level, material_price, material_price_yuan, build_flag, path,
          first_subject_code, first_subject_name, second_subject_code, second_subject_name,
          third_subject_code, third_subject_name, source_row_id, sequence_no, sequence_status,
          COALESCE(business_unit_type, ''), created_at
        FROM tmp_cms_product_subject_cost_raw
        """
        + businessUnitFilter(businessUnitType, "");
  }

  private String insertSubjectSettingSql(String businessUnitType) {
    return """
        INSERT INTO cms_subject_setting_raw (
          import_batch_id, row_no, first_subject_code, first_subject_name, second_subject_code,
          second_subject_name, third_subject_code, third_subject_name, business_unit_type,
          created_at
        )
        SELECT
          COALESCE(import_batch_id, 0), row_no, first_subject_code, first_subject_name,
          second_subject_code, second_subject_name, COALESCE(third_subject_code, ''),
          third_subject_name, COALESCE(business_unit_type, ''), created_at
        FROM tmp_cms_subject_setting_raw
        """
        + businessUnitFilter(businessUnitType, "");
  }

  String insertMaterialScrapSql(String businessUnitType) {
    return """
        INSERT INTO lp_material_scrap_ref (
          material_code, material_name, material_spec, material_unit, scrap_code, scrap_name,
          scrap_spec, scrap_unit, ratio, effective_from, effective_to, business_unit_type,
          source_type, source_doc_no, cms_record_id, link_detail_id, cms_posting_period,
          cms_effective_date, approval_time, sync_time, remark, created_at, updated_at
        )
        SELECT
          material_code, material_name, material_spec, material_unit, scrap_code, scrap_name,
          scrap_spec, scrap_unit, COALESCE(ratio, 1.0), effective_from, effective_to,
          COALESCE(business_unit_type, 'COMMERCIAL'), source_type, source_doc_no, cms_record_id,
          link_detail_id, cms_posting_period, cms_effective_date, approval_time, sync_time,
          remark, created_at, updated_at
        FROM (
          SELECT
            t.*,
            ROW_NUMBER() OVER (
              PARTITION BY COALESCE(t.business_unit_type, 'COMMERCIAL'), t.material_code
              ORDER BY
                t.cms_posting_period DESC,
                t.cms_effective_date DESC,
                t.approval_time DESC,
                t.sync_time DESC,
                t.updated_at DESC,
                t.source_doc_no DESC,
                t.cms_record_id DESC,
                t.scrap_code DESC
            ) AS row_rank
          FROM tmp_lp_material_scrap_ref t
          WHERE t.material_code IS NOT NULL
            AND TRIM(t.material_code) <> ''
            AND t.scrap_code IS NOT NULL
            AND TRIM(t.scrap_code) <> ''
        ) ranked
        WHERE ranked.row_rank = 1
        """
        + businessUnitRankedFilter(businessUnitType, "COMMERCIAL");
  }

  private String businessUnitFilter(String businessUnitType, String defaultBusinessUnit) {
    if (!StringUtils.hasText(businessUnitType)) {
      return "";
    }
    return " WHERE COALESCE(business_unit_type, '" + defaultBusinessUnit + "') = ?";
  }

  private String businessUnitRankedFilter(String businessUnitType, String defaultBusinessUnit) {
    if (!StringUtils.hasText(businessUnitType)) {
      return "";
    }
    return " AND COALESCE(ranked.business_unit_type, '" + defaultBusinessUnit + "') = ?";
  }

  private String businessUnitLiveFilter(String businessUnitType, String defaultBusinessUnit) {
    if (!StringUtils.hasText(businessUnitType)) {
      return "";
    }
    return " AND COALESCE(live.business_unit_type, '" + defaultBusinessUnit + "') = ?";
  }
}

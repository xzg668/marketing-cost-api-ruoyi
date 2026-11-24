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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CmsSyncPublishServiceImpl implements CmsSyncPublishService {
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState pendingState;
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
                validateFormalInputs(signal);
                refreshEffectiveSources(signal, current);
                if(pendingState!=null) pendingState.changed(new com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState.Change(java.util.Set.of("SALARY","PRICE"),normalizeBusinessUnit(signal.getBusinessUnitType()),java.util.Set.of(),java.util.Set.of()));
                current.setStatus(STATUS_SUCCESS);
                current.setMessage(successMessage(current));
                return current;
              });
      markSignal(signal.getId(), STATUS_SUCCESS, published.getMessage());
      return published;
    } catch (RuntimeException e) {
      log.error("CMS sync publish failed, signalId={}, batchNo={}", signal.getId(), signal.getBatchNo(), e);
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

  /** These four ETL inputs are required for salary and auxiliary source refresh. */
  private void validateFormalInputs(CmsSyncPublishSignal signal) {
    String businessUnitType = normalizeBusinessUnit(signal.getBusinessUnitType());
    for (String table : List.of("cms_plan_cost_raw", "cms_workshop_labor_raw",
        "cms_product_subject_cost_raw", "cms_subject_setting_raw")) {
      String sql = "SELECT EXISTS(SELECT 1 FROM " + table;
      Long present;
      if (StringUtils.hasText(businessUnitType)) {
        String defaultBusinessUnit = "";
        present = jdbcTemplate.queryForObject(
            sql + " WHERE COALESCE(business_unit_type, ?) = ? LIMIT 1)", Long.class,
            defaultBusinessUnit, businessUnitType);
      } else {
        present = jdbcTemplate.queryForObject(sql + " LIMIT 1)", Long.class);
      }
      if (present == null || present == 0) {
        throw new IllegalStateException(table
            + " 正式表在当前业务单元没有数据，请确认 ETL 全部抽取完成后再发送 READY 信号");
      }
    }
  }

  private void refreshEffectiveSources(
      CmsSyncPublishSignal signal, CmsSyncPublishRunResponse response) {
    if (signal.getCostYear() == null) {
      throw new IllegalArgumentException("cost_year 不能为空");
    }
    String businessUnitType = normalizeBusinessUnit(signal.getBusinessUnitType());
    CmsEffectiveSourceGenerateResponse salary =
        salaryEffectiveService.synchronizeDefaultSources(signal.getCostYear(), OPERATOR, businessUnitType);
    CmsEffectiveSourceGenerateResponse aux =
        auxEffectiveService.synchronizeDefaultSources(signal.getCostYear(), OPERATOR, businessUnitType);
    response.setEffectiveInsertedCount(salary.getInsertedCount() + aux.getInsertedCount());
    response.setEffectiveUpdatedCount(salary.getUpdatedCount() + aux.getUpdatedCount());
    response.setEffectiveRemovedCount(salary.getRemovedCount() + aux.getRemovedCount());
    response.setEffectiveSkippedCount(salary.getSkippedCount() + aux.getSkippedCount());
    response.setEffectiveBlockedCount(salary.getBlockedCount() + aux.getBlockedCount());
    response.setEffectiveErrorCount(salary.getErrorCount() + aux.getErrorCount());
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
    return "四张ETL直写表校验通过，CMS 工资及辅料公共生效来源已刷新：inserted="
        + response.getEffectiveInsertedCount()
        + ", updated="
        + response.getEffectiveUpdatedCount()
        + ", removed="
        + response.getEffectiveRemovedCount()
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

}

package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomConfirmationLog;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationLogMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteBomConfirmationServiceImpl implements QuoteBomConfirmationService {

  private static final DateTimeFormatter CONFIRM_NO_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final QuoteBomConfirmationMapper confirmationMapper;
  private final QuoteBomConfirmationLogMapper confirmationLogMapper;

  public QuoteBomConfirmationServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      BomCostingRowMapper bomCostingRowMapper,
      QuoteBomConfirmationMapper confirmationMapper,
      QuoteBomConfirmationLogMapper confirmationLogMapper) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.confirmationMapper = confirmationMapper;
    this.confirmationLogMapper = confirmationLogMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomConfirmResponse confirm(
      String oaNo, Long oaFormItemId, QuoteBomConfirmRequest request) {
    Scope scope = requireScope(oaNo, oaFormItemId);
    List<BomCostingRow> rows = loadRows(scope);
    if (rows.isEmpty()) {
      throw new QuoteIngestException("当前产品行 BOM 明细为空，无法确认");
    }

    LocalDateTime now = LocalDateTime.now();
    String operator = currentUsername("system");
    int nextVersion =
        confirmations(scope).stream()
                .map(QuoteBomConfirmation::getConfirmVersion)
                .filter(version -> version != null)
                .max(Comparator.naturalOrder())
                .orElse(0)
            + 1;

    for (QuoteBomConfirmation old : activeConfirmations(scope)) {
      old.setConfirmStatus(QuoteBomConfirmation.STATUS_INVALID);
      old.setUpdatedAt(now);
      confirmationMapper.updateById(old);
      writeLog(
          old,
          QuoteBomConfirmationLog.ACTION_STALE,
          QuoteBomConfirmation.STATUS_CONFIRMED,
          QuoteBomConfirmation.STATUS_INVALID,
          operator,
          now,
          "重复确认生成新版本，旧确认失效");
    }

    QuoteBomConfirmation entity = new QuoteBomConfirmation();
    entity.setConfirmNo(generateConfirmNo(now));
    entity.setOaNo(scope.oaNo());
    entity.setOaFormItemId(scope.oaFormItemId());
    entity.setTopProductCode(scope.productCode());
    entity.setPeriodMonth(scope.periodMonth());
    entity.setConfirmStatus(QuoteBomConfirmation.STATUS_CONFIRMED);
    entity.setConfirmVersion(nextVersion);
    entity.setRowCount(rows.size());
    entity.setManualModifiedCount(manualModifiedCount(rows));
    entity.setReplaceCount(0);
    entity.setUsageAdjustCount(0);
    entity.setConfirmedBy(operator);
    entity.setConfirmedAt(now);
    entity.setConfirmRemark(trimToNull(request == null ? null : request.getConfirmRemark()));
    entity.setBusinessUnitType(firstText(scope.item().getBusinessUnitType(), scope.form().getBusinessUnitType()));
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    if (confirmationMapper.insert(entity) <= 0) {
      throw new QuoteIngestException("BOM 确认保存失败");
    }
    writeLog(
        entity,
        QuoteBomConfirmationLog.ACTION_CONFIRM,
        null,
        QuoteBomConfirmation.STATUS_CONFIRMED,
        operator,
        now,
        entity.getConfirmRemark());
    return QuoteBomConfirmResponse.from(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomConfirmResponse cancelConfirm(
      String oaNo, Long oaFormItemId, QuoteBomCancelConfirmRequest request) {
    Scope scope = requireScope(oaNo, oaFormItemId);
    QuoteBomConfirmation latest = latestConfirmation(scope);
    if (latest == null
        || !QuoteBomConfirmation.STATUS_CONFIRMED.equalsIgnoreCase(
            trimToNull(latest.getConfirmStatus()))) {
      throw new QuoteIngestException("当前产品行没有可撤销的有效 BOM 确认");
    }

    LocalDateTime now = LocalDateTime.now();
    String operator = currentUsername("system");
    latest.setConfirmStatus(QuoteBomConfirmation.STATUS_INVALID);
    latest.setUpdatedAt(now);
    String remark = firstText(request == null ? null : request.getCancelRemark(), "撤销 BOM 确认");
    if (confirmationMapper.updateById(latest) <= 0) {
      throw new QuoteIngestException("BOM 确认撤销失败: " + latest.getConfirmNo());
    }
    writeLog(
        latest,
        QuoteBomConfirmationLog.ACTION_CANCEL,
        QuoteBomConfirmation.STATUS_CONFIRMED,
        QuoteBomConfirmation.STATUS_INVALID,
        operator,
        now,
        remark);
    return QuoteBomConfirmResponse.from(latest);
  }

  private Scope requireScope(String oaNo, Long oaFormItemId) {
    OaForm form = requireForm(oaNo);
    OaFormItem item = requireItem(form, oaFormItemId);
    String productCode = trimToNull(item.getMaterialNo());
    if (productCode == null) {
      throw new QuoteIngestException("当前产品行料号为空，无法确认 BOM");
    }
    QuoteBomStatus status = latestBomStatus(form.getOaNo(), item.getId());
    return new Scope(form, item, form.getOaNo(), item.getId(), productCode, resolvePeriodMonth(form, status));
  }

  private OaForm requireForm(String oaNo) {
    String normalized = trimToNull(oaNo);
    if (normalized == null) {
      throw new QuoteIngestException("报价单号不能为空");
    }
    OaForm form =
        oaFormMapper.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, normalized));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + normalized);
    }
    return form;
  }

  private OaFormItem requireItem(OaForm form, Long oaFormItemId) {
    if (oaFormItemId == null) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    if (item == null || !form.getId().equals(item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单: " + oaFormItemId);
    }
    return item;
  }

  private QuoteBomStatus latestBomStatus(String oaNo, Long oaFormItemId) {
    return quoteBomStatusMapper.selectOne(
        Wrappers.<QuoteBomStatus>lambdaQuery()
            .eq(QuoteBomStatus::getOaNo, oaNo)
            .eq(QuoteBomStatus::getOaFormItemId, oaFormItemId)
            .orderByDesc(QuoteBomStatus::getCheckedAt)
            .orderByDesc(QuoteBomStatus::getId)
            .last("LIMIT 1"));
  }

  private String resolvePeriodMonth(OaForm form, QuoteBomStatus status) {
    String period =
        firstText(
            status == null ? null : status.getCostPeriodMonth(),
            trimToNull(form.getAccountingPeriodMonth()));
    if (period != null) {
      return period;
    }
    if (form.getApplyDate() != null) {
      return YearMonth.from(form.getApplyDate()).toString();
    }
    return YearMonth.now().toString();
  }

  private List<BomCostingRow> loadRows(Scope scope) {
    return bomCostingRowMapper.selectQuoteCostingSnapshot(
        scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
  }

  private List<QuoteBomConfirmation> confirmations(Scope scope) {
    return confirmationMapper.selectList(scopeQuery(scope).orderByDesc(QuoteBomConfirmation::getId));
  }

  private List<QuoteBomConfirmation> activeConfirmations(Scope scope) {
    return confirmationMapper.selectList(
        scopeQuery(scope)
            .eq(QuoteBomConfirmation::getConfirmStatus, QuoteBomConfirmation.STATUS_CONFIRMED)
            .orderByDesc(QuoteBomConfirmation::getConfirmedAt)
            .orderByDesc(QuoteBomConfirmation::getId));
  }

  private QuoteBomConfirmation latestConfirmation(Scope scope) {
    return confirmationMapper.selectOne(
        scopeQuery(scope)
            .orderByDesc(QuoteBomConfirmation::getConfirmedAt)
            .orderByDesc(QuoteBomConfirmation::getId)
            .last("LIMIT 1"));
  }

  private LambdaQueryWrapper<QuoteBomConfirmation> scopeQuery(Scope scope) {
    return Wrappers.<QuoteBomConfirmation>lambdaQuery()
        .eq(QuoteBomConfirmation::getOaNo, scope.oaNo())
        .eq(QuoteBomConfirmation::getOaFormItemId, scope.oaFormItemId())
        .eq(QuoteBomConfirmation::getTopProductCode, scope.productCode())
        .eq(QuoteBomConfirmation::getPeriodMonth, scope.periodMonth());
  }

  private int manualModifiedCount(List<BomCostingRow> rows) {
    int count = 0;
    for (BomCostingRow row : rows) {
      if (Integer.valueOf(1).equals(row.getManualModified())) {
        count++;
      }
    }
    return count;
  }

  private void writeLog(
      QuoteBomConfirmation entity,
      String actionType,
      String beforeStatus,
      String afterStatus,
      String operator,
      LocalDateTime now,
      String remark) {
    QuoteBomConfirmationLog log = new QuoteBomConfirmationLog();
    log.setConfirmNo(entity.getConfirmNo());
    log.setOaNo(entity.getOaNo());
    log.setOaFormItemId(entity.getOaFormItemId());
    log.setTopProductCode(entity.getTopProductCode());
    log.setPeriodMonth(entity.getPeriodMonth());
    log.setActionType(actionType);
    log.setBeforeStatus(beforeStatus);
    log.setAfterStatus(afterStatus);
    log.setOperatorId(operator);
    log.setOperatedAt(now);
    log.setRemark(trimToNull(remark));
    log.setBusinessUnitType(entity.getBusinessUnitType());
    log.setCreatedAt(now);
    log.setUpdatedAt(now);
    confirmationLogMapper.insert(log);
  }

  private String generateConfirmNo(LocalDateTime now) {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    return "BOM-CF-" + CONFIRM_NO_TIME_FORMAT.format(now) + "-" + suffix;
  }

  private static String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String currentUsername(String fallback) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getPrincipal() == null) {
      return fallback;
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof UserDetails userDetails) {
      return StringUtils.hasText(userDetails.getUsername()) ? userDetails.getUsername() : fallback;
    }
    String value = principal.toString();
    return StringUtils.hasText(value) ? value : fallback;
  }

  private record Scope(
      OaForm form,
      OaFormItem item,
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth) {}
}

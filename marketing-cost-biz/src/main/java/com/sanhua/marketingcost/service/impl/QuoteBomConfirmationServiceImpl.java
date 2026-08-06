package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomConfirmationLog;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationLogMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeConfirmationGuard;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionScope;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
  private static final int ACTIVE = 1;
  private static final String PRODUCT_TYPE_NON_BARE = "NON_BARE";
  private static final String MAIN_BOM_PURPOSE = "主制造";

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final QuoteBomConfirmationMapper confirmationMapper;
  private final QuoteBomConfirmationLogMapper confirmationLogMapper;
  private final QuoteCostRunVersionInvalidationService versionInvalidationService;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final QuoteBomAlternativeConfirmationGuard alternativeConfirmationGuard;
  private final QuoteBomContextResolver quoteBomContextResolver;

  public QuoteBomConfirmationServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      BomCostingRowMapper bomCostingRowMapper,
      QuoteBomConfirmationMapper confirmationMapper,
      QuoteBomConfirmationLogMapper confirmationLogMapper,
      QuoteCostRunVersionInvalidationService versionInvalidationService,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteBomAlternativeConfirmationGuard alternativeConfirmationGuard,
      QuoteBomContextResolver quoteBomContextResolver) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.confirmationMapper = confirmationMapper;
    this.confirmationLogMapper = confirmationLogMapper;
    this.versionInvalidationService = versionInvalidationService;
    this.preparationRecordMapper = preparationRecordMapper;
    this.alternativeConfirmationGuard = alternativeConfirmationGuard;
    this.quoteBomContextResolver = quoteBomContextResolver;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasActiveConfirmation(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth) {
    if (!StringUtils.hasText(oaNo)
        || oaFormItemId == null
        || !StringUtils.hasText(topProductCode)
        || !StringUtils.hasText(periodMonth)) {
      return false;
    }
    Long count =
        confirmationMapper.selectCount(
            Wrappers.<QuoteBomConfirmation>lambdaQuery()
                .eq(QuoteBomConfirmation::getOaNo, oaNo.trim())
                .eq(QuoteBomConfirmation::getOaFormItemId, oaFormItemId)
                .eq(
                    QuoteBomConfirmation::getTopProductCode,
                    topProductCode.trim())
                .eq(
                    QuoteBomConfirmation::getPeriodMonth,
                    periodMonth.trim())
                .eq(
                    QuoteBomConfirmation::getConfirmStatus,
                    QuoteBomConfirmation.STATUS_CONFIRMED));
    return count != null && count > 0;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasActiveConfirmationForBuild(String effectiveBuildBatchId) {
    if (!StringUtils.hasText(effectiveBuildBatchId)) {
      return false;
    }
    Long count =
        confirmationMapper.selectCount(
            Wrappers.<QuoteBomConfirmation>lambdaQuery()
                .eq(
                    QuoteBomConfirmation::getCostingBuildBatchId,
                    effectiveBuildBatchId.trim())
                .eq(
                    QuoteBomConfirmation::getConfirmStatus,
                    QuoteBomConfirmation.STATUS_CONFIRMED));
    return count != null && count > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomConfirmResponse confirm(
      String oaNo, Long oaFormItemId, QuoteBomConfirmRequest request) {
    return confirmInternal(oaNo, oaFormItemId, null, null, request);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomConfirmResponse confirmEffective(
      String oaNo,
      Long oaFormItemId,
      String effectiveBuildBatchId,
      int replaceCount,
      QuoteBomConfirmRequest request) {
    String buildBatchId = required("最终有效BOM构建编号", effectiveBuildBatchId);
    if (replaceCount < 0) {
      throw new QuoteIngestException("替代料数量不能小于0");
    }
    return confirmInternal(oaNo, oaFormItemId, buildBatchId, replaceCount, request);
  }

  private QuoteBomConfirmResponse confirmInternal(
      String oaNo,
      Long oaFormItemId,
      String expectedBuildBatchId,
      Integer effectiveReplaceCount,
      QuoteBomConfirmRequest request) {
    Scope scope = requireScope(oaNo, oaFormItemId);
    List<BomCostingRow> rows = loadRows(scope);
    if (rows.isEmpty()) {
      throw new QuoteIngestException("当前产品行 BOM 明细为空，无法确认");
    }

    List<QuoteBomConfirmation> active = activeConfirmations(scope);
    if (!active.isEmpty()) {
      if (expectedBuildBatchId != null
          && !expectedBuildBatchId.equals(
              trimToNull(active.getFirst().getCostingBuildBatchId()))) {
        throw new QuoteIngestException("已有BOM确认引用的构建编号与当前最终树不一致");
      }
      // 确认后的 BOM 不允许直接编辑；重复请求直接返回现有版本，避免重试产生无效历史。
      return QuoteBomConfirmResponse.from(active.get(0));
    }

    int replaceCount =
        effectiveReplaceCount == null
            ? alternativeConfirmationGuard
                .validateAndCountManualAlternatives(
                    alternativeScope(scope, requirePreparation(scope)),
                    LocalDate.now(),
                    MAIN_BOM_PURPOSE)
            : effectiveReplaceCount;
    if (expectedBuildBatchId != null) {
      requireEffectiveBuildConsistency(scope, rows, expectedBuildBatchId);
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
    entity.setReplaceCount(replaceCount);
    entity.setConfirmedBy(operator);
    entity.setConfirmedAt(now);
    entity.setConfirmRemark(trimToNull(request == null ? null : request.getConfirmRemark()));
    entity.setCostingBuildBatchId(expectedBuildBatchId);
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
    versionInvalidationService.invalidateProduct(
        scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
    return QuoteBomConfirmResponse.from(entity);
  }

  private void requireEffectiveBuildConsistency(
      Scope scope, List<BomCostingRow> rows, String expectedBuildBatchId) {
    if (rows.stream()
        .anyMatch(
            row ->
                !expectedBuildBatchId.equals(
                    trimToNull(row.getBuildBatchId())))) {
      throw new QuoteIngestException("第2步结算行与最终有效BOM构建编号不一致");
    }
    QuoteBomStatus status = latestBomStatus(scope.oaNo(), scope.oaFormItemId());
    if (status == null
        || !expectedBuildBatchId.equals(
            trimToNull(status.getCostingBuildBatchId()))) {
      throw new QuoteIngestException("OA产品状态与最终有效BOM构建编号不一致");
    }
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
    versionInvalidationService.invalidateProduct(
        scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
    return QuoteBomConfirmResponse.from(latest);
  }

  private Scope requireScope(String oaNo, Long oaFormItemId) {
    OaForm form = requireForm(oaNo);
    OaFormItem item = requireItem(form, oaFormItemId);
    QuoteBomStatus latestStatus = latestBomStatus(form.getOaNo(), item.getId());
    QuoteBomContext context =
        quoteBomContextResolver.resolveWithExistingCostPeriod(
            form,
            item,
            latestStatus == null ? null : latestStatus.getCostPeriodMonth());
    return new Scope(
        form,
        item,
        form.getOaNo(),
        item.getId(),
        context.productCode(),
        context.costPeriodMonth());
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

  private List<BomCostingRow> loadRows(Scope scope) {
    return bomCostingRowMapper.selectQuoteCostingSnapshot(
        scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
  }

  private QuoteBomPreparationRecord requirePreparation(Scope scope) {
    QuoteBomPreparationRecord preparation =
        preparationRecordMapper.selectOne(
            Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
                .eq(
                    QuoteBomPreparationRecord::getOaFormItemId,
                    scope.oaFormItemId())
                .eq(
                    QuoteBomPreparationRecord::getActiveFlag,
                    ACTIVE)
                .orderByDesc(
                    QuoteBomPreparationRecord::getUpdatedAt)
                .orderByDesc(
                    QuoteBomPreparationRecord::getId)
                .last("LIMIT 1"));
    if (preparation == null
        || !scope.oaNo().equals(preparation.getOaNo())
        || !scope.form().getId().equals(
            preparation.getOaFormId())) {
      throw new QuoteIngestException(
          "当前报价产品没有有效且匹配的BOM准备记录，无法确认");
    }
    return preparation;
  }

  private QuoteBomAlternativeSelectionScope alternativeScope(
      Scope scope, QuoteBomPreparationRecord preparation) {
    QuoteDataOrganization organization =
        MaterialOrganization.normalizeQuoteDataOrganization(
            new QuoteDataOrganization(
                preparation.getPriceOrgCode(),
                preparation.getMaterialOrganizationCode()));
    String businessUnitType =
        firstText(
            scope.item().getBusinessUnitType(),
            scope.form().getBusinessUnitType());
    if (!StringUtils.hasText(businessUnitType)) {
      throw new QuoteIngestException(
          "当前报价产品缺少业务单元，无法确认");
    }
    return new QuoteBomAlternativeSelectionScope(
        scope.oaNo(),
        scope.oaFormItemId(),
        required(
            "BOM顶层产品料号",
            formalProductCode(preparation)),
        scope.periodMonth(),
        organization.priceOrgCode(),
        businessUnitType.trim());
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

  private static String formalProductCode(
      QuoteBomPreparationRecord record) {
    if (PRODUCT_TYPE_NON_BARE.equals(record.getProductType())) {
      return trimToNull(record.getQuoteProductCode());
    }
    return firstText(
        firstText(
            record.getSourceTopProductCode(),
            record.getReferenceFinishedCode()),
        record.getQuoteProductCode());
  }

  private static String required(String field, String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new QuoteIngestException(field + "不能为空");
    }
    return normalized;
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

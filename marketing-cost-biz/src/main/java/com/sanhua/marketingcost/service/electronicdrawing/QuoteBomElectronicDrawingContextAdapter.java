package com.sanhua.marketingcost.service.electronicdrawing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 以共享BOM准备记录承载电子图库流程；workflowId固定为OA产品行ID。 */
@Component
public class QuoteBomElectronicDrawingContextAdapter
    implements ElectronicDrawingWorkflowContextPort {

  private final OaFormItemMapper itemMapper;
  private final OaFormMapper formMapper;
  private final QuoteBomPreparationRecordMapper preparationMapper;
  private final QuoteBomStatusMapper statusMapper;
  private final QuoteBomSupplementVersionMapper versionMapper;
  private final QuoteBomContextResolver contextResolver;
  private final BusinessChangeLogMapper changeLogMapper;

  public QuoteBomElectronicDrawingContextAdapter(
      OaFormItemMapper itemMapper,
      OaFormMapper formMapper,
      QuoteBomPreparationRecordMapper preparationMapper,
      QuoteBomStatusMapper statusMapper,
      QuoteBomSupplementVersionMapper versionMapper,
      QuoteBomContextResolver contextResolver,
      BusinessChangeLogMapper changeLogMapper) {
    this.itemMapper = itemMapper;
    this.formMapper = formMapper;
    this.preparationMapper = preparationMapper;
    this.statusMapper = statusMapper;
    this.versionMapper = versionMapper;
    this.contextResolver = contextResolver;
    this.changeLogMapper = changeLogMapper;
  }

  @Override
  public ElectronicDrawingWorkContext load(
      Long workflowId, String businessUnitType, String applicableOrgCode, String accountingMonth) {
    Scope scope = scope(workflowId, requiredMonth(accountingMonth));
    requireSame(businessUnitType, scope.businessUnit(), "电子图库业务单元不一致");
    requireSame(applicableOrgCode, scope.context().organization().priceOrgCode(),
        "电子图库适用组织不一致");
    return context(scope);
  }

  @Override
  public ElectronicDrawingWorkContext loadForCurrentBusinessUnit(Long workflowId, String accountingMonth) {
    Scope scope = scope(workflowId, StringUtils.hasText(accountingMonth) ? requiredMonth(accountingMonth) : null);
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    if (StringUtils.hasText(current)) {
      requireSame(current, scope.businessUnit(), "当前用户无权访问该电子图库报价产品");
    }
    return context(scope);
  }

  @Override
  public ElectronicDrawingWorkContext attachPreparation(
      ElectronicDrawingWorkContext context,
      Long preparationId,
      String stage,
      Long assigneeUserId,
      String assigneeName,
      LocalDateTime updatedAt) {
    QuoteBomPreparationRecord row = preparationMapper.selectById(preparationId);
    if (row == null || !Objects.equals(row.getOaFormItemId(), context.workflowId())
        || !Objects.equals(row.getCostPeriodMonth(), context.accountingMonth())
        || !Objects.equals(row.getActiveFlag(), 1)) {
      throw conflict();
    }
    if (preparationMapper.updateElectronicStage(
        row.getId(), version(row), stage, assigneeUserId, assigneeName, updatedAt) != 1) {
      throw conflict();
    }
    return load(context.workflowId(), context.businessUnitType(), context.applicableOrgCode(), context.accountingMonth());
  }

  @Override
  public ElectronicDrawingWorkContext attachSourceVersion(
      ElectronicDrawingWorkContext context, Long sourceVersionId, LocalDateTime updatedAt) {
    QuoteBomPreparationRecord row = requirePreparation(context);
    if (preparationMapper.attachElectronicSourceVersion(
        row.getId(), context.revision(), sourceVersionId, updatedAt) != 1) {
      throw conflict();
    }
    return load(context.workflowId(), context.businessUnitType(), context.applicableOrgCode(), context.accountingMonth());
  }

  @Override
  public ElectronicDrawingWorkContext touch(
      ElectronicDrawingWorkContext context,
      Long sourceVersionId,
      Long updatedBy,
      String updatedByName,
      LocalDateTime updatedAt) {
    QuoteBomPreparationRecord row = requirePreparation(context);
    if (preparationMapper.touchElectronicWorkflow(
        row.getId(), context.revision(), sourceVersionId, updatedAt) != 1) {
      throw conflict();
    }
    return load(context.workflowId(), context.businessUnitType(), context.applicableOrgCode(), context.accountingMonth());
  }

  @Override
  public ElectronicDrawingWorkContext updateStage(
      ElectronicDrawingWorkContext context,
      String stage,
      Long assigneeUserId,
      String assigneeName,
      LocalDateTime updatedAt) {
    QuoteBomPreparationRecord row = requirePreparation(context);
    if (preparationMapper.updateElectronicStage(
        row.getId(), context.revision(), stage, assigneeUserId, assigneeName, updatedAt) != 1) {
      throw conflict();
    }
    return load(context.workflowId(), context.businessUnitType(), context.applicableOrgCode(), context.accountingMonth());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ElectronicDrawingWorkContext completePublication(
      ElectronicDrawingWorkContext context,
      String compositionFingerprint,
      LocalDateTime updatedAt) {
    QuoteBomPreparationRecord row = requirePreparation(context);
    if ("PUBLISHED".equals(row.getElectronicWorkflowStage())
        && Objects.equals(row.getElectronicCompositionFingerprint(), compositionFingerprint)) {
      return context(row, scope(context.workflowId(), context.accountingMonth()));
    }
    if (preparationMapper.completeElectronicPublication(
        row.getId(), context.revision(), compositionFingerprint, updatedAt) != 1) {
      throw conflict();
    }
    return load(context.workflowId(), context.businessUnitType(), context.applicableOrgCode(), context.accountingMonth());
  }

  @Override
  public void record(
      ElectronicDrawingWorkContext context, String eventType, String description) {
    BusinessChangeLog log = new BusinessChangeLog();
    log.setBizDomain("QUOTE_BOM");
    log.setBizType("ELECTRONIC_DRAWING");
    log.setBizId(context.preparationId());
    log.setBizDetailId(context.sourceVersionId());
    log.setOaNo(context.oaNo());
    log.setOaFormItemId(context.oaFormItemId());
    log.setFieldName(eventType);
    log.setFieldLabel("电子图库流程事件");
    log.setAfterValue(description);
    log.setChangedBy(0L);
    log.setChangedByName("系统");
    log.setChangedAt(LocalDateTime.now());
    log.setChangeSource("ELECTRONIC_DRAWING");
    log.setIdempotencyKey(eventType + ":" + context.workflowId() + ":"
        + context.accountingMonth() + ":" + context.revision());
    log.setCreatedAt(LocalDateTime.now());
    changeLogMapper.insert(log);
  }

  private ElectronicDrawingWorkContext context(Scope scope) {
    return context(scope.preparation(), scope);
  }

  private ElectronicDrawingWorkContext context(
      QuoteBomPreparationRecord preparation, Scope scope) {
    QuoteBomSupplementVersion source = sourceVersion(preparation);
    String stage = preparation == null ? null : preparation.getElectronicWorkflowStage();
    String fingerprint = firstText(
        preparation == null ? null : preparation.getElectronicCompositionFingerprint(),
        source == null ? null : source.getCompositionFingerprint());
    boolean published = "PUBLISHED".equals(stage)
        && source != null && "APPROVED".equals(source.getVersionStatus())
        && StringUtils.hasText(fingerprint);
    OaFormItem item = scope.item();
    OaForm form = scope.form();
    return new ElectronicDrawingWorkContext(
        item.getId(), preparation == null ? 0 : version(preparation),
        preparation == null ? null : preparation.getId(),
        source == null ? null : source.getId(), form.getId(), item.getId(),
        source == null ? taskNo(form, item, scope.context().costPeriodMonth()) : source.getTaskNo(),
        form.getOaNo(), scope.context().productCode(), null, item.getProductName(), item.getSpec(),
        item.getSunlModel(), preparation == null ? null : preparation.getProductType(), "FULL_BOM",
        scope.context().costPeriodMonth(), scope.context().organization().priceOrgCode(),
        scope.context().organization().materialOrganizationCode(), scope.businessUnit(),
        scope.context().organization().priceOrgCode(),
        preparation == null || Objects.equals(preparation.getActiveFlag(), 1), true,
        published ? "READY_FOR_COSTING" : preparation == null
            ? "NEED_TECH" : preparation.getPreparationStatus(),
        stage,
        preparation == null ? null : preparation.getElectronicAssigneeUserId(),
        preparation == null ? null : preparation.getElectronicAssigneeName(), fingerprint);
  }

  private Scope scope(Long workflowId, String requestedMonth) {
    if (workflowId == null || workflowId <= 0) {
      throw new IllegalArgumentException("电子图库报价产品ID不能为空");
    }
    OaFormItem item = itemMapper.selectById(workflowId);
    if (item == null) throw new IllegalArgumentException("电子图库对应的报价产品不存在");
    OaForm form = formMapper.selectById(item.getOaFormId());
    if (form == null) throw new IllegalArgumentException("电子图库对应的报价单不存在");
    List<QuoteBomPreparationRecord> rows = preparationMapper.selectList(
        Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
            .eq(QuoteBomPreparationRecord::getOaFormItemId, item.getId())
            .eq(QuoteBomPreparationRecord::getActiveFlag, 1)
            .eq(requestedMonth != null, QuoteBomPreparationRecord::getCostPeriodMonth, requestedMonth)
            .orderByDesc(QuoteBomPreparationRecord::getId));
    if (requestedMonth == null && rows != null && rows.stream()
        .map(QuoteBomPreparationRecord::getCostPeriodMonth).distinct().count() > 1) {
      throw new IllegalArgumentException("该报价产品存在多个核算月份，请从对应月份进入电子图库物料确认");
    }
    QuoteBomPreparationRecord preparation =
        rows == null || rows.isEmpty() ? null : rows.getFirst();
    QuoteBomStatus status = preparation == null
        ? statusMapper.selectOne(
            Wrappers.<QuoteBomStatus>lambdaQuery()
                .eq(QuoteBomStatus::getOaFormItemId, item.getId())
                .eq(requestedMonth != null, QuoteBomStatus::getCostPeriodMonth, requestedMonth)
                .orderByDesc(QuoteBomStatus::getCheckedAt)
                .orderByDesc(QuoteBomStatus::getId)
                .last("LIMIT 1"))
        : null;
    String periodMonth = requestedMonth != null ? requestedMonth : preparation != null
        ? preparation.getCostPeriodMonth()
        : status == null ? null : status.getCostPeriodMonth();
    QuoteBomContext context = contextResolver.resolveWithExistingCostPeriod(
        form, item, periodMonth);
    String businessUnit = firstText(item.getBusinessUnitType(), form.getBusinessUnitType());
    if (!StringUtils.hasText(businessUnit)) {
      throw new IllegalArgumentException("电子图库报价产品缺少业务单元");
    }
    return new Scope(form, item, context, businessUnit.trim(), preparation);
  }

  private QuoteBomSupplementVersion sourceVersion(QuoteBomPreparationRecord preparation) {
    if (preparation == null) return null;
    if (preparation.getElectronicSourceVersionId() != null) {
      QuoteBomSupplementVersion source = versionMapper.selectById(preparation.getElectronicSourceVersionId());
      if (source == null || !Objects.equals(source.getPreparationId(), preparation.getId())
          || !Objects.equals(source.getPeriodMonth(), preparation.getCostPeriodMonth())) {
        throw new IllegalStateException("电子图库源版本与本产品核算月份不一致");
      }
      return source;
    }
    List<QuoteBomSupplementVersion> rows = versionMapper.selectList(
        Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
            .eq(QuoteBomSupplementVersion::getPreparationId, preparation.getId())
            .eq(QuoteBomSupplementVersion::getBomSource, "ELECTRONIC_DRAWING_EXCEL")
            .eq(QuoteBomSupplementVersion::getActiveFlag, 1)
            .orderByDesc(QuoteBomSupplementVersion::getVersionNo)
            .orderByDesc(QuoteBomSupplementVersion::getId)
            .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.getFirst();
  }

  private QuoteBomPreparationRecord requirePreparation(ElectronicDrawingWorkContext context) {
    if (context == null || context.preparationId() == null) throw conflict();
    QuoteBomPreparationRecord row = preparationMapper.selectById(context.preparationId());
    if (row == null || !Objects.equals(row.getOaFormItemId(), context.workflowId())
        || !Objects.equals(row.getCostPeriodMonth(), context.accountingMonth())
        || !Objects.equals(row.getActiveFlag(), 1)
        || !Objects.equals(version(row), context.revision())) {
      throw conflict();
    }
    return row;
  }

  private static int version(QuoteBomPreparationRecord row) {
    return row.getElectronicWorkflowVersion() == null ? 0 : row.getElectronicWorkflowVersion();
  }

  private static String requiredMonth(String value) {
    if (!StringUtils.hasText(value) || !value.trim().matches("\\d{4}-\\d{2}")) {
      throw new IllegalArgumentException("电子图库核算月份必须为 YYYY-MM");
    }
    try {
      return YearMonth.parse(value.trim()).toString();
    } catch (java.time.format.DateTimeParseException exception) {
      throw new IllegalArgumentException("电子图库核算月份无效");
    }
  }

  private static String taskNo(OaForm form, OaFormItem item, String month) {
    return "ED-" + form.getOaNo() + "-" + item.getId() + "-" + month;
  }

  private static String firstText(String first, String second) {
    return StringUtils.hasText(first) ? first.trim()
        : StringUtils.hasText(second) ? second.trim() : null;
  }

  private static void requireSame(String actual, String expected, String message) {
    if (!StringUtils.hasText(actual) || !actual.trim().equalsIgnoreCase(expected)) {
      throw new IllegalArgumentException(message);
    }
  }

  private static ElectronicDrawingWorkflowRetryException conflict() {
    return new ElectronicDrawingWorkflowRetryException(
        "电子图库BOM准备记录已变化，系统将在后台自动重试");
  }

  private record Scope(
      OaForm form,
      OaFormItem item,
      QuoteBomContext context,
      String businessUnit,
      QuoteBomPreparationRecord preparation) {}
}

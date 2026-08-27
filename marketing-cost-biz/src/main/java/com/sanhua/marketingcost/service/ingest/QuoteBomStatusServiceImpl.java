package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationCurrentU9BomGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteBomStatusServiceImpl implements QuoteBomStatusService {
  private static final String SNAPSHOT_STATUS_SUCCESS = "SUCCESS";
  private static final String SNAPSHOT_SYNC_TYPE_AUTO = "AUTO";

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final QuoteBomMonthlySnapshotMapper quoteBomMonthlySnapshotMapper;
  private final QuoteCollaborationCurrentU9BomGateway u9BomGateway;
  private final U9ProductPackagingTypeResolver productPackagingTypeResolver;
  private final QuoteBomContextResolver contextResolver;
  private final CollaborationBomAvailabilityResolver collaborationBomAvailabilityResolver;
  private final Clock clock;

  @Autowired
  public QuoteBomStatusServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      QuoteBomMonthlySnapshotMapper quoteBomMonthlySnapshotMapper,
      QuoteCollaborationCurrentU9BomGateway u9BomGateway,
      U9ProductPackagingTypeResolver productPackagingTypeResolver,
      QuoteBomContextResolver contextResolver,
      CollaborationBomAvailabilityResolver collaborationBomAvailabilityResolver) {
    this(
        oaFormMapper,
        oaFormItemMapper,
        quoteBomStatusMapper,
        quoteBomMonthlySnapshotMapper,
        u9BomGateway,
        productPackagingTypeResolver,
        contextResolver,
        collaborationBomAvailabilityResolver,
        Clock.systemDefaultZone());
  }

  QuoteBomStatusServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      QuoteBomMonthlySnapshotMapper quoteBomMonthlySnapshotMapper,
      QuoteCollaborationCurrentU9BomGateway u9BomGateway,
      U9ProductPackagingTypeResolver productPackagingTypeResolver,
      QuoteBomContextResolver contextResolver,
      CollaborationBomAvailabilityResolver collaborationBomAvailabilityResolver,
      Clock clock) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.quoteBomMonthlySnapshotMapper = quoteBomMonthlySnapshotMapper;
    this.u9BomGateway = u9BomGateway;
    this.productPackagingTypeResolver = productPackagingTypeResolver;
    this.contextResolver = contextResolver;
    this.collaborationBomAvailabilityResolver = collaborationBomAvailabilityResolver;
    this.clock = clock;
  }

  @Override
  public QuoteBomStatusResponse listByOaNo(String oaNo) {
    OaForm form = requireForm(oaNo);
    List<OaFormItem> items = listItems(form.getId());
    Map<Long, QuoteBomStatus> statusByItemId = listStatusByItemId(form.getOaNo());
    return buildResponse(form, items, statusByItemId);
  }

  @Override
  @Transactional
  public QuoteBomStatusResponse checkByOaNo(String oaNo) {
    OaForm form = requireForm(oaNo);
    List<OaFormItem> items = listItems(form.getId());
    Map<Long, QuoteBomStatus> statusByItemId = listStatusByItemId(form.getOaNo());
    for (OaFormItem item : items) {
      QuoteBomStatus status = statusByItemId.get(item.getId());
      if (status == null) {
        status = createInitialStatus(form, item);
        quoteBomStatusMapper.insert(status);
        statusByItemId.put(item.getId(), status);
      }
      checkItemForCostRun(form, item, status, LocalDateTime.now(clock), null);
      quoteBomStatusMapper.updateById(status);
    }
    return buildResponse(form, items, statusByItemId);
  }

  @Override
  @Transactional
  public QuoteBomStatusResponse checkForCostRun(String oaNo) {
    OaForm form = requireForm(oaNo);
    List<OaFormItem> items = listItems(form.getId());
    Map<Long, QuoteBomStatus> statusByItemId = listStatusByItemId(form.getOaNo());
    LocalDateTime now = LocalDateTime.now(clock);
    for (OaFormItem item : items) {
      QuoteBomStatus status = statusByItemId.get(item.getId());
      if (status == null) {
        status = createInitialStatus(form, item);
        quoteBomStatusMapper.insert(status);
        statusByItemId.put(item.getId(), status);
      }
      checkItemForCostRun(form, item, status, now, null);
      quoteBomStatusMapper.updateById(status);
    }
    return buildResponse(form, items, statusByItemId);
  }

  @Override
  @Transactional
  public QuoteBomStatusItemResponse checkItemForCostRun(
      String oaNo, Long oaFormItemId) {
    return checkItemForCostRun(oaNo, oaFormItemId, null);
  }

  @Override
  @Transactional
  public QuoteBomStatusItemResponse checkItemForCostRun(
      String oaNo, Long oaFormItemId, String costPeriodMonth) {
    OaForm form = requireForm(oaNo);
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new QuoteIngestException("报价产品行ID不能为空");
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    if (item == null || !Objects.equals(form.getId(), item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不属于当前报价单");
    }
    QuoteBomStatus status =
        quoteBomStatusMapper.selectOne(
            Wrappers.lambdaQuery(QuoteBomStatus.class)
                .eq(QuoteBomStatus::getOaFormItemId, oaFormItemId)
                .last("LIMIT 1"));
    if (status == null) {
      status = createInitialStatus(form, item);
      quoteBomStatusMapper.insert(status);
    }
    checkItemForCostRun(
        form, item, status, LocalDateTime.now(clock), trimToNull(costPeriodMonth));
    quoteBomStatusMapper.updateById(status);
    return toResponseItem(form, item, status);
  }

  private void checkItemForCostRun(
      OaForm form,
      OaFormItem item,
      QuoteBomStatus status,
      LocalDateTime now,
      String costPeriodMonth) {
    QuoteBomContext context;
    try {
      context =
          costPeriodMonth == null
              ? contextResolver.resolve(form, item)
              : contextResolver.resolveWithExistingCostPeriod(form, item, costPeriodMonth);
    } catch (QuoteIngestException ex) {
      applyMissingProductStatus(status, form, item, ex.getMessage(), now);
      return;
    }
    QuoteBomReuseKey key = QuoteBomReuseKey.from(context);
    applyReuseKey(status, key, item, now);

    CurrentU9BomResult u9;
    if (!QuoteProductIdentityUtils.hasFormalMaterialNo(item)) {
      u9 = CurrentU9BomResult.notFound("新品暂无正式料号，请先由技术补齐完整BOM");
    } else {
      try {
        u9 = u9BomGateway.read(scanContext(form, item, context, now));
      } catch (RuntimeException exception) {
        applyCheckFailure(status, "U9月度BOM查询失败：" + exceptionMessage(exception), now);
        return;
      }
    }
    if (u9 == null) {
      applyCheckFailure(status, "U9月度BOM查询没有返回结果", now);
      return;
    }
    if (u9.status() == CurrentU9BomResult.Status.AVAILABLE) {
      QuoteBomMonthlySnapshot snapshot;
      try {
        snapshot = requireU9Snapshot(u9);
      } catch (QuoteIngestException exception) {
        applyCheckFailure(status, exception.getMessage(), now);
        return;
      }
      BomAvailability availability = u9Availability(u9);
      if (u9.monthlySnapshotCreated()) {
        applySyncedSnapshot(status, availability, snapshot, now);
      } else {
        applyReusedSnapshot(status, snapshot, now);
      }
      return;
    }
    if (u9.status() != CurrentU9BomResult.Status.NOT_FOUND) {
      applyCheckFailure(status, u9FailureMessage(u9), now);
      return;
    }

    // U9 的 NOT_FOUND 已按月冻结；之后只在电子图库审核结果和人工补录链路内继续。
    QuoteBomMonthlySnapshot approvedSnapshot =
        findActiveApprovedSnapshot(key, context.organization());
    if (approvedSnapshot != null) {
      applyReusedSnapshot(status, approvedSnapshot, now);
      return;
    }
    BomAvailability noU9 = BomAvailability.unavailable(u9.message());
    BomAvailability collaboration = collaborationBomAvailabilityResolver.resolve(
        item.getId(), requiredBusinessUnit(form, item), key.getCostPeriodMonth(), noU9);
    if (collaboration == null || !collaboration.isAvailable()) {
      applyNoBomStatus(
          status,
          collaboration == null ? noU9 : collaboration,
          u9.monthlySnapshotId(),
          u9.monthlySnapshotCreated(),
          now);
      return;
    }

    QuoteBomMonthlySnapshot snapshot =
        createAutoSuccessSnapshot(form, item, key, collaboration, context.organization(), now);
    deactivateActiveApprovedSnapshots(key, context.organization());
    quoteBomMonthlySnapshotMapper.insert(snapshot);
    applySyncedSnapshot(status, collaboration, snapshot, now);
  }

  private QuoteBomMonthlySnapshot findActiveApprovedSnapshot(
      QuoteBomReuseKey key, QuoteDataOrganization organization) {
    List<QuoteBomMonthlySnapshot> snapshots =
        quoteBomMonthlySnapshotMapper.selectList(
            Wrappers.<QuoteBomMonthlySnapshot>query()
                .eq("product_code", key.getProductCode())
                .eq("price_org_code", organization.priceOrgCode())
                .eq("customer_code", key.getCustomerCode())
                .eq("package_method", key.getPackageMethod())
                .eq("cost_period_month", key.getCostPeriodMonth())
                .isNull("snapshot_identity_key")
                .eq("sync_status", SNAPSHOT_STATUS_SUCCESS)
                .eq("active_flag", 1)
                .orderByDesc("sync_at")
                .orderByDesc("id")
                .last("LIMIT 1 FOR UPDATE"));
    return snapshots.isEmpty() ? null : snapshots.get(0);
  }

  private void deactivateActiveApprovedSnapshots(
      QuoteBomReuseKey key, QuoteDataOrganization organization) {
    quoteBomMonthlySnapshotMapper.update(
        null,
        Wrappers.<QuoteBomMonthlySnapshot>update()
            .set("active_flag", 0)
            .eq("product_code", key.getProductCode())
            .eq("price_org_code", organization.priceOrgCode())
            .eq("customer_code", key.getCustomerCode())
            .eq("package_method", key.getPackageMethod())
            .eq("cost_period_month", key.getCostPeriodMonth())
            .isNull("snapshot_identity_key")
            .eq("active_flag", 1));
  }

  private QuoteBomMonthlySnapshot createAutoSuccessSnapshot(
      OaForm form,
      OaFormItem item,
      QuoteBomReuseKey key,
      BomAvailability availability,
      QuoteDataOrganization organization,
      LocalDateTime now) {
    QuoteBomMonthlySnapshot snapshot = new QuoteBomMonthlySnapshot();
    snapshot.setProductCode(key.getProductCode());
    snapshot.setPriceOrgCode(organization.priceOrgCode());
    snapshot.setBusinessUnitType(requiredBusinessUnit(form, item));
    snapshot.setMaterialOrganizationCode(organization.materialOrganizationCode());
    snapshot.setCustomerCode(key.getCustomerCode());
    snapshot.setPackageMethod(key.getPackageMethod());
    snapshot.setCostPeriodMonth(key.getCostPeriodMonth());
    snapshot.setBomSource(availability.getSource());
    snapshot.setBomPurpose(availability.getBomPurpose());
    snapshot.setBomVersion(availability.getBomVersion());
    snapshot.setSyncType(SNAPSHOT_SYNC_TYPE_AUTO);
    snapshot.setSyncStatus(SNAPSHOT_STATUS_SUCCESS);
    snapshot.setSyncAt(now);
    snapshot.setSyncBy(currentUsername("SYSTEM"));
    snapshot.setSourceOaNo(form.getOaNo());
    snapshot.setSourceOaFormItemId(item.getId());
    snapshot.setBomBatchId(availability.getSyncBatchId());
    snapshot.setActiveFlag(1);
    snapshot.setCreatedAt(now);
    snapshot.setUpdatedAt(now);
    return snapshot;
  }

  private void applyReuseKey(
      QuoteBomStatus status, QuoteBomReuseKey key, OaFormItem item, LocalDateTime now) {
    status.setProductCode(key.getProductCode());
    status.setProductModel(trimToNull(item.getSunlModel()));
    status.setCustomerCode(key.getCustomerCode());
    status.setPackageType(trimToNull(item.getPackageType()));
    status.setPackageMethod(key.getPackageMethod());
    status.setCostPeriodMonth(key.getCostPeriodMonth());
    status.setTechnicianName(trimToNull(item.getTechnicianName()));
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
  }

  private void applyReusedSnapshot(
      QuoteBomStatus status, QuoteBomMonthlySnapshot snapshot, LocalDateTime now) {
    status.setBomStatus(QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode());
    status.setBomSource(snapshot.getBomSource());
    status.setBomPurpose(snapshot.getBomPurpose());
    status.setBomVersion(snapshot.getBomVersion());
    status.setEffectiveFrom(null);
    status.setEffectiveTo(null);
    status.setSyncBatchId(snapshot.getBomBatchId());
    status.setSyncRecordId(snapshot.getId());
    status.setReusedFromRecordId(snapshot.getId());
    status.setSyncAt(snapshot.getSyncAt());
    status.setErrorMessage(null);
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
  }

  private void applySyncedSnapshot(
      QuoteBomStatus status,
      BomAvailability availability,
      QuoteBomMonthlySnapshot snapshot,
      LocalDateTime now) {
    status.setBomStatus(statusForAvailability(availability));
    status.setBomSource(availability.getSource());
    status.setBomPurpose(availability.getBomPurpose());
    status.setBomVersion(availability.getBomVersion());
    status.setEffectiveFrom(availability.getEffectiveFrom());
    status.setEffectiveTo(availability.getEffectiveTo());
    status.setSyncBatchId(availability.getSyncBatchId());
    status.setSyncRecordId(snapshot.getId());
    status.setReusedFromRecordId(null);
    status.setSyncAt(now);
    status.setErrorMessage(null);
  }

  private void applyNoBomStatus(
      QuoteBomStatus status,
      BomAvailability availability,
      Long monthlySnapshotId,
      boolean monthlySnapshotCreated,
      LocalDateTime now) {
    status.setBomStatus(QuoteBomStatusCode.NO_BOM.getCode());
    status.setBomSource(null);
    status.setBomPurpose(null);
    status.setBomVersion(null);
    status.setEffectiveFrom(null);
    status.setEffectiveTo(null);
    status.setSyncBatchId(null);
    status.setSyncRecordId(monthlySnapshotId);
    status.setReusedFromRecordId(monthlySnapshotCreated ? null : monthlySnapshotId);
    status.setSyncAt(null);
    status.setErrorMessage(availability.getMessage());
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
  }

  private void applyMissingProductStatus(
      QuoteBomStatus status, OaForm form, OaFormItem item, String message, LocalDateTime now) {
    applyStatusIdentity(status, form, item, now);
    status.setBomStatus(QuoteBomStatusCode.NO_BOM.getCode());
    status.setBomSource(null);
    status.setBomPurpose(null);
    status.setBomVersion(null);
    status.setEffectiveFrom(null);
    status.setEffectiveTo(null);
    status.setSyncBatchId(null);
    status.setSyncRecordId(null);
    status.setReusedFromRecordId(null);
    status.setSyncAt(null);
    status.setErrorMessage(message);
  }

  private QuoteCollaborationScanContext scanContext(
      OaForm form, OaFormItem item, QuoteBomContext context, LocalDateTime now) {
    return new QuoteCollaborationScanContext(
        form.getId(), item.getId(), form.getOaNo(), context.costPeriodMonth(),
        requiredBusinessUnit(form, item), context.productCode(), item.getProductName(),
        item.getSpec(), item.getSunlModel(), context.organization().priceOrgCode(),
        context.organization().materialOrganizationCode(), now.toLocalDate(), now);
  }

  private QuoteBomMonthlySnapshot requireU9Snapshot(CurrentU9BomResult u9) {
    if (u9.monthlySnapshotId() == null) {
      throw new QuoteIngestException("U9月度BOM结果缺少快照ID");
    }
    QuoteBomMonthlySnapshot snapshot =
        quoteBomMonthlySnapshotMapper.selectById(u9.monthlySnapshotId());
    if (snapshot == null || !StringUtils.hasText(snapshot.getSnapshotIdentityKey())) {
      throw new QuoteIngestException("U9月度BOM快照不存在或身份无效");
    }
    return snapshot;
  }

  private BomAvailability u9Availability(CurrentU9BomResult u9) {
    BomAvailability availability = new BomAvailability();
    availability.setAvailable(true);
    availability.setSource(trimToNull(u9.source()) == null ? "U9" : u9.source());
    availability.setBomPurpose("主制造");
    availability.setBomVersion(u9.bomVersion());
    availability.setSyncBatchId(u9.syncBatchId());
    return availability;
  }

  private String u9FailureMessage(CurrentU9BomResult u9) {
    String message = trimToNull(u9.message());
    return message == null ? "U9月度BOM查询失败：" + u9.status() : message;
  }

  private void applyCheckFailure(
      QuoteBomStatus status, String message, LocalDateTime now) {
    status.setBomStatus(QuoteBomStatusCode.CHECK_FAILED.getCode());
    status.setBomSource(null);
    status.setBomPurpose(null);
    status.setBomVersion(null);
    status.setEffectiveFrom(null);
    status.setEffectiveTo(null);
    status.setSyncBatchId(null);
    status.setSyncRecordId(null);
    status.setReusedFromRecordId(null);
    status.setSyncAt(null);
    status.setErrorMessage(message);
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
  }

  private String requiredBusinessUnit(OaForm form, OaFormItem item) {
    String value = trimToNull(item.getBusinessUnitType());
    if (value == null) value = trimToNull(form.getBusinessUnitType());
    if (value == null) throw new QuoteIngestException("报价产品缺少业务单元");
    return value;
  }

  private String exceptionMessage(RuntimeException exception) {
    String message = exception == null ? null : trimToNull(exception.getMessage());
    return message == null ? exception.getClass().getSimpleName() : message;
  }

  private QuoteBomStatus createInitialStatus(OaForm form, OaFormItem item) {
    QuoteBomStatus status = new QuoteBomStatus();
    LocalDateTime now = LocalDateTime.now(clock);
    applyStatusIdentity(status, form, item, now);
    status.setCheckedAt(null);
    status.setBomStatus(
        StringUtils.hasText(item.getMaterialNo())
            ? QuoteBomStatusCode.NOT_CHECKED.getCode()
            : QuoteBomStatusCode.NO_BOM.getCode());
    status.setCreatedAt(now);
    status.setUpdatedAt(now);
    return status;
  }

  private void applyStatusIdentity(
      QuoteBomStatus status, OaForm form, OaFormItem item, LocalDateTime now) {
    status.setOaFormId(form.getId());
    status.setOaFormItemId(item.getId());
    status.setOaNo(form.getOaNo());
    status.setProductCode(QuoteProductIdentityUtils.resolveCostingCode(item));
    status.setProductModel(trimToNull(item.getSunlModel()));
    status.setCustomerCode(contextResolver.resolveCustomer(form, null).value());
    status.setPackageType(trimToNull(item.getPackageType()));
    status.setPackageMethod(contextResolver.normalizePackageMethod(item.getPackageMethod()));
    try {
      status.setCostPeriodMonth(contextResolver.resolveCostPeriodMonth(form));
    } catch (QuoteIngestException ex) {
      status.setCostPeriodMonth(null);
    }
    status.setTechnicianName(trimToNull(item.getTechnicianName()));
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
  }

  private OaForm requireForm(String oaNo) {
    String normalized = trimToNull(oaNo);
    if (normalized == null) {
      throw new QuoteIngestException("报价单号不能为空");
    }
    OaForm form =
        oaFormMapper.selectOne(Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, normalized));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + normalized);
    }
    return form;
  }

  private List<OaFormItem> listItems(Long oaFormId) {
    return oaFormItemMapper.selectList(
        Wrappers.lambdaQuery(OaFormItem.class)
            .eq(OaFormItem::getOaFormId, oaFormId)
            .orderByAsc(OaFormItem::getSeq)
            .orderByAsc(OaFormItem::getId));
  }

  private Map<Long, QuoteBomStatus> listStatusByItemId(String oaNo) {
    List<QuoteBomStatus> statuses =
        quoteBomStatusMapper.selectList(
            Wrappers.lambdaQuery(QuoteBomStatus.class).eq(QuoteBomStatus::getOaNo, oaNo));
    Map<Long, QuoteBomStatus> map = new LinkedHashMap<>();
    for (QuoteBomStatus status : statuses) {
      map.put(status.getOaFormItemId(), status);
    }
    return map;
  }

  private QuoteBomStatusResponse buildResponse(
      OaForm form, List<OaFormItem> items, Map<Long, QuoteBomStatus> statusByItemId) {
    QuoteBomStatusResponse response = new QuoteBomStatusResponse();
    response.setOaNo(form.getOaNo());
    response.setTotalCount(items.size());
    for (OaFormItem item : items) {
      QuoteBomStatus status = statusByItemId.get(item.getId());
      QuoteBomStatusItemResponse row = toResponseItem(form, item, status);
      response.getItems().add(row);
      if (isCostReadyBomStatus(row.getBomStatus())) {
        response.setSyncedCount(response.getSyncedCount() + 1);
      } else if (QuoteBomStatusCode.NO_BOM.getCode().equals(row.getBomStatus())) {
        response.setNoBomCount(response.getNoBomCount() + 1);
      } else {
        response.setUncheckedCount(response.getUncheckedCount() + 1);
      }
    }
    return response;
  }

  private QuoteBomStatusItemResponse toResponseItem(OaForm form, OaFormItem item, QuoteBomStatus status) {
    QuoteBomStatusItemResponse row = new QuoteBomStatusItemResponse();
    row.setSeq(item.getSeq());
    row.setOaFormItemId(item.getId());
    row.setProductCode(QuoteProductIdentityUtils.resolveCostingCode(item));
    row.setProductModel(trimToNull(item.getSunlModel()));
    if (status == null) {
      applyProductPackagingType(row, form, item);
      row.setBomStatus(
          StringUtils.hasText(item.getMaterialNo())
              ? QuoteBomStatusCode.NOT_CHECKED.getCode()
              : QuoteBomStatusCode.NO_BOM.getCode());
      row.setErrorMessage(
          StringUtils.hasText(item.getMaterialNo())
              ? null
              : "新品暂无正式料号，请先由技术补齐完整BOM");
      return row;
    }
    row.setId(status.getId());
    row.setProductCode(status.getProductCode());
    row.setProductModel(status.getProductModel());
    applyProductPackagingType(row, form, item);
    row.setBomStatus(status.getBomStatus());
    row.setBomSource(status.getBomSource());
    row.setBomPurpose(status.getBomPurpose());
    row.setBomVersion(status.getBomVersion());
    row.setEffectiveFrom(status.getEffectiveFrom());
    row.setEffectiveTo(status.getEffectiveTo());
    row.setCheckedAt(status.getCheckedAt());
    row.setSyncBatchId(status.getSyncBatchId());
    row.setCostPeriodMonth(status.getCostPeriodMonth());
    row.setSyncRecordId(status.getSyncRecordId());
    row.setReusedFromRecordId(status.getReusedFromRecordId());
    row.setSyncAt(status.getSyncAt());
    row.setErrorMessage(status.getErrorMessage());
    return row;
  }

  private void applyProductPackagingType(QuoteBomStatusItemResponse row, OaForm form, OaFormItem item) {
    QuoteDataOrganization organization = contextResolver.resolveOrganization(form, item);
    U9ProductPackagingTypeResolver.Result result =
        productPackagingTypeResolver.resolve(
            row.getProductCode(), organization.materialOrganizationCode());
    row.setProductPackagingType(result.productPackagingType());
    row.setMainCategoryCode(result.mainCategoryCode());
  }

  private boolean isCostReadyBomStatus(String bomStatus) {
    // 统计口径跟成本准入保持一致：检查确认有可用 BOM 的状态都算 BOM 已准备。
    return QuoteBomStatusCode.SYNCED.getCode().equals(bomStatus)
        || QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode().equals(bomStatus)
        || QuoteBomStatusCode.CURRENT_MONTH_QUOTED.getCode().equals(bomStatus)
        || QuoteBomStatusCode.U9_BOM_EXISTS.getCode().equals(bomStatus)
        || QuoteBomStatusCode.MANUAL_ENTERED.getCode().equals(bomStatus);
  }

  private String statusForAvailability(BomAvailability availability) {
    String source = availability == null ? null : trimToNull(availability.getSource());
    if ("ELECTRONIC_DRAWING_BOM".equalsIgnoreCase(source)
        || "U9_BODY+APPROVED_PACKAGE".equalsIgnoreCase(source)) {
      return QuoteBomStatusCode.MANUAL_ENTERED.getCode();
    }
    return QuoteBomStatusCode.U9_BOM_EXISTS.getCode();
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
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
}

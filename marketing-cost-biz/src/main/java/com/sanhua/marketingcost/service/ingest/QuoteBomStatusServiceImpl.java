package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private static final String SNAPSHOT_SYNC_TYPE_MANUAL = "MANUAL";
  private static final String SOURCE_COSTING_SNAPSHOT = "COSTING_SNAPSHOT";
  private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final QuoteBomMonthlySnapshotMapper quoteBomMonthlySnapshotMapper;
  private final BomAvailabilityAdapter bomAvailabilityAdapter;
  private final BomU9SourceMapper bomU9SourceMapper;
  private final U9ProductPackagingTypeResolver productPackagingTypeResolver;
  private final Clock clock;

  @Autowired
  public QuoteBomStatusServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      QuoteBomMonthlySnapshotMapper quoteBomMonthlySnapshotMapper,
      BomAvailabilityAdapter bomAvailabilityAdapter,
      BomU9SourceMapper bomU9SourceMapper,
      U9ProductPackagingTypeResolver productPackagingTypeResolver) {
    this(
        oaFormMapper,
        oaFormItemMapper,
        quoteBomStatusMapper,
        quoteBomMonthlySnapshotMapper,
        bomAvailabilityAdapter,
        bomU9SourceMapper,
        productPackagingTypeResolver,
        Clock.systemDefaultZone());
  }

  QuoteBomStatusServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      QuoteBomMonthlySnapshotMapper quoteBomMonthlySnapshotMapper,
      BomAvailabilityAdapter bomAvailabilityAdapter,
      BomU9SourceMapper bomU9SourceMapper,
      U9ProductPackagingTypeResolver productPackagingTypeResolver,
      Clock clock) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.quoteBomMonthlySnapshotMapper = quoteBomMonthlySnapshotMapper;
    this.bomAvailabilityAdapter = bomAvailabilityAdapter;
    this.bomU9SourceMapper = bomU9SourceMapper;
    this.productPackagingTypeResolver = productPackagingTypeResolver;
    this.clock = clock;
  }

  @Override
  public QuoteBomStatusResponse listByOaNo(String oaNo) {
    OaForm form = requireForm(oaNo);
    List<OaFormItem> items = listItems(form.getId());
    Map<Long, QuoteBomStatus> statusByItemId = listStatusByItemId(form.getOaNo());
    return buildResponse(form.getOaNo(), items, statusByItemId);
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
      applyAvailability(status, item);
      quoteBomStatusMapper.updateById(status);
    }
    return buildResponse(form.getOaNo(), items, statusByItemId);
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
      checkItemForCostRun(form, item, status, now);
      quoteBomStatusMapper.updateById(status);
    }
    return buildResponse(form.getOaNo(), items, statusByItemId);
  }

  @Override
  @Transactional
  public QuoteBomBatchSyncResponse batchSyncFromU9Source(List<Long> oaFormItemIds) {
    List<Long> itemIds = normalizeIds(oaFormItemIds);
    if (itemIds.isEmpty()) {
      throw new QuoteIngestException("请选择需要同步的报价单产品行");
    }

    List<OaFormItem> items =
        oaFormItemMapper.selectList(Wrappers.lambdaQuery(OaFormItem.class).in(OaFormItem::getId, itemIds));
    if (items.isEmpty()) {
      throw new QuoteIngestException("未找到可同步的报价单产品行");
    }

    Map<Long, OaForm> formById = loadFormsById(collectFormIds(items));
    Map<String, BomU9Source> latestU9ByProduct = loadLatestU9ByProduct(collectProductCodes(items));
    Map<Long, QuoteBomStatus> statusByItemId = listStatusByItemIds(itemIds);

    QuoteBomBatchSyncResponse response = new QuoteBomBatchSyncResponse();
    response.setSelectedRowCount(items.size());
    response.setDistinctProductCount(latestU9ByProduct.size());

    Set<String> missingProducts = new LinkedHashSet<>();
    LocalDateTime now = LocalDateTime.now();
    for (OaFormItem item : items) {
      OaForm form = formById.get(item.getOaFormId());
      if (form == null) {
        response.setSkippedRowCount(response.getSkippedRowCount() + 1);
        continue;
      }
      QuoteBomStatus status = statusByItemId.get(item.getId());
      if (status == null) {
        status = createInitialStatus(form, item);
        quoteBomStatusMapper.insert(status);
        statusByItemId.put(item.getId(), status);
      }
      // T14 口径：这里的“U9 同步”先读取 lp_bom_u9_source 本地全量快照，不代表已经接入外部 U9 实时接口。
      applyManualU9SourceSnapshot(
          status, form, item, latestU9ByProduct.get(trimToNull(item.getMaterialNo())), now);
      quoteBomStatusMapper.updateById(status);
      response.getItems().add(toResponseItem(item, status));
      if (QuoteBomStatusCode.SYNCED.getCode().equals(status.getBomStatus())) {
        response.setSyncedRowCount(response.getSyncedRowCount() + 1);
      } else {
        response.setNoBomRowCount(response.getNoBomRowCount() + 1);
        String productCode = trimToNull(item.getMaterialNo());
        if (productCode != null) {
          missingProducts.add(productCode);
        }
      }
    }
    response.setMissingProductCodes(new ArrayList<>(missingProducts));
    response.setDistinctProductCount(collectProductCodes(items).size());
    return response;
  }

  private void applyManualU9SourceSnapshot(
      QuoteBomStatus status, OaForm form, OaFormItem item, BomU9Source source, LocalDateTime now) {
    QuoteBomReuseKey key;
    try {
      key = QuoteBomReuseKey.from(form, item, clock);
    } catch (QuoteIngestException ex) {
      applyManualSyncFailure(status, form, item, ex.getMessage(), now);
      return;
    }
    applyReuseKey(status, key, item, now);
    if (source == null) {
      applyManualSyncFailure(status, form, item, "本地 U9 全量快照中未找到该产品 BOM", now);
      return;
    }

    // 手动同步成功后切换组合当月 active 快照；失败分支不会动旧 active 记录，避免破坏已可核算数据。
    QuoteBomMonthlySnapshot snapshot = createManualSuccessSnapshot(form, item, key, source, now);
    deactivateActiveSnapshots(key);
    quoteBomMonthlySnapshotMapper.insert(snapshot);
    status.setBomStatus(QuoteBomStatusCode.SYNCED.getCode());
    status.setBomSource(defaultU9Source(source.getSourceType()));
    status.setBomPurpose(source.getBomPurpose());
    status.setBomVersion(source.getBomVersion());
    status.setEffectiveFrom(source.getEffectiveFrom());
    status.setEffectiveTo(source.getEffectiveTo());
    status.setSyncBatchId(source.getImportBatchId());
    status.setSyncRecordId(snapshot.getId());
    status.setReusedFromRecordId(null);
    status.setSyncAt(now);
    status.setErrorMessage(null);
  }

  private void checkItemForCostRun(OaForm form, OaFormItem item, QuoteBomStatus status, LocalDateTime now) {
    QuoteBomReuseKey key;
    try {
      key = QuoteBomReuseKey.from(form, item, clock);
    } catch (QuoteIngestException ex) {
      applyMissingProductStatus(status, form, item, ex.getMessage(), now);
      return;
    }
    applyReuseKey(status, key, item, now);

    QuoteBomMonthlySnapshot activeSnapshot = findActiveSuccessSnapshot(key);
    if (activeSnapshot != null) {
      // 同组合当月已经存在 active 成功快照，成本核算直接复用来源 BOM，不再重复自动拉取。
      applyReusedSnapshot(status, activeSnapshot, now);
      return;
    }

    BomAvailability availability =
        bomAvailabilityAdapter.findAvailableBom(
            form.getOaNo(), key.getProductCode(), key.getCostPeriodMonth());
    if (!availability.isAvailable()) {
      applyNoBomStatus(status, availability, now);
      return;
    }

    // 首次进入当月成本核算且可找到 BOM 时，生成新的 active 快照，后续同组合本月可直接沿用。
    QuoteBomMonthlySnapshot snapshot = createAutoSuccessSnapshot(form, item, key, availability, now);
    deactivateActiveSnapshots(key);
    quoteBomMonthlySnapshotMapper.insert(snapshot);
    applySyncedSnapshot(status, availability, snapshot, now);
  }

  private QuoteBomMonthlySnapshot findActiveSuccessSnapshot(QuoteBomReuseKey key) {
    List<QuoteBomMonthlySnapshot> snapshots =
        quoteBomMonthlySnapshotMapper.selectList(
            Wrappers.<QuoteBomMonthlySnapshot>query()
                .eq("product_code", key.getProductCode())
                .eq("customer_code", key.getCustomerCode())
                .eq("package_method", key.getPackageMethod())
                .eq("cost_period_month", key.getCostPeriodMonth())
                .eq("sync_status", SNAPSHOT_STATUS_SUCCESS)
                .eq("active_flag", 1)
                .orderByDesc("sync_at")
                .orderByDesc("id")
                .last("LIMIT 1"));
    return snapshots.isEmpty() ? null : snapshots.get(0);
  }

  private void deactivateActiveSnapshots(QuoteBomReuseKey key) {
    quoteBomMonthlySnapshotMapper.update(
        null,
        Wrappers.<QuoteBomMonthlySnapshot>update()
            .set("active_flag", 0)
            .eq("product_code", key.getProductCode())
            .eq("customer_code", key.getCustomerCode())
            .eq("package_method", key.getPackageMethod())
            .eq("cost_period_month", key.getCostPeriodMonth())
            .eq("active_flag", 1));
  }

  private QuoteBomMonthlySnapshot createAutoSuccessSnapshot(
      OaForm form,
      OaFormItem item,
      QuoteBomReuseKey key,
      BomAvailability availability,
      LocalDateTime now) {
    QuoteBomMonthlySnapshot snapshot = new QuoteBomMonthlySnapshot();
    snapshot.setProductCode(key.getProductCode());
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

  private QuoteBomMonthlySnapshot createManualSuccessSnapshot(
      OaForm form,
      OaFormItem item,
      QuoteBomReuseKey key,
      BomU9Source source,
      LocalDateTime now) {
    QuoteBomMonthlySnapshot snapshot = new QuoteBomMonthlySnapshot();
    snapshot.setProductCode(key.getProductCode());
    snapshot.setCustomerCode(key.getCustomerCode());
    snapshot.setPackageMethod(key.getPackageMethod());
    snapshot.setCostPeriodMonth(key.getCostPeriodMonth());
    snapshot.setBomSource(defaultU9Source(source.getSourceType()));
    snapshot.setBomPurpose(source.getBomPurpose());
    snapshot.setBomVersion(source.getBomVersion());
    snapshot.setSyncType(SNAPSHOT_SYNC_TYPE_MANUAL);
    snapshot.setSyncStatus(SNAPSHOT_STATUS_SUCCESS);
    snapshot.setSyncAt(now);
    snapshot.setSyncBy(currentUsername("MANUAL"));
    snapshot.setSourceOaNo(form.getOaNo());
    snapshot.setSourceOaFormItemId(item.getId());
    snapshot.setBomBatchId(source.getImportBatchId());
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

  private void applyNoBomStatus(QuoteBomStatus status, BomAvailability availability, LocalDateTime now) {
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
    status.setErrorMessage(availability.getMessage());
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
  }

  private void applyMissingProductStatus(
      QuoteBomStatus status, OaForm form, OaFormItem item, String message, LocalDateTime now) {
    status.setOaFormId(form.getId());
    status.setOaFormItemId(item.getId());
    status.setOaNo(form.getOaNo());
    status.setProductCode(trimToNull(item.getMaterialNo()));
    status.setProductModel(trimToNull(item.getSunlModel()));
    status.setCustomerCode(QuoteBomReuseKey.normalizeEmpty(firstText(item.getCustomerCode(), form.getCustomer())));
    status.setPackageType(trimToNull(item.getPackageType()));
    status.setPackageMethod(QuoteBomReuseKey.normalizeEmpty(item.getPackageMethod()));
    status.setCostPeriodMonth(null);
    status.setTechnicianName(trimToNull(item.getTechnicianName()));
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
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

  private void applyManualSyncFailure(
      QuoteBomStatus status, OaForm form, OaFormItem item, String message, LocalDateTime now) {
    status.setOaFormId(form.getId());
    status.setOaFormItemId(item.getId());
    status.setOaNo(form.getOaNo());
    status.setProductCode(trimToNull(item.getMaterialNo()));
    status.setProductModel(trimToNull(item.getSunlModel()));
    status.setCustomerCode(QuoteBomReuseKey.normalizeEmpty(firstText(item.getCustomerCode(), form.getCustomer())));
    status.setPackageType(trimToNull(item.getPackageType()));
    status.setPackageMethod(QuoteBomReuseKey.normalizeEmpty(item.getPackageMethod()));
    status.setTechnicianName(trimToNull(item.getTechnicianName()));
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
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
  }

  private void applyAvailability(QuoteBomStatus status, OaFormItem item) {
    status.setProductCode(trimToNull(item.getMaterialNo()));
    status.setProductModel(trimToNull(item.getSunlModel()));
    status.setCustomerCode(trimToNull(item.getCustomerCode()));
    status.setPackageType(trimToNull(item.getPackageType()));
    status.setPackageMethod(trimToNull(item.getPackageMethod()));
    status.setTechnicianName(trimToNull(item.getTechnicianName()));
    status.setCostPeriodMonth(currentPeriodMonth());
    status.setCheckedAt(LocalDateTime.now());
    status.setUpdatedAt(LocalDateTime.now());

    BomAvailability availability =
        bomAvailabilityAdapter.findAvailableBom(
            status.getOaNo(), item.getMaterialNo(), status.getCostPeriodMonth());
    if (availability.isAvailable()) {
      status.setBomStatus(statusForAvailability(availability));
      status.setBomSource(availability.getSource());
      status.setBomPurpose(availability.getBomPurpose());
      status.setBomVersion(availability.getBomVersion());
      status.setEffectiveFrom(availability.getEffectiveFrom());
      status.setEffectiveTo(availability.getEffectiveTo());
      status.setSyncBatchId(availability.getSyncBatchId());
      status.setErrorMessage(null);
      return;
    }

    status.setBomStatus(QuoteBomStatusCode.NO_BOM.getCode());
    status.setBomSource(null);
    status.setBomPurpose(null);
    status.setBomVersion(null);
    status.setEffectiveFrom(null);
    status.setEffectiveTo(null);
    status.setSyncBatchId(null);
    status.setErrorMessage(availability.getMessage());
  }

  private QuoteBomStatus createInitialStatus(OaForm form, OaFormItem item) {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setOaFormId(form.getId());
    status.setOaFormItemId(item.getId());
    status.setOaNo(form.getOaNo());
    status.setProductCode(trimToNull(item.getMaterialNo()));
    status.setProductModel(trimToNull(item.getSunlModel()));
    status.setCustomerCode(trimToNull(item.getCustomerCode()));
    status.setPackageType(trimToNull(item.getPackageType()));
    status.setPackageMethod(trimToNull(item.getPackageMethod()));
    status.setTechnicianName(trimToNull(item.getTechnicianName()));
    status.setBomStatus(
        StringUtils.hasText(item.getMaterialNo())
            ? QuoteBomStatusCode.NOT_CHECKED.getCode()
            : QuoteBomStatusCode.NO_BOM.getCode());
    status.setCreatedAt(LocalDateTime.now());
    status.setUpdatedAt(LocalDateTime.now());
    return status;
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

  private Map<Long, QuoteBomStatus> listStatusByItemIds(List<Long> itemIds) {
    List<QuoteBomStatus> statuses =
        quoteBomStatusMapper.selectList(
            Wrappers.lambdaQuery(QuoteBomStatus.class).in(QuoteBomStatus::getOaFormItemId, itemIds));
    Map<Long, QuoteBomStatus> map = new LinkedHashMap<>();
    for (QuoteBomStatus status : statuses) {
      map.put(status.getOaFormItemId(), status);
    }
    return map;
  }

  private List<Long> normalizeIds(List<Long> ids) {
    if (ids == null) {
      return List.of();
    }
    LinkedHashSet<Long> normalized = new LinkedHashSet<>();
    for (Long id : ids) {
      if (id != null && id > 0) {
        normalized.add(id);
      }
    }
    return new ArrayList<>(normalized);
  }

  private Set<Long> collectFormIds(List<OaFormItem> items) {
    Set<Long> ids = new LinkedHashSet<>();
    for (OaFormItem item : items) {
      if (item.getOaFormId() != null) {
        ids.add(item.getOaFormId());
      }
    }
    return ids;
  }

  private Set<String> collectProductCodes(List<OaFormItem> items) {
    Set<String> codes = new LinkedHashSet<>();
    for (OaFormItem item : items) {
      String productCode = trimToNull(item.getMaterialNo());
      if (productCode != null) {
        codes.add(productCode);
      }
    }
    return codes;
  }

  private Map<Long, OaForm> loadFormsById(Collection<Long> formIds) {
    Map<Long, OaForm> map = new LinkedHashMap<>();
    if (formIds == null || formIds.isEmpty()) {
      return map;
    }
    List<OaForm> forms = oaFormMapper.selectBatchIds(formIds);
    for (OaForm form : forms) {
      map.put(form.getId(), form);
    }
    return map;
  }

  private Map<String, BomU9Source> loadLatestU9ByProduct(Set<String> productCodes) {
    Map<String, BomU9Source> map = new LinkedHashMap<>();
    if (productCodes.isEmpty()) {
      return map;
    }
    List<BomU9Source> rows =
        bomU9SourceMapper.selectList(
            Wrappers.lambdaQuery(BomU9Source.class)
                .in(BomU9Source::getParentMaterialNo, productCodes)
                .orderByDesc(BomU9Source::getImportedAt)
                .orderByDesc(BomU9Source::getId));
    for (BomU9Source row : rows) {
      String productCode = trimToNull(row.getParentMaterialNo());
      if (productCode != null && !map.containsKey(productCode)) {
        map.put(productCode, row);
      }
    }
    return map;
  }

  private String defaultU9Source(String sourceType) {
    if ("U9_API".equalsIgnoreCase(trimToNull(sourceType))) {
      return "U9_API";
    }
    return "U9_SOURCE";
  }

  private QuoteBomStatusResponse buildResponse(
      String oaNo, List<OaFormItem> items, Map<Long, QuoteBomStatus> statusByItemId) {
    QuoteBomStatusResponse response = new QuoteBomStatusResponse();
    response.setOaNo(oaNo);
    response.setTotalCount(items.size());
    for (OaFormItem item : items) {
      QuoteBomStatus status = statusByItemId.get(item.getId());
      QuoteBomStatusItemResponse row = toResponseItem(item, status);
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

  private QuoteBomStatusItemResponse toResponseItem(OaFormItem item, QuoteBomStatus status) {
    QuoteBomStatusItemResponse row = new QuoteBomStatusItemResponse();
    row.setSeq(item.getSeq());
    row.setOaFormItemId(item.getId());
    row.setProductCode(trimToNull(item.getMaterialNo()));
    row.setProductModel(trimToNull(item.getSunlModel()));
    if (status == null) {
      applyProductPackagingType(row);
      row.setBomStatus(
          StringUtils.hasText(item.getMaterialNo())
              ? QuoteBomStatusCode.NOT_CHECKED.getCode()
              : QuoteBomStatusCode.NO_BOM.getCode());
      row.setErrorMessage(
          StringUtils.hasText(item.getMaterialNo()) ? null : "产品料号为空，无法自动匹配 BOM");
      return row;
    }
    row.setId(status.getId());
    row.setProductCode(status.getProductCode());
    row.setProductModel(status.getProductModel());
    applyProductPackagingType(row);
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
    row.setManualTaskNo(status.getManualTaskNo());
    row.setSupplementTaskId(status.getSupplementTaskId());
    row.setErrorMessage(status.getErrorMessage());
    return row;
  }

  private void applyProductPackagingType(QuoteBomStatusItemResponse row) {
    U9ProductPackagingTypeResolver.Result result =
        productPackagingTypeResolver.resolve(row.getProductCode());
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
    if (SOURCE_COSTING_SNAPSHOT.equalsIgnoreCase(source)) {
      return QuoteBomStatusCode.CURRENT_MONTH_QUOTED.getCode();
    }
    return QuoteBomStatusCode.U9_BOM_EXISTS.getCode();
  }

  private String currentPeriodMonth() {
    return PERIOD_FORMATTER.format(YearMonth.now(clock == null ? Clock.systemDefaultZone() : clock));
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String firstText(String first, String second) {
    String normalizedFirst = trimToNull(first);
    return normalizedFirst == null ? trimToNull(second) : normalizedFirst;
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

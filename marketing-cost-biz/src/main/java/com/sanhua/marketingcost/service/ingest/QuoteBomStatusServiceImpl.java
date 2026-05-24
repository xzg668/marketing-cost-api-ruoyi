package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteBomStatusServiceImpl implements QuoteBomStatusService {
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final BomAvailabilityAdapter bomAvailabilityAdapter;
  private final BomU9SourceMapper bomU9SourceMapper;
  private final U9ProductPackagingTypeResolver productPackagingTypeResolver;

  public QuoteBomStatusServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      BomAvailabilityAdapter bomAvailabilityAdapter,
      BomU9SourceMapper bomU9SourceMapper,
      U9ProductPackagingTypeResolver productPackagingTypeResolver) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.bomAvailabilityAdapter = bomAvailabilityAdapter;
    this.bomU9SourceMapper = bomU9SourceMapper;
    this.productPackagingTypeResolver = productPackagingTypeResolver;
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
      applyU9SourceSnapshot(status, item, latestU9ByProduct.get(trimToNull(item.getMaterialNo())), now);
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

  private void applyU9SourceSnapshot(
      QuoteBomStatus status, OaFormItem item, BomU9Source source, LocalDateTime now) {
    status.setProductCode(trimToNull(item.getMaterialNo()));
    status.setProductModel(trimToNull(item.getSunlModel()));
    status.setCustomerCode(trimToNull(item.getCustomerCode()));
    status.setPackageType(trimToNull(item.getPackageType()));
    status.setPackageMethod(trimToNull(item.getPackageMethod()));
    status.setTechnicianName(trimToNull(item.getTechnicianName()));
    status.setCheckedAt(now);
    status.setUpdatedAt(now);
    if (source == null) {
      status.setBomStatus(QuoteBomStatusCode.NO_BOM.getCode());
      status.setBomSource(null);
      status.setBomPurpose(null);
      status.setBomVersion(null);
      status.setEffectiveFrom(null);
      status.setEffectiveTo(null);
      status.setSyncBatchId(null);
      status.setErrorMessage("本地 U9 全量快照中未找到该产品 BOM");
      return;
    }
    status.setBomStatus(QuoteBomStatusCode.SYNCED.getCode());
    status.setBomSource(defaultU9Source(source.getSourceType()));
    status.setBomPurpose(source.getBomPurpose());
    status.setBomVersion(source.getBomVersion());
    status.setEffectiveFrom(source.getEffectiveFrom());
    status.setEffectiveTo(source.getEffectiveTo());
    status.setSyncBatchId(source.getImportBatchId());
    status.setErrorMessage(null);
  }

  private void applyAvailability(QuoteBomStatus status, OaFormItem item) {
    status.setProductCode(trimToNull(item.getMaterialNo()));
    status.setProductModel(trimToNull(item.getSunlModel()));
    status.setCustomerCode(trimToNull(item.getCustomerCode()));
    status.setPackageType(trimToNull(item.getPackageType()));
    status.setPackageMethod(trimToNull(item.getPackageMethod()));
    status.setTechnicianName(trimToNull(item.getTechnicianName()));
    status.setCheckedAt(LocalDateTime.now());
    status.setUpdatedAt(LocalDateTime.now());

    BomAvailability availability =
        bomAvailabilityAdapter.findAvailableBom(status.getOaNo(), item.getMaterialNo());
    if (availability.isAvailable()) {
      status.setBomStatus(QuoteBomStatusCode.SYNCED.getCode());
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
      if (QuoteBomStatusCode.SYNCED.getCode().equals(row.getBomStatus())) {
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

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}

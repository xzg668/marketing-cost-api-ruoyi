package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sanhua.marketingcost.dto.CostRunMonthlyRepriceSubmitRequest;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.CostRunBatchStatus;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.enums.CostRunTaskStatus;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunTaskSubmissionService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CostRunTaskSubmissionServiceImpl implements CostRunTaskSubmissionService {

  private static final int DEFAULT_MAX_RETRY_COUNT = 3;
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private final CostRunBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;

  public CostRunTaskSubmissionServiceImpl(
      CostRunBatchMapper batchMapper,
      CostRunTaskMapper taskMapper,
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper) {
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
  }

  @Override
  @Transactional
  public CostRunTaskSubmissionResult submitQuote(String oaNo) {
    return submitQuote(oaNo, null);
  }

  @Override
  @Transactional
  public CostRunTaskSubmissionResult submitQuote(String oaNo, List<Long> oaFormItemIds) {
    String normalizedOaNo = required("oaNo", oaNo);
    Set<Long> selectedIds = normalizeIds(oaFormItemIds);
    OaForm oaForm =
        oaFormMapper.selectOne(
            new LambdaQueryWrapper<OaForm>().eq(OaForm::getOaNo, normalizedOaNo));
    if (oaForm == null) {
      throw new IllegalArgumentException("OA 单不存在：" + normalizedOaNo);
    }
    String pricingMonth = resolveQuotePricingMonth();
    String businessUnitType = required("businessUnitType", oaForm.getBusinessUnitType());
    CostRunBatch existing =
        findBatch(CostRunTaskScene.QUOTE, normalizedOaNo, pricingMonth, businessUnitType);
    CostRunBatch batch =
        existing == null
            ? buildBatch(
                CostRunTaskScene.QUOTE,
                normalizedOaNo,
                pricingMonth,
                null,
                businessUnitType,
                null,
                null,
                quoteSnapshot(normalizedOaNo, pricingMonth, businessUnitType))
            : existing;

    List<OaFormItem> items =
        oaFormItemMapper.selectList(
            new LambdaQueryWrapper<OaFormItem>()
                .eq(OaFormItem::getOaFormId, oaForm.getId())
                .orderByAsc(OaFormItem::getSeq)
                .orderByAsc(OaFormItem::getId));
    List<OaFormItem> selectedItems = filterSelectedItems(items, selectedIds);
    if (!selectedIds.isEmpty() && selectedItems.size() != selectedIds.size()) {
      throw new IllegalArgumentException("存在不属于该 OA 的产品明细行");
    }
    TaskBuildResult tasks = buildQuoteTasks(batch, oaForm, selectedItems);
    boolean existingBatch = existing != null;
    if (existing == null) {
      batch.setTotalCount(tasks.taskCount());
      batch.setSkippedCount(tasks.skippedCount());
      String draftBatchNo = batch.getBatchNo();
      batch = insertOrLoadExisting(batch);
      if (!draftBatchNo.equals(batch.getBatchNo())) {
        existingBatch = true;
        tasks = buildQuoteTasks(batch, oaForm, selectedItems);
      }
    }
    if (existingBatch) {
      resetExistingQuoteBatchForRerun(batch.getBatchNo(), tasks.tasks());
    }
    insertTasks(tasks.tasks());
    return CostRunTaskSubmissionResult.of(
        batch.getBatchNo(),
        batch.getScene(),
        batch.getSourceNo(),
        batch.getStatus(),
        selectedItems.size(),
        tasks.taskCount(),
        tasks.skippedCount(),
        existingBatch);
  }

  @Override
  @Transactional
  public CostRunTaskSubmissionResult submitMonthlyReprice(CostRunMonthlyRepriceSubmitRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("月度调价提交请求不能为空");
    }
    String repriceNo = required("repriceNo", request.getRepriceNo());
    String pricingMonth = normalizePricingMonth(required("pricingMonth", request.getPricingMonth()));
    String businessUnitType = required("businessUnitType", request.getBusinessUnitType());
    CostRunBatch existing =
        findBatch(CostRunTaskScene.MONTHLY_REPRICE, repriceNo, pricingMonth, businessUnitType);
    boolean retryExistingBatch = isRetryableTerminalBatch(existing);
    CostRunBatch batch =
        existing == null
            ? buildBatch(
                CostRunTaskScene.MONTHLY_REPRICE,
                repriceNo,
                pricingMonth,
                request.getPriceAsOfTime(),
                businessUnitType,
                request.getCreatedBy(),
                request.getCreatedName(),
                monthlyRepriceSnapshot(request))
            : existing;
    TaskBuildResult tasks = buildMonthlyRepriceTasks(batch, request);
    boolean existingBatch = existing != null;
    if (existing == null) {
      batch.setTotalCount(tasks.taskCount());
      batch.setSkippedCount(tasks.skippedCount());
      String draftBatchNo = batch.getBatchNo();
      batch = insertOrLoadExisting(batch);
      if (!draftBatchNo.equals(batch.getBatchNo())) {
        existingBatch = true;
        tasks = buildMonthlyRepriceTasks(batch, request);
      }
    }
    if (retryExistingBatch) {
      resetFailedBatchForRetry(batch.getBatchNo());
    }
    insertTasks(tasks.tasks());
    List<MonthlyRepriceCalcObject> objects =
        request.getCalcObjects() == null ? List.of() : request.getCalcObjects();
    return CostRunTaskSubmissionResult.of(
        batch.getBatchNo(),
        batch.getScene(),
        batch.getSourceNo(),
        batch.getStatus(),
        objects.size(),
        tasks.taskCount(),
        tasks.skippedCount(),
        existingBatch);
  }

  private CostRunBatch insertOrLoadExisting(CostRunBatch batch) {
    int inserted = batchMapper.insertIgnore(batch);
    if (inserted == 1) {
      return batch;
    }
    CostRunBatch existing =
        findBatch(
            CostRunTaskScene.fromCode(batch.getScene()),
            batch.getSourceNo(),
            batch.getPricingMonth(),
            batch.getBusinessUnitType());
    if (existing == null) {
      throw new IllegalStateException("通用核算批次写入失败：" + batch.getBatchNo());
    }
    return existing;
  }

  private void insertTasks(List<CostRunTask> tasks) {
    for (CostRunTask task : tasks) {
      taskMapper.insertIgnore(task);
    }
  }

  private CostRunBatch findBatch(
      CostRunTaskScene scene, String sourceNo, String pricingMonth, String businessUnitType) {
    LambdaQueryWrapper<CostRunBatch> wrapper =
        new LambdaQueryWrapper<CostRunBatch>()
            .eq(CostRunBatch::getScene, scene.name())
            .eq(CostRunBatch::getSourceNo, sourceNo);
    if (StringUtils.hasText(pricingMonth)) {
      wrapper.eq(CostRunBatch::getPricingMonth, pricingMonth);
    } else {
      wrapper.isNull(CostRunBatch::getPricingMonth);
    }
    if (StringUtils.hasText(businessUnitType)) {
      wrapper.eq(CostRunBatch::getBusinessUnitType, businessUnitType);
    } else {
      wrapper.isNull(CostRunBatch::getBusinessUnitType);
    }
    return batchMapper.selectOne(wrapper);
  }

  private boolean isRetryableTerminalBatch(CostRunBatch batch) {
    if (batch == null || !StringUtils.hasText(batch.getStatus())) {
      return false;
    }
    CostRunBatchStatus status = CostRunBatchStatus.fromCode(batch.getStatus());
    return status == CostRunBatchStatus.FAILED
        || status == CostRunBatchStatus.PARTIAL_FAILED
        || status == CostRunBatchStatus.CANCELED;
  }

  private void resetFailedBatchForRetry(String batchNo) {
    LocalDateTime now = LocalDateTime.now();
    batchMapper.resetFailedBatchForRetry(batchNo, now);
    taskMapper.resetBatchTasksForRetry(batchNo, now);
  }

  private void resetExistingQuoteBatchForRerun(String batchNo, List<CostRunTask> tasks) {
    if (!StringUtils.hasText(batchNo) || tasks == null || tasks.isEmpty()) {
      return;
    }
    List<String> calcObjectKeys =
        tasks.stream()
            .map(CostRunTask::getCalcObjectKey)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    if (calcObjectKeys.isEmpty()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    batchMapper.resetQuoteBatchForRerun(batchNo, now);
    taskMapper.resetQuoteTasksForRerun(batchNo, calcObjectKeys, now);
  }

  private CostRunBatch buildBatch(
      CostRunTaskScene scene,
      String sourceNo,
      String pricingMonth,
      LocalDateTime priceAsOfTime,
      String businessUnitType,
      String createdBy,
      String createdName,
      String requestSnapshotJson) {
    LocalDateTime now = LocalDateTime.now();
    CostRunBatch batch = new CostRunBatch();
    batch.setBatchNo(newBatchNo(scene));
    batch.setScene(scene.name());
    batch.setSourceNo(sourceNo);
    batch.setPricingMonth(pricingMonth);
    batch.setPriceAsOfTime(priceAsOfTime);
    batch.setBusinessUnitType(businessUnitType);
    batch.setStatus(CostRunBatchStatus.PENDING.name());
    batch.setTotalCount(0);
    batch.setSuccessCount(0);
    batch.setFailedCount(0);
    batch.setSkippedCount(0);
    batch.setProgress(0);
    batch.setRequestSnapshotJson(requestSnapshotJson);
    batch.setCreatedBy(normalizeToNull(createdBy));
    batch.setCreatedName(normalizeToNull(createdName));
    batch.setCreatedAt(now);
    batch.setUpdatedAt(now);
    return batch;
  }

  private TaskBuildResult buildQuoteTasks(CostRunBatch batch, OaForm oaForm, List<OaFormItem> items) {
    Map<String, CostRunTask> tasksByKey = new LinkedHashMap<>();
    int skipped = 0;
    for (OaFormItem item : items == null ? List.<OaFormItem>of() : items) {
      CostRunTask task = buildQuoteTask(batch, oaForm, item);
      if (task == null || tasksByKey.putIfAbsent(task.getCalcObjectKey(), task) != null) {
        skipped++;
      }
    }
    return new TaskBuildResult(List.copyOf(tasksByKey.values()), skipped);
  }

  private CostRunTask buildQuoteTask(CostRunBatch batch, OaForm oaForm, OaFormItem item) {
    if (item == null) {
      return null;
    }
    String productCode = normalize(item.getMaterialNo());
    if (!StringUtils.hasText(productCode)) {
      return null;
    }
    CostRunTask task = baseTask(batch);
    task.setCalcObjectKey("QUOTE:" + item.getId());
    task.setOaNo(oaForm.getOaNo());
    task.setOaFormItemId(item.getId());
    task.setProductCode(productCode);
    task.setPackageMethod(normalizeToNull(item.getPackageMethod()));
    task.setCustomerName(normalizeToNull(oaForm.getCustomer()));
    task.setRequestSnapshotJson(
        quoteTaskSnapshot(oaForm.getOaNo(), item.getId(), productCode, item.getPackageMethod()));
    return task;
  }

  private TaskBuildResult buildMonthlyRepriceTasks(
      CostRunBatch batch, CostRunMonthlyRepriceSubmitRequest request) {
    List<MonthlyRepriceCalcObject> objects =
        request.getCalcObjects() == null ? List.of() : request.getCalcObjects();
    Map<String, CostRunTask> tasksByKey = new LinkedHashMap<>();
    int skipped = 0;
    for (MonthlyRepriceCalcObject object : objects) {
      CostRunTask task = buildMonthlyRepriceTask(batch, request, object);
      if (task == null || tasksByKey.putIfAbsent(task.getCalcObjectKey(), task) != null) {
        skipped++;
      }
    }
    return new TaskBuildResult(List.copyOf(tasksByKey.values()), skipped);
  }

  private CostRunTask buildMonthlyRepriceTask(
      CostRunBatch batch, CostRunMonthlyRepriceSubmitRequest request, MonthlyRepriceCalcObject object) {
    if (object == null) {
      return null;
    }
    String productCode = normalize(object.getProductCode());
    if (!StringUtils.hasText(productCode)) {
      return null;
    }
    String oaNo = normalize(object.getOaNo());
    String packageMethod = normalizeToNull(object.getPackageMethod());
    String customerName = normalizeToNull(object.getCustomerName());
    String normalizedCustomerName = normalizeCustomerName(customerName);
    CostRunTask task = baseTask(batch);
    task.setCalcObjectKey(calcObjectKey(oaNo, productCode, packageMethod, normalizedCustomerName));
    task.setOaNo(oaNo);
    task.setOaFormItemId(object.getOaFormItemId());
    task.setProductCode(productCode);
    task.setPackageMethod(packageMethod);
    task.setCustomerName(customerName);
    task.setAdjustBatchId(request.getAdjustBatchId());
    task.setBomSourcePolicy(normalizeToNull(request.getBomSourcePolicy()));
    task.setRequestSnapshotJson(monthlyRepriceTaskSnapshot(request, object));
    return task;
  }

  private CostRunTask baseTask(CostRunBatch batch) {
    LocalDateTime now = LocalDateTime.now();
    CostRunTask task = new CostRunTask();
    task.setBatchNo(batch.getBatchNo());
    task.setScene(batch.getScene());
    task.setSourceNo(batch.getSourceNo());
    task.setBusinessUnitType(batch.getBusinessUnitType());
    task.setPricingMonth(batch.getPricingMonth());
    task.setPriceAsOfTime(batch.getPriceAsOfTime());
    task.setStatus(CostRunTaskStatus.PENDING.name());
    task.setProgress(0);
    task.setRetryCount(0);
    task.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private String resolveQuotePricingMonth() {
    // 报价实时核算按任务提交时的当前核算月份取价，不再沿用 OA 申请月或表头核算期间。
    return CostPricingPeriodUtils.currentPricingMonth();
  }

  private String normalizePricingMonth(String pricingMonth) {
    return CostPricingPeriodUtils.normalizePricingMonth(pricingMonth);
  }

  private String newBatchNo(CostRunTaskScene scene) {
    String prefix = scene == CostRunTaskScene.QUOTE ? "CRQ" : "CRM";
    return prefix + "-" + CostPricingPeriodUtils.currentPricingMonth() + "-"
        + UUID.randomUUID().toString().replace("-", "");
  }

  private String calcObjectKey(
      String oaNo, String productCode, String packageMethod, String normalizedCustomerName) {
    String canonical =
        String.join(
            "\u001F",
            normalize(oaNo),
            normalize(productCode),
            normalize(packageMethod),
            normalize(normalizedCustomerName));
    return sha256Hex(canonical);
  }

  private String normalizeCustomerName(String value) {
    String normalized = normalize(value);
    return StringUtils.hasText(normalized) ? normalized.toUpperCase(Locale.ROOT) : "";
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }

  private Set<Long> normalizeIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return Set.of();
    }
    Set<Long> normalized = new LinkedHashSet<>();
    for (Long id : ids) {
      if (id != null && id > 0) {
        normalized.add(id);
      }
    }
    return normalized;
  }

  private List<OaFormItem> filterSelectedItems(List<OaFormItem> items, Set<Long> selectedIds) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }
    if (selectedIds == null || selectedIds.isEmpty()) {
      return items;
    }
    return items.stream()
        .filter(item -> item != null && selectedIds.contains(item.getId()))
        .toList();
  }

  private String normalizeToNull(String value) {
    String normalized = normalize(value);
    return StringUtils.hasText(normalized) ? normalized : null;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
  }

  private String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      char[] chars = new char[bytes.length * 2];
      for (int i = 0; i < bytes.length; i++) {
        int v = bytes[i] & 0xff;
        chars[i * 2] = HEX[v >>> 4];
        chars[i * 2 + 1] = HEX[v & 0x0f];
      }
      return new String(chars);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
    }
  }

  private String quoteSnapshot(String oaNo, String pricingMonth, String businessUnitType) {
    return json(
        "scene", CostRunTaskScene.QUOTE.name(),
        "oaNo", oaNo,
        "pricingMonth", pricingMonth,
        "businessUnitType", businessUnitType);
  }

  private String quoteTaskSnapshot(
      String oaNo, Long oaFormItemId, String productCode, String packageMethod) {
    return json(
        "oaNo", oaNo,
        "oaFormItemId", String.valueOf(oaFormItemId),
        "productCode", productCode,
        "packageMethod", normalizeToNull(packageMethod));
  }

  private String monthlyRepriceSnapshot(CostRunMonthlyRepriceSubmitRequest request) {
    return json(
        "scene", CostRunTaskScene.MONTHLY_REPRICE.name(),
        "repriceNo", request.getRepriceNo(),
        "pricingMonth", request.getPricingMonth(),
        "businessUnitType", request.getBusinessUnitType(),
        "adjustBatchId", request.getAdjustBatchId() == null ? null : String.valueOf(request.getAdjustBatchId()),
        "bomSourcePolicy", normalizeToNull(request.getBomSourcePolicy()));
  }

  private String monthlyRepriceTaskSnapshot(
      CostRunMonthlyRepriceSubmitRequest request, MonthlyRepriceCalcObject object) {
    return json(
        "repriceNo", request.getRepriceNo(),
        "oaNo", object.getOaNo(),
        "oaFormItemId", object.getOaFormItemId() == null ? null : String.valueOf(object.getOaFormItemId()),
        "productCode", object.getProductCode(),
        "packageMethod", normalizeToNull(object.getPackageMethod()),
        "customerName", normalizeToNull(object.getCustomerName()));
  }

  private String json(String... pairs) {
    StringBuilder builder = new StringBuilder("{");
    for (int i = 0; i < pairs.length; i += 2) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append('"').append(escapeJson(pairs[i])).append('"').append(':');
      String value = pairs[i + 1];
      if (value == null) {
        builder.append("null");
      } else {
        builder.append('"').append(escapeJson(value)).append('"');
      }
    }
    return builder.append('}').toString();
  }

  private String escapeJson(String value) {
    return value == null
        ? ""
        : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  private record TaskBuildResult(List<CostRunTask> tasks, int skippedCount) {
    int taskCount() {
      return tasks.size();
    }
  }
}

package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MonthlyRepriceAuditLogDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceAuditLogQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceActiveLockDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceCostItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepricePageResponse;
import com.sanhua.marketingcost.dto.MonthlyRepricePartItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceResultDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceResultQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceTaskDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceTaskQueryRequest;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.MonthlyRepriceCostItem;
import com.sanhua.marketingcost.entity.MonthlyRepricePartItem;
import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceCostItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepricePartItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MonthlyRepriceQueryService;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceQueryServiceImpl implements MonthlyRepriceQueryService {

  private static final String ROLE_BU_DIRECTOR = "BU_DIRECTOR";
  private static final String STATUS_CONFIRMED = "CONFIRMED";
  private static final String NO_VISIBLE_STATUS = "__NO_VISIBLE_STATUS__";
  private static final String PERM_REVIEW = "price:monthly-reprice:review";
  private static final String PERM_OPERATE = "price:monthly-reprice:operate";
  private static final String PERM_CONFIRM = "price:monthly-reprice:confirm";
  private static final String SCENE_MONTHLY_REPRICE = "MONTHLY_REPRICE";
  private static final List<String> LOCKING_STATUSES =
      List.of("CREATED", "PREPARING", "RUNNING", "WAIT_CONFIRM");

  private final MonthlyRepriceBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;
  private final MonthlyRepriceResultMapper resultMapper;
  private final MonthlyRepricePartItemMapper partItemMapper;
  private final MonthlyRepriceCostItemMapper costItemMapper;
  private final MonthlyRepriceAuditLogMapper auditLogMapper;
  private final PermissionService permissionService;

  public MonthlyRepriceQueryServiceImpl(
      MonthlyRepriceBatchMapper batchMapper,
      CostRunTaskMapper taskMapper,
      MonthlyRepriceResultMapper resultMapper,
      MonthlyRepricePartItemMapper partItemMapper,
      MonthlyRepriceCostItemMapper costItemMapper,
      MonthlyRepriceAuditLogMapper auditLogMapper,
      PermissionService permissionService) {
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
    this.resultMapper = resultMapper;
    this.partItemMapper = partItemMapper;
    this.costItemMapper = costItemMapper;
    this.auditLogMapper = auditLogMapper;
    this.permissionService = permissionService;
  }

  @Override
  public MonthlyRepricePageResponse<MonthlyRepriceBatchDto> pageBatches(
      MonthlyRepriceBatchQueryRequest request) {
    MonthlyRepriceBatchQueryRequest req =
        request == null ? new MonthlyRepriceBatchQueryRequest() : request;
    LambdaQueryWrapper<MonthlyRepriceBatch> query =
        Wrappers.lambdaQuery(MonthlyRepriceBatch.class);
    eqText(query, MonthlyRepriceBatch::getPricingMonth, req.getPricingMonth());
    likeText(query, MonthlyRepriceBatch::getRepriceNo, req.getRepriceNo());
    eqText(query, MonthlyRepriceBatch::getCreatedBy, req.getCreatedBy());
    eqText(query, MonthlyRepriceBatch::getConfirmedBy, req.getConfirmedBy());
    eqText(query, MonthlyRepriceBatch::getBusinessUnitType, visibleBusinessUnit(req.getBusinessUnitType()));
    applyBatchStatusVisibility(query, req.getStatus());
    orderBatches(query, req.getSortBy(), req.getSortDirection());
    Page<MonthlyRepriceBatch> page =
        batchMapper.selectPage(new Page<>(page(req.getPage()), size(req.getPageSize())), query);
    return new MonthlyRepricePageResponse<>(
        page.getTotal(), page.getRecords().stream().map(MonthlyRepriceBatchDto::fromEntity).toList());
  }

  @Override
  public MonthlyRepriceBatchDto getBatch(String repriceNo) {
    MonthlyRepriceBatch batch = findBatch(required("repriceNo", repriceNo));
    if (batch == null) {
      return null;
    }
    assertVisibleBatch(batch);
    return MonthlyRepriceBatchDto.fromEntity(batch);
  }

  @Override
  public MonthlyRepricePageResponse<MonthlyRepriceTaskDto> pageTasks(
      String repriceNo, MonthlyRepriceTaskQueryRequest request) {
    MonthlyRepriceBatch batch = visibleBatch(required("repriceNo", repriceNo));
    MonthlyRepriceTaskQueryRequest req =
        request == null ? new MonthlyRepriceTaskQueryRequest() : request;
    LambdaQueryWrapper<CostRunTask> query =
        Wrappers.lambdaQuery(CostRunTask.class)
            .eq(CostRunTask::getScene, SCENE_MONTHLY_REPRICE)
            .eq(CostRunTask::getSourceNo, batch.getRepriceNo());
    eqText(query, CostRunTask::getStatus, req.getStatus());
    if (StringUtils.hasText(req.getKeyword())) {
      String like = req.getKeyword().trim();
      query.and(w -> w.like(CostRunTask::getOaNo, like)
          .or().like(CostRunTask::getProductCode, like)
          .or().like(CostRunTask::getCustomerName, like)
          .or().like(CostRunTask::getCalcObjectKey, like));
    }
    orderTasks(query, req.getSortBy(), req.getSortDirection());
    Page<CostRunTask> page =
        taskMapper.selectPage(new Page<>(page(req.getPage()), size(req.getPageSize())), query);
    return new MonthlyRepricePageResponse<>(
        page.getTotal(), page.getRecords().stream().map(MonthlyRepriceTaskDto::fromCostRunTask).toList());
  }

  @Override
  public MonthlyRepricePageResponse<MonthlyRepriceResultDto> pageResults(
      String repriceNo, MonthlyRepriceResultQueryRequest request) {
    MonthlyRepriceBatch batch = visibleBatch(required("repriceNo", repriceNo));
    MonthlyRepriceResultQueryRequest req =
        request == null ? new MonthlyRepriceResultQueryRequest() : request;
    LambdaQueryWrapper<MonthlyRepriceResult> query =
        Wrappers.lambdaQuery(MonthlyRepriceResult.class)
            .eq(MonthlyRepriceResult::getRepriceNo, batch.getRepriceNo());
    eqText(query, MonthlyRepriceResult::getOaNo, req.getOaNo());
    eqText(query, MonthlyRepriceResult::getProductCode, req.getProductCode());
    likeText(query, MonthlyRepriceResult::getCustomerName, req.getCustomerName());
    eqText(query, MonthlyRepriceResult::getCalcStatus, req.getCalcStatus());
    if (StringUtils.hasText(req.getKeyword())) {
      String like = req.getKeyword().trim();
      query.and(w -> w.like(MonthlyRepriceResult::getOaNo, like)
          .or().like(MonthlyRepriceResult::getProductCode, like)
          .or().like(MonthlyRepriceResult::getCustomerName, like)
          .or().like(MonthlyRepriceResult::getCalcObjectKey, like));
    }
    orderResults(query, req.getSortBy(), req.getSortDirection());
    Page<MonthlyRepriceResult> page =
        resultMapper.selectPage(new Page<>(page(req.getPage()), size(req.getPageSize())), query);
    return new MonthlyRepricePageResponse<>(
        page.getTotal(), page.getRecords().stream().map(MonthlyRepriceResultDto::fromEntity).toList());
  }

  @Override
  public List<MonthlyRepricePartItemDto> listPartItems(String repriceNo, Long resultId) {
    MonthlyRepriceResult result = visibleResult(repriceNo, resultId);
    return partItemMapper.selectList(
            Wrappers.lambdaQuery(MonthlyRepricePartItem.class)
                .eq(MonthlyRepricePartItem::getRepriceNo, result.getRepriceNo())
                .eq(MonthlyRepricePartItem::getCalcObjectKey, result.getCalcObjectKey())
                .orderByAsc(MonthlyRepricePartItem::getLineNo)
                .orderByAsc(MonthlyRepricePartItem::getId))
        .stream()
        .map(MonthlyRepricePartItemDto::fromEntity)
        .toList();
  }

  @Override
  public List<MonthlyRepriceCostItemDto> listCostItems(String repriceNo, Long resultId) {
    MonthlyRepriceResult result = visibleResult(repriceNo, resultId);
    return costItemMapper.selectList(
            Wrappers.lambdaQuery(MonthlyRepriceCostItem.class)
                .eq(MonthlyRepriceCostItem::getRepriceNo, result.getRepriceNo())
                .eq(MonthlyRepriceCostItem::getCalcObjectKey, result.getCalcObjectKey())
                .orderByAsc(MonthlyRepriceCostItem::getLineNo)
                .orderByAsc(MonthlyRepriceCostItem::getId))
        .stream()
        .map(MonthlyRepriceCostItemDto::fromEntity)
        .toList();
  }

  @Override
  public MonthlyRepricePageResponse<MonthlyRepriceAuditLogDto> pageAuditLogs(
      MonthlyRepriceAuditLogQueryRequest request) {
    MonthlyRepriceAuditLogQueryRequest req =
        request == null ? new MonthlyRepriceAuditLogQueryRequest() : request;
    LambdaQueryWrapper<MonthlyRepriceAuditLog> query =
        Wrappers.lambdaQuery(MonthlyRepriceAuditLog.class);
    String repriceNo = trimToNull(req.getRepriceNo());
    if (StringUtils.hasText(repriceNo)) {
      visibleBatch(repriceNo);
      query.eq(MonthlyRepriceAuditLog::getRepriceNo, repriceNo);
    } else if (!canViewUnconfirmed()) {
      query.inSql(
          MonthlyRepriceAuditLog::getRepriceNo,
          "SELECT reprice_no FROM lp_monthly_reprice_batch WHERE status = 'CONFIRMED'");
    }
    eqText(query, MonthlyRepriceAuditLog::getPricingMonth, req.getPricingMonth());
    eqText(query, MonthlyRepriceAuditLog::getBusinessUnitType, visibleBusinessUnit(req.getBusinessUnitType()));
    eqText(query, MonthlyRepriceAuditLog::getOperationType, req.getOperationType());
    likeText(query, MonthlyRepriceAuditLog::getOperatorName, req.getOperatorName());
    orderAuditLogs(query, req.getSortBy(), req.getSortDirection());
    Page<MonthlyRepriceAuditLog> page =
        auditLogMapper.selectPage(new Page<>(page(req.getPage()), size(req.getPageSize())), query);
    return new MonthlyRepricePageResponse<>(
        page.getTotal(), page.getRecords().stream().map(MonthlyRepriceAuditLogDto::fromEntity).toList());
  }

  @Override
  public MonthlyRepriceActiveLockDto getActiveLock() {
    String businessUnitType = visibleBusinessUnit(null);
    if (!StringUtils.hasText(businessUnitType)) {
      return MonthlyRepriceActiveLockDto.unlocked();
    }
    MonthlyRepriceBatch batch =
        batchMapper.selectOne(
            Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
                .eq(MonthlyRepriceBatch::getBusinessUnitType, businessUnitType)
                .in(MonthlyRepriceBatch::getStatus, LOCKING_STATUSES)
                .orderByDesc(MonthlyRepriceBatch::getUpdatedAt)
                .last("LIMIT 1"));
    if (batch == null) {
      return MonthlyRepriceActiveLockDto.unlocked();
    }
    MonthlyRepriceActiveLockDto dto = new MonthlyRepriceActiveLockDto();
    dto.setLocked(true);
    dto.setRepriceNo(batch.getRepriceNo());
    dto.setPricingMonth(batch.getPricingMonth());
    dto.setBusinessUnitType(batch.getBusinessUnitType());
    dto.setStatus(batch.getStatus());
    dto.setMessage("当前业务单元正在月度调价，暂不能发起成本核算");
    return dto;
  }

  private MonthlyRepriceBatch visibleBatch(String repriceNo) {
    MonthlyRepriceBatch batch = findBatch(repriceNo);
    if (batch == null) {
      throw new IllegalArgumentException("月度调价批次不存在：" + repriceNo);
    }
    assertVisibleBatch(batch);
    return batch;
  }

  private MonthlyRepriceResult visibleResult(String repriceNo, Long resultId) {
    visibleBatch(required("repriceNo", repriceNo));
    if (resultId == null) {
      throw new IllegalArgumentException("resultId 必填");
    }
    MonthlyRepriceResult result =
        resultMapper.selectOne(
            Wrappers.lambdaQuery(MonthlyRepriceResult.class)
                .eq(MonthlyRepriceResult::getRepriceNo, repriceNo)
                .eq(MonthlyRepriceResult::getId, resultId)
                .last("LIMIT 1"));
    if (result == null) {
      throw new IllegalArgumentException("月度调价结果不存在：" + resultId);
    }
    // 明细下钻必须以结果行的 calcObjectKey 为边界，避免一个 OA 多产品时串明细。
    if (!StringUtils.hasText(result.getCalcObjectKey())) {
      throw new IllegalArgumentException("月度调价结果缺少 calcObjectKey，不能下钻明细：" + resultId);
    }
    return result;
  }

  private MonthlyRepriceBatch findBatch(String repriceNo) {
    return batchMapper.selectOne(
        Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
            .eq(MonthlyRepriceBatch::getRepriceNo, repriceNo)
            .last("LIMIT 1"));
  }

  private void assertVisibleBatch(MonthlyRepriceBatch batch) {
    if (batch == null) {
      return;
    }
    // 月度调价数据按业务单元隔离，避免普通用户跨业务单元查看未发布或已发布结果。
    String visibleBu = visibleBusinessUnit(batch.getBusinessUnitType());
    if (StringUtils.hasText(visibleBu)
        && !normalize(visibleBu).equals(normalize(batch.getBusinessUnitType()))) {
      throw new AccessDeniedException("不能查看其他业务单元的月度调价批次");
    }
    // 未确认批次属于发布前数据，只允许业务总监或明确授权的复核角色查看。
    if (!STATUS_CONFIRMED.equals(normalize(batch.getStatus())) && !canViewUnconfirmed()) {
      throw new AccessDeniedException("普通用户不能查看未确认的月度调价结果");
    }
  }

  private void applyBatchStatusVisibility(
      LambdaQueryWrapper<MonthlyRepriceBatch> query, String requestedStatus) {
    String status = normalize(requestedStatus);
    if (canViewUnconfirmed()) {
      if (StringUtils.hasText(status)) {
        query.eq(MonthlyRepriceBatch::getStatus, status);
      }
      return;
    }
    if (StringUtils.hasText(status) && !STATUS_CONFIRMED.equals(status)) {
      query.eq(MonthlyRepriceBatch::getStatus, NO_VISIBLE_STATUS);
    } else {
      query.eq(MonthlyRepriceBatch::getStatus, STATUS_CONFIRMED);
    }
  }

  private boolean canViewUnconfirmed() {
    return BusinessUnitContext.isAdmin()
        || permissionService.hasRole(ROLE_BU_DIRECTOR)
        || permissionService.hasAnyPermi(PERM_REVIEW, PERM_OPERATE, PERM_CONFIRM);
  }

  private String visibleBusinessUnit(String requestedBusinessUnitType) {
    String requested = trimToNull(requestedBusinessUnitType);
    if (BusinessUnitContext.isAdmin()) {
      return requested;
    }
    String current = trimToNull(BusinessUnitContext.getCurrentBusinessUnitType());
    if (!StringUtils.hasText(current)) {
      throw new AccessDeniedException("缺少业务单元上下文，不能查询月度调价数据");
    }
    if (StringUtils.hasText(requested) && !normalize(current).equals(normalize(requested))) {
      throw new AccessDeniedException("不能查询其他业务单元的月度调价数据");
    }
    return current;
  }

  private void orderBatches(
      LambdaQueryWrapper<MonthlyRepriceBatch> query, String sortBy, String sortDirection) {
    boolean asc = isAsc(sortDirection);
    switch (sortKey(sortBy)) {
      case "createdat" -> order(query, asc, MonthlyRepriceBatch::getCreatedAt);
      case "startedat" -> order(query, asc, MonthlyRepriceBatch::getStartedAt);
      case "finishedat" -> order(query, asc, MonthlyRepriceBatch::getFinishedAt);
      case "confirmedat" -> order(query, asc, MonthlyRepriceBatch::getConfirmedAt);
      case "pricingmonth" -> order(query, asc, MonthlyRepriceBatch::getPricingMonth);
      case "repriceno" -> order(query, asc, MonthlyRepriceBatch::getRepriceNo);
      case "status" -> order(query, asc, MonthlyRepriceBatch::getStatus);
      default -> query.orderByDesc(MonthlyRepriceBatch::getUpdatedAt);
    }
    query.orderByDesc(MonthlyRepriceBatch::getId);
  }

  private void orderTasks(
      LambdaQueryWrapper<CostRunTask> query, String sortBy, String sortDirection) {
    boolean asc = isAsc(sortDirection);
    switch (sortKey(sortBy)) {
      case "status" -> order(query, asc, CostRunTask::getStatus);
      case "retrycount" -> order(query, asc, CostRunTask::getRetryCount);
      case "startedat" -> order(query, asc, CostRunTask::getStartedAt);
      case "finishedat" -> order(query, asc, CostRunTask::getFinishedAt);
      case "updatedat" -> order(query, asc, CostRunTask::getUpdatedAt);
      default -> query.orderByAsc(CostRunTask::getId);
    }
  }

  private void orderResults(
      LambdaQueryWrapper<MonthlyRepriceResult> query, String sortBy, String sortDirection) {
    boolean asc = isAsc(sortDirection);
    switch (sortKey(sortBy)) {
      case "totalcost" -> order(query, asc, MonthlyRepriceResult::getTotalCost);
      case "materialcost" -> order(query, asc, MonthlyRepriceResult::getMaterialCost);
      case "productcode" -> order(query, asc, MonthlyRepriceResult::getProductCode);
      case "createdat" -> order(query, asc, MonthlyRepriceResult::getCreatedAt);
      case "updatedat" -> order(query, asc, MonthlyRepriceResult::getUpdatedAt);
      default -> query.orderByAsc(MonthlyRepriceResult::getId);
    }
  }

  private void orderAuditLogs(
      LambdaQueryWrapper<MonthlyRepriceAuditLog> query, String sortBy, String sortDirection) {
    boolean asc = isAsc(sortDirection);
    switch (sortKey(sortBy)) {
      case "operationtype" -> order(query, asc, MonthlyRepriceAuditLog::getOperationType);
      case "operatorname" -> order(query, asc, MonthlyRepriceAuditLog::getOperatorName);
      case "createdat" -> order(query, asc, MonthlyRepriceAuditLog::getCreatedAt);
      default -> order(query, asc, MonthlyRepriceAuditLog::getOperationTime);
    }
    query.orderByDesc(MonthlyRepriceAuditLog::getId);
  }

  private <T> void order(
      LambdaQueryWrapper<T> query, boolean asc, SFunction<T, ?> column) {
    if (asc) {
      query.orderByAsc(column);
    } else {
      query.orderByDesc(column);
    }
  }

  private boolean isAsc(String sortDirection) {
    return "asc".equalsIgnoreCase(trimToNull(sortDirection));
  }

  private String sortKey(String sortBy) {
    String key = normalize(sortBy);
    return StringUtils.hasText(key) ? key.replace("_", "").toLowerCase() : "";
  }

  private <T> void eqText(
      LambdaQueryWrapper<T> query, SFunction<T, ?> column, String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private <T> void likeText(
      LambdaQueryWrapper<T> query, SFunction<T, ?> column, String value) {
    if (StringUtils.hasText(value)) {
      query.like(column, value.trim());
    }
  }

  private int page(Integer page) {
    return page == null || page < 1 ? 1 : page;
  }

  private int size(Integer pageSize) {
    if (pageSize == null || pageSize < 1) {
      return 20;
    }
    return Math.min(pageSize, 1000);
  }

  private String required(String field, String value) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      throw new IllegalArgumentException(field + " 必填");
    }
    return normalized;
  }

  private String trimToNull(String value) {
    String normalized = normalize(value);
    return StringUtils.hasText(normalized) ? normalized : null;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }
}

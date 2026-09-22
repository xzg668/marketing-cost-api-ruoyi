package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechAuxItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechPackageItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechSalaryItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechProductMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisTechnicalDataTaskRepository implements TechnicalDataTaskRepository {
  private final QuoteTechTaskMapper taskMapper;
  private final QuoteTechProductMapper productMapper;
  private final QuoteTechModuleMapper moduleMapper;
  private final QuoteTechDataVersionMapper versionMapper;
  private final QuoteTechPackageItemMapper packageItemMapper;
  private final QuoteTechAuxItemMapper auxItemMapper;
  private final QuoteTechSalaryItemMapper salaryItemMapper;

  public MybatisTechnicalDataTaskRepository(
      QuoteTechTaskMapper taskMapper,
      QuoteTechProductMapper productMapper,
      QuoteTechModuleMapper moduleMapper,
      QuoteTechDataVersionMapper versionMapper,
      QuoteTechPackageItemMapper packageItemMapper,
      QuoteTechAuxItemMapper auxItemMapper,
      QuoteTechSalaryItemMapper salaryItemMapper) {
    this.taskMapper = taskMapper;
    this.productMapper = productMapper;
    this.moduleMapper = moduleMapper;
    this.versionMapper = versionMapper;
    this.packageItemMapper = packageItemMapper;
    this.auxItemMapper = auxItemMapper;
    this.salaryItemMapper = salaryItemMapper;
  }

  @Override
  public QuoteTechTask upsertActiveTask(QuoteTechTask task) {
    taskMapper.insertOrGetActive(task);
    if (task.getId() == null) throw persistence("技术资料任务", null);
    QuoteTechTask stored = taskMapper.selectById(task.getId());
    if (stored == null) throw persistence("技术资料任务", task.getId());
    return stored;
  }

  @Override
  public Optional<QuoteTechTask> lockActiveTask(
      Long oaFormItemId, String accountingMonth) {
    return Optional.ofNullable(
        taskMapper.selectActiveForUpdate(oaFormItemId, accountingMonth));
  }

  @Override
  public Optional<QuoteTechTask> findTask(Long taskId) {
    return Optional.ofNullable(taskMapper.selectById(taskId));
  }

  @Override
  public int assignUnassignedTask(
      Long taskId,
      Integer expectedVersion,
      Long assigneeUserId,
      String assigneeName,
      String sourceRequestId,
      LocalDateTime dueAt,
      Long actorId) {
    return taskMapper.assignUnassigned(
        taskId, expectedVersion, assigneeUserId, assigneeName, sourceRequestId, dueAt, actorId);
  }

  @Override
  public QuoteTechProduct upsertActiveProduct(QuoteTechProduct product) {
    productMapper.insertOrGetActive(product);
    if (product.getId() == null) throw persistence("技术资料产品", null);
    QuoteTechProduct stored = productMapper.selectById(product.getId());
    if (stored == null) throw persistence("技术资料产品", product.getId());
    return stored;
  }

  @Override
  public List<QuoteTechProduct> findProducts(Long taskId) {
    return productMapper.selectByTaskId(taskId);
  }

  @Override
  public QuoteTechModule upsertModule(QuoteTechModule module) {
    moduleMapper.insertOrGet(module);
    if (module.getId() == null) throw persistence("技术资料模块", null);
    QuoteTechModule stored = moduleMapper.selectById(module.getId());
    if (stored == null) throw persistence("技术资料模块", module.getId());
    return stored;
  }

  @Override
  public List<QuoteTechModule> findModules(Long productId) {
    return moduleMapper.selectByProductId(productId);
  }

  @Override
  public List<QuoteTechModule> findModules(List<Long> productIds) {
    return productIds == null || productIds.isEmpty()
        ? List.of() : moduleMapper.selectByProductIds(productIds);
  }

  @Override
  public List<QuoteTechDataVersion> findVersions(List<Long> versionIds) {
    return versionIds == null || versionIds.isEmpty()
        ? List.of() : versionMapper.selectBatchIds(versionIds);
  }

  @Override
  public int countPackageItems(Long versionId) {
    return versionId == null ? 0 : packageItemMapper.countByVersionId(versionId);
  }

  @Override
  public int countAuxItems(Long versionId) {
    return versionId == null ? 0 : auxItemMapper.countByVersionId(versionId);
  }

  @Override
  public int countSalaryItems(Long versionId) {
    return versionId == null ? 0 : salaryItemMapper.countByVersionId(versionId);
  }

  @Override
  public int deactivateProducts(Long taskId, LocalDateTime changedAt) {
    return productMapper.deactivateByTaskId(taskId, changedAt);
  }

  @Override
  public long countAccessibleProducts(
      String accessMode,
      Long userId,
      String businessUnitType,
      String taskStatus,
      String accountingMonth,
      String keyword) {
    return productMapper.countAccessibleWorkbenchRows(
        accessMode, userId, businessUnitType, taskStatus, accountingMonth, keyword);
  }

  @Override
  public List<QuoteTechProduct> findAccessibleProductPage(
      String accessMode,
      Long userId,
      String businessUnitType,
      String taskStatus,
      String accountingMonth,
      String keyword,
      int offset,
      int size) {
    return productMapper.selectAccessibleWorkbenchPage(
        accessMode, userId, businessUnitType, taskStatus, accountingMonth, keyword, offset, size);
  }

  @Override
  public List<QuoteTechTask> findTasks(List<Long> taskIds) {
    return taskIds == null || taskIds.isEmpty()
        ? List.of() : taskMapper.selectBatchIds(taskIds);
  }

  private TechnicalDataTaskException persistence(String label, Long id) {
    return new TechnicalDataTaskException(
        TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT,
        id == null ? label + "写入后未返回主键" : label + "写入后不存在：" + id);
  }
}

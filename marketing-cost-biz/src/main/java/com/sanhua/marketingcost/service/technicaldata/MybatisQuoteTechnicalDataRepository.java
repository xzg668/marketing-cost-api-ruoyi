package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechReviewItem;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechAuxItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechPackageItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechProductMapper;
import com.sanhua.marketingcost.mapper.QuoteTechReviewItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechSalaryItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisQuoteTechnicalDataRepository implements QuoteTechnicalDataRepository {
  private final QuoteTechTaskMapper taskMapper;
  private final QuoteTechProductMapper productMapper;
  private final QuoteTechModuleMapper moduleMapper;
  private final QuoteTechDataVersionMapper versionMapper;
  private final QuoteTechPackageItemMapper packageItemMapper;
  private final QuoteTechAuxItemMapper auxItemMapper;
  private final QuoteTechSalaryItemMapper salaryItemMapper;
  private final QuoteTechReviewItemMapper reviewItemMapper;

  public MybatisQuoteTechnicalDataRepository(
      QuoteTechTaskMapper taskMapper,
      QuoteTechProductMapper productMapper,
      QuoteTechModuleMapper moduleMapper,
      QuoteTechDataVersionMapper versionMapper,
      QuoteTechPackageItemMapper packageItemMapper,
      QuoteTechAuxItemMapper auxItemMapper,
      QuoteTechSalaryItemMapper salaryItemMapper,
      QuoteTechReviewItemMapper reviewItemMapper) {
    this.taskMapper = taskMapper;
    this.productMapper = productMapper;
    this.moduleMapper = moduleMapper;
    this.versionMapper = versionMapper;
    this.packageItemMapper = packageItemMapper;
    this.auxItemMapper = auxItemMapper;
    this.salaryItemMapper = salaryItemMapper;
    this.reviewItemMapper = reviewItemMapper;
  }

  @Override
  public QuoteTechTask insertTask(QuoteTechTask task) {
    requireInserted(taskMapper.insert(task), "技术资料任务");
    return task;
  }

  @Override
  public Optional<QuoteTechTask> findTask(Long taskId) {
    return Optional.ofNullable(taskMapper.selectById(taskId));
  }

  @Override
  public Optional<QuoteTechTask> lockTask(Long taskId) {
    return Optional.ofNullable(taskMapper.selectByIdForUpdate(taskId));
  }

  @Override
  public QuoteTechProduct insertProduct(QuoteTechProduct product) {
    requireInserted(productMapper.insert(product), "技术资料产品");
    return product;
  }

  @Override
  public Optional<QuoteTechProduct> findProduct(Long productId) {
    return Optional.ofNullable(productMapper.selectById(productId));
  }

  @Override
  public Optional<QuoteTechProduct> lockProduct(Long productId) {
    return Optional.ofNullable(productMapper.selectByIdForUpdate(productId));
  }

  @Override
  public Optional<QuoteTechProduct> findActiveProduct(
      Long oaFormItemId, String accountingMonth) {
    return Optional.ofNullable(
        productMapper.selectActiveByItemAndMonth(oaFormItemId, accountingMonth));
  }

  @Override
  public QuoteTechModule insertModule(QuoteTechModule module) {
    requireInserted(moduleMapper.insert(module), "技术资料模块");
    return module;
  }

  @Override
  public Optional<QuoteTechModule> findModule(Long moduleId) {
    return Optional.ofNullable(moduleMapper.selectById(moduleId));
  }

  @Override
  public QuoteTechDataVersion insertVersion(QuoteTechDataVersion version) {
    requireInserted(versionMapper.insert(version), "技术资料版本");
    return version;
  }

  @Override
  public Optional<QuoteTechDataVersion> findVersion(Long versionId) {
    return Optional.ofNullable(versionMapper.selectById(versionId));
  }

  @Override
  public Optional<QuoteTechDataVersion> lockVersion(Long versionId) {
    return Optional.ofNullable(versionMapper.selectByIdForUpdate(versionId));
  }

  @Override
  public int maxVersionNo(Long productId) {
    return versionMapper.selectMaxVersionNo(productId);
  }

  @Override
  public List<QuoteTechModule> lockModules(Long productId) {
    return moduleMapper.selectByProductIdForUpdate(productId);
  }

  @Override
  public List<QuoteTechProduct> lockActiveProducts(Long taskId) {
    return productMapper.selectActiveByTaskIdForUpdate(taskId);
  }

  @Override
  public QuoteTechReviewItem insertReviewItem(QuoteTechReviewItem reviewItem) {
    requireInserted(reviewItemMapper.insert(reviewItem), "技术资料审核项");
    return reviewItem;
  }

  @Override
  public List<QuoteTechReviewItem> findReviewItems(Long taskId, int reviewRound) {
    return reviewItemMapper.selectByTaskRound(taskId, reviewRound);
  }

  @Override
  public int insertPackageItemsIfDraft(Long versionId, List<QuoteTechPackageItem> items) {
    return packageItemMapper.insertBatchIfDraft(versionId, items);
  }

  @Override
  public int insertAuxItemsIfDraft(Long versionId, List<QuoteTechAuxItem> items) {
    return auxItemMapper.insertBatchIfDraft(versionId, items);
  }

  @Override
  public int insertSalaryItemsIfDraft(Long versionId, List<QuoteTechSalaryItem> items) {
    return salaryItemMapper.insertBatchIfDraft(versionId, items);
  }

  @Override
  public List<QuoteTechPackageItem> findPackageItems(Long versionId) {
    return packageItemMapper.selectByVersionId(versionId);
  }

  @Override
  public int countPackageItems(Long versionId) {
    return packageItemMapper.countByVersionId(versionId);
  }

  @Override
  public int deleteAllPackageItemsIfDraft(Long versionId) {
    return packageItemMapper.deleteAllIfDraft(versionId);
  }

  @Override
  public List<QuoteTechAuxItem> findAuxItems(Long versionId) {
    return auxItemMapper.selectByVersionId(versionId);
  }

  @Override
  public int countAuxItems(Long versionId) {
    return auxItemMapper.countByVersionId(versionId);
  }

  @Override
  public int deleteAllAuxItemsIfDraft(Long versionId) {
    return auxItemMapper.deleteAllIfDraft(versionId);
  }

  @Override
  public List<QuoteTechSalaryItem> findSalaryItems(Long versionId) {
    return salaryItemMapper.selectByVersionId(versionId);
  }

  @Override
  public int countSalaryItems(Long versionId) {
    return salaryItemMapper.countByVersionId(versionId);
  }

  @Override
  public int deleteAllSalaryItemsIfDraft(Long versionId) {
    return salaryItemMapper.deleteAllIfDraft(versionId);
  }

  @Override
  public int updatePackageItemIfDraft(QuoteTechPackageItem item) {
    return packageItemMapper.updateIfDraft(item);
  }

  @Override
  public int updateAuxItemIfDraft(QuoteTechAuxItem item) {
    return auxItemMapper.updateIfDraft(item);
  }

  @Override
  public int updateSalaryItemIfDraft(QuoteTechSalaryItem item) {
    return salaryItemMapper.updateIfDraft(item);
  }

  @Override
  public int deletePackageItemIfDraft(Long versionId, Long itemId) {
    return packageItemMapper.deleteIfDraft(versionId, itemId);
  }

  @Override
  public int deleteAuxItemIfDraft(Long versionId, Long itemId) {
    return auxItemMapper.deleteIfDraft(versionId, itemId);
  }

  @Override
  public int deleteSalaryItemIfDraft(Long versionId, Long itemId) {
    return salaryItemMapper.deleteIfDraft(versionId, itemId);
  }

  @Override
  public int updateDraftVersion(
      QuoteTechDataVersion version, int expectedVersion, LocalDateTime updatedAt) {
    return versionMapper.updateDraftWithVersion(version, expectedVersion, updatedAt);
  }

  @Override
  public int transitionVersion(
      Long versionId,
      String expectedStatus,
      String targetStatus,
      int expectedVersion,
      String contentFingerprint,
      String referenceSnapshotJson,
      Long actorId,
      LocalDateTime changedAt) {
    return versionMapper.transitionStatus(
        versionId,
        expectedStatus,
        targetStatus,
        expectedVersion,
        contentFingerprint,
        referenceSnapshotJson,
        actorId,
        changedAt);
  }

  @Override
  public int updateProductPointers(
      QuoteTechProduct product, int expectedVersion, LocalDateTime changedAt) {
    return productMapper.updatePointersWithVersion(product, expectedVersion, changedAt);
  }

  @Override
  public int updateModule(
      QuoteTechModule module, int expectedVersion, LocalDateTime changedAt) {
    return moduleMapper.updateWithVersion(module, expectedVersion, changedAt);
  }

  @Override
  public int markTaskInProgress(Long taskId, Long actorId, LocalDateTime changedAt) {
    return taskMapper.markInProgress(taskId, actorId, changedAt);
  }

  @Override
  public int submitTask(
      Long taskId,
      int expectedVersion,
      int reviewRound,
      String idempotencyKey,
      String submissionFingerprint,
      Long actorId,
      LocalDateTime submittedAt) {
    return taskMapper.submitWithVersion(
        taskId, expectedVersion, reviewRound, idempotencyKey,
        submissionFingerprint, actorId, submittedAt);
  }

  private void requireInserted(int rows, String label) {
    if (rows != 1) {
      throw new IllegalStateException(label + "写入行数异常：" + rows);
    }
  }
}

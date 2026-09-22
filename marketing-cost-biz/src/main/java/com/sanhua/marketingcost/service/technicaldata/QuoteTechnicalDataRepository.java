package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** T2 只负责新技术资料模型的基础持久化，不包含任务初始化业务规则。 */
public interface QuoteTechnicalDataRepository {
  QuoteTechTask insertTask(QuoteTechTask task);

  Optional<QuoteTechTask> findTask(Long taskId);

  Optional<QuoteTechTask> lockTask(Long taskId);

  QuoteTechProduct insertProduct(QuoteTechProduct product);

  Optional<QuoteTechProduct> findProduct(Long productId);

  Optional<QuoteTechProduct> lockProduct(Long productId);

  Optional<QuoteTechProduct> findActiveProduct(Long oaFormItemId, String accountingMonth);

  QuoteTechModule insertModule(QuoteTechModule module);

  Optional<QuoteTechModule> findModule(Long moduleId);

  QuoteTechDataVersion insertVersion(QuoteTechDataVersion version);

  Optional<QuoteTechDataVersion> findVersion(Long versionId);

  Optional<QuoteTechDataVersion> lockVersion(Long versionId);

  int maxVersionNo(Long productId);

  List<QuoteTechModule> lockModules(Long productId);

  List<QuoteTechProduct> lockActiveProducts(Long taskId);

  int insertPackageItemsIfDraft(Long versionId, List<QuoteTechPackageItem> items);

  int insertAuxItemsIfDraft(Long versionId, List<QuoteTechAuxItem> items);

  int insertSalaryItemsIfDraft(Long versionId, List<QuoteTechSalaryItem> items);

  List<QuoteTechPackageItem> findPackageItems(Long versionId);

  int countPackageItems(Long versionId);

  int deleteAllPackageItemsIfDraft(Long versionId);

  List<QuoteTechAuxItem> findAuxItems(Long versionId);

  int countAuxItems(Long versionId);

  int deleteAllAuxItemsIfDraft(Long versionId);

  List<QuoteTechSalaryItem> findSalaryItems(Long versionId);

  int countSalaryItems(Long versionId);

  int deleteAllSalaryItemsIfDraft(Long versionId);

  int updateDraftVersion(
      QuoteTechDataVersion version, int expectedVersion, LocalDateTime updatedAt);

  int transitionVersion(
      Long versionId,
      String expectedStatus,
      String targetStatus,
      int expectedVersion,
      String contentFingerprint,
      String referenceSnapshotJson,
      Long actorId,
      LocalDateTime changedAt);

  int updateProductPointers(
      QuoteTechProduct product, int expectedVersion, LocalDateTime changedAt);

  int updateModule(
      QuoteTechModule module, int expectedVersion, LocalDateTime changedAt);

  int markTaskInProgress(Long taskId, Long actorId, LocalDateTime changedAt);

}

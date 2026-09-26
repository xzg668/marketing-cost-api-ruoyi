package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TechnicalDataTaskRepository {
  QuoteTechTask upsertActiveTask(QuoteTechTask task);

  Optional<QuoteTechTask> lockActiveTask(
      Long oaFormItemId, String accountingMonth);

  Optional<QuoteTechTask> findTask(Long taskId);

  int assignUnassignedTask(
      Long taskId,
      Integer expectedVersion,
      Long assigneeUserId,
      String assigneeName,
      String sourceRequestId,
      LocalDateTime dueAt,
      Long actorId);

  QuoteTechProduct upsertActiveProduct(QuoteTechProduct product);

  List<QuoteTechProduct> findProducts(Long taskId);

  QuoteTechModule upsertModule(QuoteTechModule module);

  List<QuoteTechModule> findModules(Long productId);

  List<QuoteTechModule> findModules(List<Long> productIds);

  List<QuoteTechDataVersion> findVersions(List<Long> versionIds);

  int countPackageItems(Long versionId);

  int countAuxItems(Long versionId);

  int countSalaryItems(Long versionId);

  int deactivateProducts(Long taskId, LocalDateTime changedAt);

  long countAccessibleProducts(
      String accessMode,
      Long userId,
      String businessUnitType,
      String taskStatus,
      String accountingMonth,
      String keyword, String oaNo);

  List<QuoteTechProduct> findAccessibleProductPage(
      String accessMode,
      Long userId,
      String businessUnitType,
      String taskStatus,
      String accountingMonth,
      String keyword,
      String oaNo,
      int offset,
      int size);

  List<QuoteTechTask> findTasks(List<Long> taskIds);
}

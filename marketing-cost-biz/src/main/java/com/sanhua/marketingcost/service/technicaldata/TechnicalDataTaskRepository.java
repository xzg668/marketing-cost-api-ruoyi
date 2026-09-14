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
      String oaNo, String accountingMonth, Long assigneeUserId);

  Optional<QuoteTechTask> findTask(Long taskId);

  QuoteTechProduct upsertActiveProduct(QuoteTechProduct product);

  Optional<QuoteTechProduct> lockActiveProduct(Long oaFormItemId, String accountingMonth);

  List<QuoteTechProduct> findProducts(Long taskId);

  List<QuoteTechProduct> lockActiveProducts(Long taskId);

  QuoteTechModule upsertModule(QuoteTechModule module);

  List<QuoteTechModule> findModules(Long productId);

  List<QuoteTechModule> findModules(List<Long> productIds);

  List<QuoteTechDataVersion> findVersions(List<Long> versionIds);

  int countPackageItems(Long versionId);

  int countAuxItems(Long versionId);

  int countSalaryItems(Long versionId);

  int deactivateProducts(Long taskId, LocalDateTime changedAt);

  int deactivateTask(
      Long taskId, String reason, Long actorId, LocalDateTime changedAt);

  long countAccessible(
      String accessMode,
      Long userId,
      String taskStatus,
      String accountingMonth);

  List<QuoteTechTask> findAccessiblePage(
      String accessMode,
      Long userId,
      String taskStatus,
      String accountingMonth,
      int offset,
      int size);

  long countAccessibleProducts(
      String accessMode,
      Long userId,
      String taskStatus,
      String accountingMonth,
      String keyword);

  List<QuoteTechProduct> findAccessibleProductPage(
      String accessMode,
      Long userId,
      String taskStatus,
      String accountingMonth,
      String keyword,
      int offset,
      int size);

  List<QuoteTechTask> findTasks(List<Long> taskIds);
}

package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.util.List;

public interface QuoteTechnicalDataPersistenceService {
  QuoteTechTask createTask(QuoteTechTask task);

  QuoteTechProduct createProduct(QuoteTechProduct product);

  QuoteTechModule createModule(QuoteTechModule module);

  QuoteTechDataVersion createVersion(QuoteTechDataVersion version);

  QuoteTechDataVersion createDraftWithPackageItems(
      QuoteTechDataVersion version, List<QuoteTechPackageItem> items);

  void addPackageItems(Long versionId, List<QuoteTechPackageItem> items);

  void addAuxItems(Long versionId, List<QuoteTechAuxItem> items);

  void addSalaryItems(Long versionId, List<QuoteTechSalaryItem> items);

  QuoteTechDataVersion updateDraft(QuoteTechDataVersion version, int expectedRowVersion);

  QuoteTechDataVersion transitionVersion(
      Long versionId,
      String expectedStatus,
      String targetStatus,
      int expectedRowVersion,
      String contentFingerprint,
      Long actorId);

  QuoteTechDataVersion freezeDraftForSubmission(
      Long productId, int expectedProductVersion, Long actorId);

}

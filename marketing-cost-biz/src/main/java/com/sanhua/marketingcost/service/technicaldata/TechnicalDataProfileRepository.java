package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.time.LocalDateTime;
import java.util.Optional;

public interface TechnicalDataProfileRepository {
  Optional<QuoteTechProduct> lockProduct(Long productId);

  Optional<QuoteTechTask> lockTask(Long taskId);

  Optional<QuoteTechModule> lockProfileModule(Long productId);

  Optional<QuoteTechDataVersion> lockVersion(Long versionId);

  QuoteTechDataVersion insertVersion(QuoteTechDataVersion version);

  int updateDraftProfile(
      QuoteTechDataVersion version, int expectedVersion, LocalDateTime updatedAt);

  int updateProductProfilePointer(
      Long productId,
      Long versionId,
      int expectedVersion,
      LocalDateTime updatedAt);

  int updateProfileModule(
      Long moduleId,
      Long versionId,
      int expectedVersion,
      LocalDateTime updatedAt);

  int markTaskInProgress(Long taskId, Long actorId, LocalDateTime updatedAt);
}

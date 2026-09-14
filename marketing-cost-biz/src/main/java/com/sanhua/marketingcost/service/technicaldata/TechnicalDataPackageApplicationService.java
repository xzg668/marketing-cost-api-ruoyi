package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageSaveRequest;

public interface TechnicalDataPackageApplicationService {
  TechnicalDataPackageResponse get(Long productId, TechnicalDataActor actor);

  TechnicalDataPackageReferenceResponse references(
      Long productId, String keyword, TechnicalDataActor actor);

  TechnicalDataPackageResponse applyReference(
      Long productId, TechnicalDataPackageReferenceRequest request, TechnicalDataActor actor);

  TechnicalDataPackageResponse save(
      Long productId, TechnicalDataPackageSaveRequest request, TechnicalDataActor actor);

  TechnicalDataPackageResponse delete(
      Long productId, Long itemId, Integer expectedVersion, TechnicalDataActor actor);
}

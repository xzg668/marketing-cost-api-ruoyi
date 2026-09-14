package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryReferenceRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryReferenceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalarySaveRequest;

public interface TechnicalDataSalaryApplicationService {
  TechnicalDataSalaryResponse get(Long productId, TechnicalDataActor actor);

  TechnicalDataSalaryReferenceResponse references(
      Long productId, String keyword, TechnicalDataActor actor);

  TechnicalDataSalaryResponse applyReference(
      Long productId, TechnicalDataSalaryReferenceRequest request, TechnicalDataActor actor);

  TechnicalDataSalaryResponse save(
      Long productId, TechnicalDataSalarySaveRequest request, TechnicalDataActor actor);

  TechnicalDataSalaryResponse delete(
      Long productId, Long itemId, Integer expectedVersion, TechnicalDataActor actor);
}

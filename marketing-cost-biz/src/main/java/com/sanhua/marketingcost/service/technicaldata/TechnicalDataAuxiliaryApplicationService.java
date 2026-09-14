package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryReferenceRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryReferenceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliarySaveRequest;

public interface TechnicalDataAuxiliaryApplicationService {
  TechnicalDataAuxiliaryResponse get(Long productId, TechnicalDataActor actor);

  TechnicalDataAuxiliaryReferenceResponse references(
      Long productId, String keyword, TechnicalDataActor actor);

  TechnicalDataAuxiliaryResponse applyReference(
      Long productId, TechnicalDataAuxiliaryReferenceRequest request, TechnicalDataActor actor);

  TechnicalDataAuxiliaryResponse save(
      Long productId, TechnicalDataAuxiliarySaveRequest request, TechnicalDataActor actor);

  TechnicalDataAuxiliaryResponse delete(
      Long productId, Long itemId, Integer expectedVersion, TechnicalDataActor actor);
}

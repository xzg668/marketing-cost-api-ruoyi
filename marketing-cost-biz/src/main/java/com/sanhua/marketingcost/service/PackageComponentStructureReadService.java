package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;

public interface PackageComponentStructureReadService {

  PackageComponentStructureReadResult readByReference(
      String referenceFinishedCode, String sourceTopProductCode, String periodMonth);

  PackageComponentStructureReadResult readApprovedReferenceForBareProduct(String bareProductCode);
}

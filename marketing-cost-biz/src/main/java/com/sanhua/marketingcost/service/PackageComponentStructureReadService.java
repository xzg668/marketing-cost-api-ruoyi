package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;

public interface PackageComponentStructureReadService {

  PackageComponentStructureReadResult readByReference(
      String referenceFinishedCode, String sourceTopProductCode, String periodMonth);

  PackageComponentStructureReadResult readByReference(
      String referenceFinishedCode,
      String sourceTopProductCode,
      String periodMonth,
      String priceOrgCode,
      String materialOrganizationCode);

  PackageComponentStructureReadResult readApprovedReferenceForBareProduct(String bareProductCode);

  PackageComponentStructureReadResult readApprovedReferenceForBareProduct(
      String bareProductCode, String priceOrgCode, String materialOrganizationCode);
}

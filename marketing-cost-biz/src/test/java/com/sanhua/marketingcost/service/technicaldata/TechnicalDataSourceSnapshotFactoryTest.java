package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TechnicalDataSourceSnapshotFactoryTest {
  private final TechnicalDataSourceSnapshotFactory factory = new TechnicalDataSourceSnapshotFactory(new ObjectMapper());

  @Test void fingerprintBindsSourceIdentityValuesAndOrganization() {
    var first = factory.create(source(1001L, "MODEL-A", "210"));
    assertThat(factory.create(source(1001L, "MODEL-A", "210"))).isEqualTo(first);
    assertThat(factory.create(source(1001L, "MODEL-B", "210")).fingerprint()).isNotEqualTo(first.fingerprint());
    assertThat(factory.create(source(1002L, "MODEL-A", "210")).fingerprint()).isNotEqualTo(first.fingerprint());
    assertThat(factory.create(source(1001L, "MODEL-A", "220")).fingerprint()).isNotEqualTo(first.fingerprint());
    assertThat(factory.readProfile(first.json())).isEqualTo(
        new TechnicalDataSourceSnapshotFactory.SourceProfile("MODEL-A", "标准品", false, new BigDecimal("1000"), "PIECE"));
    assertThat(first.json()).contains("annualVolume", "externalLineId").doesNotContain("validPackageSource");
  }

  @Test void legacyProfileReadsOriginalNewProductMeaning() {
    assertThat(factory.readProfile("{\"sourceModel\":\"OLD\",\"sourceProductProperty\":\"标准品\",\"newProduct\":true}"))
        .isEqualTo(new TechnicalDataSourceSnapshotFactory.SourceProfile("OLD", "标准品", true, null, null));
  }

  @Test void missingSourceFieldsRemainUnknownAndZeroVolumeIsNotMissing() {
    var missing = factory.readProfile("{}");
    assertThat(missing.productModel()).isNull();
    assertThat(missing.newProduct()).isNull();
    assertThat(missing.annualVolume()).isNull();
    assertThat(missing.annualVolumeUnit()).isNull();
    var known = factory.readProfile("{\"annualVolume\":0,\"annualVolumeUnit\":\"TEN_THOUSAND_PIECES\"}");
    assertThat(known.annualVolume()).isEqualByComparingTo("0");
    assertThat(known.annualVolumeUnit()).isEqualTo("TEN_THOUSAND_PIECES");
  }

  private TechnicalDataProductSource source(Long itemId, String model, String org) {
    return new TechnicalDataProductSource(10L, "FI-SC-006-20260914-TEST", itemId, "LINE-1", 1,
        "MAT", "产品", model, "SPEC", "标准品", false, new BigDecimal("1000"), "PIECE", "标准包装", "COMMERCIAL", org, org);
  }
}

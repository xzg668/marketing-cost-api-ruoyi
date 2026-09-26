package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplementBomAvailabilityResolverTest {
  private final QuoteBomSupplementVersionMapper mapper =
      mock(QuoteBomSupplementVersionMapper.class);
  private final SupplementBomAvailabilityResolver resolver =
      new SupplementBomAvailabilityResolver(mapper);

  @Test
  void laterU9BomAlwaysWinsOverApprovedElectronicDrawingBom() throws Exception {
    BomAvailability u9 = available("U9C");

    BomAvailability result = resolver.resolve(11L, "COMMERCIAL", "2026-08", u9);

    assertThat(result).isNull();
    verify(mapper, never()).selectList(any());
  }

  @Test
  void approvedElectronicDrawingBomIsFallbackOnlyWhenU9IsMissing() throws Exception {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(91L);
    version.setCompositionFingerprint("fp-91");
    when(mapper.selectList(any())).thenReturn(List.of(version));

    BomAvailability result = resolver.resolve(
        11L, "COMMERCIAL", "2026-08", BomAvailability.unavailable("U9无BOM"));

    assertThat(result.isAvailable()).isTrue();
    assertThat(result.getSource()).isEqualTo("ELECTRONIC_DRAWING_BOM");
    assertThat(result.getSyncBatchId()).isEqualTo("SUPPLEMENT_VERSION:91");
  }

  @Test
  void missingApprovedSupplementLeavesBomUnavailable() {
    when(mapper.selectList(any())).thenReturn(List.of());

    BomAvailability result = resolver.resolve(
        11L, "COMMERCIAL", "2026-08", BomAvailability.unavailable("U9无BOM"));

    assertThat(result).isNull();
  }


  private static BomAvailability available(String source) {
    BomAvailability availability = new BomAvailability();
    availability.setAvailable(true);
    availability.setSource(source);
    return availability;
  }
}

package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class CollaborationBomAvailabilityResolverTest {
  @Mock JdbcTemplate jdbc;

  @Test
  void laterU9BomAlwaysWinsOverApprovedElectronicDrawingBom() throws Exception {
    stub("FULL_BOM", 91L, null, "fp-91");
    BomAvailability u9 = available("U9C");

    BomAvailability result = new CollaborationBomAvailabilityResolver(jdbc)
        .resolve(11L, "COMMERCIAL", "2026-08", u9);

    assertThat(result).isNull();
  }

  @Test
  void approvedElectronicDrawingBomIsFallbackOnlyWhenU9IsMissing() throws Exception {
    stub("FULL_BOM", 91L, null, "fp-91");

    BomAvailability result = new CollaborationBomAvailabilityResolver(jdbc)
        .resolve(11L, "COMMERCIAL", "2026-08", BomAvailability.unavailable("U9无BOM"));

    assertThat(result.isAvailable()).isTrue();
    assertThat(result.getSource()).isEqualTo("ELECTRONIC_DRAWING_BOM");
    assertThat(result.getSyncBatchId()).isEqualTo("SUPPLEMENT_VERSION:91");
  }

  @Test
  void bareProductCombinesU9BodyAndApprovedPackage() throws Exception {
    stub("BARE_PACKAGE", null, 73L, null);

    BomAvailability result = new CollaborationBomAvailabilityResolver(jdbc)
        .resolve(11L, "COMMERCIAL", "2026-08", available("U9C"));

    assertThat(result.isAvailable()).isTrue();
    assertThat(result.getSource()).isEqualTo("U9_BODY+APPROVED_PACKAGE");
    assertThat(result.getSyncBatchId()).isEqualTo("PACKAGE_REFERENCE:73");
  }

  @SuppressWarnings("unchecked")
  private void stub(String scope, Long supplement, Long packageRef, String fingerprint)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("primary_scope")).thenReturn(scope);
    when(rs.getObject("supplement_version_id", Long.class)).thenReturn(supplement);
    when(rs.getObject("package_reference_id", Long.class)).thenReturn(packageRef);
    when(rs.getString("electronic_bom_fingerprint")).thenReturn(fingerprint);
    when(rs.getString("accounting_month")).thenReturn("2026-08");
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(invocation -> List.of(((RowMapper<Object>) invocation.getArgument(1))
            .mapRow(rs, 0)));
  }

  private static BomAvailability available(String source) {
    BomAvailability availability = new BomAvailability();
    availability.setAvailable(true);
    availability.setSource(source);
    return availability;
  }
}

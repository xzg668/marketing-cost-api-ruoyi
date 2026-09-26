package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.sanhua.marketingcost.dto.RollupPartComponentDto;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class RollupDisplaySnapshotIntegrationTest extends BomMapperTestBase {
  @Autowired CostRunPartItemMapper parts;
  @Autowired CostRunTraceSnapshotMapper snapshots;

  @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

  @Test void frozenComponentsRemainReadableWithoutLiveBomAndKeepPrecision() {
    var part = part("COMMERCIAL");
    snapshot(part, part.getCostRunNo(), """
        {"rollupDisplayComponents":[
          {"childMaterialCode":"RAW-A","childMaterialName":"铜管","childMaterialSpec":"Φ22",
           "childQtyPerTop":0.09821158,"childUnitCost":4.00192804,"childRawPriceType":"联动价"},
          {"childMaterialCode":"RAW-B","childQtyPerTop":0,"childUnitCost":0,"childRawPriceType":"固定价"}
        ]}
        """);
    identity("COMMERCIAL");
    var stored = parts.selectRollupDisplaySnapshots(List.of(part.getId()));
    assertThat(stored).hasSize(1);
    var result = JsonUtils.parseArray(stored.getFirst().componentsJson(), RollupPartComponentDto.class);
    assertThat(result).hasSize(2);
    assertThat(result.getFirst().getChildMaterialName()).isEqualTo("铜管");
    assertThat(result.getFirst().getChildMaterialSpec()).isEqualTo("Φ22");
    assertThat(result.getFirst().getChildQtyPerTop()).isEqualByComparingTo("0.09821158");
    assertThat(result.getFirst().getChildUnitCost()).isEqualByComparingTo("4.00192804");
    assertThat(result.getFirst().getChildRawPriceType()).isEqualTo("联动价");
    assertThat(result.getLast().getChildUnitCost()).isZero();
    assertThat(parts.selectRollupDisplayComponents(List.of(part.getId()))).isEmpty();
  }

  @Test void explicitEmptySnapshotReturnsMarkerWhileOlderSnapshotRemainsUnmarked() {
    var empty = part("COMMERCIAL"); var old = part("COMMERCIAL");
    snapshot(empty, empty.getCostRunNo(), "{\"rollupDisplayComponents\":[]}");
    snapshot(old, old.getCostRunNo(), "{\"partItem\":{\"partCode\":\"OLD\"}}");
    var result = parts.selectRollupDisplaySnapshots(List.of(empty.getId(), old.getId()));
    assertThat(result).singleElement().satisfies(row -> {
      assertThat(row.partItemId()).isEqualTo(empty.getId());
      assertThat(row.componentsJson()).isEqualTo("[]");
    });
  }

  @Test void snapshotsCannotCrossBusinessUnitEvenForAnAdmin() {
    var commercial = part("COMMERCIAL"); var plate = part("PLATE");
    for (var part : List.of(commercial, plate)) snapshot(part, part.getCostRunNo(), "{\"rollupDisplayComponents\":[]}");
    identity("COMMERCIAL");
    assertThat(parts.selectRollupDisplaySnapshots(List.of(commercial.getId(), plate.getId())))
        .singleElement().satisfies(row -> assertThat(row.partItemId()).isEqualTo(commercial.getId()));
    identity("PLATE");
    assertThat(parts.selectRollupDisplaySnapshots(List.of(commercial.getId(), plate.getId())))
        .singleElement().satisfies(row -> assertThat(row.partItemId()).isEqualTo(plate.getId()));
  }

  @Test void unrelatedRunSnapshotCannotProvideComponentsForRequestedPart() {
    var part = part("COMMERCIAL");
    snapshot(part, "OTHER-RUN", "{\"rollupDisplayComponents\":[]}");
    assertThat(parts.selectRollupDisplaySnapshots(List.of(part.getId()))).isEmpty();
  }

  private CostRunPartItem part(String unit) {
    var row = new CostRunPartItem(); row.setCostRunNo("ROLLUP-" + UUID.randomUUID());
    row.setOaNo("TEST-ROLLUP"); row.setProductCode("PRODUCT"); row.setPartCode("MAKE");
    row.setBomRowId(999999L); row.setBusinessUnitType(unit); parts.insert(row); return row;
  }

  private void snapshot(CostRunPartItem part, String run, String json) {
    var row = new CostRunTraceSnapshot(); row.setCostRunVersionId(1L); row.setCostRunNo(run);
    row.setOaNo(part.getOaNo()); row.setOaFormItemId(1L); row.setProductCode(part.getProductCode());
    row.setPricingMonth("2026-09"); row.setTraceType("PART_PRICE"); row.setTraceKey("PART:" + part.getId());
    row.setPartItemId(part.getId()); row.setSourceSnapshotJson(json); row.setBusinessUnitType(part.getBusinessUnitType());
    snapshots.insert(row);
  }

  private void identity(String unit) {
    var authentication = new UsernamePasswordAuthenticationToken("admin", null, List.of());
    authentication.setDetails(Map.of("businessUnitType", unit));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}

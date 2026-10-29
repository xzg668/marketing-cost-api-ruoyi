package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Packaging;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TechnicalDataPackageRulesTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test void rawQuantityDividesByPackageBaseWithoutAnotherParentMultiplier() throws Exception {
    Packaging packaging = json.readValue("""
        {"entryMode":"REFERENCE","quantityBasis":"PACKAGE_PARENT","parentBaseQuantity":150,"parentQuantity":99}
        """, Packaging.class);
    var child = new QuoteTechPackageItem(); child.setQuantity(new BigDecimal("150"));
    assertThat(TechnicalDataPackageRules.perProduct(packaging,child)).isEqualByComparingTo("1");
    child.setQuantity(new BigDecimal("2"));
    assertThat(TechnicalDataPackageRules.perProduct(packaging,child)).isEqualByComparingTo("0.01333333");
  }

  @Test void frozenLegacyQuantitiesKeepTheirOriginalMeaning() throws Exception {
    Packaging packaging = json.readValue("""
        {"entryMode":"MANUAL","parentQuantityUnit":"包装组件/件产品","parentQuantity":2}
        """, Packaging.class);
    var child = new QuoteTechPackageItem(); child.setQuantity(new BigDecimal("0.5"));
    assertThat(TechnicalDataPackageRules.perProduct(packaging,child)).isEqualByComparingTo("1");
    assertThat(json.writeValueAsString(packaging)).doesNotContain("parentBaseQuantity", "quantityBasis");
  }

  @Test void missingBaseIsNeverSilentlyAssumedToBeOne() throws Exception {
    Packaging packaging = json.readValue("""
        {"entryMode":"MANUAL","quantityBasis":"PACKAGE_PARENT"}
        """, Packaging.class);
    var child = new QuoteTechPackageItem(); child.setQuantity(BigDecimal.ONE);
    assertThat(TechnicalDataPackageRules.canCalculate(packaging,child)).isFalse();
    assertThatThrownBy(() -> TechnicalDataPackageRules.perProduct(packaging,child)).hasMessageContaining("母件底数");
  }
}

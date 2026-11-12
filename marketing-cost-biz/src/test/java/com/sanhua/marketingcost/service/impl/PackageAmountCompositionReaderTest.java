package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.CostAmountComposition;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class PackageAmountCompositionReaderTest {
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final MonthlyPriceDocumentCodec codec=new MonthlyPriceDocumentCodec(json);
  final PricePrepareItemMapper preparations=mock(PricePrepareItemMapper.class);
  final PackageComponentSnapshotDetailMapper names=mock(PackageComponentSnapshotDetailMapper.class);
  final PackageAmountCompositionReader reader=new PackageAmountCompositionReader(preparations,names,json,codec);
  final QuoteCostRunVersion version=new QuoteCostRunVersion();
  final CostRunPartItem part=new CostRunPartItem();
  final CostRunCostItem fee=new CostRunCostItem();
  final PricePrepareItem prepared=new PricePrepareItem();
  final ObjectNode snapshot=json.createObjectNode();
  PackageAmountCompositionReaderTest() {
    version.setId(1L);version.setOaNo("OA");version.setOaFormItemId(2L);version.setCostRunNo("RUN");version.setProductCode("TOP");version.setPricingMonth("2026-11");version.setBusinessUnitType("COMMERCIAL");
    part.setId(10L);part.setCostRunVersionId(1L);part.setCostRunNo("RUN");part.setProductCode("TOP");part.setBusinessUnitType("COMMERCIAL");part.setPartCode("PACK");part.setPriceOrgCode("210");part.setPricePrepareItemId(101L);part.setQty(d("3"));part.setUnitPrice(d(".28"));part.setAmount(d(".84"));
    prepared.setId(101L);prepared.setOaNo("OA");prepared.setOaFormItemId(2L);prepared.setTopProductCode("TOP");prepared.setBusinessUnitType("COMMERCIAL");prepared.setPeriodMonth("2026-11");prepared.setPriceOrgCode("210");prepared.setMaterialCode("PACK");prepared.setResultRefType("PACKAGE_PRICE");prepared.setUnitPrice(d(".28"));
    fee.setCostCode("OTHER_EXP_PACKAGE");fee.setBaseAmount(d(".84"));fee.setAmount(d(".898800"));
    snapshot.put("calculatorVersion","PACKAGE_PRICE_V1");snapshot.put("materialCode","PACK");snapshot.put("businessUnitType","COMMERCIAL");snapshot.put("priceOrgCode","210");snapshot.put("periodMonth","2026-11");snapshot.put("snapshotId",7);snapshot.put("parentBaseQty",100);
    var children=snapshot.putArray("components");
    children.addObject().put("snapshotDetailId",11).put("materialCode","BOX").put("quantity",2).put("parentBaseQty",10).put("quantityBasis","PACKAGE_PARENT").put("unitPrice",12).put("amount",24).put("priceSource","FIXED");
    children.addObject().put("snapshotDetailId",12).put("materialCode","BAG").put("quantity",1).put("parentBaseQty",1).put("quantityBasis","PACKAGE_PARENT").put("unitPrice",4).put("amount",4).put("priceSource","FIXED");
    when(preparations.selectBatchIds(anyCollection())).thenReturn(List.of(prepared));
    var name=new PackageComponentSnapshotDetail();name.setId(11L);name.setSnapshotId(7L);name.setPackageMaterialCode("PACK");name.setChildMaterialCode("BOX");name.setChildMaterialName("纸箱");name.setBusinessUnitType("COMMERCIAL");
    when(names.selectBatchIds(anyCollection())).thenReturn(List.of(name));
  }
  BigDecimal d(String value){return new BigDecimal(value);}
  MonthlyCostVersionService.Stored stored(){return new MonthlyCostVersionService.Stored(version,null,List.of(part),List.of(fee));}
  CostAmountComposition captured(){return new CostAmountComposition("SAVED","",".84","0",null,List.of(),List.of(Map.of("kind","PACKAGE_FEE_PARAMETERS","coefficient","1.07","mode","COMPONENT")));}
  CostAmountComposition read(CostAmountComposition evidence){prepared.setInputSnapshotJson(snapshot.toString());return reader.read(stored(),fee,evidence);}
  @Test void ChildPricesQuantitiesAndMotherBaseUseTheSavedPackageAlgorithmAndCapturedCoefficient() {
    var result=read(captured());
    assertThat(result.rows()).anySatisfy(row->assertThat(row).containsEntry("materialCode","BOX").containsEntry("materialName","纸箱")
        .containsEntry("unitPrice","12").containsEntry("quantity","2").containsEntry("childBaseQty","10")
        .containsEntry("parentBaseQty","100").containsEntry("packageQuantity","3").containsEntry("amountYuan","0.72"));
    assertThat(result.rows()).anySatisfy(row->{assertThat(row.get("kind")).isEqualTo("PACKAGE_PARENT");assertThat(row.get("calculation")).contains("28 ÷ 包装母件底数 100 = 组件单价 0.28");});
    assertThat(result.rows()).anySatisfy(row->assertThat(row).containsEntry("coefficient","1.07").containsEntry("coefficientBasis","本次保存的包装系数"));
    assertThat(result.note()).doesNotContain("反推");assertThat(fee.getAmount()).isEqualByComparingTo(".898800");
  }
  @Test void LegacyChildDenominatorsAreHonoredRatherThanApplyingTheNewBasisToHistory() {
    var child=(ObjectNode)snapshot.path("components").get(0);child.remove("quantityBasis");child.put("parentBaseQty",2);child.put("amount",12);
    prepared.setUnitPrice(d(".16"));part.setUnitPrice(d(".16"));part.setAmount(d(".48"));fee.setAmount(d(".504"));
    var result=read(null);
    assertThat(result.rows()).anySatisfy(row->{assertThat(row.get("materialCode")).isEqualTo("BOX");assertThat(row.get("calculation")).contains("12 × 2 ÷ 2 = 12");});
    assertThat(result.note()).contains("反推","1.05");
  }
  @Test void OldCoefficientIsClearlyDerivedAndNeverReadFromTodaysRules() {
    var result=read(null);
    assertThat(result.note()).contains("未单独记录","反推","不代表读取了当时配置");
    assertThat(result.rows()).anySatisfy(row->assertThat(row).containsEntry("coefficient","1.07").containsEntry("coefficientBasis","由保存金额反推的核对系数"));
  }
  @Test void ZeroQuantityRowsAreVisibleAndNeverBecomeMissingPrices() {
    snapshot.withArray("components").addObject().put("snapshotDetailId",13).put("materialCode","ZERO").put("quantity",0).put("parentBaseQty",1).put("quantityBasis","PACKAGE_PARENT").put("unitPrice",9).put("amount",0);
    assertThat(read(captured()).rows()).anySatisfy(row->assertThat(row).containsEntry("materialCode","ZERO").containsEntry("quantity","0").containsEntry("amountYuan","0"));
  }
  @Test void ForeignVersionOrUnitPreparationIsRejectedBeforeReadingChildren() {
    prepared.setBusinessUnitType("HOUSEHOLD");
    assertThatThrownBy(()->read(captured())).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(names);
    prepared.setBusinessUnitType("COMMERCIAL");part.setCostRunVersionId(99L);
    assertThatThrownBy(()->read(captured())).isInstanceOf(AccessDeniedException.class);
  }
  @Test void MissingOldEvidenceIsDisclosedWithoutConsultingTodaysPackagePrices() {
    snapshot.remove("calculatorVersion");
    var result=read(null);assertThat(result.note()).contains("未保存子件计算底稿","不读取当前包装价格");
    assertThat(result.rows()).isEmpty();verifyNoInteractions(names);
  }
  @Test void WrappedSnapshotUsesItsOriginalSavedValues() {
    prepared.setInputSnapshotJson(codec.wrap(snapshot.toString()));
    var result=reader.read(stored(),fee,captured());
    assertThat(result.rows()).anySatisfy(row->assertThat(row).containsEntry("materialCode","BOX").containsEntry("unitPrice","12"));
  }
}

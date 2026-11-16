package com.sanhua.marketingcost.service.materialshape;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.settlement.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OutsourceAssemblyScrapTest {
  final QuoteMaterialShapeInputs shapes=mock(QuoteMaterialShapeInputs.class);
  final MaterialMasterRawMapper masters=mock(MaterialMasterRawMapper.class);
  final MakePartScrapMappingService scraps=mock(MakePartScrapMappingService.class);
  final OutsourceScrapPreparation target=new OutsourceScrapPreparation(shapes,masters,scraps,null,
      null,mock(JdbcTemplate.class),new ObjectMapper());
  BomSettlementNode node(String code,String parent,String shape,String path) {
    return new BomSettlementNode(1L,"P",parent,code,1,path,BigDecimal.ONE,BigDecimal.ONE,code,null,
        shape,null,null,null,null,null,"主制造","F001",1,0,null,null,null,"210","COMMERCIAL","COMMERCIAL",null);
  }
  @Test void outsourcedAssemblyRetainsManufacturedChildrenAndExistingRecoveriesWithoutDemandingSingleRawWeight() {
    var record=new QuoteBomPreparationRecord();record.setOaFormItemId(160L);
    when(shapes.read("COMMERCIAL","2026-11",160L)).thenReturn(new QuoteMaterialShapeInputs.Catalog(
        List.of(new QuoteMaterialShapeInputs.Source("210","201850113","LINKED","OUTSOURCE",1)),
        List.of(new QuoteMaterialShapeInputs.Choice("210","201850113","OUTSOURCE",1))));
    var nodes=List.of(node("201850113","P","委外加工件","/P/201850113/"),
        node("201850157","201850113","制造件","/P/201850113/201850157/"),
        node("RAW","201850157","采购件","/P/201850113/201850157/RAW/"));
    var recovery=new BomCostingRow();recovery.setMaterialCode("EXISTING-SCRAP");
    var built=new BomSettlementRowBuildResult(List.of(recovery),List.of(),List.of(),List.of(),null);
    assertThat(target.add(record,nodes,built,"COMMERCIAL","2026-11",LocalDateTime.of(2026,11,14,0,0)))
        .isSameAs(built);
    verifyNoInteractions(masters,scraps);
  }
}

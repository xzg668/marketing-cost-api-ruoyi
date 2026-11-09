package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class JjbMeasurementUnitsTest {
  @Test void frozenUnitWinsAndMissingParentUnitUsesOnlyItsOwnMaterialOrganization() {
    var mapper = mock(MaterialMasterRawMapper.class);
    var frozen = row(1L,"FROZEN").toBuilder().unit("千克").build();
    var parent = row(2L,"MAKE");
    var own = material(11L,"MAKE","PLATE","只");
    var other = material(12L,"MAKE","COMMERCIAL","千克");
    when(mapper.selectByLatestBatchAndCodes(List.of("MAKE"),null,"PLATE")).thenReturn(List.of(own,other));
    var result = new JjbMeasurementUnits(mapper).complete(sheet(List.of(frozen,parent)),
        List.of(part(1L,"FROZEN","COMMERCIAL"),part(2L,"MAKE","PLATE")));
    assertThat(result.rows().getFirst()).isEqualTo(frozen);
    assertThat(result.rows().get(1).unit()).isEqualTo("只");
    assertThat(result.rows().get(1).sources()).singleElement().satisfies(source -> {
      assertThat(source.id()).isEqualTo(11L);assertThat(source.description()).contains("计量单位");
    });
    assertThat(result.rows().get(1).quantity()).isEqualTo("0.00001");
    assertThat(result.rows().get(1).amount()).isEqualTo("0.050000");
    verify(mapper).selectByLatestBatchAndCodes(List.of("MAKE"),null,"PLATE");verifyNoMoreInteractions(mapper);
  }

  @Test void absentIdentityOrUnrelatedChildDoesNotGuessUnitOrQueryAnotherOrganization() {
    var mapper = mock(MaterialMasterRawMapper.class);
    var rows = List.of(row(1L,"UNKNOWN"),row(2L,"PARENT\n【CHILD】"));
    var result = new JjbMeasurementUnits(mapper).complete(sheet(rows),
        List.of(part(1L,"UNKNOWN",null),part(2L,"PARENT","COMMERCIAL")));
    assertThat(result.rows()).isEqualTo(rows);verifyNoInteractions(mapper);
  }

  private static JjbReadModel.Row row(long id,String code) {
    return JjbReadModel.Row.builder().key("PART:"+id).kind("PART").code(code).partItemIds(List.of(id))
        .quantity("0.00001").amount("0.050000").build();
  }
  private static CostRunPartItem part(long id,String code,String organization) {
    var p=new CostRunPartItem();p.setId(id);p.setPartCode(code);p.setMaterialOrganizationCode(organization);return p;
  }
  private static MaterialMasterRaw material(long id,String code,String organization,String unit) {
    var p=new MaterialMasterRaw();p.setId(id);p.setMaterialCode(code);p.setOrganizationCode(organization);p.setUnit(unit);return p;
  }
  private static JjbReadModel sheet(List<JjbReadModel.Row> rows) {
    return new JjbReadModel(null,null,rows,List.of(),new JjbReadModel.Totals("1.000000","0.000000","1.000000"),List.of());
  }
}

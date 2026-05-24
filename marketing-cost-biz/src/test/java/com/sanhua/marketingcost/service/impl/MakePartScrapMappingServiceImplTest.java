package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MakePartScrapMappingServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, MaterialScrapRef.class);
  }

  @Test
  @DisplayName("多废料映射返回多条，并按 scrap_code 去重")
  void listMappingsReturnsMultipleScraps() {
    MaterialScrapRefMapper mapper = mock(MaterialScrapRefMapper.class);
    MakePartScrapMappingServiceImpl service = new MakePartScrapMappingServiceImpl(mapper);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                scrap(1L, "RAW-001", "SCRAP-A"),
                scrap(2L, "RAW-001", "SCRAP-B"),
                scrap(3L, "RAW-001", "SCRAP-A")));

    List<MaterialScrapRef> result = service.listMappings("RAW-001", "COMMERCIAL");

    assertThat(result).hasSize(2);
    assertThat(result).extracting(MaterialScrapRef::getScrapCode).containsExactly("SCRAP-A", "SCRAP-B");
  }

  private MaterialScrapRef scrap(Long id, String materialCode, String scrapCode) {
    MaterialScrapRef row = new MaterialScrapRef();
    row.setId(id);
    row.setMaterialCode(materialCode);
    row.setScrapCode(scrapCode);
    return row;
  }
}

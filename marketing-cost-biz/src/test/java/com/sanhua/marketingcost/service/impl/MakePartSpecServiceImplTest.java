package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MakePartSpecImportRequest;
import com.sanhua.marketingcost.dto.MakePartSpecUpdateRequest;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.mapper.MakePartSpecMapper;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.service.PriceScrapService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MakePartSpecServiceImplTest {
  private MakePartSpecMapper specMapper;
  private MaterialScrapRefMapper materialScrapRefMapper;
  private PriceScrapService priceScrapService;
  private MakePartSpecServiceImpl service;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MakePartSpec.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MaterialScrapRef.class);
    specMapper = mock(MakePartSpecMapper.class);
    materialScrapRefMapper = mock(MaterialScrapRefMapper.class);
    priceScrapService = mock(PriceScrapService.class);
    service = new MakePartSpecServiceImpl(specMapper, materialScrapRefMapper, priceScrapService);
  }

  @Test
  @DisplayName("T10: 分页行补充 CMS 多废料映射和当前价状态")
  void pageEnrichesCmsScrapStatus() {
    MakePartSpec spec = new MakePartSpec();
    spec.setMaterialCode("MAKE-1");
    spec.setRawMaterialCode(" 301 050066 ");
    Page<MakePartSpec> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(spec));
    when(specMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
    when(materialScrapRefMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            mapping("301050066", "301990317", "铜废料"),
            mapping("301050066", "301990999", "铝废料")));
    when(priceScrapService.getCurrentByScrapCodes(any()))
        .thenReturn(Map.of("301990317", scrap("301990317", "75.66")));

    Page<MakePartSpec> result = service.page(null, null, 1, 20);

    MakePartSpec row = result.getRecords().get(0);
    assertThat(row.getCmsMappingStatus()).isEqualTo("MULTI_SCRAP_MISSING_PRICE");
    assertThat(row.getCmsScraps()).hasSize(2);
    assertThat(row.getCmsScraps().get(0).getScrapCode()).isEqualTo("301990317");
    assertThat(row.getCmsScraps().get(0).getCurrentRecyclePrice()).isEqualByComparingTo("75.66");
    assertThat(row.getCmsScraps().get(1).getStatus()).isEqualTo("MISSING_PRICE");
  }

  @Test
  @DisplayName("T10: 新增自制件规格忽略历史 recycleCode/recycleUnitPrice")
  void createIgnoresHistoricalScrapFields() {
    MakePartSpecUpdateRequest request = new MakePartSpecUpdateRequest();
    request.setMaterialCode("MAKE-2");
    request.setRawMaterialCode(" 301 050066 ");
    request.setRecycleCode("A");
    request.setRecycleUnitPrice(new BigDecimal("999"));

    MakePartSpec created = service.create(request);

    assertThat(created.getMaterialCode()).isEqualTo("MAKE-2");
    assertThat(created.getRawMaterialCode()).isEqualTo("301050066");
    assertThat(created.getRecycleCode()).isNull();
    assertThat(created.getRecycleUnitPrice()).isNull();
  }

  @Test
  @DisplayName("T10: 修改自制件规格不覆盖库里已有历史废料字段")
  void updatePreservesHistoricalScrapFields() {
    MakePartSpec existing = new MakePartSpec();
    existing.setId(8L);
    existing.setMaterialCode("MAKE-3");
    existing.setRecycleCode("A");
    existing.setRecycleUnitPrice(new BigDecimal("12.34"));
    when(specMapper.selectById(8L)).thenReturn(existing);
    MakePartSpecUpdateRequest request = new MakePartSpecUpdateRequest();
    request.setRecycleCode("B");
    request.setRecycleUnitPrice(new BigDecimal("56.78"));
    request.setRawMaterialCode("301 050066");

    MakePartSpec updated = service.update(8L, request);

    assertThat(updated.getRecycleCode()).isEqualTo("A");
    assertThat(updated.getRecycleUnitPrice()).isEqualByComparingTo("12.34");
    assertThat(updated.getRawMaterialCode()).isEqualTo("301050066");
  }

  @Test
  @DisplayName("T10: 导入自制件规格忽略历史 recycleCode/recycleUnitPrice")
  void importIgnoresHistoricalScrapFields() {
    when(specMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    MakePartSpecImportRequest.MakePartSpecImportRow row =
        new MakePartSpecImportRequest.MakePartSpecImportRow();
    row.setMaterialCode("MAKE-4");
    row.setRecycleCode("A");
    row.setRecycleUnitPrice(new BigDecimal("99.99"));
    MakePartSpecImportRequest request = new MakePartSpecImportRequest();
    request.setRows(List.of(row));

    List<MakePartSpec> imported = service.importItems(request);

    assertThat(imported).hasSize(1);
    assertThat(imported.get(0).getRecycleCode()).isNull();
    assertThat(imported.get(0).getRecycleUnitPrice()).isNull();
  }

  private static MaterialScrapRef mapping(String materialCode, String scrapCode, String scrapName) {
    MaterialScrapRef mapping = new MaterialScrapRef();
    mapping.setMaterialCode(materialCode);
    mapping.setScrapCode(scrapCode);
    mapping.setScrapName(scrapName);
    return mapping;
  }

  private static PriceScrap scrap(String scrapCode, String price) {
    PriceScrap scrap = new PriceScrap();
    scrap.setScrapCode(scrapCode);
    scrap.setRecyclePrice(new BigDecimal(price));
    scrap.setUnit("KG");
    return scrap;
  }
}

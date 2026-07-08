package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("PackageComponentIdentifyServiceImpl")
class PackageComponentIdentifyServiceImplTest {

  private MaterialMasterRawMapper materialMasterRawMapper;
  private PackageComponentIdentifyServiceImpl service;

  @BeforeEach
  void setUp() {
    materialMasterRawMapper = mock(MaterialMasterRawMapper.class);
    service = new PackageComponentIdentifyServiceImpl(materialMasterRawMapper);
  }

  @Test
  @DisplayName("命中 15155 前缀且 shape_attr=虚拟 时识别为包装组件")
  void identifiesPackageComponent() {
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("9830000026238", "1515501", "虚拟")));

    boolean result = service.isPackageComponent(" 9830000026238 ", "COMMERCIAL");

    assertThat(result).isTrue();
    ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(materialMasterRawMapper).selectByLatestBatchAndCodes(captor.capture(), isNull(), eq("COMMERCIAL"));
    assertThat(captor.getValue()).containsExactly("9830000026238");
  }

  @Test
  @DisplayName("主分类不是 15155 前缀时不是包装组件")
  void rejectsMainCategoryMismatch() {
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("M-001", "15154", "虚拟")));

    assertThat(service.isPackageComponent("M-001", "COMMERCIAL")).isFalse();
  }

  @Test
  @DisplayName("shape_attr 不是虚拟时不是包装组件")
  void rejectsShapeMismatch() {
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw("M-002", "1515509", "采购件")));

    assertThat(service.isPackageComponent("M-002", "COMMERCIAL")).isFalse();
  }

  @Test
  @DisplayName("料号不存在时不是包装组件")
  void rejectsMissingMaterial() {
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of());

    assertThat(service.isPackageComponent("NO-SUCH", "COMMERCIAL")).isFalse();
  }

  @Test
  @DisplayName("批量识别返回所有输入料号的判断结果")
  void batchIdentifyReturnsAllInputCodes() {
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(
            raw("PKG-001", "1515500", " 虚拟 "),
            raw("NORMAL-001", "1515600", "虚拟")));

    Map<String, Boolean> result =
        service.batchIdentify(
            List.of(" PKG-001 ", "NORMAL-001", "MISS-001", "PKG-001", " "),
            "COMMERCIAL");

    assertThat(result)
        .containsEntry("PKG-001", true)
        .containsEntry("NORMAL-001", false)
        .containsEntry("MISS-001", false)
        .hasSize(3);
  }

  @Test
  @DisplayName("指定板换组织时按 PLATE 查询包装组件")
  void batchIdentifyUsesPlateOrganization() {
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE")))
        .thenReturn(List.of(raw("PKG-PLATE", "1515500", "虚拟")));

    Map<String, Boolean> result = service.batchIdentify(List.of("PKG-PLATE"), "PLATE");

    assertThat(result).containsEntry("PKG-PLATE", true);
    verify(materialMasterRawMapper).selectByLatestBatchAndCodes(any(), isNull(), eq("PLATE"));
  }

  @Test
  @DisplayName("空输入不访问数据库")
  void blankInputDoesNotQueryDatabase() {
    assertThat(service.isPackageComponent(" ", "COMMERCIAL")).isFalse();
    assertThat(service.batchIdentify(List.of("", "  "), "COMMERCIAL")).isEmpty();

    verifyNoInteractions(materialMasterRawMapper);
  }

  @Test
  @DisplayName("缺组织时直接报错，不默认读取商用料品主档")
  void missingOrganizationFailsFast() {
    assertThatThrownBy(() -> service.isPackageComponent("PKG-001"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("显式传入料品组织");
    assertThatThrownBy(() -> service.batchIdentify(List.of("PKG-001")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("显式传入料品组织");
  }

  private MaterialMasterRaw raw(String materialCode, String mainCategoryCode, String shapeAttr) {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setMaterialCode(materialCode);
    row.setMainCategoryCode(mainCategoryCode);
    row.setShapeAttr(shapeAttr);
    return row;
  }
}

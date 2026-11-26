package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BomHierarchySubtreeQueryTest {
  static final LocalDate DATE = LocalDate.of(2026, 11, 17);

  @BeforeAll static void metadata() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        BomRawHierarchy.class);
  }

  @Test void topProductKeepsItsOriginalTreeAndQuantities() {
    var mapper = mock(BomRawHierarchyMapper.class);
    var rows = tree("TOP", "5");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(rows);
    var result = service(mapper).getHierarchyTree(" TOP ", "主制造", DATE, "U9", "210");
    assertThat(result.getMaterialCode()).isEqualTo("TOP");
    assertThat(result.getChildren()).hasSize(2);
    assertThat(result.getChildren().getFirst().getQtyPerTop()).isEqualByComparingTo("5");
    verify(mapper, times(1)).selectList(any(Wrapper.class));
  }

  @Test void makePartBecomesRootWithoutSiblingsAndUsesItsOwnQuantities() {
    var rows = tree("TOP", "5");
    var mapper = fallback(rows);
    var result = query(mapper);
    assertThat(result.getMaterialCode()).isEqualTo("MAKE");
    assertThat(result.getPath()).isEqualTo("/MAKE/");
    assertThat(result.getLevel()).isZero();
    assertThat(result.getQtyPerParent()).isEqualByComparingTo("1");
    assertThat(result.getQtyPerTop()).isEqualByComparingTo("1");
    assertThat(result.getChildren()).hasSize(1);
    var sub = result.getChildren().getFirst();
    assertThat(sub.getPath()).isEqualTo("/MAKE/SUB@10/");
    assertThat(sub.getQtyPerTop()).isEqualByComparingTo("2.5");
    assertThat(sub.getChildren().getFirst().getQtyPerTop()).isEqualByComparingTo("0.5");
    assertThat(rows.get(1).getPath()).isEqualTo("/TOP/MAKE@10/");
    assertThat(rows.get(1).getQtyPerTop()).isEqualByComparingTo("5");
    assertThat(rows.get(3).getQtyPerTop()).isEqualByComparingTo("2.5");
    assertThat(rows.get(1).getTopProductCode()).isEqualTo("TOP");
  }

  @Test void zeroIncomingQuantityDoesNotPreventDisplayingChildUsage() {
    var result = query(fallback(tree("TOP", "0")));
    assertThat(result.getChildren().getFirst().getQtyPerTop()).isEqualByComparingTo("2.5");
    assertThat(result.getChildren().getFirst().getChildren().getFirst().getQtyPerTop())
        .isEqualByComparingTo("0.5");
  }

  @Test void samePartUnderDifferentProductsIsDisplayedOnce() {
    var first = tree("A", "3");
    var second = tree("B", "7");
    var mapper = mock(BomRawHierarchyMapper.class);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(), List.of(first.get(1), second.get(1)), first, second);
    assertThat(query(mapper).getChildren()).hasSize(1);
  }

  @Test void distinctBomContextsAreNeverSilentlyCombinedOrChosen() {
    var first = tree("A", "3");
    var second = tree("B", "7");
    second.get(2).setQtyPerParent(new BigDecimal("9"));
    var mapper = mock(BomRawHierarchyMapper.class);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(), List.of(first.get(1), second.get(1)), first, second);
    assertThatThrownBy(() -> query(mapper)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不同的下级 BOM");
  }

  @Test void missingAncestorMakesOrphanPartUnavailable() {
    var rows = tree("TOP", "5");
    rows.removeFirst();
    var mapper = mock(BomRawHierarchyMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(rows.getFirst()), rows);
    assertThat(query(mapper)).isNull();
  }

  @Test void absentPartReturnsNullAndInvalidInputNeverReads() {
    var mapper = mock(BomRawHierarchyMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    assertThat(query(mapper)).isNull();
    clearInvocations(mapper);
    assertThatThrownBy(() -> service(mapper).getHierarchyTree("  ", null, DATE, "U9", "210"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service(mapper).getHierarchyTree("MAKE", null, DATE, "U9", null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(mapper);
  }

  @Test void latestEffectivePathWinsAndDescendantAlternativeIdentityIsKept() {
    var rows = tree("TOP", "5");
    var old = rows.get(2);
    old.setChildType("ALTERNATIVE"); old.setAlternativeGroupKey("group-1");
    old.setSourceU9RowId(123L);
    var newer = row(99L, "TOP", "MAKE", "SUB", 2, old.getPath(), "3", "15");
    newer.setEffectiveFrom(DATE); newer.setChildType("ALTERNATIVE");
    newer.setAlternativeGroupKey("group-1"); newer.setSourceU9RowId(123L);
    rows.add(newer);
    var result = query(fallback(rows));
    var sub = result.getChildren().getFirst();
    assertThat(sub.getQtyPerTop()).isEqualByComparingTo("3");
    assertThat(sub.getSourceU9RowId()).isEqualTo(123L);
    assertThat(sub.getChildType()).isEqualTo("ALTERNATIVE");
    assertThat(sub.getAlternativeGroupKey()).isEqualTo("group-1");
  }

  @Test void twoOccurrencesUnderSameTopRetainOneCompleteSubtree() {
    var rows = tree("TOP", "5");
    var duplicate = tree("TOP", "8");
    for (var row : duplicate.subList(1, 4)) {
      row.setPath(row.getPath().replace("MAKE@10", "MAKE@20"));
      rows.add(row);
    }
    assertThat(query(fallback(rows)).getChildren()).hasSize(1);
  }

  static BomHierarchyQueryServiceImpl service(BomRawHierarchyMapper mapper) {
    return new BomHierarchyQueryServiceImpl(mapper,
        new PlateCommercialMakeBomExpansionService(mapper, mock(MaterialMasterRawMapper.class)));
  }

  @Test void costingBareBodyNeverCombinesDifferentUsagesAndRejectsConflictingStructures() {
    var a = tree("A", "3");
    var b = tree("B", "7");
    for (var row : a) row.setBuildBatchId("BUILD");
    for (var row : b) row.setBuildBatchId("BUILD");
    var mapper = mock(BomRawHierarchyMapper.class);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(a.get(1), b.get(1)), a, b);
    var body = BareProductBomSubtree.read(mapper, "MAKE", "210", DATE);
    assertThat(body).extracting(BomRawHierarchy::getMaterialCode).containsExactly("MAKE", "SUB", "RAW");
    assertThat(body.getLast().getQtyPerTop()).isEqualByComparingTo("0.5");
    assertThat(body.getFirst().getBomVersion()).isNull();
    assertThat(a.get(1).getBomVersion()).isEqualTo("F001");

    b.get(2).setQtyPerParent(BigDecimal.TEN);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(a.get(1), b.get(1)), a, b);
    assertThatThrownBy(() -> BareProductBomSubtree.read(mapper, "MAKE", "210", DATE))
        .hasMessageContaining("不同的有效主制造 BOM");
  }

  private BomHierarchyTreeDto query(BomRawHierarchyMapper mapper) {
    return service(mapper).getHierarchyTree("MAKE", "主制造", DATE, "U9", "210");
  }

  private BomRawHierarchyMapper fallback(List<BomRawHierarchy> rows) {
    var mapper = mock(BomRawHierarchyMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(rows.get(1)), rows);
    return mapper;
  }

  static List<BomRawHierarchy> tree(String top, String makeQty) {
    BigDecimal qty = new BigDecimal(makeQty);
    return new ArrayList<>(List.of(
        row(1L, top, top, top, 0, "/" + top + "/", "1", "1"),
        row(2L, top, top, "MAKE", 1, "/" + top + "/MAKE@10/", makeQty, makeQty),
        row(3L, top, "MAKE", "SUB", 2, "/" + top + "/MAKE@10/SUB@10/", "2.5", qty.multiply(new BigDecimal("2.5")).toPlainString()),
        row(4L, top, "SUB", "RAW", 3, "/" + top + "/MAKE@10/SUB@10/RAW@10/", "0.2", qty.multiply(new BigDecimal("0.5")).toPlainString()),
        row(5L, top, top, "SIBLING", 1, "/" + top + "/SIBLING@20/", "8", "8")));
  }

  static BomRawHierarchy row(long id, String top, String parent, String code, int level,
      String path, String perParent, String perTop) {
    var row = new BomRawHierarchy();
    row.setId(id); row.setTopProductCode(top); row.setParentCode(parent);
    row.setMaterialCode(code); row.setMaterialName("名称-" + code);
    row.setLevel(level); row.setPath(path); row.setQtyPerParent(new BigDecimal(perParent));
    row.setQtyPerTop(new BigDecimal(perTop)); row.setPriceOrgCode("210");
    row.setSourceType("U9"); row.setBomPurpose("主制造"); row.setBomVersion("F001");
    row.setShapeAttr(level == 3 ? "采购件" : "制造件");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1)); row.setIsLeaf(level == 3 ? 1 : 0);
    return row;
  }
}

package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BomManageParentRow;
import com.sanhua.marketingcost.dto.BomManageRefreshRequest;
import com.sanhua.marketingcost.entity.BomManageItem;
import com.sanhua.marketingcost.entity.BomManualItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.BomManageItemMapper;
import com.sanhua.marketingcost.mapper.BomManualItemMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.BomManageItemService;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BomManageItemServiceImpl implements BomManageItemService {
  private static final String FILTER_RULE = "A";

  private final BomManageItemMapper bomManageItemMapper;
  private final BomManualItemMapper bomManualItemMapper;
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;

  public BomManageItemServiceImpl(
      BomManageItemMapper bomManageItemMapper,
      BomManualItemMapper bomManualItemMapper,
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper) {
    this.bomManageItemMapper = bomManageItemMapper;
    this.bomManualItemMapper = bomManualItemMapper;
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
  }

  @Override
  public Page<BomManageParentRow> page(
      String oaNo, String bomCode, String materialNo, String shapeAttr, int page, int pageSize) {
    int current = Math.max(page, 1);
    int size = Math.max(pageSize, 1);
    String oaNoFilter = trimToNull(oaNo);
    String bomCodeFilter = trimToNull(bomCode);
    String materialNoFilter = trimToNull(materialNo);
    String shapeAttrFilter = trimToNull(shapeAttr);
    long total = Objects.requireNonNullElse(
        bomManageItemMapper.countParentRows(
            oaNoFilter, bomCodeFilter, materialNoFilter, shapeAttrFilter),
        0L);
    Page<BomManageParentRow> pager = new Page<>(current, size);
    pager.setTotal(total);
    if (total <= 0) {
      pager.setRecords(List.of());
      return pager;
    }
    long offset = (long) (current - 1) * size;
    List<BomManageParentRow> records = bomManageItemMapper.selectParentRows(
        oaNoFilter, bomCodeFilter, materialNoFilter, shapeAttrFilter, offset, size);
    pager.setRecords(records == null ? List.of() : records);
    return pager;
  }

  @Override
  public List<BomManageItem> listDetails(
      String oaNo, Long oaFormItemId, String bomCode, String rootItemCode, String shapeAttr) {
    String oaNoFilter = trimToNull(oaNo);
    String bomCodeFilter = trimToNull(bomCode);
    String rootItemCodeFilter = trimToNull(rootItemCode);
    String shapeAttrFilter = trimToNull(shapeAttr);
    if (!StringUtils.hasText(oaNoFilter) || oaFormItemId == null
        || !StringUtils.hasText(bomCodeFilter) || !StringUtils.hasText(rootItemCodeFilter)) {
      return List.of();
    }
    List<BomManageItem> rows = bomManageItemMapper.selectDetailRows(
        oaNoFilter, oaFormItemId, bomCodeFilter, rootItemCodeFilter, shapeAttrFilter);
    return rows == null ? List.of() : rows;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int refresh(BomManageRefreshRequest request) {
    if (request == null) {
      return 0;
    }
    String oaNo = trimToNull(request.getOaNo());
    String bomCode = trimToNull(request.getBomCode());
    if (!StringUtils.hasText(oaNo)) {
      return 0;
    }
    return refreshByOaNo(oaNo, bomCode);
  }

  private int refreshByOaNo(String oaNo, String bomCode) {
    OaForm form = oaFormMapper.selectOne(Wrappers.lambdaQuery(OaForm.class)
        .eq(OaForm::getOaNo, oaNo));
    if (form == null) {
      return 0;
    }
    List<OaFormItem> items = oaFormItemMapper.selectList(Wrappers.lambdaQuery(OaFormItem.class)
        .eq(OaFormItem::getOaFormId, form.getId()));
    bomManageItemMapper.delete(
        Wrappers.lambdaQuery(BomManageItem.class).eq(BomManageItem::getOaNo, oaNo));
    if (items.isEmpty()) {
      return 0;
    }
    List<String> materialNos = items.stream()
        .map(OaFormItem::getMaterialNo)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .toList();
    if (materialNos.isEmpty()) {
      return 0;
    }
    List<BomManualItem> roots = bomManualItemMapper.selectList(
        Wrappers.lambdaQuery(BomManualItem.class)
            .eq(BomManualItem::getBomLevel, 1)
            .in(BomManualItem::getItemCode, materialNos));
    if (StringUtils.hasText(bomCode)) {
      roots = roots.stream()
          .filter(root -> Objects.equals(bomCode, trimToNull(root.getBomCode())))
          .toList();
    }
    if (roots.isEmpty()) {
      return 0;
    }
    Set<String> bomCodes = new HashSet<>();
    for (BomManualItem root : roots) {
      if (StringUtils.hasText(root.getBomCode())) {
        bomCodes.add(root.getBomCode().trim());
      }
    }
    var itemsByBom = new java.util.HashMap<String, List<BomManualItem>>();
    for (String code : bomCodes) {
      List<BomManualItem> bomItems = bomManualItemMapper.selectList(
          Wrappers.lambdaQuery(BomManualItem.class).eq(BomManualItem::getBomCode, code));
      itemsByBom.put(code, bomItems);
    }
    return insertLeafItems(form, items, roots, itemsByBom);
  }

  private int insertLeafItems(OaForm form, List<OaFormItem> oaItems,
      List<BomManualItem> roots, java.util.Map<String, List<BomManualItem>> itemsByBom) {
    if (form == null || oaItems == null || oaItems.isEmpty() || roots == null || roots.isEmpty()) {
      return 0;
    }
    int inserted = 0;
    Set<String> uniqueKeys = new HashSet<>();
    var rootsByMaterial = new java.util.HashMap<String, List<BomManualItem>>();
    for (BomManualItem root : roots) {
      if (!StringUtils.hasText(root.getItemCode())) {
        continue;
      }
      String key = root.getItemCode().trim();
      rootsByMaterial.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(root);
    }
    for (OaFormItem oaItem : oaItems) {
      String materialNo = trimToNull(oaItem.getMaterialNo());
      if (!StringUtils.hasText(materialNo)) {
        continue;
      }
      List<BomManualItem> matchedRoots = rootsByMaterial.get(materialNo);
      if (matchedRoots == null || matchedRoots.isEmpty()) {
        continue;
      }
      for (BomManualItem root : matchedRoots) {
        String bomCode = trimToNull(root.getBomCode());
        if (!StringUtils.hasText(bomCode)) {
          continue;
        }
        List<BomManualItem> bomItems = itemsByBom.get(bomCode);
        if (bomItems == null || bomItems.isEmpty()) {
          continue;
        }
        List<BomManualItem> leaves = findLeafItems(bomItems, root.getItemCode());
        for (BomManualItem leaf : leaves) {
          if (!StringUtils.hasText(leaf.getItemCode())) {
            continue;
          }
          String leafCode = leaf.getItemCode().trim();
          String key = oaItem.getId() + "|" + bomCode + "|" + leafCode;
          if (!uniqueKeys.add(key)) {
            continue;
          }
          BomManageItem entity = new BomManageItem();
          entity.setOaNo(form.getOaNo());
          entity.setOaFormId(form.getId());
          entity.setOaFormItemId(oaItem.getId());
          entity.setMaterialNo(materialNo);
          entity.setProductName(oaItem.getProductName());
          entity.setProductSpec(oaItem.getSpec());
          entity.setProductModel(oaItem.getSunlModel());
          entity.setCustomerName(form.getCustomer());
          entity.setCopperPriceTax(form.getCopperPrice());
          entity.setZincPriceTax(form.getZincPrice());
          entity.setAluminumPriceTax(form.getAluminumPrice());
          entity.setSteelPriceTax(form.getSteelPrice());
          entity.setBomCode(bomCode);
          entity.setRootItemCode(trimToNull(root.getItemCode()));
          entity.setItemCode(leafCode);
          entity.setItemName(leaf.getItemName());
          entity.setItemSpec(leaf.getItemSpec());
          entity.setItemModel(leaf.getItemModel());
          entity.setShapeAttr(leaf.getShapeAttr());
          entity.setBomQty(leaf.getBomQty());
          entity.setMaterial(leaf.getMaterial());
          entity.setSource(leaf.getSource());
          entity.setFilterRule(FILTER_RULE);
          bomManageItemMapper.insert(entity);
          inserted += 1;
        }
      }
    }
    return inserted;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private List<BomManualItem> findLeafItems(List<BomManualItem> items, String rootItemCode) {
    if (items == null || items.isEmpty() || !StringUtils.hasText(rootItemCode)) {
      return List.of();
    }
    String rootCode = rootItemCode.trim();
    var childrenByParent = new java.util.HashMap<String, java.util.List<BomManualItem>>();
    var itemByCode = new java.util.HashMap<String, BomManualItem>();
    for (BomManualItem item : items) {
      if (!StringUtils.hasText(item.getItemCode())) {
        continue;
      }
      String itemCode = item.getItemCode().trim();
      itemByCode.put(itemCode, item);
      if (StringUtils.hasText(item.getParentCode())) {
        String parent = item.getParentCode().trim();
        childrenByParent
            .computeIfAbsent(parent, key -> new java.util.ArrayList<>())
            .add(item);
      }
    }
    if (!itemByCode.containsKey(rootCode)) {
      return List.of();
    }
    var leaves = new java.util.ArrayList<BomManualItem>();
    var stack = new java.util.ArrayDeque<String>();
    var visited = new java.util.HashSet<String>();
    stack.push(rootCode);
    while (!stack.isEmpty()) {
      String current = stack.pop();
      if (!visited.add(current)) {
        continue;
      }
      List<BomManualItem> children = childrenByParent.get(current);
      if (children == null || children.isEmpty()) {
        BomManualItem leaf = itemByCode.get(current);
        if (leaf != null) {
          leaves.add(leaf);
        }
        continue;
      }
      for (BomManualItem child : children) {
        if (StringUtils.hasText(child.getItemCode())) {
          stack.push(child.getItemCode().trim());
        }
      }
    }
    return leaves;
  }
}

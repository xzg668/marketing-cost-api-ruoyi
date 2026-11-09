package com.sanhua.marketingcost.service.jjb;

import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.*;
import org.springframework.stereotype.Component;

/** Missing descriptive units may come from the explicitly owned material master. */
@Component
public class JjbMeasurementUnits {
  private record Identity(String organization, String code) {}
  private final MaterialMasterRawMapper materials;
  public JjbMeasurementUnits(MaterialMasterRawMapper materials) { this.materials = materials; }

  JjbReadModel complete(JjbReadModel sheet, List<CostRunPartItem> parts) {
    var byId = new HashMap<Long, CostRunPartItem>();
    parts.forEach(part -> byId.put(part.getId(), part));
    var required = new LinkedHashMap<String, Identity>();
    var groups = new LinkedHashMap<String, Set<String>>();
    for (var row : sheet.rows()) {
      if (row.unit() != null || row.quantity() == null || row.partItemIds().size() != 1
          || !Set.of("PART", "HOUSEHOLD", "EXPLANATION").contains(row.kind())) continue;
      var part = byId.get(row.partItemIds().getFirst());
      if (part == null || !Objects.equals(row.code(), part.getPartCode())) continue;
      String organization = part.getMaterialOrganizationCode();
      if (organization == null || organization.isBlank()) {
        if (part.getPriceOrgCode() == null || part.getPriceOrgCode().isBlank()) continue;
        organization = MaterialOrganization.fromPriceOrgCode(part.getPriceOrgCode()).getCode();
      } else organization = MaterialOrganization.normalize(organization);
      required.put(row.key(), new Identity(organization, row.code()));
      groups.computeIfAbsent(organization, key -> new LinkedHashSet<>()).add(row.code());
    }
    var found = new HashMap<Identity, MaterialMasterRaw>();
    groups.forEach((organization, codes) -> {
      var list = new ArrayList<>(codes);
      for (int start = 0; start < list.size(); start += 256) {
        var selected = materials.selectByLatestBatchAndCodes(list.subList(start, Math.min(start + 256, list.size())), null, organization);
        if (selected == null) continue;
        for (var material : selected) {
          if (!organization.equals(material.getOrganizationCode()) || !codes.contains(material.getMaterialCode())
              || material.getUnit() == null || material.getUnit().isBlank()) continue;
          var identity = new Identity(organization, material.getMaterialCode());
          var old = found.putIfAbsent(identity, material);
          if (old != null && !old.getUnit().equals(material.getUnit()))
            throw new IllegalStateException("料品档案计量单位不唯一：" + material.getMaterialCode());
        }
      }
    });
    var rows = sheet.rows().stream().map(row -> {
      var material = found.get(required.get(row.key()));
      if (material == null) return row;
      var sources = new ArrayList<>(row.sources());
      sources.add(new JjbReadModel.Source("lp_material_master_raw", material.getId(), "计量单位取当前同组织料品档案；保存金额不变"));
      return row.toBuilder().unit(material.getUnit().trim()).sources(sources).build();
    }).toList();
    return new JjbReadModel(sheet.identity(), sheet.header(), rows, sheet.monthlyPrices(), sheet.totals(), sheet.warnings());
  }
}

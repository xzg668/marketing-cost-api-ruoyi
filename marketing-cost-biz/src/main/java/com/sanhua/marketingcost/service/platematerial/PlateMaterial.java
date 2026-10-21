package com.sanhua.marketingcost.service.platematerial;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;

/** 应用类别与钢材牌号共同标识材质，名称及下拉顺序由财务原表确认。 */
public enum PlateMaterial {
  WATER_FLUORINE_SUS304("水氟SUS304"),
  WATER_FLUORINE_316L("水氟316L"),
  FLUORINE_FLUORINE_SUS304("氟氟SUS304"),
  FLUORINE_FLUORINE_316L("氟氟316L"),
  WATER_WATER_SUS304("水水SUS304"),
  WATER_WATER_316L("水水316L");

  private final String value;

  PlateMaterial(String value) { this.value = value; }

  @JsonValue
  public String value() { return value; }

  public static List<String> options() {
    return Arrays.stream(values()).map(PlateMaterial::value).toList();
  }

  @JsonCreator
  public static PlateMaterial parse(String value) {
    return Arrays.stream(values()).filter(material -> material.value.equals(value))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("板片材质必须为六种合法选项之一"));
  }
}

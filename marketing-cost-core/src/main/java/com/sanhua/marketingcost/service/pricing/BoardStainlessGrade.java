package com.sanhua.marketingcost.service.pricing;

import java.util.Locale;
import java.util.regex.Pattern;

/** 板换材质由规格和真实因素引用交叉确认，不能按料号或缺省304判断。 */
public enum BoardStainlessGrade {
  SUS304,
  SUS316;

  private static final Pattern SPEC_304 =
      Pattern.compile("SUS304(?![0-9])|S30400(?![0-9])|YL\\(304\\)");
  private static final Pattern SPEC_316 =
      Pattern.compile("SUS316L?(?![0-9])|S31603(?![0-9])|(?<![A-Z0-9])316L(?![0-9])|YL\\(316L?\\)");

  public static BoardStainlessGrade fromSpecification(String specification) {
    String value = normalize(specification).replace('（', '(').replace('）', ')');
    boolean is304 = SPEC_304.matcher(value).find();
    boolean is316 = SPEC_316.matcher(value).find();
    if (is304 && is316) throw new IllegalArgumentException("板换规格同时包含304和316材质：" + specification);
    return is304 ? SUS304 : is316 ? SUS316 : null;
  }

  public static BoardStainlessGrade fromFactorKey(String canonicalKey) {
    String value = normalize(canonicalKey).replace(" ", "");
    int separator = value.indexOf('|');
    String name = separator < 0 ? value : value.substring(separator + 1);
    if (name.matches("304(?:/.*)?")) return SUS304;
    if (name.matches("316L?(?:/.*)?")) return SUS316;
    return null;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toUpperCase(Locale.ROOT).trim();
  }
}

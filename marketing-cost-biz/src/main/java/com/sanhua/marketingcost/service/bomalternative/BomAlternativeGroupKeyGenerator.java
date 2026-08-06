package com.sanhua.marketingcost.service.bomalternative;

/** 替代组稳定键与父路径指纹生成契约。 */
public interface BomAlternativeGroupKeyGenerator {

  /** 根据稳定业务位置生成64位小写SHA-256。 */
  String generate(BomAlternativeGroupIdentity identity);

  /** 对规范化后的直接父件路径生成64位小写SHA-256指纹。 */
  String parentPathFingerprint(String parentPath);
}

package com.sanhua.marketingcost.security;

/**
 * 外部技术协作门户允许处理的业务模块。
 *
 * <p>认证与页面入口共用这一组稳定代码；BOM、价格、CMS 的业务实现仍各自独立。
 */
public enum CollaborationPortalModule {
  BOM,
  PRICE,
  CMS
}

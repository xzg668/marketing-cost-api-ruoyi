package com.sanhua.marketingcost.integration.oa.oauth;

/** OA 身份不继承系统账号的管理员/报价员权限。未在技术目录中的查看人无需创建系统账号。 */
public record OaOAuthPrincipal(String employeeNo, String name, Long userId, long formId) {}

package com.sanhua.marketingcost.dto;

/** 已冻结的部品拆分依据；componentsJson 为空数组代表当时明确不拆分。 */
public record RollupDisplaySnapshot(Long partItemId, String componentsJson) {}

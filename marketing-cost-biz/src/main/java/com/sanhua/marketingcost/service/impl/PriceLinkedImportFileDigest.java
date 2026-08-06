package com.sanhua.marketingcost.service.impl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class PriceLinkedImportFileDigest {

  private PriceLinkedImportFileDigest() {
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前运行环境不支持SHA-256", exception);
    }
  }
}

package com.sanhua.marketingcost.service.ingest;

public class QuotePdfToken {
  private String text;
  private int pageIndex;
  private float x;
  private float y;
  private float width;
  private float height;

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public int getPageIndex() {
    return pageIndex;
  }

  public void setPageIndex(int pageIndex) {
    this.pageIndex = pageIndex;
  }

  public float getX() {
    return x;
  }

  public void setX(float x) {
    this.x = x;
  }

  public float getY() {
    return y;
  }

  public void setY(float y) {
    this.y = y;
  }

  public float getWidth() {
    return width;
  }

  public void setWidth(float width) {
    this.width = width;
  }

  public float getHeight() {
    return height;
  }

  public void setHeight(float height) {
    this.height = height;
  }
}

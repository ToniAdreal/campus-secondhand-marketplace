package com.toni.marketplace.common;

/** Thrown when a category name (case-insensitive) or slug is already taken. */
public class DuplicateCategoryException extends RuntimeException {

  public DuplicateCategoryException(String message) {
    super(message);
  }
}

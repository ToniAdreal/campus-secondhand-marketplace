package com.toni.marketplace.common;

/**
 * Thrown when an ADMIN tries to delete a category that still has listings
 * pointing at it — deleting it would orphan those listings' category, and
 * the item.category FK is deliberately ON DELETE SET NULL, so this product
 * rule is enforced in the service layer instead (backlog #70).
 */
public class CategoryInUseException extends RuntimeException {

  public CategoryInUseException(String message) {
    super(message);
  }
}

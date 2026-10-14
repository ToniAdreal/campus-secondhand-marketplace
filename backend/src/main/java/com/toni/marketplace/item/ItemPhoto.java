package com.toni.marketplace.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * One photo in a listing's gallery. The row stores only the public URL path
 * ({@code /uploads/<uuid>.<ext>}) — the file itself lives on disk under
 * {@code app.uploads.dir} and is served by the {@code /uploads/**} static
 * mapping (see {@link ImageStorageService}). {@code position} is the display
 * order; the lowest position is the listing's primary photo.
 */
@Entity
@Table(name = "item_photo")
public class ItemPhoto {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "item_id", nullable = false)
  private Item item;

  @Column(name = "url", nullable = false, length = 1024)
  private String url;

  /** Display order within the listing's gallery; lowest is primary. */
  @Column(name = "position", nullable = false)
  private int position;

  /**
   * Optimistic locking (backlog #162, Flyway V32): a gallery reorder
   * rewrites positions through the merge path, so two concurrent
   * reorders of the same gallery fail fast on the losing flush instead
   * of silently overwriting each other — the failure surfaces as
   * {@code ObjectOptimisticLockingFailureException}, which
   * {@code GlobalExceptionHandler} maps to 409 (backlog #147).
   */
  @Version
  private Long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ItemPhoto() {}

  public ItemPhoto(Item item, String url, int position) {
    this.item = item;
    this.url = url;
    this.position = position;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Item getItem() { return item; }
  public String getUrl() { return url; }
  public int getPosition() { return position; }
  public void setPosition(int position) { this.position = position; }
  public Long getVersion() { return version; }
  public Instant getCreatedAt() { return createdAt; }
}

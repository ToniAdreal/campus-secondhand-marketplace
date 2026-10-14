package com.toni.marketplace.moderation;

/**
 * Why a listing was reported (backlog #160). The reporter picks exactly
 * one; the set is deliberately small and marketplace-shaped — free text
 * lives in the optional note, never here, so the moderation queue stays
 * filterable by category of abuse.
 */
public enum ReportReason {
  SPAM,
  SCAM,
  PROHIBITED,
  OTHER
}

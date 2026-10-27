package com.toni.marketplace.auth;

import jakarta.validation.constraints.Size;

/**
 * Renames one enrolled passkey (backlog #241): the display-only
 * friendly name for the row. Deliberately different from the
 * category-rename shape — {@code name} is optional and may be
 * blank: a blank name clears the stored label back to NULL, and the
 * settings surface falls back to the enrolment-date label, so a
 * renamed-to-blank row is never rendered blank. At most 100
 * characters, matching the Flyway V71 column. Renaming changes no
 * credential material and writes no audit row (unlike enrolment /
 * removal, which are security transitions); it is step-up gated like
 * every other passkey mutation, so a merely-authenticated token
 * cannot relabel a holder's keys into a deletion mistake.
 */
public record PasskeyRenameRequest(
    @Size(max = 100, message = "name must be at most 100 characters")
    String name) {}

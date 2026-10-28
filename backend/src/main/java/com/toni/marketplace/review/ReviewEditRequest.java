package com.toni.marketplace.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/reviews/{id} (backlog #254). A review edit is a
 * full replacement of the rating and the text — the same bounds as
 * {@link ReviewCreateRequest}, so an edit can never smuggle in a
 * rating or text the create path would reject. Blank text is
 * normalised to null by the service, exactly like create, so a
 * star-only edit is a complete edit.
 */
public record ReviewEditRequest(
    @NotNull @Min(1) @Max(5) Integer rating,
    @Size(max = 500) String body) {
}

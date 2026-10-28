package com.toni.marketplace.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.order.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * The edit-window boundary (backlog #254), pinned exactly with a
 * fixed clock — the MockMvc suite can only backdate to 14 vs 16
 * minutes against wall time, so the inclusive boundary
 * ({@code createdAt + window} itself is accepted, one second later
 * is 422) lives here, where the clock is not racing the test.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceEditWindowTest {

  private static final Instant CREATED = Instant.parse("2026-10-28T08:00:00Z");

  @Mock private SellerReviewRepository reviews;
  @Mock private OrderRepository orders;
  @Mock private UserRepository users;

  private ReviewService serviceAt(Instant now) {
    return new ReviewService(reviews, orders, users, new ReviewProperties(),
        Clock.fixed(now, ZoneId.of("UTC")));
  }

  private SellerReview reviewWithResponse(String sellerResponse) {
    SellerReview review = new SellerReview(31L, 5L, 3L, 4, "Good, minor scuff");
    ReflectionTestUtils.setField(review, "id", 21L);
    ReflectionTestUtils.setField(review, "createdAt", CREATED);
    if (sellerResponse != null) {
      review.setSellerResponse(sellerResponse);
    }
    when(reviews.findById(21L)).thenReturn(Optional.of(review));
    // Lenient: the 422 tests reject before save / username lookup, so
    // these stubs are unused there — strict stubs would fail them.
    lenient().when(reviews.save(review)).thenReturn(review);
    lenient().when(users.findById(5L)).thenReturn(Optional.of(
        new User("buyer", "buyer@example.com", "hashed")));
    return review;
  }

  @Test
  void editExactlyAtTheWindowBoundaryIsAccepted() {
    SellerReview review = reviewWithResponse(null);

    var edited = serviceAt(CREATED.plusSeconds(15 * 60))
        .editReview(5L, 21L, 5, "On reflection, perfect");

    assertThat(edited.rating()).isEqualTo(5);
    assertThat(edited.body()).isEqualTo("On reflection, perfect");
    assertThat(review.getRating()).isEqualTo(5);
  }

  @Test
  void editOneSecondPastTheWindowIs422() {
    reviewWithResponse(null);

    assertThatThrownBy(() ->
        serviceAt(CREATED.plusSeconds(15 * 60 + 1))
            .editReview(5L, 21L, 5, "Too late"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void sellerResponseBlocksEvenInsideTheWindow() {
    reviewWithResponse("Thanks for trading!");

    assertThatThrownBy(() ->
        serviceAt(CREATED.plusSeconds(60))
            .editReview(5L, 21L, 1, "Rewritten after the reply"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }
}

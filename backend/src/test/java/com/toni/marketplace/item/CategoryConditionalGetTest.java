package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.EnumSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * ETag / conditional GET for the category list (backlog #145): {@code GET
 * /api/categories} serves a strong ETag derived from the (already
 * #87-cached) DTO payload, and a request whose {@code If-None-Match}
 * carries that validator answers 304 with an empty body instead of
 * re-downloading the same small taxonomy on every SPA load. Any ADMIN
 * category write changes the payload, so the post-rename GET must carry
 * a new ETag and answer 200.
 *
 * <p>The category cache is process-wide state shared with the other test
 * classes in this context, so — like {@code CategoryCacheTest} — every
 * test here clears it before and after itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryConditionalGetTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private CategoryRepository categoryRepo;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private CacheManager cacheManager;

  private String userToken;
  private String adminToken;

  @BeforeEach
  void setUp() {
    clearCache();
    User user = users.save(new User("etag-reader", "etag-reader@example.com",
        passwords.encode("some-password")));
    userToken = jwt.createTokenPair(user).accessToken();

    User admin = new User("etag-admin", "etag-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  @AfterEach
  void tearDown() {
    // The surrounding test transaction rolls the rows back, but cache
    // entries would survive into other test classes — never leak them.
    clearCache();
  }

  private void clearCache() {
    var cache = cacheManager.getCache(CategoryCacheConfig.CATEGORIES_CACHE);
    assertThat(cache).isNotNull();
    cache.clear();
  }

  private MvcResult firstGet() throws Exception {
    return mockMvc.perform(get("/api/categories")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andReturn();
  }

  @Test
  void firstGetReturns200WithStrongETag() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/categories")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(header().exists(HttpHeaders.ETAG))
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(6))
        .andReturn();

    String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
    assertThat(etag)
        .as("a strong validator is quoted and never weak (W/)")
        .startsWith("\"")
        .endsWith("\"")
        .doesNotStartWith("W/");
    // The unquoted value is the hex SHA-256 of the payload.
    assertThat(etag.substring(1, etag.length() - 1)).hasSize(64);
  }

  @Test
  void matchingIfNoneMatchReturns304WithEmptyBody() throws Exception {
    String etag = firstGet().getResponse().getHeader(HttpHeaders.ETAG);
    assertThat(etag).isNotBlank();

    mockMvc.perform(get("/api/categories")
            .header("Authorization", "Bearer " + userToken)
            .header(HttpHeaders.IF_NONE_MATCH, etag))
        .andExpect(status().isNotModified())
        .andExpect(header().string(HttpHeaders.ETAG, etag))
        .andExpect(content().string(""));
  }

  @Test
  void nonMatchingIfNoneMatchReturns200WithBody() throws Exception {
    mockMvc.perform(get("/api/categories")
            .header("Authorization", "Bearer " + userToken)
            .header(HttpHeaders.IF_NONE_MATCH,
                "\"0000000000000000000000000000000000000000000000000000000000000000\""))
        .andExpect(status().isOk())
        .andExpect(header().exists(HttpHeaders.ETAG))
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(6));
  }

  @Test
  void renameChangesTheETagAndStaleValidatorGets200() throws Exception {
    Category temp = categoryRepo.save(new Category("Etag Temp", "etag-temp"));
    String etagBefore = firstGet().getResponse().getHeader(HttpHeaders.ETAG);
    assertThat(etagBefore).isNotBlank();

    mockMvc.perform(patch("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Etag Temp Renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Etag Temp Renamed"));

    MvcResult after = mockMvc.perform(get("/api/categories")
            .header("Authorization", "Bearer " + userToken)
            .header(HttpHeaders.IF_NONE_MATCH, etagBefore))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.slug == 'etag-temp')].name")
            .value("Etag Temp Renamed"))
        .andReturn();

    String etagAfter = after.getResponse().getHeader(HttpHeaders.ETAG);
    assertThat(etagAfter).isNotBlank().isNotEqualTo(etagBefore);

    // And the new validator is itself cacheable: echoing it back is a 304.
    mockMvc.perform(get("/api/categories")
            .header("Authorization", "Bearer " + userToken)
            .header(HttpHeaders.IF_NONE_MATCH, etagAfter))
        .andExpect(status().isNotModified())
        .andExpect(content().string(""));
  }
}

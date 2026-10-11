package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #130: the refresh cookie's {@code Secure} attribute follows
 * {@code app.auth.cookie-secure}, and the logout clear-cookie carries the
 * SAME attributes as the set cookie (they must agree or the browser will
 * not overwrite the stored cookie). The default (off) class pins the
 * local-dev behaviour; the {@code Secure}-on class below pins the HTTPS
 * behaviour. Cookie headers are compared as raw {@code Set-Cookie}
 * strings because MockMvc's parsed {@code Cookie} view drops the
 * {@code Secure} attribute.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RefreshCookieSecureTest {

  @Autowired
  private MockMvc mockMvc;

  private static final String REGISTER =
      "{\"username\":\"carol\",\"email\":\"carol@example.com\",\"password\":\"s3cret-pass1\"}";

  @Test
  void setCookieOmitsSecureByDefault() throws Exception {
    MvcResult registered = register();

    String setCookie = registered.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("refresh_token=").contains("HttpOnly");
    assertThat(setCookie).doesNotContain("Secure");
  }

  @Test
  void clearCookieMatchesSetCookieAttributesByDefault() throws Exception {
    MvcResult registered = register();
    String setCookie = registered.getResponse().getHeader("Set-Cookie");
    String access = com.jayway.jsonpath.JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.accessToken");

    MvcResult logout = mockMvc.perform(post("/api/auth/logout")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();

    String clearCookie = logout.getResponse().getHeader("Set-Cookie");
    assertThat(clearCookie).contains("refresh_token=").contains("Max-Age=0");
    // Same Secure-ness as the set cookie: both off by default.
    assertThat(clearCookie.contains("Secure")).isEqualTo(setCookie.contains("Secure"));
  }

  private MvcResult register() throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isOk())
        .andReturn();
  }
}

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.auth.cookie-secure=true")
class RefreshCookieSecureEnabledTest {

  @Autowired
  private MockMvc mockMvc;

  private static final String REGISTER =
      "{\"username\":\"dave\",\"email\":\"dave@example.com\",\"password\":\"s3cret-pass1\"}";

  @Test
  void setCookieCarriesSecureWhenPropertyOn() throws Exception {
    MvcResult registered = register();

    String setCookie = registered.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("refresh_token=").contains("HttpOnly").contains("Secure");
  }

  @Test
  void clearCookieMatchesSetCookieAttributesWhenSecureOn() throws Exception {
    MvcResult registered = register();
    String setCookie = registered.getResponse().getHeader("Set-Cookie");
    String access = com.jayway.jsonpath.JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.accessToken");

    MvcResult logout = mockMvc.perform(post("/api/auth/logout")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();

    String clearCookie = logout.getResponse().getHeader("Set-Cookie");
    assertThat(clearCookie).contains("refresh_token=").contains("Max-Age=0").contains("Secure");
    assertThat(clearCookie.contains("Secure")).isEqualTo(setCookie.contains("Secure"));
  }

  private MvcResult register() throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isOk())
        .andReturn();
  }
}

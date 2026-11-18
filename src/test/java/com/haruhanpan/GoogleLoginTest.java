package com.haruhanpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.haruhanpan.google.GoogleLoginService;
import com.haruhanpan.google.GoogleLoginService.GoogleLoginException;
import com.haruhanpan.google.GoogleLoginService.GoogleProfile;
import com.haruhanpan.google.GoogleLoginSuccessHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google 로그인. 실제 Google 서버 없이 "Google 이 이 계정이라고 알려줬다"는 상황을 만들어 검사한다.
 * 클라이언트 ID/비밀은 가짜 값 (Google 로 보내는 주소가 제대로 만들어지는지만 확인).
 */
@SpringBootTest(properties = {
		"spring.security.oauth2.client.registration.google.client-id=test-client-id",
		"spring.security.oauth2.client.registration.google.client-secret=test-secret",
		"haru.google-allowed-domain=mycompany.com",
		"haru.google-open-signup=false" })  // 공개 가입(승인제)은 GoogleApprovalTest 에서
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GoogleLoginTest {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		TestSupport.register(registry, "google" + System.nanoTime());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	GoogleLoginService google;

	@Autowired
	UserDetailsService users;

	@Autowired
	TokenBasedRememberMeServices rememberMe;

	private static GoogleProfile profile(String sub, String email, String hd) {
		return new GoogleProfile(sub, email, true, hd, "홍길동");
	}

	private String newInvite() throws Exception {
		String team = json.readTree(mvc.perform(post("/api/spaces").with(user("haru")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"팀\"}"))
			.andReturn().getResponse().getContentAsString()).get("id").asString();
		return json.readTree(mvc.perform(post("/api/spaces/" + team + "/invites").with(user("haru")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"editor\"}"))
			.andReturn().getResponse().getContentAsString()).get("token").asString();
	}

	private JsonNode all(String login) throws Exception {
		return json.readTree(mvc.perform(get("/api/all").with(user(login))).andReturn().getResponse().getContentAsString());
	}

	@Test
	void 설정돼_있으면_로그인화면에_버튼_Google로_이동() throws Exception {
		mvc.perform(get("/api/session")).andExpect(jsonPath("$.google").value(true));
		mvc.perform(get("/auth/google")).andExpect(redirectedUrl("/oauth2/authorization/google"));
		String location = mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isFound())
			.andReturn().getResponse().getHeader("Location");
		assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth")
			.contains("client_id=test-client-id")
			.contains("scope=openid%20profile%20email")
			.contains("redirect_uri=http://localhost/login/oauth2/code/google");
	}

	@Test
	void 초대받지_않은_Google_계정은_거절() {
		assertThatThrownBy(() -> google.resolve(profile("sub-1", "stranger@gmail.com", null), null))
			.isInstanceOf(GoogleLoginException.class).hasMessage("google_not_invited");
		// 이메일 인증 안 된 계정도 거절
		assertThatThrownBy(() -> google.resolve(new GoogleProfile("sub-2", "a@mycompany.com", false, "mycompany.com", "a"), null))
			.hasMessage("google_unverified");
	}

	@Test
	void 초대_링크로_Google_가입하면_팀에_참여하고_다음부터_바로_로그인() throws Exception {
		String token = newInvite();
		String login = google.resolve(profile("sub-hong", "gildong.hong@gmail.com", null), new GoogleLoginService.Invite(token));
		assertThat(login).isEqualTo("gildong.hong");

		JsonNode data = all(login);
		assertThat(data.get("me").get("name").asString()).isEqualTo("홍길동");
		assertThat(data.get("me").get("passwordSet").asBoolean()).isFalse();
		assertThat(data.get("spaces").size()).isEqualTo(2);
		assertThat(data.get("google").get("email").asString()).isEqualTo("gildong.hong@gmail.com");

		assertThat(google.resolve(profile("sub-hong", "gildong.hong@gmail.com", null), null)).isEqualTo(login);
		// 같은 초대 링크는 다시 못 씀
		assertThatThrownBy(() -> google.resolve(profile("sub-other", "x@gmail.com", null), new GoogleLoginService.Invite(token)))
			.hasMessage("invite_invalid");
	}

	@Test
	void 아이디가_겹치면_숫자를_붙인다() throws Exception {
		String a = google.resolve(profile("s1", "haru@mycompany.com", "mycompany.com"), null);
		assertThat(a).isEqualTo("haru2"); // 관리자 아이디 haru 와 겹침
	}

	@Test
	void 기존_계정에_연결하면_Google로_로그인() throws Exception {
		assertThat(google.resolve(profile("sub-admin", "admin@gmail.com", null), new GoogleLoginService.Link("u-owner")))
			.isEqualTo("haru");
		assertThat(google.resolve(profile("sub-admin", "admin@gmail.com", null), null)).isEqualTo("haru");

		// 이미 다른 사람에 연결된 Google 계정을 내 계정에 연결하려 하면 거절
		String token = newInvite();
		String other = google.resolve(profile("sub-x", "x@gmail.com", null), new GoogleLoginService.Invite(token));
		String otherId = all(other).get("me").get("id").asString();
		assertThatThrownBy(() -> google.resolve(profile("sub-admin", "admin@gmail.com", null), new GoogleLoginService.Link(otherId)))
			.hasMessage("google_in_use");

		// 연결 해제 (관리자는 비밀번호가 있으므로 가능)
		mvc.perform(delete("/api/me/google").with(user("haru")).with(csrf())).andExpect(status().isOk());
		assertThatThrownBy(() -> google.resolve(profile("sub-admin", "admin@gmail.com", null), null))
			.hasMessage("google_not_invited");
	}

	@Test
	void 허용_도메인은_초대_없이_가입_다른_도메인은_거절() {
		assertThat(google.resolve(profile("s-a", "kim@mycompany.com", "mycompany.com"), null)).isEqualTo("kim");
		assertThatThrownBy(() -> google.resolve(profile("s-b", "kim@other.com", "other.com"), null))
			.hasMessage("google_not_invited");
		// 개인 gmail 은 hd 가 없음 → 이메일 주소만 회사 도메인이어도 거절 (Workspace 계정만 허용)
		assertThatThrownBy(() -> google.resolve(profile("s-c", "fake@mycompany.com", null), null))
			.hasMessage("google_not_invited");
	}

	@Test
	void Google_가입자는_비밀번호_없이_처음_정할_수_있고_그전엔_연결해제_불가() throws Exception {
		String login = google.resolve(profile("s-p", "park@mycompany.com", "mycompany.com"), null);
		mvc.perform(delete("/api/me/google").with(user(login)).with(csrf())).andExpect(status().isBadRequest());
		mvc.perform(put("/api/me/password").with(user(login)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"next\":\"park-password-123\"}"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/login").with(csrf()).param("username", login).param("password", "park-password-123"))
			.andExpect(status().isOk());
		mvc.perform(delete("/api/me/google").with(user(login)).with(csrf())).andExpect(status().isOk());
	}

	@Test
	void 인증_성공_처리기는_하루한판_사용자로_로그인시키고_로그인유지_쿠키를_준다() throws Exception {
		google.resolve(profile("sub-admin", "admin@gmail.com", null), new GoogleLoginService.Link("u-owner"));
		var handler = new GoogleLoginSuccessHandler(google, users, rememberMe);

		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
		MockHttpServletResponse res = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(req, res, googleAuth("sub-admin", "admin@gmail.com"));

		assertThat(res.getRedirectedUrl()).isEqualTo("/");
		assertThat(res.getCookie("haru_remember")).isNotNull();
		SecurityContext ctx = (SecurityContext) req.getSession().getAttribute("SPRING_SECURITY_CONTEXT");
		assertThat(ctx.getAuthentication().getName()).isEqualTo("haru");

		// 초대받지 않은 계정: 로그인 화면으로 돌려보내고 로그인 상태가 남지 않음
		MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
		MockHttpServletResponse res2 = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(req2, res2, googleAuth("sub-unknown", "who@gmail.com"));
		assertThat(res2.getRedirectedUrl()).isEqualTo("/login.html?error=google_not_invited");
		SecurityContext ctx2 = (SecurityContext) req2.getSession().getAttribute("SPRING_SECURITY_CONTEXT");
		assertThat(ctx2 == null || ctx2.getAuthentication() == null).isTrue();
	}

	/**
	 * 실제 흐름에서 Spring Security 는 성공 처리기보다 먼저 로그인 유지 서비스를 Google 인증 정보로 부른다.
	 * 예전에는 여기서 "no user" 로 실패해 Google 로그인이 항상 실패했다 (2026-11-06 수정).
	 */
	@Test
	void 로그인유지_서비스는_Google_인증정보를_받아도_실패하지_않는다() {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
		MockHttpServletResponse res = new MockHttpServletResponse();
		rememberMe.loginSuccess(req, res, googleAuth("sub-anyone", "anyone@gmail.com"));
		assertThat(res.getCookie("haru_remember")).isNull(); // 쿠키는 성공 처리기가 하루한판 사용자로 바꾼 뒤에 만든다
	}

	@Test
	void 초대_Google_가입_시작은_유효한_링크만() throws Exception {
		mvc.perform(post("/api/invites/google").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"nope\"}"))
			.andExpect(status().isBadRequest());
		String token = newInvite();
		mvc.perform(post("/api/invites/google").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.url").value("/oauth2/authorization/google"));
	}

	private static OAuth2AuthenticationToken googleAuth(String sub, String email) {
		OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60),
				Map.of("sub", sub, "email", email, "email_verified", true, "name", "홍길동", "iss", "https://accounts.google.com"));
		var user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
		return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
	}
}

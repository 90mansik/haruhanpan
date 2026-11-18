package com.haruhanpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.haruhanpan.auth.LoginAttemptService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthTest {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		TestSupport.register(registry, "auth");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	LoginAttemptService attempts;

	@BeforeEach
	void resetAttempts() {
		attempts.reset();
	}

	private MvcResult login(String password, String ip) throws Exception {
		return mvc.perform(post("/api/login").with(csrf())
				.with(r -> { r.setRemoteAddr(ip); return r; })
				.param("username", "haru").param("password", password))
			.andReturn();
	}

	@Test
	void 로그인_없이_화면은_로그인페이지로_API는_401() throws Exception {
		mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrl("/login.html"));
		mvc.perform(get("/api/all")).andExpect(status().isUnauthorized());
		mvc.perform(get("/login.html")).andExpect(status().isOk());
		mvc.perform(get("/api/session")).andExpect(jsonPath("$.loggedIn").value(false))
			.andExpect(jsonPath("$.google").value(false)); // Google 클라이언트 ID 가 없으면 꺼짐
		mvc.perform(get("/board")).andExpect(status().isFound()).andExpect(redirectedUrl("/login.html"));
		for (String pwa : new String[] {"/manifest.json", "/sw.js", "/offline.html", "/icons/icon-192.png", "/icons/apple-touch-icon.png"}) {
			mvc.perform(get(pwa)).andExpect(status().isOk()); // 설치형 앱 파일은 로그인 없이
		}
		mvc.perform(get("/ledger")).andExpect(status().isFound()).andExpect(redirectedUrl("/login.html"));
		mvc.perform(get("/").with(user("haru"))).andExpect(forwardedUrl("/home.html"));
		mvc.perform(get("/board").with(user("haru"))).andExpect(forwardedUrl("/index.html"));
		mvc.perform(get("/api/health")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.db").value("ok"));
		mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isFound())
			.andExpect(redirectedUrl("/login.html"));
	}

	@Test
	void 틀린_비밀번호는_401_같은_IP_6번째는_429() throws Exception {
		for (int i = 0; i < 5; i++) {
			assertThat(login("wrong-password", "10.0.0.1").getResponse().getStatus()).isEqualTo(401);
		}
		assertThat(login("wrong-password", "10.0.0.1").getResponse().getStatus()).isEqualTo(429);
		// 맞는 비밀번호여도 막힌 동안은 429
		assertThat(login(TestSupport.PASSWORD, "10.0.0.1").getResponse().getStatus()).isEqualTo(429);
		// 다른 IP 는 영향 없음
		assertThat(login(TestSupport.PASSWORD, "10.0.0.2").getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void 맞는_비밀번호면_로그인유지_쿠키로_API_사용() throws Exception {
		MvcResult result = login(TestSupport.PASSWORD, "10.0.0.3");
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		Cookie remember = result.getResponse().getCookie("haru_remember");
		assertThat(remember).isNotNull();
		assertThat(remember.isHttpOnly()).isTrue();

		// 세션 없이 로그인 유지 쿠키만으로 접근 (서버 재시작 상황)
		mvc.perform(get("/api/all").cookie(remember)).andExpect(status().isOk());
	}

	@Test
	void 위조된_로그인유지_쿠키는_401() throws Exception {
		mvc.perform(get("/api/all").cookie(new Cookie("haru_remember", "aGFydTo5OTk5OTk5OTk5OTk5OlNIQTI1NjpmYWtl")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void CSRF_토큰_없는_변경요청은_403() throws Exception {
		mvc.perform(put("/api/todos/t1").with(user("haru"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void 보안_헤더() throws Exception {
		mvc.perform(get("/login.html"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Referrer-Policy", "same-origin"))
			.andExpect(header().exists("Content-Security-Policy"));
	}
}

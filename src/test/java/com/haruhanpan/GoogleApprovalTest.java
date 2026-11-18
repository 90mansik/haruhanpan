package com.haruhanpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.haruhanpan.account.AccountService;
import com.haruhanpan.google.GoogleLoginService;
import com.haruhanpan.google.GoogleLoginService.GoogleProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 누구나 Google 로 가입 신청 → 사이트 관리자 승인 후 사용 */
@SpringBootTest(properties = {
		"spring.security.oauth2.client.registration.google.client-id=test-client-id",
		"spring.security.oauth2.client.registration.google.client-secret=test-secret",
		"haru.google-open-signup=true" })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GoogleApprovalTest {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		TestSupport.register(registry, "approval" + System.nanoTime());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	GoogleLoginService google;

	@Autowired
	AccountService accounts;

	@Autowired
	JdbcClient jdbc;

	private String signUp(String sub, String email) {
		return google.resolve(new GoogleProfile(sub, email, true, null, "신청자"), null);
	}

	private ResultActions as(String login, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
			String body) throws Exception {
		req.with(user(login)).with(csrf());
		if (body != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(req);
	}

	private JsonNode body(ResultActions r) throws Exception {
		return json.readTree(r.andReturn().getResponse().getContentAsString());
	}

	private String pendingId() throws Exception {
		return body(as("haru", get("/api/all"), null)).get("pendingUsers").get(0).get("id").asString();
	}

	private String newTeamInvite() throws Exception {
		String team = body(as("haru", post("/api/spaces"), "{\"name\":\"팀\"}")).get("id").asString();
		return body(as("haru", post("/api/spaces/" + team + "/invites"), "{\"role\":\"editor\"}")).get("token").asString();
	}

	@Test
	void 로그인화면에_가입_안내() throws Exception {
		mvc.perform(get("/api/session")).andExpect(jsonPath("$.googleSignup").value(true));
	}

	@Test
	void 가입_신청자는_승인_전에는_데이터를_못본다() throws Exception {
		String login = signUp("s-new", "newbie@gmail.com");
		as(login, get("/api/all"), null).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("approval_pending"));
		as(login, post("/api/spaces"), "{\"name\":\"몰래 만든 팀\"}").andExpect(status().isForbidden());

		JsonNode pending = body(as("haru", get("/api/all"), null)).get("pendingUsers");
		assertThat(pending.size()).isEqualTo(1);
		assertThat(pending.get(0).get("email").asString()).isEqualTo("newbie@gmail.com");
	}

	@Test
	void 관리자가_승인하면서_팀에_추가() throws Exception {
		String team = body(as("haru", post("/api/spaces"), "{\"name\":\"DBA팀\"}")).get("id").asString();
		String login = signUp("s-new", "newbie@gmail.com");
		String uid = pendingId();

		as("haru", post("/api/admin/users/" + uid + "/approve"), "{\"spaceId\":\"" + team + "\",\"role\":\"viewer\"}")
			.andExpect(status().isOk());

		JsonNode data = body(as(login, get("/api/all"), null).andExpect(status().isOk()));
		assertThat(data.get("spaces").size()).isEqualTo(2);
		assertThat(data.get("spaces").get(1).get("role").asString()).isEqualTo("viewer");
		assertThat(body(as("haru", get("/api/all"), null)).get("pendingUsers").size()).isZero();
	}

	@Test
	void 거절하면_계정이_지워지고_다시_신청하면_새로_대기() throws Exception {
		String login = signUp("s-new", "newbie@gmail.com");
		as("haru", delete("/api/admin/users/" + pendingId()), null).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE login = :l").param("l", login).query(Integer.class).single())
			.isZero();
		assertThat(signUp("s-new", "newbie@gmail.com")).isEqualTo(login); // 같은 Google 계정으로 다시 신청 가능
		// 승인된 사용자는 거절(삭제)할 수 없다
		as("haru", delete("/api/admin/users/u-owner"), null).andExpect(status().isBadRequest());
	}

	@Test
	void 사이트_관리자만_승인_거절() throws Exception {
		String invite = newTeamInvite();
		mvc.perform(post("/api/invites/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + invite + "\",\"login\":\"kim\",\"name\":\"김\",\"password\":\"" + TestSupport.PASSWORD + "\"}"))
			.andExpect(status().isOk());
		signUp("s-new", "newbie@gmail.com");
		String uid = pendingId();
		as("kim", post("/api/admin/users/" + uid + "/approve"), "{}").andExpect(status().isForbidden());
		as("kim", delete("/api/admin/users/" + uid), null).andExpect(status().isForbidden());
		assertThat(body(as("kim", get("/api/all"), null)).has("pendingUsers")).isFalse();
	}

	@Test
	void 승인_대기자가_초대_링크를_받으면_바로_승인() throws Exception {
		String login = signUp("s-new", "newbie@gmail.com");
		String invite = newTeamInvite();
		as(login, post("/api/invites/accept"), "{\"token\":\"" + invite + "\"}").andExpect(status().isOk());
		JsonNode data = body(as(login, get("/api/all"), null).andExpect(status().isOk()));
		assertThat(data.get("spaces").size()).isEqualTo(2);
	}

	@Test
	void 한달_지난_신청은_자동_정리() throws Exception {
		String login = signUp("s-old", "old@gmail.com");
		signUp("s-recent", "recent@gmail.com");
		jdbc.sql("UPDATE users SET created_at = '2020-01-01T00:00:00Z' WHERE login = :l").param("l", login).update();
		assertThat(accounts.purgeStalePending()).isEqualTo(1);
		JsonNode pending = body(as("haru", get("/api/all"), null)).get("pendingUsers");
		assertThat(pending.size()).isEqualTo(1);
		assertThat(pending.get(0).get("email").asString()).isEqualTo("recent@gmail.com");
	}
}

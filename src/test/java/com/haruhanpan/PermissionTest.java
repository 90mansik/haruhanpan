package com.haruhanpan;

import static com.haruhanpan.DataApiTest.find;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.haruhanpan.auth.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 사이트 권한: 하루가계부 사용 권한, 관리자 권한 주고 빼기 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PermissionTest {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		TestSupport.register(registry, "perm" + System.nanoTime());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	LoginAttemptService attempts;

	String kim;

	@BeforeEach
	void signupKim() throws Exception {
		attempts.reset();
		String team = body(as("haru", post("/api/spaces"), "{\"name\":\"DBA팀\"}")).get("id").asString();
		String token = body(as("haru", post("/api/spaces/" + team + "/invites"), "{\"role\":\"editor\"}")
			.andExpect(status().isOk())).get("token").asString();
		mvc.perform(post("/api/invites/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\",\"login\":\"kim\",\"name\":\"김철수\",\"password\":\""
					+ TestSupport.PASSWORD + "\"}"))
			.andExpect(status().isOk());
		kim = me("kim").get("id").asString();
	}

	private ResultActions as(String login, MockHttpServletRequestBuilder req, String content) throws Exception {
		req.with(user(login)).with(csrf());
		if (content != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		return mvc.perform(req);
	}

	private JsonNode body(ResultActions r) throws Exception {
		return json.readTree(r.andReturn().getResponse().getContentAsString());
	}

	private JsonNode me(String login) throws Exception {
		return body(mvc.perform(get("/api/all").with(user(login))).andExpect(status().isOk())).get("me");
	}

	private ResultActions grant(String by, String uid, String content) throws Exception {
		return as(by, put("/api/admin/users/" + uid + "/permissions"), content);
	}

	@Test
	void 가계부는_허락받은_사람만_화면과_API를_쓴다() throws Exception {
		assertThat(me("kim").get("ledger").asBoolean()).isFalse();
		assertThat(me("haru").get("ledger").asBoolean()).isTrue(); // 관리자는 처음부터 켜져 있다
		mvc.perform(get("/ledger").with(user("kim"))).andExpect(redirectedUrl("/?ledger=denied"));
		mvc.perform(get("/api/ledger/meta").with(user("kim"))).andExpect(status().isForbidden());
		String personal = body(mvc.perform(get("/api/all").with(user("kim")))).get("spaces").get(0).get("id").asString();
		as("kim", put("/api/ledger/spaces/" + personal + "/entries/e1"),
				"{\"date\":\"2026-11-01\",\"kind\":\"variable\",\"major\":\"식비\",\"amount\":1000}")
			.andExpect(status().isForbidden());

		grant("haru", kim, "{\"ledger\":true}").andExpect(status().isOk());
		assertThat(me("kim").get("ledger").asBoolean()).isTrue();
		mvc.perform(get("/api/ledger/meta").with(user("kim"))).andExpect(status().isOk());
		mvc.perform(get("/ledger").with(user("kim"))).andExpect(status().isOk());

		grant("haru", kim, "{\"ledger\":false}").andExpect(status().isOk());
		mvc.perform(get("/api/ledger/meta").with(user("kim"))).andExpect(status().isForbidden());
	}

	@Test
	void 관리자만_사용자_관리를_하고_관리자_권한도_줄_수_있다() throws Exception {
		mvc.perform(get("/api/admin/users").with(user("kim"))).andExpect(status().isForbidden());
		grant("kim", kim, "{\"ledger\":true}").andExpect(status().isForbidden());

		JsonNode list = body(mvc.perform(get("/api/admin/users").with(user("haru"))).andExpect(status().isOk()));
		assertThat(list.size()).isEqualTo(2);
		assertThat(find(list, kim).get("admin").asBoolean()).isFalse();

		// 김철수를 관리자로: 이제 사용자 관리와 가입 승인 대기 목록을 본다
		grant("haru", kim, "{\"admin\":true}").andExpect(status().isOk());
		assertThat(me("kim").get("admin").asBoolean()).isTrue();
		assertThat(me("kim").get("ledger").asBoolean()).isFalse(); // 보내지 않은 항목은 그대로
		mvc.perform(get("/api/admin/users").with(user("kim"))).andExpect(status().isOk());
		assertThat(body(mvc.perform(get("/api/all").with(user("kim")))).has("pendingUsers")).isTrue();

		// 내 관리자 권한은 스스로 못 뺀다. 다른 관리자는 뺄 수 있다
		grant("kim", kim, "{\"admin\":false}").andExpect(status().isBadRequest());
		String haru = me("haru").get("id").asString();
		grant("kim", haru, "{\"admin\":false}").andExpect(status().isOk());
		grant("haru", kim, "{\"admin\":false}").andExpect(status().isForbidden()); // 이제 관리자가 아님
		assertThat(me("kim").get("admin").asBoolean()).isTrue();
		grant("kim", "u-nope", "{\"ledger\":true}").andExpect(status().isBadRequest());
	}
}

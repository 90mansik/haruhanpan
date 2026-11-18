package com.haruhanpan;

import static com.haruhanpan.DataApiTest.find;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 팀 공간: 초대·가입·권한·공간 격리·버전 충돌·재설정 링크 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TeamTest {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		TestSupport.register(registry, "team" + System.nanoTime());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	LoginAttemptService attempts;

	String team;

	@BeforeEach
	void createTeamWithKim() throws Exception {
		attempts.reset();
		team = body(as("haru", post("/api/spaces"), "{\"name\":\"DBA팀\",\"color\":\"#16a34a\"}")).get("id").asString();
		signup(invite("editor"), "kim", "김철수");
	}

	// ───────── 도우미 ─────────

	private ResultActions as(String login,
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String content) throws Exception {
		req.with(user(login)).with(csrf());
		if (content != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		return mvc.perform(req);
	}

	private JsonNode body(ResultActions r) throws Exception {
		return json.readTree(r.andReturn().getResponse().getContentAsString());
	}

	private JsonNode all(String login) throws Exception {
		return body(mvc.perform(get("/api/all").with(user(login))).andExpect(status().isOk()));
	}

	private String invite(String role) throws Exception {
		return body(as("haru", post("/api/spaces/" + team + "/invites"), "{\"role\":\"" + role + "\"}")
			.andExpect(status().isOk())).get("token").asString();
	}

	private ResultActions anonymous(String path, String content) throws Exception {
		return mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content));
	}

	private void signup(String token, String login, String name) throws Exception {
		anonymous("/api/invites/accept", "{\"token\":\"" + token + "\",\"login\":\"" + login + "\",\"name\":\"" + name
				+ "\",\"password\":\"" + TestSupport.PASSWORD + "\"}")
			.andExpect(status().isOk());
	}

	private String userId(String login) throws Exception {
		return all(login).get("me").get("id").asString();
	}

	// ───────── 테스트 ─────────

	@Test
	void 초대로_가입한_멤버는_팀공간과_자기_개인공간만_본다() throws Exception {
		as("haru", put("/api/spaces/sp-owner/todos/secret"), "{\"title\":\"관리자 개인 할 일\"}").andExpect(status().isOk());

		JsonNode kim = all("kim");
		assertThat(kim.get("spaces").size()).isEqualTo(2); // 개인 + DBA팀
		assertThat(kim.get("spaces").get(1).get("name").asString()).isEqualTo("DBA팀");
		assertThat(kim.get("spaces").get(1).get("role").asString()).isEqualTo("editor");
		assertThat(kim.get("spaces").get(1).get("members").size()).isEqualTo(2);
		assertThat(find(kim.get("todos"), "secret")).isNull();
		assertThat(kim.get("routines").size()).isZero(); // 관리자의 개인 루틴은 안 보임

		as("kim", put("/api/spaces/sp-owner/todos/x"), "{\"title\":\"남의 공간\"}").andExpect(status().isForbidden());
		as("kim", put("/api/spaces/sp-owner/routineChecks/2026-11-05/r1"), "").andExpect(status().isForbidden());
	}

	@Test
	void 팀_할일_공유_담당자_최근활동() throws Exception {
		String kimId = userId("kim");
		as("kim", put("/api/spaces/" + team + "/todos/t1"),
				"{\"title\":\"백업 점검\",\"assignee\":\"" + kimId + "\"}").andExpect(status().isOk());

		JsonNode owner = all("haru");
		JsonNode t = find(owner.get("todos"), "t1");
		assertThat(t.get("spaceId").asString()).isEqualTo(team);
		assertThat(t.get("assignee").asString()).isEqualTo(kimId);
		assertThat(t.get("createdBy").asString()).isEqualTo(kimId);
		assertThat(owner.get("activity").get(0).get("title").asString()).isEqualTo("백업 점검");
		assertThat(owner.get("activity").get(0).get("action").asString()).isEqualTo("create");

		// 멤버가 아닌 사람은 담당자로 지정할 수 없다
		as("kim", put("/api/spaces/" + team + "/todos/t2"), "{\"title\":\"x\",\"assignee\":\"u-nobody\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void 다른_공간의_id로_덮어쓰기_불가() throws Exception {
		as("haru", put("/api/spaces/sp-owner/todos/mine"), "{\"title\":\"원래 제목\"}").andExpect(status().isOk());
		as("kim", put("/api/spaces/" + team + "/todos/mine"), "{\"title\":\"덮어쓰기 시도\"}").andExpect(status().isBadRequest());
		as("kim", delete("/api/spaces/" + team + "/todos/mine"), null).andExpect(status().isOk()); // 팀 공간엔 없음 → 지워지는 것 없음
		assertThat(find(all("haru").get("todos"), "mine").get("title").asString()).isEqualTo("원래 제목");
	}

	@Test
	void 보기_권한은_읽기와_자기_루틴체크만() throws Exception {
		signup(invite("viewer"), "lee", "이영희");
		as("haru", put("/api/spaces/" + team + "/routines/tr1"), "{\"title\":\"팀 점검\"}").andExpect(status().isOk());

		as("lee", put("/api/spaces/" + team + "/todos/v1"), "{\"title\":\"x\"}").andExpect(status().isForbidden());
		as("lee", put("/api/spaces/" + team + "/routineChecks/2026-11-05/tr1"), "").andExpect(status().isOk());
		as("kim", put("/api/spaces/" + team + "/routineChecks/2026-11-05/tr1"), "").andExpect(status().isOk());

		JsonNode checks = all("haru").get("routineChecks");
		assertThat(checks.size()).isEqualTo(2); // 사람별로 따로 기록
		as("lee", post("/api/spaces/" + team + "/invites"), "{\"role\":\"editor\"}").andExpect(status().isForbidden());
	}

	@Test
	void 버전이_다르면_409() throws Exception {
		as("haru", put("/api/spaces/" + team + "/notes/n1"), "{\"title\":\"회의록\",\"body\":\"v1\"}").andExpect(status().isOk());
		as("kim", put("/api/spaces/" + team + "/notes/n1"), "{\"title\":\"회의록\",\"body\":\"김 수정\",\"version\":1}")
			.andExpect(status().isOk());
		JsonNode conflict = body(as("haru", put("/api/spaces/" + team + "/notes/n1"),
				"{\"title\":\"회의록\",\"body\":\"관리자 수정\",\"version\":1}").andExpect(status().isConflict()));
		assertThat(conflict.get("version").asLong()).isEqualTo(2);
		assertThat(find(all("haru").get("notes"), "n1").get("body").asString()).isEqualTo("김 수정");
	}

	@Test
	void 초대_링크는_1회용() throws Exception {
		String token = invite("editor");
		signup(token, "park", "박민수");
		anonymous("/api/invites/accept", "{\"token\":\"" + token + "\",\"login\":\"choi\",\"name\":\"최\",\"password\":\""
				+ TestSupport.PASSWORD + "\"}")
			.andExpect(status().isBadRequest());
		assertThat(body(anonymous("/api/invites/peek", "{\"token\":\"" + token + "\"}")).get("valid").asBoolean()).isFalse();
		// 아이디 중복·짧은 비밀번호
		String t2 = invite("editor");
		anonymous("/api/invites/accept", "{\"token\":\"" + t2 + "\",\"login\":\"kim\",\"name\":\"또김\",\"password\":\""
				+ TestSupport.PASSWORD + "\"}").andExpect(status().isBadRequest());
		anonymous("/api/invites/accept", "{\"token\":\"" + t2 + "\",\"login\":\"new1\",\"name\":\"새\",\"password\":\"short\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void 마지막_관리자는_못나가고_관리자만_내보내기() throws Exception {
		String kimId = userId("kim");
		as("haru", delete("/api/spaces/" + team + "/members/u-owner"), null).andExpect(status().isBadRequest());
		as("kim", delete("/api/spaces/" + team + "/members/u-owner"), null).andExpect(status().isForbidden());
		as("haru", put("/api/spaces/" + team + "/members/u-owner"), "{\"role\":\"editor\"}").andExpect(status().isBadRequest());

		as("haru", put("/api/spaces/" + team + "/members/" + kimId), "{\"role\":\"owner\"}").andExpect(status().isOk());
		as("haru", delete("/api/spaces/" + team + "/members/u-owner"), null).andExpect(status().isOk()); // 이제 나갈 수 있음
		assertThat(all("haru").get("spaces").size()).isEqualTo(1);
	}

	@Test
	void 개인_항목을_팀으로_옮기면_팀원이_보고_담당자는_정리() throws Exception {
		as("haru", put("/api/spaces/sp-owner/notes/n1"), "{\"title\":\"회의 메모\",\"body\":\"내용\"}").andExpect(status().isOk());
		assertThat(find(all("kim").get("notes"), "n1")).isNull();

		as("haru", post("/api/spaces/sp-owner/notes/n1/move"), "{\"to\":\"" + team + "\"}").andExpect(status().isOk());
		JsonNode note = find(all("kim").get("notes"), "n1");
		assertThat(note.get("spaceId").asString()).isEqualTo(team);
		assertThat(note.get("body").asString()).isEqualTo("내용");
		assertThat(all("haru").get("activity").get(0).get("action").asString()).isEqualTo("movein");

		// 팀 → 개인으로 옮기면 담당자가 비워진다
		String kimId = userId("kim");
		as("haru", put("/api/spaces/" + team + "/todos/t1"), "{\"title\":\"x\",\"assignee\":\"" + kimId + "\"}").andExpect(status().isOk());
		as("haru", post("/api/spaces/" + team + "/todos/t1/move"), "{\"to\":\"sp-owner\"}").andExpect(status().isOk());
		JsonNode todo = find(all("haru").get("todos"), "t1");
		assertThat(todo.get("spaceId").asString()).isEqualTo("sp-owner");
		assertThat(todo.get("assignee").asString()).isEmpty();
		assertThat(find(all("kim").get("todos"), "t1")).isNull();
	}

	@Test
	void 옮기기는_양쪽_편집권한_필요_루틴은_불가() throws Exception {
		signup(invite("viewer"), "lee", "이영희");
		as("haru", put("/api/spaces/sp-owner/todos/mine"), "{\"title\":\"개인\"}").andExpect(status().isOk());
		// 남의 개인 공간에서 꺼내 가기 불가
		as("kim", post("/api/spaces/sp-owner/todos/mine/move"), "{\"to\":\"" + team + "\"}").andExpect(status().isForbidden());
		// 보기 권한인 팀으로 넣기 불가
		String leePersonal = all("lee").get("spaces").get(0).get("id").asString();
		as("lee", put("/api/spaces/" + leePersonal + "/todos/l1"), "{\"title\":\"이영희 할 일\"}").andExpect(status().isOk());
		as("lee", post("/api/spaces/" + leePersonal + "/todos/l1/move"), "{\"to\":\"" + team + "\"}").andExpect(status().isForbidden());
		// 루틴은 옮기지 않음
		as("haru", post("/api/spaces/sp-owner/routines/r1/move"), "{\"to\":\"" + team + "\"}").andExpect(status().isBadRequest());
		// 없는 항목
		as("haru", post("/api/spaces/sp-owner/todos/nope/move"), "{\"to\":\"" + team + "\"}").andExpect(status().isNotFound());
	}

	@Test
	void 팀공간_삭제시_데이터도_삭제() throws Exception {
		as("kim", put("/api/spaces/" + team + "/todos/t1"), "{\"title\":\"팀 할 일\"}").andExpect(status().isOk());
		as("kim", delete("/api/spaces/" + team), null).andExpect(status().isForbidden());
		as("haru", delete("/api/spaces/sp-owner"), null).andExpect(status().isBadRequest()); // 개인 공간은 못 지움
		as("haru", delete("/api/spaces/" + team), null).andExpect(status().isOk());
		assertThat(all("kim").get("spaces").size()).isEqualTo(1);
		assertThat(find(all("kim").get("todos"), "t1")).isNull();
	}

	@Test
	void 재설정_링크는_사이트관리자만_만들고_새_비밀번호로_로그인() throws Exception {
		String kimId = userId("kim");
		as("kim", post("/api/users/u-owner/reset-link"), null).andExpect(status().isForbidden());
		String token = body(as("haru", post("/api/users/" + kimId + "/reset-link"), null).andExpect(status().isOk()))
			.get("token").asString();
		assertThat(body(anonymous("/api/invites/peek", "{\"token\":\"" + token + "\"}")).get("login").asString()).isEqualTo("kim");
		anonymous("/api/invites/reset", "{\"token\":\"" + token + "\",\"password\":\"brand-new-password-1\"}").andExpect(status().isOk());

		mvc.perform(post("/api/login").with(csrf()).param("username", "kim").param("password", TestSupport.PASSWORD))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/login").with(csrf()).param("username", "kim").param("password", "brand-new-password-1"))
			.andExpect(status().isOk());
	}

	@Test
	void 비밀번호_변경은_현재_비밀번호_확인() throws Exception {
		as("kim", put("/api/me/password"), "{\"current\":\"wrong\",\"next\":\"another-password-12\"}").andExpect(status().isBadRequest());
		as("kim", put("/api/me/password"), "{\"current\":\"" + TestSupport.PASSWORD + "\",\"next\":\"another-password-12\"}")
			.andExpect(status().isOk());
		as("kim", put("/api/me"), "{\"name\":\"김대리\"}").andExpect(status().isOk());
		assertThat(all("kim").get("me").get("name").asString()).isEqualTo("김대리");
	}
}

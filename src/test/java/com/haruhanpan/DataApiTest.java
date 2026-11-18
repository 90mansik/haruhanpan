package com.haruhanpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
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

/** 개인 공간(sp-owner)에서의 저장·조회·가져오기 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DataApiTest {

	static final String SP = "/api/spaces/sp-owner";
	static Path dataDir;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		dataDir = TestSupport.register(registry, "data" + System.nanoTime());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	private ResultActions putJson(String path, String body) throws Exception {
		return mvc.perform(put(path).with(user("haru")).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private JsonNode all() throws Exception {
		String body = mvc.perform(get("/api/all").with(user("haru")))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return json.readTree(body);
	}

	static JsonNode find(JsonNode list, String id) {
		for (JsonNode n : list) {
			if (n.get("id").asString().equals(id)) {
				return n;
			}
		}
		return null;
	}

	@Test
	void 기본_데이터_관리자_개인공간_공휴일_루틴() throws Exception {
		JsonNode data = all();
		assertThat(data.get("me").get("login").asString()).isEqualTo("haru");
		assertThat(data.get("me").get("admin").asBoolean()).isTrue();
		assertThat(data.get("spaces").size()).isEqualTo(1);
		assertThat(data.get("spaces").get(0).get("kind").asString()).isEqualTo("personal");
		assertThat(data.get("holidays").get("2026-09-25").asString()).isEqualTo("추석");
		assertThat(data.get("routines").size()).isEqualTo(4);
	}

	@Test
	void 컬렉션별_저장_조회_수정_버전증가() throws Exception {
		putJson(SP + "/todos/t1", "{\"title\":\"할 일\",\"due\":\"2026-11-05\",\"priority\":\"high\",\"rank\":1000}")
			.andExpect(status().isOk());
		putJson(SP + "/events/e1", "{\"title\":\"회의\",\"date\":\"2026-11-06\",\"time\":\"10:00\",\"repeat\":\"weekly\",\"dday\":true}")
			.andExpect(status().isOk());
		putJson(SP + "/notes/n1", "{\"title\":\"메모\",\"body\":\"본문\",\"pinned\":true}").andExpect(status().isOk());
		putJson(SP + "/snippets/s1", "{\"title\":\"쿼리\",\"code\":\"SELECT 1 FROM dual\",\"lang\":\"java\"}")
			.andExpect(status().isOk());
		putJson(SP + "/routines/r9", "{\"title\":\"새 루틴\"}").andExpect(status().isOk());
		String res = putJson(SP + "/todos/t1", "{\"title\":\"수정됨\",\"status\":\"doing\",\"version\":1}")
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(res).get("version").asLong()).isEqualTo(2);

		JsonNode data = all();
		JsonNode todo = find(data.get("todos"), "t1");
		assertThat(todo.get("title").asString()).isEqualTo("수정됨");
		assertThat(todo.get("status").asString()).isEqualTo("doing");
		assertThat(todo.get("spaceId").asString()).isEqualTo("sp-owner");
		assertThat(todo.get("createdBy").asString()).isEqualTo("u-owner");
		assertThat(todo.get("version").asLong()).isEqualTo(2);
		JsonNode event = find(data.get("events"), "e1");
		assertThat(event.get("date").asString()).isEqualTo("2026-11-06");
		assertThat(event.get("dday").asBoolean()).isTrue();
		assertThat(find(data.get("notes"), "n1").get("pinned").asBoolean()).isTrue();
		assertThat(find(data.get("snippets"), "s1").get("lang").asString()).isEqualTo("java");
		JsonNode routines = data.get("routines");
		assertThat(routines.get(routines.size() - 1).get("id").asString()).isEqualTo("r9");
	}

	@Test
	void 빈_문자열은_빈_문자열로_돌아온다() throws Exception {
		putJson(SP + "/todos/t2", "{\"title\":\"x\",\"due\":\"\",\"tag\":\"\",\"memo\":\"\"}").andExpect(status().isOk());
		JsonNode todo = find(all().get("todos"), "t2");
		assertThat(todo.get("due").asString()).isEmpty();
		assertThat(todo.get("assignee").asString()).isEmpty();
	}

	@Test
	void 긴_메모_본문_CLOB() throws Exception {
		String body = "가".repeat(100_000);
		putJson(SP + "/notes/long", "{\"title\":\"긴 메모\",\"body\":\"" + body + "\"}").andExpect(status().isOk());
		assertThat(find(all().get("notes"), "long").get("body").asString()).hasSize(100_000);
	}

	@Test
	void 할일_status와_done_보정() throws Exception {
		putJson(SP + "/todos/a", "{\"title\":\"a\",\"done\":true}").andExpect(status().isOk());
		putJson(SP + "/todos/b", "{\"title\":\"b\",\"status\":\"done\",\"done\":false}").andExpect(status().isOk());
		putJson(SP + "/todos/c", "{\"title\":\"c\",\"status\":\"weird\"}").andExpect(status().isOk());
		JsonNode todos = all().get("todos");
		assertThat(find(todos, "a").get("status").asString()).isEqualTo("done");
		assertThat(find(todos, "b").get("done").asBoolean()).isTrue();
		assertThat(find(todos, "c").get("status").asString()).isEqualTo("todo");
	}

	@Test
	void 루틴_체크_순서_삭제시_기록도_삭제() throws Exception {
		putJson(SP + "/routineChecks/2026-11-05/r1", "").andExpect(status().isOk());
		putJson(SP + "/routineChecks/2026-11-05/r2", "").andExpect(status().isOk());
		putJson(SP + "/routines/order", "[\"r4\",\"r3\",\"r2\",\"r1\"]").andExpect(status().isOk());
		JsonNode data = all();
		assertThat(data.get("routineChecks").size()).isEqualTo(2);
		assertThat(data.get("routineChecks").get(0).get("userId").asString()).isEqualTo("u-owner");
		assertThat(data.get("routines").get(0).get("id").asString()).isEqualTo("r4");

		mvc.perform(delete(SP + "/routines/r1").with(user("haru")).with(csrf())).andExpect(status().isOk());
		data = all();
		assertThat(data.get("routines").size()).isEqualTo(3);
		assertThat(data.get("routineChecks").size()).isEqualTo(1);
	}

	@Test
	void 공간_휴무일_지정_해제_공휴일은_그대로() throws Exception {
		putJson(SP + "/dayOffs/2026-11-20", "{\"name\":\"휴가\"}").andExpect(status().isOk());
		JsonNode data = all();
		assertThat(data.get("spaceDayOffs").get("sp-owner").get("2026-11-20").asString()).isEqualTo("휴가");
		assertThat(data.get("holidays").has("2026-11-20")).isFalse();
		mvc.perform(delete(SP + "/dayOffs/2026-11-20").with(user("haru")).with(csrf())).andExpect(status().isOk());
		mvc.perform(delete(SP + "/dayOffs/2026-12-25").with(user("haru")).with(csrf())).andExpect(status().isOk());
		data = all();
		assertThat(data.get("spaceDayOffs").has("sp-owner")).isFalse();
		assertThat(data.get("holidays").get("2026-12-25").asString()).isEqualTo("성탄절");
	}

	@Test
	void 잘못된_입력은_400_모르는_컬렉션은_404() throws Exception {
		putJson(SP + "/todos/bad id!", "{\"title\":\"x\"}").andExpect(status().isBadRequest());
		putJson(SP + "/routineChecks/20261105/r1", "").andExpect(status().isBadRequest());
		putJson(SP + "/unknown/x1", "{}").andExpect(status().isNotFound());
		putJson(SP + "/todos/t9", "not json").andExpect(status().isBadRequest());
	}

	@Test
	void 예전_1인용_내보내기_파일도_가져오기_가능_교체전_백업() throws Exception {
		putJson(SP + "/todos/old", "{\"title\":\"예전 할 일\"}").andExpect(status().isOk());
		String data = """
			{"todos":[{"id":"n1","title":"새 할 일","status":"doing","rank":1000}],
			 "events":[], "notes":[], "snippets":[],
			 "routines":[{"id":"x2","title":"둘"},{"id":"x1","title":"하나"}],
			 "routineLog":{"2026-11-01":["x1"]},
			 "holidays":{"2026-12-25":"성탄절", "2026-12-24":"이브 휴무"}}
			""";
		mvc.perform(post(SP + "/import").with(user("haru")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(data))
			.andExpect(status().isOk());

		JsonNode all = all();
		assertThat(all.get("todos").size()).isEqualTo(1);
		assertThat(all.get("todos").get(0).get("id").asString()).isEqualTo("n1");
		assertThat(all.get("routines").get(0).get("id").asString()).isEqualTo("x2");
		assertThat(all.get("routineChecks").get(0).get("routineId").asString()).isEqualTo("x1");
		// 공휴일과 같은 항목은 건너뛰고, 나머지는 공간 휴무일로
		assertThat(all.get("spaceDayOffs").get("sp-owner").size()).isEqualTo(1);
		assertThat(all.get("spaceDayOffs").get("sp-owner").get("2026-12-24").asString()).isEqualTo("이브 휴무");

		try (Stream<Path> files = Files.list(dataDir.resolve("backup"))) {
			assertThat(files.anyMatch(f -> f.getFileName().toString().endsWith("_before-import.json"))).isTrue();
		}
	}

	@Test
	void 가져오기_중_오류면_기존_데이터_유지() throws Exception {
		putJson(SP + "/todos/keep", "{\"title\":\"남아야 함\"}").andExpect(status().isOk());
		String bad = "{\"todos\":[{\"id\":\"ok1\",\"title\":\"a\"},{\"id\":\"bad id!\",\"title\":\"b\"}]}";
		mvc.perform(post(SP + "/import").with(user("haru")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(bad))
			.andExpect(status().isBadRequest());

		JsonNode todos = all().get("todos");
		assertThat(todos.size()).isEqualTo(1);
		assertThat(todos.get(0).get("id").asString()).isEqualTo("keep");
		assertThat(all().get("routines").size()).isEqualTo(4);
	}

	@Test
	void 할_일_반복은_정해진_값만_저장한다() throws Exception {
		putJson(SP + "/todos/r1", "{\"title\":\"주간 보고\",\"due\":\"2026-11-20\",\"repeat\":\"weekly\"}").andExpect(status().isOk());
		putJson(SP + "/todos/r2", "{\"title\":\"이상한 반복\",\"repeat\":\"hourly\"}").andExpect(status().isOk());
		JsonNode todos = all().get("todos");
		assertThat(find(todos, "r1").get("repeat").asString()).isEqualTo("weekly");
		assertThat(find(todos, "r2").get("repeat").asString()).isEmpty();
	}
}

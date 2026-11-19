package com.haruhanpan;

import static com.haruhanpan.DataApiTest.find;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 하루위키: 페이지 트리 검사·하위 페이지 함께 삭제/이동·이미지 첨부 권한·가져오기 때 트리 유지 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WikiTest {

	static final String SP = "/api/spaces/sp-owner";

	/** 가장 작은 PNG (1×1) */
	static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R',
			0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1f, 0x15, (byte) 0xc4, (byte) 0x89};

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		TestSupport.register(registry, "wiki" + System.nanoTime());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	LoginAttemptService attempts;

	String team;

	@BeforeEach
	void createTeamWithViewerKim() throws Exception {
		attempts.reset();
		team = body(as("haru", post("/api/spaces"), "{\"name\":\"DBA팀\",\"color\":\"#16a34a\"}")).get("id").asString();
		String token = body(as("haru", post("/api/spaces/" + team + "/invites"), "{\"role\":\"viewer\"}")
			.andExpect(status().isOk())).get("token").asString();
		mvc.perform(post("/api/invites/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\",\"login\":\"kim\",\"name\":\"김철수\",\"password\":\""
					+ TestSupport.PASSWORD + "\"}"))
			.andExpect(status().isOk());
	}

	// ───────── 도우미 ─────────

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

	private JsonNode notes(String login) throws Exception {
		return body(mvc.perform(get("/api/all").with(user(login))).andExpect(status().isOk())).get("notes");
	}

	private ResultActions page(String space, String id, String title, String parent) throws Exception {
		return as("haru", put("/api/spaces/" + space + "/notes/" + id),
				"{\"title\":\"" + title + "\",\"parentId\":\"" + (parent == null ? "" : parent) + "\"}");
	}

	private ResultActions upload(String login, String space, String pageId, byte[] data) throws Exception {
		return mvc.perform(multipart("/api/spaces/" + space + "/files").file(new MockMultipartFile("file", "a.png", "image/png", data))
			.param("page", pageId).with(user(login)).with(csrf()));
	}

	// ───────── 테스트 ─────────

	@Test
	void 상위_페이지는_같은_공간에만_순환은_안됨() throws Exception {
		page("sp-owner", "a", "A", null).andExpect(status().isOk());
		page("sp-owner", "b", "B", "a").andExpect(status().isOk());
		page("sp-owner", "c", "C", "b").andExpect(status().isOk());
		assertThat(find(notes("haru"), "c").get("parentId").asString()).isEqualTo("b");

		page("sp-owner", "a", "A", "c").andExpect(status().isBadRequest()); // a → c → b → a
		page("sp-owner", "a", "A", "a").andExpect(status().isBadRequest());
		page(team, "t1", "팀 페이지", "a").andExpect(status().isBadRequest()); // 다른 공간의 페이지
		page("sp-owner", "d", "D", "nope").andExpect(status().isBadRequest());
	}

	@Test
	void 페이지를_지우면_하위_페이지와_첨부_이미지도_지운다() throws Exception {
		page("sp-owner", "a", "A", null).andExpect(status().isOk());
		page("sp-owner", "b", "B", "a").andExpect(status().isOk());
		page("sp-owner", "c", "C", "b").andExpect(status().isOk());
		page("sp-owner", "keep", "다른 페이지", null).andExpect(status().isOk());
		String url = body(upload("haru", "sp-owner", "c", PNG).andExpect(status().isOk())).get("file").get("url").asString();
		mvc.perform(get(url).with(user("haru"))).andExpect(status().isOk());

		as("haru", delete(SP + "/notes/a"), null).andExpect(status().isOk());

		JsonNode left = notes("haru");
		assertThat(find(left, "a")).isNull();
		assertThat(find(left, "b")).isNull();
		assertThat(find(left, "c")).isNull();
		assertThat(find(left, "keep")).isNotNull();
		mvc.perform(get(url).with(user("haru"))).andExpect(status().isNotFound());
	}

	@Test
	void 페이지를_옮기면_하위_페이지와_이미지도_같이_간다() throws Exception {
		page("sp-owner", "a", "A", null).andExpect(status().isOk());
		page("sp-owner", "b", "B", "a").andExpect(status().isOk());
		String url = body(upload("haru", "sp-owner", "b", PNG).andExpect(status().isOk())).get("file").get("url").asString();
		mvc.perform(get(url).with(user("kim"))).andExpect(status().isForbidden()); // 아직 관리자 개인 공간

		as("haru", post(SP + "/notes/a/move"), "{\"to\":\"" + team + "\"}").andExpect(status().isOk());

		JsonNode kim = notes("kim");
		assertThat(find(kim, "a").get("spaceId").asString()).isEqualTo(team);
		assertThat(find(kim, "b").get("spaceId").asString()).isEqualTo(team);
		assertThat(find(kim, "b").get("parentId").asString()).isEqualTo("a");
		mvc.perform(get(url).with(user("kim"))).andExpect(status().isOk()); // 팀 멤버는 이제 볼 수 있다
	}

	@Test
	void 이미지는_그림_파일만_편집자만_올리고_멤버만_본다() throws Exception {
		page("sp-owner", "a", "A", null).andExpect(status().isOk());
		JsonNode res = body(upload("haru", "sp-owner", "a", PNG).andExpect(status().isOk()));
		assertThat(res.get("success").asInt()).isEqualTo(1);
		String url = res.get("file").get("url").asString();
		assertThat(url).startsWith("/files/");

		mvc.perform(get(url).with(user("haru")))
			.andExpect(status().isOk())
			.andExpect(r -> assertThat(r.getResponse().getContentType()).isEqualTo("image/png"))
			.andExpect(r -> assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff"));
		mvc.perform(get(url).with(user("kim"))).andExpect(status().isForbidden()); // 다른 사람의 개인 공간

		upload("haru", "sp-owner", "a", "<script>alert(1)</script>".getBytes()).andExpect(status().isBadRequest());
		upload("kim", team, null, PNG).andExpect(status().isForbidden()); // 보기 권한
		upload("kim", "sp-owner", null, PNG).andExpect(status().isForbidden()); // 멤버 아님
		mvc.perform(get("/files/f-nope").with(user("haru"))).andExpect(status().isNotFound());
	}

	@Test
	void 가져오기를_해도_페이지_트리가_유지된다() throws Exception {
		page("sp-owner", "old", "지워질 페이지", null).andExpect(status().isOk());
		String data = """
			{"todos":[], "events":[], "snippets":[], "routines":[], "routineLog":{}, "holidays":{},
			 "notes":[{"id":"p","title":"부모","content":"{\\"blocks\\":[]}"},
			          {"id":"k","title":"자식","parentId":"p","sort":1}]}
			""";
		as("haru", post(SP + "/import"), data).andExpect(status().isOk());

		JsonNode list = notes("haru");
		assertThat(list.size()).isEqualTo(2);
		JsonNode parent = null;
		JsonNode child = null;
		for (JsonNode n : list) {
			if (n.get("title").asString().equals("부모")) {
				parent = n;
			} else {
				child = n;
			}
		}
		assertThat(parent).isNotNull();
		assertThat(child.get("title").asString()).isEqualTo("자식");
		assertThat(child.get("parentId").asString()).isEqualTo(parent.get("id").asString());
	}
}

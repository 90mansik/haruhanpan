package com.haruhanpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.haruhanpan.auth.LoginAttemptService;
import com.haruhanpan.ledger.LedgerRecurringJob;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 가계부: 기본 분류, 내역 저장·요약, 월 시작일, 사람 칸, 권한, 버전 충돌, 설정, 정기결제, 누적 저축 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LedgerTest {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		TestSupport.register(registry, "ledger" + System.nanoTime());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	LoginAttemptService attempts;

	@Autowired
	LedgerRecurringJob recurringJob;

	@Autowired
	JdbcClient jdbc;

	String team;

	@BeforeEach
	void createTeam() throws Exception {
		attempts.reset();
		team = body(as("haru", post("/api/spaces"), "{\"name\":\"우리집\",\"color\":\"#16a34a\"}")).get("id").asString();
		signup(invite("editor"), "kim", "김철수");
		signup(invite("viewer"), "lee", "이영희");
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

	private String invite(String role) throws Exception {
		return body(as("haru", post("/api/spaces/" + team + "/invites"), "{\"role\":\"" + role + "\"}")
			.andExpect(status().isOk())).get("token").asString();
	}

	private void signup(String token, String login, String name) throws Exception {
		mvc.perform(post("/api/invites/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\",\"login\":\"" + login + "\",\"name\":\"" + name
						+ "\",\"password\":\"" + TestSupport.PASSWORD + "\"}"))
			.andExpect(status().isOk());
	}

	private String userId(String login) throws Exception {
		return body(mvc.perform(get("/api/ledger/meta").with(user(login)))).get("me").get("id").asString();
	}

	private JsonNode month(String login, String sid, String month) throws Exception {
		return body(mvc.perform(get("/api/ledger/spaces/" + sid).param("month", month).with(user(login)))
			.andExpect(status().isOk()));
	}

	private ResultActions entry(String login, String sid, String id, String content) throws Exception {
		return as(login, put("/api/ledger/spaces/" + sid + "/entries/" + id), content);
	}

	private static String e(String date, String kind, String major, long amount, String method, String person) {
		return "{\"date\":\"" + date + "\",\"kind\":\"" + kind + "\",\"major\":\"" + major + "\",\"amount\":" + amount
				+ ",\"method\":\"" + method + "\",\"personId\":\"" + person + "\"}";
	}

	// ───────── 테스트 ─────────

	@Test
	void 화면_주소와_설정이_없을_때의_기본값() throws Exception {
		mvc.perform(get("/ledger").with(user("haru"))).andExpect(forwardedUrl("/ledger.html"));

		JsonNode m = month("haru", "sp-owner", "2026-02");
		assertThat(m.get("from").asString()).isEqualTo("2026-02-01");
		assertThat(m.get("to").asString()).isEqualTo("2026-02-28");
		assertThat(m.get("settings").get("monthStartDay").asInt()).isEqualTo(1);
		assertThat(m.get("settings").get("methods").get(0).asString()).isEqualTo("생활비카드");
		assertThat(m.get("categories").get("expense").get(0).get("major").asString()).isEqualTo("주거");
		assertThat(m.get("categories").get("income").get(0).get("minors").size()).isZero();
		assertThat(m.get("entries").size()).isZero();
		assertThat(m.get("summary").get("savingRate").isNull()).isTrue();

		JsonNode meta = body(mvc.perform(get("/api/ledger/meta").with(user("kim"))));
		assertThat(meta.get("spaces").size()).isEqualTo(2);
		assertThat(meta.get("spaces").get(1).get("members").size()).isEqualTo(3);
	}

	@Test
	void 내역을_저장하면_월_요약이_계산된다() throws Exception {
		String kim = userId("kim");
		entry("haru", team, "i1", e("2026-02-25", "income", "월급", 3_000_000, "", kim)).andExpect(status().isOk());
		entry("haru", team, "s1", e("2026-02-25", "saving", "목적통장", 500_000, "", "")).andExpect(status().isOk());
		entry("kim", team, "f1", e("2026-02-01", "fixed", "주거", 1_000_000, "현금/이체", "")).andExpect(status().isOk());
		entry("kim", team, "v1", e("2026-02-10", "variable", "식비", 30_000, "생활비카드", kim)).andExpect(status().isOk());
		entry("kim", team, "v2", e("2026-02-11", "variable", "식비", 20_000, "생활비카드", "")).andExpect(status().isOk());
		entry("kim", team, "old", e("2026-01-31", "variable", "식비", 9_999, "", "")).andExpect(status().isOk());

		JsonNode m = month("lee", team, "2026-02");
		assertThat(m.get("entries").size()).isEqualTo(5); // 1/31 은 1월
		assertThat(m.get("entries").get(0).get("date").asString()).isEqualTo("2026-02-25");
		JsonNode s = m.get("summary");
		assertThat(s.get("income").asLong()).isEqualTo(3_000_000);
		assertThat(s.get("saving").asLong()).isEqualTo(500_000);
		assertThat(s.get("expense").asLong()).isEqualTo(1_050_000);
		assertThat(s.get("fixed").asLong()).isEqualTo(1_000_000);
		assertThat(s.get("surplus").asLong()).isEqualTo(1_450_000);
		assertThat(s.get("savingRate").asDouble()).isEqualTo(500_000.0 / 3_000_000);
		assertThat(s.get("byMajor").get(0).get("major").asString()).isEqualTo("주거");
		assertThat(s.get("byMajor").get(1).get("amount").asLong()).isEqualTo(50_000);
		assertThat(s.get("byMethod").get(0).get("method").asString()).isEqualTo("현금/이체");
		JsonNode kimRow = null;
		for (JsonNode p : s.get("byPerson")) {
			if (p.get("personId").asString().equals(kim)) {
				kimRow = p;
			}
		}
		assertThat(kimRow.get("income").asLong()).isEqualTo(3_000_000);
		assertThat(kimRow.get("expense").asLong()).isEqualTo(30_000);
		assertThat(m.get("entries").get(0).get("createdBy").asString()).isEqualTo(userId("haru"));
	}

	@Test
	void 월_시작일이_25일이면_전달_25일부터_이번달_24일까지() throws Exception {
		as("haru", put("/api/ledger/spaces/sp-owner/settings"),
				"{\"monthStartDay\":25,\"methods\":[\"카드\"],\"categories\":{\"expense\":[{\"major\":\"식비\",\"minors\":[\"외식\"]}]}}")
			.andExpect(status().isOk());
		entry("haru", "sp-owner", "a", e("2026-01-25", "variable", "식비", 1000, "카드", "")).andExpect(status().isOk());
		entry("haru", "sp-owner", "b", e("2026-02-24", "variable", "식비", 2000, "카드", "")).andExpect(status().isOk());
		entry("haru", "sp-owner", "c", e("2026-02-25", "variable", "식비", 4000, "카드", "")).andExpect(status().isOk());

		JsonNode m = month("haru", "sp-owner", "2026-02");
		assertThat(m.get("from").asString()).isEqualTo("2026-01-25");
		assertThat(m.get("to").asString()).isEqualTo("2026-02-24");
		assertThat(m.get("summary").get("expense").asLong()).isEqualTo(3000);
		assertThat(m.get("settings").get("methods").size()).isEqualTo(1);
		assertThat(m.get("categories").get("expense").size()).isEqualTo(1);
		assertThat(m.get("categories").get("income").size()).isZero(); // 저장하면 기본값 대신 저장한 것
	}

	@Test
	void 권한_보기는_조회만_멤버가_아니면_403() throws Exception {
		entry("lee", team, "x", e("2026-02-01", "variable", "식비", 1000, "", "")).andExpect(status().isForbidden());
		as("lee", put("/api/ledger/spaces/" + team + "/settings"), "{\"monthStartDay\":1}").andExpect(status().isForbidden());
		entry("kim", team, "x", e("2026-02-01", "variable", "식비", 1000, "", "")).andExpect(status().isOk());
		as("lee", delete("/api/ledger/spaces/" + team + "/entries/x"), null).andExpect(status().isForbidden());

		mvc.perform(get("/api/ledger/spaces/sp-owner").param("month", "2026-02").with(user("kim")))
			.andExpect(status().isForbidden());
		entry("kim", "sp-owner", "y", e("2026-02-01", "variable", "식비", 1000, "", "")).andExpect(status().isForbidden());
		mvc.perform(get("/api/ledger/meta")).andExpect(status().isUnauthorized());
	}

	@Test
	void 입력_검사와_사람은_공간_멤버만() throws Exception {
		entry("haru", team, "a", e("2026-02-01", "variable", "식비", 0, "", "")).andExpect(status().isBadRequest());
		entry("haru", team, "a", e("2026-02-30", "variable", "식비", 100, "", "")).andExpect(status().isBadRequest());
		entry("haru", team, "a", e("2026-02-01", "expense", "식비", 100, "", "")).andExpect(status().isBadRequest());
		entry("haru", team, "a", e("2026-02-01", "variable", "", 100, "", "")).andExpect(status().isBadRequest());
		entry("haru", team, "a", "{\"date\":\"2026-02-01\",\"kind\":\"variable\",\"major\":\"식비\",\"amount\":10.5}")
			.andExpect(status().isBadRequest());
		entry("haru", team, "a", e("2026-02-01", "variable", "식비", 100, "", "u-nobody")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/ledger/spaces/" + team).param("month", "2026-2").with(user("haru")))
			.andExpect(status().isBadRequest());
		as("haru", put("/api/ledger/spaces/" + team + "/settings"), "{\"monthStartDay\":29}").andExpect(status().isBadRequest());
		as("haru", put("/api/ledger/spaces/" + team + "/settings"),
				"{\"monthStartDay\":1,\"categories\":{\"expense\":[{\"major\":\"식비\"},{\"major\":\"식비\"}]}}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void 수정과_삭제_그리고_버전_충돌() throws Exception {
		entry("haru", team, "a", e("2026-02-01", "variable", "식비", 100, "", "")).andExpect(status().isOk());
		String v1 = "{\"version\":1,\"date\":\"2026-02-01\",\"kind\":\"variable\",\"major\":\"식비\",\"amount\":";
		assertThat(body(entry("kim", team, "a", v1 + "200}")).get("version").asLong()).isEqualTo(2);
		entry("haru", team, "a", v1 + "300}").andExpect(status().isConflict()); // 김철수가 먼저 고침
		assertThat(month("haru", team, "2026-02").get("summary").get("expense").asLong()).isEqualTo(200);

		entry("haru", "sp-owner", "a", e("2026-02-01", "variable", "식비", 1, "", "")).andExpect(status().is4xxClientError());
		as("kim", delete("/api/ledger/spaces/" + team + "/entries/a"), null).andExpect(status().isOk());
		assertThat(month("haru", team, "2026-02").get("entries").size()).isZero();
	}

	@Test
	void 팀_공간을_지우면_가계부도_지워진다() throws Exception {
		entry("kim", team, "a", e("2026-02-01", "variable", "식비", 100, "", "")).andExpect(status().isOk());
		as("haru", put("/api/ledger/spaces/" + team + "/settings"), "{\"monthStartDay\":10}").andExpect(status().isOk());
		as("haru", delete("/api/spaces/" + team), null).andExpect(status().isOk());

		String again = body(as("haru", post("/api/spaces"), "{\"name\":\"새 팀\"}")).get("id").asString();
		entry("haru", again, "a", e("2026-02-01", "variable", "식비", 100, "", "")).andExpect(status().isOk()); // id 재사용 가능
	}

	// ───────── 정기결제·누적 저축 ─────────

	private int recurringEntries(String recurringId) {
		return jdbc.sql("SELECT COUNT(*) FROM ledger_entries WHERE recurring_id = :id").param("id", recurringId)
			.query(Integer.class).single();
	}

	private static String recurring(String name, long amount, int day, String start, String end) {
		return "{\"name\":\"" + name + "\",\"major\":\"문화생활\",\"minor\":\"OTT/구독\",\"amount\":" + amount
				+ ",\"method\":\"생활비카드\",\"dayOfMonth\":" + day + ",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}";
	}

	@Test
	void 정기결제는_지난_결제일을_한_번씩만_기록하고_지운_내역은_다시_만들지_않는다() throws Exception {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		String start = YearMonth.from(today).minusMonths(2).atDay(1).toString();
		as("kim", put("/api/ledger/spaces/" + team + "/recurring/netflix"), recurring("넷플릭스", 17000, 1, start, ""))
			.andExpect(status().isOk());
		assertThat(recurringEntries("netflix")).isEqualTo(3); // 두 달 전·지난달·이번 달 1일

		recurringJob.run();
		assertThat(recurringEntries("netflix")).isEqualTo(3); // 같은 달은 한 번만

		JsonNode m = month("kim", team, YearMonth.from(today).toString());
		JsonNode entry = m.get("entries").get(0);
		assertThat(entry.get("recurringId").asString()).isEqualTo("netflix");
		assertThat(entry.get("kind").asString()).isEqualTo("fixed");
		assertThat(entry.get("memo").asString()).isEqualTo("넷플릭스");
		assertThat(entry.get("date").asString()).isEqualTo(YearMonth.from(today).atDay(1).toString());
		assertThat(m.get("recurring").get(0).get("dayOfMonth").asInt()).isEqualTo(1);
		assertThat(m.get("recurringRuns").size()).isEqualTo(1);

		as("kim", delete("/api/ledger/spaces/" + team + "/entries/" + entry.get("id").asString()), null).andExpect(status().isOk());
		recurringJob.run();
		assertThat(recurringEntries("netflix")).isEqualTo(2); // 해지한 달처럼 지운 내역은 그대로

		as("kim", delete("/api/ledger/spaces/" + team + "/recurring/netflix"), null).andExpect(status().isOk());
		assertThat(recurringEntries("netflix")).isEqualTo(2); // 정기결제를 지워도 만든 내역은 남는다
	}

	@Test
	void 정기결제_시작일_이전과_미래_결제일은_기록하지_않는다() throws Exception {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		as("haru", put("/api/ledger/spaces/sp-owner/recurring/future"),
				recurring("다음 달부터", 9900, 1, YearMonth.from(today).plusMonths(1).atDay(1).toString(), "")).andExpect(status().isOk());
		as("haru", put("/api/ledger/spaces/sp-owner/recurring/later"),
				recurring("오늘 시작", 9900, today.getDayOfMonth(), today.plusDays(1).toString(), "")).andExpect(status().isOk());
		recurringJob.run();
		assertThat(recurringEntries("future")).isZero();
		assertThat(recurringEntries("later")).isZero(); // 이번 달 결제일이 시작일보다 앞

		as("haru", put("/api/ledger/spaces/sp-owner/recurring/ended"),
				recurring("끝난 구독", 5000, 1, YearMonth.from(today).minusMonths(3).atDay(1).toString(),
						YearMonth.from(today).minusMonths(2).atDay(15).toString())).andExpect(status().isOk());
		assertThat(recurringEntries("ended")).isEqualTo(2); // 끝나는 날까지만

		as("haru", put("/api/ledger/spaces/sp-owner/recurring/x"), recurring("", 1000, 1, "", "")).andExpect(status().isBadRequest());
		as("haru", put("/api/ledger/spaces/sp-owner/recurring/x"), recurring("a", 1000, 32, "", "")).andExpect(status().isBadRequest());
		as("haru", put("/api/ledger/spaces/sp-owner/recurring/x"), recurring("a", 1000, 1, "2026-02-01", "2026-01-01"))
			.andExpect(status().isBadRequest());
		as("lee", put("/api/ledger/spaces/" + team + "/recurring/x"), recurring("a", 1000, 1, "", "")).andExpect(status().isForbidden());
	}

	@Test
	void 누적_저축은_그_달_마지막_날까지의_저축_합계() throws Exception {
		entry("haru", team, "s1", "{\"date\":\"2026-01-10\",\"kind\":\"saving\",\"major\":\"저축·투자\",\"minor\":\"주식/CMA\",\"amount\":1000000}")
			.andExpect(status().isOk());
		entry("haru", team, "s2", "{\"date\":\"2026-02-10\",\"kind\":\"saving\",\"major\":\"저축·투자\",\"minor\":\"청약통장\",\"amount\":100000}")
			.andExpect(status().isOk());
		entry("haru", team, "s3", "{\"date\":\"2026-02-11\",\"kind\":\"saving\",\"major\":\"목적통장\",\"minor\":\"여행통장\",\"amount\":300000}")
			.andExpect(status().isOk());
		entry("haru", team, "s4", "{\"date\":\"2026-03-01\",\"kind\":\"saving\",\"major\":\"목적통장\",\"amount\":999}")
			.andExpect(status().isOk());

		JsonNode t = month("lee", team, "2026-02").get("savingTotals");
		assertThat(t.get("total").asLong()).isEqualTo(1_400_000); // 3월 것은 빼고
		assertThat(t.get("byMajor").get(0).get("major").asString()).isEqualTo("저축·투자");
		assertThat(t.get("byMajor").get(0).get("amount").asLong()).isEqualTo(1_100_000);
		assertThat(t.get("byMajor").get(0).get("minors").get(0).get("minor").asString()).isEqualTo("주식/CMA");
		assertThat(t.get("byMajor").get(1).get("amount").asLong()).isEqualTo(300_000);
	}

	@Test
	void 연간은_달마다_합계를_월_시작일_기준으로_나눈다() throws Exception {
		as("haru", put("/api/ledger/spaces/sp-owner/settings"), "{\"monthStartDay\":25,\"methods\":[],\"categories\":{}}")
			.andExpect(status().isOk());
		entry("haru", "sp-owner", "a", e("2025-12-25", "income", "월급", 3_000_000, "", "")).andExpect(status().isOk()); // 2026년 1월
		entry("haru", "sp-owner", "b", e("2026-01-24", "variable", "식비", 10_000, "카드", "")).andExpect(status().isOk()); // 1월
		entry("haru", "sp-owner", "c", e("2026-01-25", "variable", "식비", 20_000, "카드", "")).andExpect(status().isOk()); // 2월
		entry("haru", "sp-owner", "d", e("2026-02-01", "saving", "목적통장", 500_000, "", "")).andExpect(status().isOk()); // 2월
		entry("haru", "sp-owner", "x", e("2026-12-25", "variable", "식비", 999, "", "")).andExpect(status().isOk()); // 2027년

		JsonNode y = body(mvc.perform(get("/api/ledger/spaces/sp-owner/year").param("year", "2026").with(user("haru")))
			.andExpect(status().isOk()));
		assertThat(y.get("from").asString()).isEqualTo("2025-12-25");
		assertThat(y.get("to").asString()).isEqualTo("2026-12-24");
		assertThat(y.get("months").size()).isEqualTo(12);
		JsonNode jan = y.get("months").get(0);
		assertThat(jan.get("income").asLong()).isEqualTo(3_000_000);
		assertThat(jan.get("expense").asLong()).isEqualTo(10_000);
		JsonNode feb = y.get("months").get(1);
		assertThat(feb.get("expense").asLong()).isEqualTo(20_000);
		assertThat(feb.get("saving").asLong()).isEqualTo(500_000);
		assertThat(y.get("months").get(11).get("count").asInt()).isZero();
		assertThat(y.get("summary").get("expense").asLong()).isEqualTo(30_000);
		assertThat(y.get("summary").get("byMajor").get(0).get("amount").asLong()).isEqualTo(30_000);
		assertThat(y.get("savingByMajor").get(0).get("major").asString()).isEqualTo("목적통장");
		assertThat(y.get("savingTotals").get("total").asLong()).isEqualTo(500_000);

		mvc.perform(get("/api/ledger/spaces/" + team + "/year").param("year", "2026").with(user("haru"))).andExpect(status().isOk());
		mvc.perform(get("/api/ledger/spaces/sp-owner/year").param("year", "2026").with(user("kim"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/ledger/spaces/sp-owner/year").param("year", "1999").with(user("haru"))).andExpect(status().isBadRequest());
	}
}

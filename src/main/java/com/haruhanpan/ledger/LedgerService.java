package com.haruhanpan.ledger;

import com.haruhanpan.account.AppUser;
import com.haruhanpan.common.Ids;
import com.haruhanpan.ledger.LedgerRecurringRepository.Recurring;
import com.haruhanpan.ledger.LedgerRepository.Entry;
import com.haruhanpan.space.Role;
import com.haruhanpan.space.SpaceService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가계부. 장부는 공간 단위이고 권한은 기존 공간 규칙을 따른다.
 * <ul>
 *   <li>보기(viewer): 조회</li>
 *   <li>편집(editor): 내역 추가·수정·삭제, 분류·결제수단·월 시작일 변경</li>
 * </ul>
 * 한 달은 "월 시작일"부터 다음 달 시작일 전날까지다. 시작일이 25일이면 2월 = 1/25 ~ 2/24.
 *
 * <p>정기결제: 결제일(그 달에 없으면 말일)이 되면 내역을 자동으로 만든다 ({@link #runRecurring}).
 * 달마다 기록 표시(runs)를 남겨 한 번만 만들고, 만든 내역을 지워도 다시 만들지 않는다. 시작일 이전 달은 소급하지 않는다.
 */
@Service
public class LedgerService {

	private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
	private static final Set<String> ENTRY_KINDS = Set.of("income", "saving", "fixed", "variable");
	private static final List<String> CATEGORY_KINDS = List.of("income", "saving", "expense");
	private static final long MAX_AMOUNT = 999_999_999_999L;
	private static final int NAME_MAX = 50;
	/** 정기결제 "오늘" 기준 (서버 시간대와 상관없이 한국 날짜) */
	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final LedgerRepository ledger;
	private final LedgerRecurringRepository recurring;
	private final SpaceService spaces;

	public LedgerService(LedgerRepository ledger, LedgerRecurringRepository recurring, SpaceService spaces) {
		this.ledger = ledger;
		this.recurring = recurring;
		this.spaces = spaces;
	}

	static LocalDate today() {
		return LocalDate.now(ZONE);
	}

	/** 가계부 화면을 열 때: 나, 내 공간과 멤버 */
	@Transactional(readOnly = true)
	public Map<String, Object> meta(AppUser me) {
		List<Map<String, Object>> mySpaces = spaces.spacesOf(me);
		var members = spaces.membersOf(mySpaces.stream().map(s -> (String) s.get("id")).toList());
		mySpaces.forEach(s -> s.put("members", members.getOrDefault((String) s.get("id"), List.of())));
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("me", me.toPublic());
		data.put("spaces", mySpaces);
		return data;
	}

	/** 한 달 치: 설정, 분류, 기간, 내역, 요약 */
	@Transactional(readOnly = true)
	public Map<String, Object> month(AppUser me, String spaceId, String month) {
		spaces.require(me, spaceId, Role.VIEWER);
		Map<String, Object> settings = settingsOf(spaceId);
		LocalDate[] period = period(parseMonth(month), (Integer) settings.get("monthStartDay"));
		List<Map<String, Object>> entries = ledger.entries(spaceId, period[0].toString(), period[1].toString());

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("month", month);
		data.put("from", period[0].toString());
		data.put("to", period[1].toString());
		data.put("settings", settings);
		data.put("categories", categoriesOf(spaceId));
		data.put("entries", entries);
		data.put("summary", summarize(entries));
		data.put("recurring", recurring.findBySpace(spaceId));
		List<String> months = new ArrayList<>();
		for (YearMonth ym = YearMonth.from(period[0]); !ym.isAfter(YearMonth.from(period[1])); ym = ym.plusMonths(1)) {
			months.add(ym.toString());
		}
		data.put("recurringRuns", recurring.runs(spaceId, months));
		data.put("savingTotals", savingTotals(ledger.savingTotals(spaceId, period[1].toString())));
		return data;
	}

	/**
	 * 한 해: 달마다 수입·저축·지출 합계, 그 해 전체 요약(대분류별·결제수단별·사람별), 저축 대분류별, 연말 기준 누적 저축.
	 * 달 구분은 월 시작일을 따른다 (시작일 25일이면 1월 = 전해 12/25 ~ 1/24).
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> year(AppUser me, String spaceId, int year) {
		spaces.require(me, spaceId, Role.VIEWER);
		if (year < 2000 || year > 2100) {
			throw new IllegalArgumentException("연도는 2000~2100");
		}
		Map<String, Object> settings = settingsOf(spaceId);
		int startDay = (Integer) settings.get("monthStartDay");
		LocalDate from = period(YearMonth.of(year, 1), startDay)[0];
		LocalDate to = period(YearMonth.of(year, 12), startDay)[1];
		List<Map<String, Object>> entries = ledger.entries(spaceId, from.toString(), to.toString());

		List<Map<String, Object>> months = new ArrayList<>();
		for (int m = 1; m <= 12; m++) {
			LocalDate[] p = period(YearMonth.of(year, m), startDay);
			String f = p[0].toString();
			String t = p[1].toString();
			List<Map<String, Object>> inMonth = entries.stream()
				.filter(e -> ((String) e.get("date")).compareTo(f) >= 0 && ((String) e.get("date")).compareTo(t) <= 0)
				.toList();
			Map<String, Object> sum = summarize(inMonth);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("month", YearMonth.of(year, m).toString());
			row.put("from", f);
			row.put("to", t);
			for (String k : List.of("income", "saving", "expense", "fixed", "variable", "surplus", "savingRate")) {
				row.put(k, sum.get(k));
			}
			row.put("count", inMonth.size());
			months.add(row);
		}
		Map<String, Long> savingByMajor = new LinkedHashMap<>();
		entries.stream().filter(e -> "saving".equals(e.get("kind")))
			.forEach(e -> savingByMajor.merge((String) e.get("major"), (Long) e.get("amount"), Long::sum));

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("year", year);
		data.put("from", from.toString());
		data.put("to", to.toString());
		data.put("settings", settings);
		data.put("categories", categoriesOf(spaceId));
		data.put("months", months);
		data.put("summary", summarize(entries));
		data.put("savingByMajor", ranked(savingByMajor, "major"));
		data.put("savingTotals", savingTotals(ledger.savingTotals(spaceId, to.toString())));
		return data;
	}

	// ───────── 정기결제 ─────────

	/** 저장하고, 시작일 이후 이미 지난 결제일이 있으면 바로 기록한다. @return 저장 후 버전 */
	@Transactional
	public long saveRecurring(AppUser me, String spaceId, String id, Map<String, Object> body) {
		spaces.require(me, spaceId, Role.EDITOR);
		requireId(id);
		Recurring r = toRecurring(spaceId, body);
		Long expected = body.get("version") instanceof Number n && n.longValue() > 0 ? n.longValue() : null;
		long version = recurring.upsert(spaceId, me.id(), id, r, expected, Instant.now().toString());
		runRecurring(id, today());
		return version;
	}

	@Transactional
	public boolean deleteRecurring(AppUser me, String spaceId, String id) {
		spaces.require(me, spaceId, Role.EDITOR);
		requireId(id);
		return recurring.delete(spaceId, id);
	}

	/** 자동 기록 대상 id (사용 중이고 시작일이 지난 것) */
	@Transactional(readOnly = true)
	public List<String> activeRecurringIds(LocalDate today) {
		return recurring.activeIds(today.toString());
	}

	/**
	 * 정기결제 하나에 대해 시작일 ~ today(끝나는 날이 있으면 그날까지) 사이의 결제일 중
	 * 아직 기록하지 않은 달의 내역을 만든다. @return 만든 내역 수
	 */
	@Transactional
	public int runRecurring(String id, LocalDate today) {
		Map<String, Object> r = recurring.find(id).orElse(null);
		if (r == null || !(Boolean) r.get("active")) {
			return 0;
		}
		LocalDate start = LocalDate.parse((String) r.get("startDate"));
		String endText = (String) r.get("endDate");
		LocalDate last = endText.isEmpty() || today.isBefore(LocalDate.parse(endText)) ? today : LocalDate.parse(endText);
		int day = (Integer) r.get("dayOfMonth");
		int created = 0;
		for (YearMonth ym = YearMonth.from(start); !ym.isAfter(YearMonth.from(last)); ym = ym.plusMonths(1)) {
			LocalDate due = dueDate(ym, day);
			if (due.isBefore(start) || due.isAfter(last) || recurring.hasRun(id, ym.toString())) {
				continue;
			}
			String now = Instant.now().toString();
			String entryId = Ids.newId("le");
			recurring.insertRun(id, ym.toString(), entryId, now);
			Entry e = new Entry(due.toString(), (String) r.get("kind"), (String) r.get("major"), (String) r.get("minor"),
					(String) r.get("name"), (Long) r.get("amount"), (String) r.get("method"), (String) r.get("personId"));
			ledger.insertFromRecurring((String) r.get("spaceId"), entryId, e, id, (String) r.get("updatedBy"), now);
			created++;
		}
		return created;
	}

	/** 그 달의 결제일. 결제일이 그 달에 없으면(31일 등) 말일 */
	static LocalDate dueDate(YearMonth ym, int dayOfMonth) {
		return ym.atDay(Math.min(dayOfMonth, ym.lengthOfMonth()));
	}

	/** 저축 누적 {total, byMajor: [{major, amount, minors: [{minor, amount}]}]} (큰 순서) */
	static Map<String, Object> savingTotals(List<Object[]> rows) {
		Map<String, Map<String, Long>> byMajor = new LinkedHashMap<>();
		long total = 0;
		for (Object[] row : rows) {
			long amount = (Long) row[2];
			byMajor.computeIfAbsent((String) row[0], k -> new LinkedHashMap<>()).merge((String) row[1], amount, Long::sum);
			total += amount;
		}
		List<Map<String, Object>> majors = new ArrayList<>();
		byMajor.forEach((major, minors) -> majors.add(Map.of(
				"major", major,
				"amount", minors.values().stream().mapToLong(Long::longValue).sum(),
				"minors", ranked(minors, "minor"))));
		majors.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("amount")).reversed());
		return Map.of("total", total, "byMajor", majors);
	}

	/** @return 저장 후 버전 */
	@Transactional
	public long saveEntry(AppUser me, String spaceId, String id, Map<String, Object> body) {
		spaces.require(me, spaceId, Role.EDITOR);
		requireId(id);
		Entry entry = toEntry(spaceId, body);
		Long expected = body.get("version") instanceof Number n && n.longValue() > 0 ? n.longValue() : null;
		return ledger.upsert(spaceId, me.id(), id, entry, expected, Instant.now().toString());
	}

	@Transactional
	public boolean deleteEntry(AppUser me, String spaceId, String id) {
		spaces.require(me, spaceId, Role.EDITOR);
		requireId(id);
		return ledger.delete(spaceId, id);
	}

	/** 분류·결제수단·월 시작일을 한 번에 저장한다 {monthStartDay, methods: [..], categories: {kind: [{major, minors}]}} */
	@Transactional
	public void saveSettings(AppUser me, String spaceId, Map<String, Object> body) {
		spaces.require(me, spaceId, Role.EDITOR);
		int day = body.get("monthStartDay") instanceof Number n ? n.intValue() : 0;
		if (day < 1 || day > 28) {
			throw new IllegalArgumentException("월 시작일은 1~28일");
		}
		List<String> methods = names(body.get("methods"), "결제수단", 30);
		Map<String, Map<String, List<String>>> categories = new LinkedHashMap<>();
		Map<?, ?> raw = body.get("categories") instanceof Map<?, ?> m ? m : Map.of();
		for (String kind : CATEGORY_KINDS) {
			Map<String, List<String>> majors = new LinkedHashMap<>();
			if (raw.get(kind) instanceof List<?> list) {
				for (Object o : list) {
					if (!(o instanceof Map<?, ?> c)) {
						continue;
					}
					String major = name(c.get("major"), "대분류");
					if (majors.containsKey(major)) {
						throw new IllegalArgumentException("대분류가 겹칩니다: " + major);
					}
					List<String> minors = names(c.get("minors"), "소분류", 30);
					if (String.join("\n", minors).length() > 1000) {
						throw new IllegalArgumentException("소분류가 너무 많습니다: " + major);
					}
					majors.put(major, minors);
				}
			}
			if (majors.size() > 50) {
				throw new IllegalArgumentException("대분류는 구분마다 50개까지");
			}
			categories.put(kind, majors);
		}
		ledger.saveSettings(spaceId, day, methods, categories, me.id(), Instant.now().toString());
	}

	/** 백업용: 공간 하나의 가계부 전체 */
	@Transactional(readOnly = true)
	public Map<String, Object> exportSpace(String spaceId) {
		Map<String, Object> data = new LinkedHashMap<>();
		ledger.settings(spaceId).ifPresent(s -> {
			data.put("settings", s);
			data.put("categories", toApi(ledger.categories(spaceId)));
		});
		data.put("entries", ledger.allEntries(spaceId));
		data.put("recurring", recurring.findBySpace(spaceId));
		return data;
	}

	// ───────── 기간·요약 ─────────

	/** month 의 [시작일, 마지막 날]. startDay 가 1 이면 달력 그대로 */
	static LocalDate[] period(YearMonth month, int startDay) {
		if (startDay <= 1) {
			return new LocalDate[] {month.atDay(1), month.atEndOfMonth()};
		}
		LocalDate from = month.minusMonths(1).atDay(startDay);
		LocalDate to = month.atDay(startDay).minusDays(1);
		return new LocalDate[] {from, to};
	}

	/**
	 * 수입·저축·지출 합계, 잉여현금(수입 − 저축 − 지출), 저축률(저축 ÷ 수입, 수입이 없으면 null),
	 * 지출의 대분류별·결제수단별 합계, 사람별 수입·지출 (사람이 비어 있으면 personId "" = 공동).
	 */
	static Map<String, Object> summarize(List<Map<String, Object>> entries) {
		long income = 0;
		long saving = 0;
		long fixed = 0;
		long variable = 0;
		Map<String, Long> byMajor = new LinkedHashMap<>();
		Map<String, Long> byMethod = new LinkedHashMap<>();
		Map<String, long[]> byPerson = new LinkedHashMap<>(); // [수입, 지출]
		for (Map<String, Object> e : entries) {
			long amount = (Long) e.get("amount");
			String kind = (String) e.get("kind");
			long[] person = byPerson.computeIfAbsent((String) e.get("personId"), k -> new long[2]);
			switch (kind) {
				case "income" -> {
					income += amount;
					person[0] += amount;
				}
				case "saving" -> saving += amount;
				default -> {
					if ("fixed".equals(kind)) {
						fixed += amount;
					} else {
						variable += amount;
					}
					person[1] += amount;
					byMajor.merge((String) e.get("major"), amount, Long::sum);
					byMethod.merge((String) e.get("method"), amount, Long::sum);
				}
			}
		}
		long expense = fixed + variable;
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("income", income);
		s.put("saving", saving);
		s.put("expense", expense);
		s.put("fixed", fixed);
		s.put("variable", variable);
		s.put("surplus", income - saving - expense);
		s.put("savingRate", income > 0 ? (double) saving / income : null);
		s.put("byMajor", ranked(byMajor, "major"));
		s.put("byMethod", ranked(byMethod, "method"));
		s.put("byPerson", byPerson.entrySet().stream()
			.filter(p -> p.getValue()[0] > 0 || p.getValue()[1] > 0)
			.map(p -> Map.<String, Object>of("personId", p.getKey(), "income", p.getValue()[0], "expense", p.getValue()[1]))
			.toList());
		return s;
	}

	private static List<Map<String, Object>> ranked(Map<String, Long> sums, String key) {
		return sums.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.map(e -> Map.<String, Object>of(key, e.getKey(), "amount", e.getValue()))
			.toList();
	}

	// ───────── 설정 ─────────

	private Map<String, Object> settingsOf(String spaceId) {
		return ledger.settings(spaceId).orElseGet(() -> {
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("monthStartDay", LedgerDefaults.MONTH_START_DAY);
			s.put("methods", LedgerDefaults.METHODS);
			return s;
		});
	}

	/** 저장된 분류, 한 번도 저장하지 않았으면 기본값. 화면 형식 {kind: [{major, minors}]} */
	private Map<String, Object> categoriesOf(String spaceId) {
		boolean saved = ledger.settings(spaceId).isPresent();
		return toApi(saved ? ledger.categories(spaceId) : LedgerDefaults.categories());
	}

	private static Map<String, Object> toApi(Map<String, Map<String, List<String>>> categories) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (String kind : CATEGORY_KINDS) {
			List<Map<String, Object>> list = new ArrayList<>();
			categories.getOrDefault(kind, Map.of())
				.forEach((major, minors) -> list.add(Map.of("major", major, "minors", minors)));
			result.put(kind, list);
		}
		return result;
	}

	// ───────── 입력 검사 ─────────

	private Entry toEntry(String spaceId, Map<String, Object> body) {
		String date = text(body.get("date"));
		try {
			LocalDate.parse(date);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("날짜 형식이 아닙니다");
		}
		String kind = text(body.get("kind"));
		if (!ENTRY_KINDS.contains(kind)) {
			throw new IllegalArgumentException("구분은 수입·저축·고정지출·변동지출 중 하나");
		}
		long amount = body.get("amount") instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())
				? n.longValue() : 0;
		if (amount <= 0 || amount > MAX_AMOUNT) {
			throw new IllegalArgumentException("금액은 1원 이상 정수");
		}
		String major = name(body.get("major"), "대분류");
		String minor = optional(body.get("minor"), "소분류", NAME_MAX);
		String memo = optional(body.get("memo"), "사용내역", 500);
		String method = optional(body.get("method"), "결제수단", NAME_MAX);
		String person = text(body.get("personId"));
		if (!person.isEmpty() && !memberIds(spaceId).contains(person)) {
			throw new IllegalArgumentException("이 공간의 멤버가 아닙니다");
		}
		return new Entry(date, kind, major, minor, memo, amount, method, person);
	}

	private Recurring toRecurring(String spaceId, Map<String, Object> body) {
		String name = text(body.get("name"));
		if (name.isEmpty() || name.length() > 100) {
			throw new IllegalArgumentException("이름은 1~100자");
		}
		String kind = text(body.get("kind"));
		if (kind.isEmpty()) {
			kind = "fixed";
		}
		if (!ENTRY_KINDS.contains(kind)) {
			throw new IllegalArgumentException("구분은 수입·저축·고정지출·변동지출 중 하나");
		}
		long amount = body.get("amount") instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())
				? n.longValue() : 0;
		if (amount <= 0 || amount > MAX_AMOUNT) {
			throw new IllegalArgumentException("금액은 1원 이상 정수");
		}
		int day = body.get("dayOfMonth") instanceof Number n ? n.intValue() : 0;
		if (day < 1 || day > 31) {
			throw new IllegalArgumentException("결제일은 1~31일");
		}
		String start = text(body.get("startDate"));
		if (start.isEmpty()) {
			start = today().toString();
		}
		String end = text(body.get("endDate"));
		try {
			LocalDate.parse(start);
			if (!end.isEmpty() && LocalDate.parse(end).isBefore(LocalDate.parse(start))) {
				throw new IllegalArgumentException("끝나는 날은 시작일 이후");
			}
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("날짜 형식이 아닙니다");
		}
		String person = text(body.get("personId"));
		if (!person.isEmpty() && !memberIds(spaceId).contains(person)) {
			throw new IllegalArgumentException("이 공간의 멤버가 아닙니다");
		}
		boolean active = !(body.get("active") instanceof Boolean b) || b;
		return new Recurring(name, kind, name(body.get("major"), "대분류"), optional(body.get("minor"), "소분류", NAME_MAX),
				amount, optional(body.get("method"), "결제수단", NAME_MAX), person, day, start, end, active);
	}

	private Set<String> memberIds(String spaceId) {
		return spaces.membersOf(List.of(spaceId)).getOrDefault(spaceId, List.of()).stream()
			.map(m -> (String) m.get("id")).collect(Collectors.toSet());
	}

	private static YearMonth parseMonth(String month) {
		try {
			return YearMonth.parse(month == null ? "" : month);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("월 형식은 YYYY-MM");
		}
	}

	private static void requireId(String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw new IllegalArgumentException("잘못된 id");
		}
	}

	/** 비어 있으면 안 되는 이름 (1~50자) */
	private static String name(Object value, String label) {
		String s = text(value);
		if (s.isEmpty() || s.length() > NAME_MAX || s.contains("\n")) {
			throw new IllegalArgumentException(label + "은(는) 1~" + NAME_MAX + "자");
		}
		return s;
	}

	private static String optional(Object value, String label, int max) {
		String s = text(value);
		if (s.length() > max) {
			throw new IllegalArgumentException(label + "은(는) " + max + "자까지");
		}
		return s;
	}

	/** 이름 목록: 빈 값은 버리고, 겹치면 하나만 */
	private static List<String> names(Object value, String label, int maxCount) {
		List<String> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		if (value instanceof List<?> list) {
			for (Object o : list) {
				String s = text(o);
				if (!s.isEmpty() && seen.add(s)) {
					result.add(name(s, label));
				}
			}
		}
		if (result.size() > maxCount) {
			throw new IllegalArgumentException(label + "은(는) " + maxCount + "개까지");
		}
		return result;
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().strip();
	}
}

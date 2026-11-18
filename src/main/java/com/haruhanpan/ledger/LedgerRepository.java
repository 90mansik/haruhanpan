package com.haruhanpan.ledger;

import com.haruhanpan.common.ApiErrors.Conflict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 가계부 설정·분류·내역 저장. 모든 쿼리는 space_id 로 범위를 묶는다. */
@Repository
public class LedgerRepository {

	private static final String ENTRY_COLUMNS = "id, entry_date, kind, major, minor, memo, amount, method, person_id,"
			+ " created_by, updated_by, created_at, updated_at, version, recurring_id";

	private final JdbcClient jdbc;

	public LedgerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ───────── 설정·분류 ─────────

	/** 저장된 설정 {monthStartDay, methods}. 한 번도 저장하지 않았으면 empty */
	public Optional<Map<String, Object>> settings(String spaceId) {
		return jdbc.sql("SELECT month_start_day, methods FROM ledger_settings WHERE space_id = :sid")
			.param("sid", spaceId)
			.query((rs, n) -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("monthStartDay", rs.getInt("month_start_day"));
				s.put("methods", lines(rs.getString("methods")));
				return s;
			})
			.optional();
	}

	/** kind → 대분류 → 소분류 (sort_no 순) */
	public Map<String, Map<String, List<String>>> categories(String spaceId) {
		Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
		jdbc.sql("SELECT kind, major, minors FROM ledger_categories WHERE space_id = :sid ORDER BY kind, sort_no")
			.param("sid", spaceId)
			.query(rs -> {
				result.computeIfAbsent(rs.getString("kind"), k -> new LinkedHashMap<>())
					.put(rs.getString("major"), lines(rs.getString("minors")));
			});
		return result;
	}

	public void saveSettings(String spaceId, int monthStartDay, List<String> methods,
			Map<String, Map<String, List<String>>> categories, String userId, String now) {
		jdbc.sql("DELETE FROM ledger_settings WHERE space_id = :sid").param("sid", spaceId).update();
		jdbc.sql("""
				INSERT INTO ledger_settings (space_id, month_start_day, methods, updated_by, updated_at)
				VALUES (:sid, :day, :methods, :uid, :now)
				""")
			.param("sid", spaceId).param("day", monthStartDay).param("methods", String.join("\n", methods))
			.param("uid", userId).param("now", now)
			.update();
		jdbc.sql("DELETE FROM ledger_categories WHERE space_id = :sid").param("sid", spaceId).update();
		for (var kind : categories.entrySet()) {
			int sort = 0;
			for (var major : kind.getValue().entrySet()) {
				jdbc.sql("""
						INSERT INTO ledger_categories (space_id, kind, major, minors, sort_no)
						VALUES (:sid, :kind, :major, :minors, :sort)
						""")
					.param("sid", spaceId).param("kind", kind.getKey()).param("major", major.getKey())
					.param("minors", String.join("\n", major.getValue())).param("sort", sort++)
					.update();
			}
		}
	}

	// ───────── 내역 ─────────

	/** from ~ to (둘 다 포함, YYYY-MM-DD) 내역. 최근 날짜가 먼저 */
	public List<Map<String, Object>> entries(String spaceId, String from, String to) {
		return jdbc.sql("SELECT " + ENTRY_COLUMNS + " FROM ledger_entries"
				+ " WHERE space_id = :sid AND entry_date BETWEEN :from AND :to"
				+ " ORDER BY entry_date DESC, created_at DESC, id")
			.param("sid", spaceId).param("from", from).param("to", to)
			.query((rs, n) -> readEntry(rs))
			.list();
	}

	public List<Map<String, Object>> allEntries(String spaceId) {
		return jdbc.sql("SELECT " + ENTRY_COLUMNS + " FROM ledger_entries WHERE space_id = :sid ORDER BY entry_date, id")
			.param("sid", spaceId)
			.query((rs, n) -> readEntry(rs))
			.list();
	}

	public Optional<Long> version(String spaceId, String id) {
		return jdbc.sql("SELECT version FROM ledger_entries WHERE id = :id AND space_id = :sid")
			.param("id", id).param("sid", spaceId).query(Long.class).optional();
	}

	/**
	 * 있으면 수정, 없으면 추가하고 새 버전을 돌려준다. expectedVersion 이 서버 버전과 다르면 {@link Conflict}.
	 * 다른 공간에 같은 id 가 있으면 INSERT 가 PK 위반으로 실패한다 (다른 공간의 내역을 덮어쓸 수 없음).
	 */
	public long upsert(String spaceId, String userId, String id, Entry e, Long expectedVersion, String now) {
		Optional<Long> current = version(spaceId, id);
		if (current.isEmpty()) {
			jdbc.sql("""
					INSERT INTO ledger_entries (id, space_id, entry_date, kind, major, minor, memo, amount, method,
					  person_id, created_by, updated_by, created_at, updated_at, version)
					VALUES (:id, :sid, :date, :kind, :major, :minor, :memo, :amount, :method,
					  :person, :uid, :uid, :now, :now, 1)
					""")
				.params(params(spaceId, userId, id, e, now))
				.update();
			return 1;
		}
		long cur = current.get();
		if (expectedVersion != null && expectedVersion != cur) {
			throw new Conflict(cur);
		}
		int updated = jdbc.sql("""
				UPDATE ledger_entries
				   SET entry_date = :date, kind = :kind, major = :major, minor = :minor, memo = :memo,
				       amount = :amount, method = :method, person_id = :person,
				       updated_by = :uid, updated_at = :now, version = version + 1
				 WHERE id = :id AND space_id = :sid AND version = :cur
				""")
			.params(params(spaceId, userId, id, e, now)).param("cur", cur)
			.update();
		if (updated == 0) {
			throw new Conflict(version(spaceId, id).orElse(0L)); // 그 사이 다른 요청이 먼저 바꿈
		}
		return cur + 1;
	}

	public boolean delete(String spaceId, String id) {
		return jdbc.sql("DELETE FROM ledger_entries WHERE id = :id AND space_id = :sid")
			.param("id", id).param("sid", spaceId).update() > 0;
	}

	/** 정기결제가 만든 내역 (작성자 = 정기결제를 마지막으로 고친 사람) */
	public void insertFromRecurring(String spaceId, String id, Entry e, String recurringId, String userId, String now) {
		jdbc.sql("""
				INSERT INTO ledger_entries (id, space_id, entry_date, kind, major, minor, memo, amount, method,
				  person_id, created_by, updated_by, created_at, updated_at, version, recurring_id)
				VALUES (:id, :sid, :date, :kind, :major, :minor, :memo, :amount, :method,
				  :person, :uid, :uid, :now, :now, 1, :rid)
				""")
			.params(params(spaceId, userId, id, e, now)).param("rid", recurringId)
			.update();
	}

	/** to 까지 저축으로 기록한 금액의 대분류·소분류별 합계 [major, minor, amount] */
	public List<Object[]> savingTotals(String spaceId, String to) {
		return jdbc.sql("""
				SELECT major, minor, SUM(amount) AS total FROM ledger_entries
				 WHERE space_id = :sid AND kind = 'saving' AND entry_date <= :to
				 GROUP BY major, minor
				""")
			.param("sid", spaceId).param("to", to)
			.query((rs, n) -> new Object[] {nvl(rs.getString("major")), nvl(rs.getString("minor")), rs.getLong("total")})
			.list();
	}

	private static Map<String, Object> params(String spaceId, String userId, String id, Entry e, String now) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("id", id);
		p.put("sid", spaceId);
		p.put("date", e.date());
		p.put("kind", e.kind());
		p.put("major", e.major());
		p.put("minor", e.minor());
		p.put("memo", e.memo());
		p.put("amount", e.amount());
		p.put("method", e.method());
		p.put("person", e.personId());
		p.put("uid", userId);
		p.put("now", now);
		return p;
	}

	private static Map<String, Object> readEntry(ResultSet rs) throws SQLException {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", rs.getString("id"));
		m.put("date", rs.getString("entry_date"));
		m.put("kind", rs.getString("kind"));
		m.put("major", nvl(rs.getString("major")));
		m.put("minor", nvl(rs.getString("minor")));
		m.put("memo", nvl(rs.getString("memo")));
		m.put("amount", rs.getLong("amount"));
		m.put("method", nvl(rs.getString("method")));
		m.put("personId", nvl(rs.getString("person_id")));
		m.put("createdBy", nvl(rs.getString("created_by")));
		m.put("updatedBy", nvl(rs.getString("updated_by")));
		m.put("createdAt", nvl(rs.getString("created_at")));
		m.put("updatedAt", nvl(rs.getString("updated_at")));
		m.put("version", rs.getLong("version"));
		m.put("recurringId", nvl(rs.getString("recurring_id")));
		return m;
	}

	private static List<String> lines(String s) {
		List<String> list = new ArrayList<>();
		if (s != null) {
			for (String line : s.split("\n")) {
				if (!line.isBlank()) {
					list.add(line);
				}
			}
		}
		return list;
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	/** 검사를 마친 내역 한 건 */
	public record Entry(String date, String kind, String major, String minor, String memo, long amount,
			String method, String personId) {
	}
}

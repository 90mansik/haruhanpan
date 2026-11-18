package com.haruhanpan.ledger;

import com.haruhanpan.common.ApiErrors.Conflict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 정기결제와 "이번 달 기록함" 표시(runs). */
@Repository
public class LedgerRecurringRepository {

	private static final String COLUMNS = "id, space_id, name, kind, major, minor, amount, method, person_id, day_of_month,"
			+ " start_date, end_date, active, created_by, updated_by, version";

	private final JdbcClient jdbc;

	public LedgerRecurringRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findBySpace(String spaceId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM ledger_recurring WHERE space_id = :sid ORDER BY day_of_month, name, id")
			.param("sid", spaceId).query((rs, n) -> read(rs)).list();
	}

	public Optional<Map<String, Object>> find(String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM ledger_recurring WHERE id = :id").param("id", id)
			.query((rs, n) -> read(rs)).optional();
	}

	/** 자동 기록 대상: 사용 중이고 시작일이 지난 것 */
	public List<String> activeIds(String today) {
		return jdbc.sql("SELECT id FROM ledger_recurring WHERE active = 1 AND start_date <= :today ORDER BY id")
			.param("today", today).query(String.class).list();
	}

	public Optional<Long> version(String spaceId, String id) {
		return jdbc.sql("SELECT version FROM ledger_recurring WHERE id = :id AND space_id = :sid")
			.param("id", id).param("sid", spaceId).query(Long.class).optional();
	}

	/** 있으면 수정, 없으면 추가. expectedVersion 이 서버 버전과 다르면 {@link Conflict} */
	public long upsert(String spaceId, String userId, String id, Recurring r, Long expectedVersion, String now) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("id", id);
		p.put("sid", spaceId);
		p.put("name", r.name());
		p.put("kind", r.kind());
		p.put("major", r.major());
		p.put("minor", r.minor());
		p.put("amount", r.amount());
		p.put("method", r.method());
		p.put("person", r.personId());
		p.put("day", r.dayOfMonth());
		p.put("start", r.startDate());
		p.put("end", r.endDate());
		p.put("active", r.active() ? 1 : 0);
		p.put("uid", userId);
		p.put("now", now);
		Optional<Long> current = version(spaceId, id);
		if (current.isEmpty()) {
			jdbc.sql("""
					INSERT INTO ledger_recurring (id, space_id, name, kind, major, minor, amount, method, person_id,
					  day_of_month, start_date, end_date, active, created_by, updated_by, created_at, updated_at, version)
					VALUES (:id, :sid, :name, :kind, :major, :minor, :amount, :method, :person,
					  :day, :start, :end, :active, :uid, :uid, :now, :now, 1)
					""")
				.params(p).update();
			return 1;
		}
		long cur = current.get();
		if (expectedVersion != null && expectedVersion != cur) {
			throw new Conflict(cur);
		}
		p.put("cur", cur);
		int updated = jdbc.sql("""
				UPDATE ledger_recurring
				   SET name = :name, kind = :kind, major = :major, minor = :minor, amount = :amount, method = :method,
				       person_id = :person, day_of_month = :day, start_date = :start, end_date = :end, active = :active,
				       updated_by = :uid, updated_at = :now, version = version + 1
				 WHERE id = :id AND space_id = :sid AND version = :cur
				""")
			.params(p).update();
		if (updated == 0) {
			throw new Conflict(version(spaceId, id).orElse(0L));
		}
		return cur + 1;
	}

	/** 정기결제와 기록 표시를 지운다. 이미 만든 내역은 남긴다 */
	public boolean delete(String spaceId, String id) {
		if (jdbc.sql("DELETE FROM ledger_recurring WHERE id = :id AND space_id = :sid")
			.param("id", id).param("sid", spaceId).update() == 0) {
			return false;
		}
		jdbc.sql("DELETE FROM ledger_recurring_runs WHERE recurring_id = :id").param("id", id).update();
		return true;
	}

	public void deleteAllIn(String spaceId) {
		jdbc.sql("DELETE FROM ledger_recurring_runs WHERE recurring_id IN (SELECT id FROM ledger_recurring WHERE space_id = :sid)")
			.param("sid", spaceId).update();
		jdbc.sql("DELETE FROM ledger_recurring WHERE space_id = :sid").param("sid", spaceId).update();
	}

	public boolean hasRun(String recurringId, String month) {
		return jdbc.sql("SELECT COUNT(*) FROM ledger_recurring_runs WHERE recurring_id = :id AND run_month = :m")
			.param("id", recurringId).param("m", month).query(Integer.class).single() > 0;
	}

	public void insertRun(String recurringId, String month, String entryId, String now) {
		jdbc.sql("INSERT INTO ledger_recurring_runs (recurring_id, run_month, entry_id, created_at) VALUES (:id, :m, :e, :now)")
			.param("id", recurringId).param("m", month).param("e", entryId).param("now", now).update();
	}

	/** 이 공간 정기결제의 기록 표시 중 months 에 해당하는 것 [{recurringId, month, entryId}] */
	public List<Map<String, Object>> runs(String spaceId, Collection<String> months) {
		return jdbc.sql("""
				SELECT r.recurring_id, r.run_month, r.entry_id FROM ledger_recurring_runs r
				  JOIN ledger_recurring c ON c.id = r.recurring_id
				 WHERE c.space_id = :sid AND r.run_month IN (:months)
				""")
			.param("sid", spaceId).param("months", months)
			.query((rs, n) -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("recurringId", rs.getString("recurring_id"));
				m.put("month", rs.getString("run_month"));
				m.put("entryId", nvl(rs.getString("entry_id")));
				return m;
			})
			.list();
	}

	private static Map<String, Object> read(ResultSet rs) throws SQLException {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", rs.getString("id"));
		m.put("spaceId", rs.getString("space_id"));
		m.put("name", nvl(rs.getString("name")));
		m.put("kind", rs.getString("kind"));
		m.put("major", nvl(rs.getString("major")));
		m.put("minor", nvl(rs.getString("minor")));
		m.put("amount", rs.getLong("amount"));
		m.put("method", nvl(rs.getString("method")));
		m.put("personId", nvl(rs.getString("person_id")));
		m.put("dayOfMonth", rs.getInt("day_of_month"));
		m.put("startDate", rs.getString("start_date"));
		m.put("endDate", nvl(rs.getString("end_date")));
		m.put("active", rs.getInt("active") == 1);
		m.put("createdBy", nvl(rs.getString("created_by")));
		m.put("updatedBy", nvl(rs.getString("updated_by")));
		m.put("version", rs.getLong("version"));
		return m;
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	/** 검사를 마친 정기결제 */
	public record Recurring(String name, String kind, String major, String minor, long amount, String method,
			String personId, int dayOfMonth, String startDate, String endDate, boolean active) {
	}
}

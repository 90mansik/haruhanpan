package com.haruhanpan.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 루틴 순서·루틴 체크(사람별)·휴일(공휴일 + 공간별 휴무일). */
@Repository
public class RoutineRepository {

	/** 공휴일 범위 표시. 공간별 휴무일은 scope = space_id */
	public static final String PUBLIC = "*";

	private final JdbcClient jdbc;

	public RoutineRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ───────── 루틴 순서 ─────────

	public void setOrder(String spaceId, List<String> ids) {
		for (int i = 0; i < ids.size(); i++) {
			jdbc.sql("UPDATE routines SET sort_order = :ord WHERE id = :id AND space_id = :sid")
				.param("ord", i + 1).param("id", ids.get(i)).param("sid", spaceId)
				.update();
		}
	}

	public boolean routineInSpace(String spaceId, String routineId) {
		return jdbc.sql("SELECT COUNT(*) FROM routines WHERE id = :id AND space_id = :sid")
			.param("id", routineId).param("sid", spaceId).query(Integer.class).single() > 0;
	}

	// ───────── 루틴 체크: [{date, routineId, userId}] ─────────

	public List<Map<String, Object>> findChecks(Collection<String> spaceIds, String since) {
		if (spaceIds.isEmpty()) {
			return List.of();
		}
		List<Map<String, Object>> list = new ArrayList<>();
		jdbc.sql("""
				SELECT c.log_date, c.routine_id, c.user_id
				  FROM routine_checks c JOIN routines r ON r.id = c.routine_id
				 WHERE r.space_id IN (:ids) AND c.log_date >= :since
				 ORDER BY c.log_date
				""")
			.param("ids", spaceIds).param("since", since)
			.query(rs -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("date", rs.getString(1));
				m.put("routineId", rs.getString(2));
				m.put("userId", rs.getString(3));
				list.add(m);
			});
		return list;
	}

	public void setCheck(String date, String routineId, String userId, boolean checked) {
		jdbc.sql("DELETE FROM routine_checks WHERE log_date = :d AND routine_id = :r AND user_id = :u")
			.param("d", date).param("r", routineId).param("u", userId).update();
		if (checked) {
			jdbc.sql("INSERT INTO routine_checks (log_date, routine_id, user_id) VALUES (:d, :r, :u)")
				.param("d", date).param("r", routineId).param("u", userId).update();
		}
	}

	public void deleteChecksOf(String routineId) {
		jdbc.sql("DELETE FROM routine_checks WHERE routine_id = :r").param("r", routineId).update();
	}

	public void deleteChecksIn(String spaceId) {
		jdbc.sql("DELETE FROM routine_checks WHERE routine_id IN (SELECT id FROM routines WHERE space_id = :sid)")
			.param("sid", spaceId).update();
	}

	// ───────── 휴일 ─────────

	/** scope 별 휴일: scope → {date: name} */
	public Map<String, Map<String, String>> findDayOffs(Collection<String> scopes) {
		Map<String, Map<String, String>> result = new LinkedHashMap<>();
		if (scopes.isEmpty()) {
			return result;
		}
		jdbc.sql("SELECT scope, hdate, name FROM day_offs WHERE scope IN (:scopes) ORDER BY hdate")
			.param("scopes", scopes)
			.query(rs -> {
				String name = rs.getString(3);
				result.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>()).put(rs.getString(2), name == null ? "" : name);
			});
		return result;
	}

	public void setDayOff(String scope, String date, String name) {
		deleteDayOff(scope, date);
		jdbc.sql("INSERT INTO day_offs (scope, hdate, name) VALUES (:s, :d, :n)")
			.param("s", scope).param("d", date).param("n", name).update();
	}

	public boolean deleteDayOff(String scope, String date) {
		return jdbc.sql("DELETE FROM day_offs WHERE scope = :s AND hdate = :d").param("s", scope).param("d", date).update() > 0;
	}

	public void deleteDayOffsIn(String scope) {
		jdbc.sql("DELETE FROM day_offs WHERE scope = :s").param("s", scope).update();
	}
}

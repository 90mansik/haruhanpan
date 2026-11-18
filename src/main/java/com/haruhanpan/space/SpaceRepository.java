package com.haruhanpan.space;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SpaceRepository {

	/** 공간을 지울 때 함께 지울 데이터 테이블 */
	private static final List<String> DATA_TABLES = List.of("todos", "events", "notes", "snippets");

	private final JdbcClient jdbc;

	public SpaceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 내가 속한 공간들 (개인 공간이 맨 앞) */
	public List<Map<String, Object>> spacesOf(String userId) {
		return jdbc.sql("""
				SELECT s.id, s.name, s.kind, s.color, m.role
				  FROM spaces s JOIN space_members m ON m.space_id = s.id
				 WHERE m.user_id = :uid
				 ORDER BY CASE s.kind WHEN 'personal' THEN 0 ELSE 1 END, s.created_at, s.id
				""")
			.param("uid", userId)
			.query((rs, n) -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("id", rs.getString("id"));
				s.put("name", nvl(rs.getString("name")));
				s.put("kind", rs.getString("kind"));
				s.put("color", nvl(rs.getString("color")));
				s.put("role", rs.getString("role"));
				return s;
			}).list();
	}

	/** 모든 공간 (백업용) */
	public List<Map<String, Object>> allSpaces() {
		return jdbc.sql("SELECT id, name, kind FROM spaces ORDER BY created_at, id")
			.query((rs, n) -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("id", rs.getString(1));
				s.put("name", nvl(rs.getString(2)));
				s.put("kind", rs.getString(3));
				return s;
			}).list();
	}

	/** 여러 공간의 멤버 목록: spaceId → [{id, name, role}] */
	public Map<String, List<Map<String, Object>>> membersOf(Collection<String> spaceIds) {
		Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
		if (spaceIds.isEmpty()) {
			return result;
		}
		jdbc.sql("""
				SELECT m.space_id, u.id, u.name, m.role
				  FROM space_members m JOIN users u ON u.id = m.user_id
				 WHERE m.space_id IN (:ids)
				 ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'editor' THEN 1 ELSE 2 END, u.name
				""")
			.param("ids", spaceIds)
			.query(rs -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", rs.getString("id"));
				m.put("name", nvl(rs.getString("name")));
				m.put("role", rs.getString("role"));
				result.computeIfAbsent(rs.getString("space_id"), k -> new ArrayList<>()).add(m);
			});
		return result;
	}

	public Optional<Access> access(String spaceId, String userId) {
		return jdbc.sql("""
				SELECT s.kind, m.role FROM spaces s JOIN space_members m ON m.space_id = s.id
				 WHERE s.id = :sid AND m.user_id = :uid
				""")
			.param("sid", spaceId).param("uid", userId)
			.query((rs, n) -> new Access(spaceId, rs.getString("kind"), Role.of(rs.getString("role"))))
			.optional();
	}

	public Optional<String> nameOf(String spaceId) {
		return jdbc.sql("SELECT name FROM spaces WHERE id = :id").param("id", spaceId).query(String.class).optional();
	}

	public void insertSpace(String id, String name, String kind, String color, String at) {
		jdbc.sql("INSERT INTO spaces (id, name, kind, color, created_at) VALUES (:id, :name, :kind, :color, :at)")
			.param("id", id).param("name", name).param("kind", kind).param("color", color).param("at", at)
			.update();
	}

	public void updateSpace(String id, String name, String color) {
		jdbc.sql("UPDATE spaces SET name = :name, color = :color WHERE id = :id")
			.param("name", name).param("color", color).param("id", id).update();
	}

	public void addMember(String spaceId, String userId, Role role, String at) {
		jdbc.sql("INSERT INTO space_members (space_id, user_id, role, joined_at) VALUES (:sid, :uid, :role, :at)")
			.param("sid", spaceId).param("uid", userId).param("role", role.db()).param("at", at)
			.update();
	}

	public void setRole(String spaceId, String userId, Role role) {
		jdbc.sql("UPDATE space_members SET role = :role WHERE space_id = :sid AND user_id = :uid")
			.param("role", role.db()).param("sid", spaceId).param("uid", userId).update();
	}

	public boolean removeMember(String spaceId, String userId) {
		return jdbc.sql("DELETE FROM space_members WHERE space_id = :sid AND user_id = :uid")
			.param("sid", spaceId).param("uid", userId).update() > 0;
	}

	public int countOwners(String spaceId) {
		return jdbc.sql("SELECT COUNT(*) FROM space_members WHERE space_id = :sid AND role = 'owner'")
			.param("sid", spaceId).query(Integer.class).single();
	}

	/** 공간과 그 안의 데이터를 모두 지운다 */
	public void deleteCascade(String spaceId) {
		jdbc.sql("DELETE FROM routine_checks WHERE routine_id IN (SELECT id FROM routines WHERE space_id = :sid)")
			.param("sid", spaceId).update();
		jdbc.sql("DELETE FROM routines WHERE space_id = :sid").param("sid", spaceId).update();
		for (String table : DATA_TABLES) {
			jdbc.sql("DELETE FROM " + table + " WHERE space_id = :sid").param("sid", spaceId).update();
		}
		jdbc.sql("DELETE FROM day_offs WHERE scope = :sid").param("sid", spaceId).update();
		jdbc.sql("DELETE FROM ledger_recurring_runs WHERE recurring_id IN (SELECT id FROM ledger_recurring WHERE space_id = :sid)")
			.param("sid", spaceId).update();
		for (String table : List.of("ledger_entries", "ledger_recurring", "ledger_categories", "ledger_settings")) {
			jdbc.sql("DELETE FROM " + table + " WHERE space_id = :sid").param("sid", spaceId).update();
		}
		jdbc.sql("DELETE FROM activity WHERE space_id = :sid").param("sid", spaceId).update();
		jdbc.sql("DELETE FROM invites WHERE space_id = :sid").param("sid", spaceId).update();
		jdbc.sql("DELETE FROM space_members WHERE space_id = :sid").param("sid", spaceId).update();
		jdbc.sql("DELETE FROM spaces WHERE id = :sid").param("sid", spaceId).update();
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}
}

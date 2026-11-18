package com.haruhanpan.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

	private static final String COLUMNS = "id, login, name, password_hash, admin, disabled, password_set, approved";

	private final JdbcClient jdbc;

	public UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<AppUser> findByLogin(String login) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE login = :login")
			.param("login", login == null ? "" : login.toLowerCase())
			.query(UserRepository::map).optional();
	}

	public Optional<AppUser> findById(String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id").param("id", id)
			.query(UserRepository::map).optional();
	}

	public boolean loginTaken(String login) {
		return jdbc.sql("SELECT COUNT(*) FROM users WHERE login = :login").param("login", login)
			.query(Integer.class).single() > 0;
	}

	public void insert(String id, String login, String name, String passwordHash, boolean passwordSet, boolean approved,
			String createdAt) {
		jdbc.sql("INSERT INTO users (id, login, name, password_hash, admin, disabled, password_set, approved, created_at) "
				+ "VALUES (:id, :login, :name, :hash, 0, 0, :pset, :approved, :at)")
			.param("id", id).param("login", login.toLowerCase()).param("name", name)
			.param("hash", passwordHash).param("pset", passwordSet ? 1 : 0).param("approved", approved ? 1 : 0)
			.param("at", createdAt)
			.update();
	}

	public void updateName(String id, String name) {
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
	}

	/** 본인이 정한 비밀번호로 바꾼다 (password_set = 1) */
	public void updatePassword(String id, String passwordHash) {
		jdbc.sql("UPDATE users SET password_hash = :hash, password_set = 1 WHERE id = :id")
			.param("hash", passwordHash).param("id", id).update();
	}

	public void approve(String id) {
		jdbc.sql("UPDATE users SET approved = 1 WHERE id = :id").param("id", id).update();
	}

	/** 승인 대기 중인 사용자 (Google 이메일 포함, 오래된 순) */
	public List<Map<String, Object>> findPending() {
		return jdbc.sql("""
				SELECT u.id, u.login, u.name, u.created_at, i.email
				  FROM users u LEFT JOIN user_identities i ON i.user_id = u.id
				 WHERE u.approved = 0
				 ORDER BY u.created_at
				""")
			.query((rs, n) -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", rs.getString(1));
				m.put("login", rs.getString(2));
				m.put("name", nvl(rs.getString(3)));
				m.put("createdAt", nvl(rs.getString(4)));
				m.put("email", nvl(rs.getString(5)));
				return m;
			}).list();
	}

	/** 이 시각보다 먼저 가입하고 아직 승인되지 않은 사용자 id */
	public List<String> pendingBefore(String createdBefore) {
		return jdbc.sql("SELECT id FROM users WHERE approved = 0 AND created_at < :t")
			.param("t", createdBefore).query(String.class).list();
	}

	/** 사용자와 로그인 연결 정보를 지운다 (공간 데이터는 호출하는 쪽에서 먼저 정리) */
	public void delete(String id) {
		jdbc.sql("DELETE FROM user_identities WHERE user_id = :id").param("id", id).update();
		jdbc.sql("DELETE FROM space_members WHERE user_id = :id").param("id", id).update();
		jdbc.sql("DELETE FROM routine_checks WHERE user_id = :id").param("id", id).update();
		jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update();
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	private static AppUser map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new AppUser(rs.getString("id"), rs.getString("login"), rs.getString("name"),
				rs.getString("password_hash"), rs.getInt("admin") != 0, rs.getInt("disabled") != 0,
				rs.getInt("password_set") != 0, rs.getInt("approved") != 0);
	}
}

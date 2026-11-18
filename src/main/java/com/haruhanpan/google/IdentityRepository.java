package com.haruhanpan.google;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 외부 계정(Google sub) ↔ 하루한판 사용자. 사용자 한 명에 Google 계정 하나 */
@Repository
public class IdentityRepository {

	static final String GOOGLE = "google";

	private final JdbcClient jdbc;

	public IdentityRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<String> userOf(String subject) {
		return jdbc.sql("SELECT user_id FROM user_identities WHERE provider = :p AND subject = :s")
			.param("p", GOOGLE).param("s", subject).query(String.class).optional();
	}

	/** 연결된 Google 이메일 (화면 표시용) */
	public Optional<String> emailOf(String userId) {
		return jdbc.sql("SELECT email FROM user_identities WHERE provider = :p AND user_id = :u")
			.param("p", GOOGLE).param("u", userId).query(String.class).optional();
	}

	/** 이 사용자의 기존 연결을 지우고 새로 연결 */
	public void link(String userId, String subject, String email) {
		unlink(userId);
		jdbc.sql("INSERT INTO user_identities (provider, subject, user_id, email, created_at) VALUES (:p, :s, :u, :e, :at)")
			.param("p", GOOGLE).param("s", subject).param("u", userId).param("e", email)
			.param("at", Instant.now().toString())
			.update();
	}

	public boolean unlink(String userId) {
		return jdbc.sql("DELETE FROM user_identities WHERE provider = :p AND user_id = :u")
			.param("p", GOOGLE).param("u", userId).update() > 0;
	}
}

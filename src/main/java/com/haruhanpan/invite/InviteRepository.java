package com.haruhanpan.invite;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InviteRepository {

	/** 토큰 하나. 원문 토큰은 저장하지 않고 SHA-256 해시로 찾는다 */
	public record Invite(String tokenHash, String kind, String spaceId, String role, String userId,
			String expiresAt, String usedAt) {
	}

	private final JdbcClient jdbc;

	public InviteRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(String tokenHash, String kind, String spaceId, String role, String userId,
			String createdBy, String createdAt, String expiresAt) {
		jdbc.sql("""
				INSERT INTO invites (token_hash, kind, space_id, role, user_id, created_by, created_at, expires_at)
				VALUES (:h, :kind, :sid, :role, :uid, :by, :at, :exp)
				""")
			.param("h", tokenHash).param("kind", kind).param("sid", spaceId).param("role", role)
			.param("uid", userId).param("by", createdBy).param("at", createdAt).param("exp", expiresAt)
			.update();
	}

	public Optional<Invite> find(String tokenHash) {
		return jdbc.sql("SELECT token_hash, kind, space_id, role, user_id, expires_at, used_at FROM invites WHERE token_hash = :h")
			.param("h", tokenHash)
			.query((rs, n) -> new Invite(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
					rs.getString(5), rs.getString(6), rs.getString(7)))
			.optional();
	}

	/** 아직 안 쓴 토큰이면 사용 처리하고 true. 동시에 두 번 써도 한 번만 성공한다 */
	public boolean markUsed(String tokenHash, String usedAt) {
		return jdbc.sql("UPDATE invites SET used_at = :at WHERE token_hash = :h AND used_at IS NULL")
			.param("at", usedAt).param("h", tokenHash).update() == 1;
	}

	public void deleteExpired(String before) {
		jdbc.sql("DELETE FROM invites WHERE expires_at < :t").param("t", before).update();
	}
}

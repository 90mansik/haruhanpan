package com.haruhanpan.data;

import com.haruhanpan.common.ApiErrors.Conflict;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 할 일·일정·메모·스니펫·루틴의 1건 단위 저장/삭제. SQL 은 {@link com.haruhanpan.data.Collection} 정의에서 만든다. */
@Repository
public class CollectionRepository {

	private final JdbcClient jdbc;

	public CollectionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findAll(com.haruhanpan.data.Collection c, Collection<String> spaceIds) {
		if (spaceIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql(c.selectSql()).param("spaceIds", spaceIds).query((rs, rowNum) -> c.readRow(rs)).list();
	}

	public Optional<Long> version(com.haruhanpan.data.Collection c, String spaceId, String id) {
		return jdbc.sql(c.versionSql()).param("id", id).param("space_id", spaceId).query(Long.class).optional();
	}

	/**
	 * 있으면 수정, 없으면 추가하고 새 버전을 돌려준다.
	 * expectedVersion 이 있으면(화면이 마지막으로 본 버전) 서버 버전과 다를 때 {@link Conflict}.
	 * 다른 공간에 같은 id 가 있으면 INSERT 가 PK 위반으로 실패한다 (다른 공간의 데이터를 덮어쓸 수 없음).
	 */
	public long upsert(com.haruhanpan.data.Collection c, String spaceId, String userId, String id,
			Map<String, Object> item, Long expectedVersion) {
		Optional<Long> current = version(c, spaceId, id);
		Map<String, Object> params = c.toParams(item);
		params.put("id", id);
		params.put("space_id", spaceId);
		params.put("user_id", userId);
		if (current.isEmpty()) {
			jdbc.sql(c.insertSql()).params(params).update();
			return 1;
		}
		long cur = current.get();
		if (expectedVersion != null && expectedVersion != cur) {
			throw new Conflict(cur);
		}
		params.put("cur_version", cur);
		if (jdbc.sql(c.updateSql()).params(params).update() == 0) {
			throw new Conflict(version(c, spaceId, id).orElse(0L)); // 그 사이 다른 요청이 먼저 바꿈
		}
		return cur + 1;
	}

	/** 어느 공간에든 이 id 가 있는가 (가져오기 시 다른 공간과 id 가 겹치는지 확인) */
	public boolean existsAnywhere(com.haruhanpan.data.Collection c, String id) {
		return jdbc.sql("SELECT COUNT(*) FROM " + c.table() + " WHERE id = :id").param("id", id)
			.query(Integer.class).single() > 0;
	}

	/** 다른 공간으로 옮긴다. @return 옮겼으면 true (원래 공간에 없으면 false) */
	public boolean move(com.haruhanpan.data.Collection c, String fromSpace, String toSpace, String id, String userId) {
		return jdbc.sql("UPDATE " + c.table() + " SET space_id = :to, updated_by = :uid, version = version + 1"
				+ " WHERE id = :id AND space_id = :from")
			.param("to", toSpace).param("uid", userId).param("id", id).param("from", fromSpace)
			.update() > 0;
	}

	public boolean delete(com.haruhanpan.data.Collection c, String spaceId, String id) {
		return jdbc.sql(c.deleteSql()).param("id", id).param("space_id", spaceId).update() > 0;
	}

	public void deleteAllIn(com.haruhanpan.data.Collection c, String spaceId) {
		jdbc.sql("DELETE FROM " + c.table() + " WHERE space_id = :sid").param("sid", spaceId).update();
	}
}

package com.haruhanpan.data;

import com.haruhanpan.common.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 팀 공간의 "최근 활동". 같은 사람이 같은 항목을 10분 안에 또 고치면 한 줄로 합친다 (메모 자동 저장 대비). */
@Repository
public class ActivityRepository {

	private static final Duration MERGE_WINDOW = Duration.ofMinutes(10);

	private final JdbcClient jdbc;

	public ActivityRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void record(String spaceId, String userId, String action, String coll, String itemId, String title) {
		Instant now = Instant.now();
		String shortTitle = title == null ? "" : title.length() > 100 ? title.substring(0, 100) : title;
		if ("update".equals(action)) {
			int merged = jdbc.sql("""
					UPDATE activity SET at = :now, title = :title
					 WHERE space_id = :sid AND user_id = :uid AND item_id = :item AND action IN ('create', 'update') AND at > :since
					""")
				.param("now", now.toString()).param("title", shortTitle).param("sid", spaceId).param("uid", userId)
				.param("item", itemId).param("since", now.minus(MERGE_WINDOW).toString())
				.update();
			if (merged > 0) {
				return;
			}
		}
		jdbc.sql("""
				INSERT INTO activity (id, space_id, user_id, at, action, coll, item_id, title)
				VALUES (:id, :sid, :uid, :at, :action, :coll, :item, :title)
				""")
			.param("id", Ids.newId("a")).param("sid", spaceId).param("uid", userId).param("at", now.toString())
			.param("action", action).param("coll", coll).param("item", itemId).param("title", shortTitle)
			.update();
	}

	public List<Map<String, Object>> recent(Collection<String> spaceIds, int limit) {
		if (spaceIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT space_id, user_id, at, action, coll, item_id, title FROM activity
				 WHERE space_id IN (:ids) ORDER BY at DESC
				 FETCH FIRST :limit ROWS ONLY
				""")
			.param("ids", spaceIds).param("limit", limit)
			.query((rs, n) -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("spaceId", rs.getString(1));
				m.put("userId", rs.getString(2));
				m.put("at", rs.getString(3));
				m.put("action", rs.getString(4));
				m.put("coll", rs.getString(5));
				m.put("itemId", rs.getString(6));
				String title = rs.getString(7);
				m.put("title", title == null ? "" : title);
				return m;
			}).list();
	}

	public void deleteOlderThan(Instant cutoff) {
		jdbc.sql("DELETE FROM activity WHERE at < :t").param("t", cutoff.toString()).update();
	}
}

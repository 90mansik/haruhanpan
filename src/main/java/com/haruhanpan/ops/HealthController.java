package com.haruhanpan.ops;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 헬스 체크 (로그인 없이 호출 가능). 앱과 DB 가 살아 있으면 200, DB 에 닿지 않으면 503.
 * 서버 crontab 이 하루에 한 번 호출해서 Always Free ADB 에 활동을 남긴다 (KeepAliveJob 과 같은 쿼리).
 * 실패 원인은 로그에만 남기고 응답에는 넣지 않는다.
 */
@RestController
public class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);

	private final JdbcClient jdbc;

	public HealthController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/api/health")
	public ResponseEntity<Map<String, String>> health() {
		try {
			jdbc.sql("SELECT COUNT(*) FROM todos").query(Integer.class).single();
			return ResponseEntity.ok(Map.of("status", "ok", "db", "ok"));
		} catch (RuntimeException e) {
			log.warn("헬스 체크 DB 실패: {}", e.getMessage());
			return ResponseEntity.status(503).body(Map.of("status", "error", "db", "down"));
		}
	}
}

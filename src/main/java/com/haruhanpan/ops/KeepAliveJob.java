package com.haruhanpan.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Always Free Autonomous DB 는 7일간 활동이 없으면 정지된다(정지 후 90일이면 삭제).
 * 12시간마다 가벼운 쿼리를 보내 활동을 남긴다.
 * 이 쿼리가 OCI 에서 '활동'으로 인정되는지는 배포 후 콘솔에서 확인할 것 (DEPLOY.md 참고).
 */
@Component
public class KeepAliveJob {

	private static final Logger log = LoggerFactory.getLogger(KeepAliveJob.class);

	private final JdbcClient jdbc;

	public KeepAliveJob(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT12H")
	public void ping() {
		try {
			jdbc.sql("SELECT COUNT(*) FROM todos").query(Integer.class).single();
			log.debug("DB keepalive ok");
		} catch (RuntimeException e) {
			log.warn("DB keepalive 실패: {}", e.getMessage());
		}
	}
}

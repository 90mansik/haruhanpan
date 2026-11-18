package com.haruhanpan.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 횟수 제한 (메모리 보관, 서버 재시작 시 초기화).
 * IP별 15분에 5회, 전체 1시간에 30회 실패하면 429 로 막는다.
 */
@Component
public class LoginAttemptService {

	static final int IP_LIMIT = 5;
	static final Duration IP_WINDOW = Duration.ofMinutes(15);
	static final int GLOBAL_LIMIT = 30;
	static final Duration GLOBAL_WINDOW = Duration.ofHours(1);

	private final Clock clock;
	private final Map<String, Deque<Instant>> failuresByIp = new HashMap<>();
	private final Deque<Instant> globalFailures = new ArrayDeque<>();

	public LoginAttemptService() {
		this(Clock.systemUTC());
	}

	LoginAttemptService(Clock clock) {
		this.clock = clock;
	}

	public synchronized boolean isBlocked(String ip) {
		Instant now = clock.instant();
		prune(globalFailures, now.minus(GLOBAL_WINDOW));
		Deque<Instant> ipFailures = failuresByIp.get(ip);
		if (ipFailures != null) {
			prune(ipFailures, now.minus(IP_WINDOW));
		}
		return globalFailures.size() >= GLOBAL_LIMIT
				|| (ipFailures != null && ipFailures.size() >= IP_LIMIT);
	}

	public synchronized void recordFailure(String ip) {
		Instant now = clock.instant();
		failuresByIp.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(now);
		globalFailures.addLast(now);
	}

	public synchronized void recordSuccess(String ip) {
		failuresByIp.remove(ip);
	}

	/** 테스트용 */
	public synchronized void reset() {
		failuresByIp.clear();
		globalFailures.clear();
	}

	private static void prune(Deque<Instant> times, Instant cutoff) {
		while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
			times.pollFirst();
		}
	}
}

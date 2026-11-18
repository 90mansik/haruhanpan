package com.haruhanpan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

	/** 시간을 직접 움직일 수 있는 시계 */
	static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-05T00:00:00Z");

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}

	@Test
	void IP별_5회_실패하면_15분간_차단() {
		MutableClock clock = new MutableClock();
		LoginAttemptService service = new LoginAttemptService(clock);
		for (int i = 0; i < 5; i++) {
			assertThat(service.isBlocked("1.1.1.1")).isFalse();
			service.recordFailure("1.1.1.1");
		}
		assertThat(service.isBlocked("1.1.1.1")).isTrue();
		assertThat(service.isBlocked("2.2.2.2")).isFalse();

		clock.now = clock.now.plus(Duration.ofMinutes(16));
		assertThat(service.isBlocked("1.1.1.1")).isFalse();
	}

	@Test
	void 전체_1시간_30회_실패하면_모두_차단() {
		MutableClock clock = new MutableClock();
		LoginAttemptService service = new LoginAttemptService(clock);
		for (int i = 0; i < 30; i++) {
			service.recordFailure("10.0.0." + i);
		}
		assertThat(service.isBlocked("9.9.9.9")).isTrue();
		clock.now = clock.now.plus(Duration.ofMinutes(61));
		assertThat(service.isBlocked("9.9.9.9")).isFalse();
	}

	@Test
	void 성공하면_해당_IP_실패기록_초기화() {
		LoginAttemptService service = new LoginAttemptService(new MutableClock());
		for (int i = 0; i < 4; i++) {
			service.recordFailure("1.1.1.1");
		}
		service.recordSuccess("1.1.1.1");
		service.recordFailure("1.1.1.1");
		assertThat(service.isBlocked("1.1.1.1")).isFalse();
	}
}

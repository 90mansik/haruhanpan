package com.haruhanpan.common;

/** API 에서 던지는 예외. ApiExceptionHandler 가 상태 코드로 바꾼다. */
public final class ApiErrors {

	private ApiErrors() {
	}

	/** 403: 이 공간의 멤버가 아니거나 역할이 부족함 */
	public static class Forbidden extends RuntimeException {
		public Forbidden(String message) {
			super(message);
		}
	}

	/** 404 */
	public static class NotFound extends RuntimeException {
		public NotFound(String message) {
			super(message);
		}
	}

	/** 409: 다른 사람이 먼저 수정함 (낙관적 잠금). currentVersion 은 서버의 현재 버전 */
	public static class Conflict extends RuntimeException {
		private final long currentVersion;

		public Conflict(long currentVersion) {
			super("다른 사람이 먼저 수정했습니다");
			this.currentVersion = currentVersion;
		}

		public long currentVersion() {
			return currentVersion;
		}
	}
}

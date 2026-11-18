package com.haruhanpan.common;

import java.security.SecureRandom;
import java.util.Base64;

/** 서버에서 만드는 id·토큰 */
public final class Ids {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Ids() {
	}

	/** 예: sp-k3j9x2a1b7 */
	public static String newId(String prefix) {
		return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(RANDOM.nextInt(1 << 30), 36);
	}

	/** 초대·재설정 링크용 256비트 토큰 (URL 안전) */
	public static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}

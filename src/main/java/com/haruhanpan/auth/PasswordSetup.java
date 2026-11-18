package com.haruhanpan.auth;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/**
 * {@code java -jar haruhanpan.jar hash-password} 로 실행하는 비밀번호 설정 도구.
 * Spring 을 띄우지 않고, 입력받은 비밀번호의 BCrypt 해시를 data-dir/password.hash 에 저장한다.
 * 서버가 다음에 시작할 때 이 해시를 관리자(아이디 haru) 비밀번호로 적용하고 파일을 지운다 (OwnerBootstrap).
 * 비밀번호가 바뀌면 관리자의 기존 로그인 유지 쿠키는 자동으로 무효가 된다.
 */
public final class PasswordSetup {

	public static final int MIN_LENGTH = 12;

	private PasswordSetup() {
	}

	public static void run() throws IOException {
		Path dataDir = Path.of(System.getenv().getOrDefault("HARU_DATA_DIR", "./data"));
		char[] first = read("새 비밀번호 (" + MIN_LENGTH + "자 이상): ");
		char[] second = read("한 번 더 입력: ");
		try {
			if (!Arrays.equals(first, second)) {
				fail("두 비밀번호가 다릅니다.");
			}
			if (first.length < MIN_LENGTH) {
				fail("비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다.");
			}
			String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(new String(first));
			Files.createDirectories(dataDir);
			Path hashFile = dataDir.resolve(com.haruhanpan.account.OwnerBootstrap.HASH_FILE);
			SecretFiles.writePrivate(hashFile, hash);
			System.out.println("저장했습니다: " + hashFile.toAbsolutePath());
			System.out.println("서버를 다시 시작하면 관리자(아이디 haru) 비밀번호로 적용됩니다.");
		} finally {
			Arrays.fill(first, ' ');
			Arrays.fill(second, ' ');
		}
	}

	private static char[] read(String prompt) throws IOException {
		Console console = System.console();
		if (console != null) {
			char[] pw = console.readPassword(prompt);
			return pw == null ? new char[0] : pw;
		}
		// IDE 등 콘솔이 없는 환경: 화면에 보이지만 표준 입력으로 받는다
		System.out.print(prompt);
		String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
		return line == null ? new char[0] : line.toCharArray();
	}

	private static void fail(String message) {
		System.err.println(message);
		System.exit(1);
	}
}

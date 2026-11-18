package com.haruhanpan.auth;

import com.haruhanpan.config.HaruProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 로그인 유지 쿠키 서명 키: data-dir/remember.key (없으면 자동 생성, 권한 600).
 * 이 파일을 지우고 재시작하면 모든 사용자의 로그인 유지가 풀린다.
 */
@Component
public class RememberKey {

	static final String FILE = "remember.key";

	private final String value;

	public RememberKey(HaruProperties props) throws IOException {
		Files.createDirectories(props.dataDir());
		Path keyFile = props.dataDir().resolve(FILE);
		if (!Files.exists(keyFile)) {
			byte[] key = new byte[32];
			new SecureRandom().nextBytes(key);
			SecretFiles.writePrivate(keyFile, HexFormat.of().formatHex(key));
		}
		this.value = Files.readString(keyFile).strip();
	}

	public String value() {
		return value;
	}
}

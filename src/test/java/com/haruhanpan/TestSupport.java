package com.haruhanpan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 테스트마다 빈 메모리 DB 와 임시 데이터 폴더를 쓴다. */
final class TestSupport {

	static final String PASSWORD = "test-password-1234";

	private TestSupport() {
	}

	static Path register(DynamicPropertyRegistry registry, String dbName) {
		try {
			Path dir = Files.createTempDirectory("haru-test");
			registry.add("haru.data-dir", dir::toString);
			registry.add("haru.dev-password", () -> PASSWORD);
			registry.add("spring.datasource.url",
					() -> "jdbc:h2:mem:" + dbName + ";MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH");
			return dir;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

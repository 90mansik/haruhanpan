package com.haruhanpan.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** 비밀값 파일을 소유자만 읽을 수 있게(600) 저장한다. */
final class SecretFiles {

	private SecretFiles() {
	}

	static void writePrivate(Path file, String content) throws IOException {
		Files.writeString(file, content + "\n", StandardCharsets.UTF_8);
		try {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException ignored) {
			// Windows 등 POSIX 권한이 없는 파일시스템
		}
	}
}

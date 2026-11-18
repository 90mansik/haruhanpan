package com.haruhanpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 운영 방식: hash-password 로 만든 password.hash 파일이 관리자 비밀번호로 적용되고 파일은 지워진다 */
@SpringBootTest
@AutoConfigureMockMvc
class OwnerBootstrapTest {

	static Path dataDir;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) throws Exception {
		dataDir = TestSupport.register(registry, "boot" + System.nanoTime());
		registry.add("haru.dev-password", () -> "");
		String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("server-password-123");
		Files.writeString(dataDir.resolve("password.hash"), hash + "\n");
	}

	@Autowired
	MockMvc mvc;

	@Test
	void 해시파일이_적용되고_삭제된다() throws Exception {
		assertThat(dataDir.resolve("password.hash")).doesNotExist();
		mvc.perform(post("/api/login").with(csrf()).param("username", "haru").param("password", "server-password-123"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/login").with(csrf()).param("username", "haru").param("password", TestSupport.PASSWORD))
			.andExpect(status().isUnauthorized());
	}
}

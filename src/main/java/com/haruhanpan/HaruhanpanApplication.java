package com.haruhanpan;

import com.haruhanpan.auth.PasswordSetup;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 하루한판 진입점.
 * <pre>
 *   java -jar haruhanpan.jar                 서버 실행 (기본 dev 프로파일 = 로컬 H2)
 *   java -jar haruhanpan.jar hash-password   로그인 비밀번호 설정 (data-dir/password.hash 생성)
 * </pre>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class HaruhanpanApplication {

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && "hash-password".equals(args[0])) {
			PasswordSetup.run();
			return;
		}
		SpringApplication.run(HaruhanpanApplication.class, args);
	}
}

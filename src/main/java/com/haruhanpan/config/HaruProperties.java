package com.haruhanpan.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 haru.* 설정.
 *
 * @param dataDir             비밀번호 해시·로그인 유지 키·백업 JSON 을 두는 폴더
 * @param backupKeepDays      일일 백업 보관 일수
 * @param rememberMeDays      로그인 유지 쿠키 유효 일수
 * @param secureCookie        HTTPS 뒤에서는 true (쿠키 Secure 속성)
 * @param devPassword         로컬 개발용 관리자 비밀번호. prod 에서는 빈 값
 * @param googleAllowedDomain Google 로그인으로 초대 없이 바로 가입할 수 있는 Workspace 도메인 (승인 불필요)
 * @param googleOpenSignup    true 면 그 외 Google 계정도 가입할 수 있지만 사이트 관리자 승인 전까지는 쓸 수 없다
 */
@ConfigurationProperties("haru")
public record HaruProperties(
		Path dataDir,
		int backupKeepDays,
		int rememberMeDays,
		boolean secureCookie,
		String devPassword,
		String googleAllowedDomain,
		boolean googleOpenSignup) {
}

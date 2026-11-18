package com.haruhanpan.google;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Google 로그인이 켜져 있는가. Google 클라이언트 ID/비밀 환경변수가 있으면 Spring Boot 가
 * ClientRegistrationRepository 를 만들고, 없으면 만들지 않는다.
 */
@Component
public class GoogleSupport {

	public static final String AUTHORIZE_URL = "/oauth2/authorization/google";

	private final ObjectProvider<ClientRegistrationRepository> registrations;

	public GoogleSupport(ObjectProvider<ClientRegistrationRepository> registrations) {
		this.registrations = registrations;
	}

	public boolean enabled() {
		ClientRegistrationRepository repo = registrations.getIfAvailable();
		return repo != null && repo.findByRegistrationId("google") != null;
	}
}

package com.haruhanpan.auth;

import com.haruhanpan.config.HaruProperties;
import com.haruhanpan.google.GoogleSupport;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 여부 확인 (로그인 없이 호출 가능). 로그인 화면이 Google 버튼 표시 여부를 판단할 때도 쓴다. */
@RestController
public class SessionController {

	private final GoogleSupport google;
	private final HaruProperties props;

	public SessionController(GoogleSupport google, HaruProperties props) {
		this.google = google;
		this.props = props;
	}

	@GetMapping("/api/session")
	public Map<String, Object> session(Authentication authentication) {
		boolean loggedIn = authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken);
		return Map.of("auth", true, "loggedIn", loggedIn, "google", google.enabled(),
				"googleSignup", google.enabled() && props.googleOpenSignup());
	}
}

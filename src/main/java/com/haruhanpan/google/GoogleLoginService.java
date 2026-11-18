package com.haruhanpan.google;

import com.haruhanpan.account.AccountService;
import com.haruhanpan.account.AppUser;
import com.haruhanpan.account.UserRepository;
import com.haruhanpan.config.HaruProperties;
import com.haruhanpan.invite.InviteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Google 로그인 결과를 하루한판 사용자로 바꾼다. 순서:
 * <ol>
 *   <li>이미 연결된 Google 계정 → 그 사용자</li>
 *   <li>"Google 연결"을 누르고 온 경우(link) → 로그인해 있던 사용자에 연결</li>
 *   <li>초대 링크에서 "Google 로 가입"을 누르고 온 경우(invite) → 새 사용자 + 팀 참여</li>
 *   <li>허용 도메인(google-allowed-domain)이 설정돼 있고 그 Workspace 계정이면 → 새 사용자</li>
 *   <li>공개 가입(google-open-signup)이 켜져 있으면 → 새 사용자(승인 대기). 관리자 승인 전에는 쓸 수 없음</li>
 *   <li>그 외 → 거절 (초대받지 않은 계정)</li>
 * </ol>
 */
@Service
public class GoogleLoginService {

	private static final Logger log = LoggerFactory.getLogger(GoogleLoginService.class);

	/** Google 이 알려준 계정 정보. hostedDomain 은 Workspace 계정일 때만 있다 (hd 클레임) */
	public record GoogleProfile(String subject, String email, boolean emailVerified, String hostedDomain, String name) {
	}

	/** 로그인 화면에 ?error= 로 넘길 사유 */
	public static class GoogleLoginException extends RuntimeException {
		private final String code;

		public GoogleLoginException(String code) {
			super(code);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	/** Google 로그인을 시작하기 전에 세션에 남겨 두는 의도 */
	public sealed interface Intent extends java.io.Serializable permits Link, Invite {
	}

	public record Link(String userId) implements Intent {
	}

	public record Invite(String token) implements Intent {
	}

	private final IdentityRepository identities;
	private final UserRepository users;
	private final AccountService accounts;
	private final InviteService invites;
	private final HaruProperties props;

	public GoogleLoginService(IdentityRepository identities, UserRepository users, AccountService accounts,
			InviteService invites, HaruProperties props) {
		this.identities = identities;
		this.users = users;
		this.accounts = accounts;
		this.invites = invites;
		this.props = props;
	}

	/** @return 로그인시킬 사용자의 아이디(login) */
	@Transactional
	public String resolve(GoogleProfile p, Intent intent) {
		var linkedUser = identities.userOf(p.subject());
		if (linkedUser.isPresent()) {
			if (intent instanceof Link link && !link.userId().equals(linkedUser.get())) {
				throw new GoogleLoginException("google_in_use"); // 이 Google 계정은 다른 사용자에 연결돼 있음
			}
			return activeLogin(linkedUser.get());
		}

		if (intent instanceof Link link) {
			identities.link(link.userId(), p.subject(), p.email());
			log.info("Google 계정 연결 user={} email={}", link.userId(), p.email());
			return activeLogin(link.userId());
		}

		if (!p.emailVerified()) {
			throw new GoogleLoginException("google_unverified");
		}
		if (intent instanceof Invite invite) {
			if (!invites.isValidJoin(invite.token())) {
				throw new GoogleLoginException("invite_invalid");
			}
			String userId = accounts.createForExternal(p.email(), p.name(), true);
			invites.joinNewUser(invite.token(), userId);
			identities.link(userId, p.subject(), p.email());
			log.info("Google 로 가입(초대) user={} email={}", userId, p.email());
			return activeLogin(userId);
		}

		String domain = props.googleAllowedDomain();
		if (StringUtils.hasText(domain) && domain.strip().equalsIgnoreCase(p.hostedDomain())) {
			String userId = accounts.createForExternal(p.email(), p.name(), true);
			identities.link(userId, p.subject(), p.email());
			log.info("Google 로 가입(허용 도메인) user={} email={}", userId, p.email());
			return activeLogin(userId);
		}
		if (props.googleOpenSignup()) {
			String userId = accounts.createForExternal(p.email(), p.name(), false);
			identities.link(userId, p.subject(), p.email());
			log.info("Google 로 가입 신청(승인 대기) user={} email={}", userId, p.email());
			return activeLogin(userId);
		}
		log.warn("초대받지 않은 Google 계정의 로그인 시도 email={}", p.email());
		throw new GoogleLoginException("google_not_invited");
	}

	private String activeLogin(String userId) {
		AppUser u = users.findById(userId).orElseThrow(() -> new GoogleLoginException("google_not_invited"));
		if (u.disabled()) {
			throw new GoogleLoginException("disabled");
		}
		return u.login();
	}
}

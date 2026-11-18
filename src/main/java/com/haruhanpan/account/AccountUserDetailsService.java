package com.haruhanpan.account;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Spring Security 로그인용: users 테이블에서 아이디(login)로 찾는다. */
@Service
public class AccountUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	public AccountUserDetailsService(UserRepository users) {
		this.users = users;
	}

	@Override
	public UserDetails loadUserByUsername(String login) {
		AppUser u = users.findByLogin(login)
			.filter(x -> x.passwordHash() != null)
			.orElseThrow(() -> new UsernameNotFoundException("no user"));
		return User.withUsername(u.login())
			.password(u.passwordHash())
			.disabled(u.disabled())
			.roles(u.admin() ? "ADMIN" : "USER")
			.build();
	}
}

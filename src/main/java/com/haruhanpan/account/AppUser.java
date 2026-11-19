package com.haruhanpan.account;

/**
 * 로그인한 사용자. 컨트롤러 메서드 파라미터로 받을 수 있다 (CurrentUserResolver).
 *
 * @param passwordSet false 면 Google 로 가입해서 본인이 정한 비밀번호가 없음 (비밀번호 로그인 불가)
 * @param approved    false 면 Google 로 직접 가입해 관리자 승인을 기다리는 중 (데이터 API 사용 불가)
 * @param ledger      하루가계부 사용 권한 (사이트 관리자가 켜 준다)
 */
public record AppUser(String id, String login, String name, String passwordHash, boolean admin, boolean disabled,
		boolean passwordSet, boolean approved, boolean ledger) {

	/** 화면에 내려보낼 때 (비밀번호 해시 제외) */
	public java.util.Map<String, Object> toPublic() {
		return java.util.Map.of("id", id, "login", login, "name", name == null ? "" : name, "admin", admin,
				"passwordSet", passwordSet, "ledger", ledger);
	}
}

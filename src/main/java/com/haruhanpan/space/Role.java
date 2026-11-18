package com.haruhanpan.space;

import java.util.Arrays;

/** 공간 안에서의 역할. 위로 갈수록 권한이 크다. */
public enum Role {
	VIEWER,   // 읽기만
	EDITOR,   // 할 일·일정·메모 등 추가·수정·삭제
	OWNER;    // + 멤버 초대·역할 변경·공간 설정·삭제

	public boolean atLeast(Role other) {
		return compareTo(other) >= 0;
	}

	public String db() {
		return name().toLowerCase();
	}

	public static Role of(String value) {
		return Arrays.stream(values()).filter(r -> r.db().equals(value)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("잘못된 역할"));
	}
}

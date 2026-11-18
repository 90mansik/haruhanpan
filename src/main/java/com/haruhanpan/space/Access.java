package com.haruhanpan.space;

/** 어떤 사용자가 어떤 공간에 가진 권한. SpaceService.require 가 돌려준다. */
public record Access(String spaceId, String kind, Role role) {

	public boolean isTeam() {
		return "team".equals(kind);
	}
}

package com.haruhanpan.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 보안 필터에서 쓰는 짧은 JSON 응답. 메시지는 코드에서 정한 고정 문자열만 넣는다. */
public final class JsonResponses {

	private JsonResponses() {
	}

	public static void write(HttpServletResponse response, int status, String error) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(error == null ? "{\"ok\":true}" : "{\"error\":\"" + error + "\"}");
	}
}

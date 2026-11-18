package com.haruhanpan.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.SqlParameterValue;

/**
 * 화면(JS)의 컬렉션 ↔ DB 테이블 매핑. 5개 컬렉션 모두 "공간 안에서 id 로 1건 저장/삭제"라서
 * 테이블마다 Repository 를 따로 만들지 않고 이 정의 하나로 SQL 을 만든다.
 * 테이블·컬럼 이름은 이 enum 에서만 오므로 SQL 문자열에 넣어도 안전하다. 값은 항상 바인드 변수.
 *
 * <p>모든 테이블 공통 컬럼(서버가 관리, 화면에서 보내도 무시):
 * space_id(spaceId), created_by(createdBy), updated_by(updatedBy), version(version)
 */
public enum Collection {

	TODOS("todos", "todos", "rank_no, created_at",
			text("title"), text("due"), text("priority"), text("tag"), text("memo"),
			bool("done"), text("doneAt", "done_at"), text("createdAt", "created_at"), text("status"),
			num("rank", "rank_no"), text("assignee", "assignee_id"), text("repeat", "repeat_rule")),

	EVENTS("events", "events", "start_date, start_time",
			text("title"), text("date", "start_date"), text("endDate", "end_date"),
			text("time", "start_time"), text("repeat", "repeat_rule"), text("until", "repeat_until"),
			text("color"), text("memo"), bool("dday")),

	NOTES("notes", "notes", "updated_at DESC",
			text("title"), clob("body"), bool("pinned"), text("updatedAt", "updated_at")),

	SNIPPETS("snippets", "snippets", "updated_at DESC",
			text("title"), text("tag"), clob("code"), text("memo"), text("lang"),
			text("updatedAt", "updated_at")),

	/** 순서(sort_order)는 화면 필드가 아니라 배열 순서. 새 루틴은 그 공간의 맨 뒤에 붙는다. */
	ROUTINES("routines", "routines", "sort_order, id",
			text("title"));

	enum Kind { TEXT, CLOB, BOOL, NUM }

	record Column(String js, String db, Kind kind) {
	}

	/** 서버가 관리하는 읽기 전용 컬럼 */
	private static final List<Column> SERVER_COLUMNS = List.of(
			new Column("id", "id", Kind.TEXT),
			new Column("spaceId", "space_id", Kind.TEXT),
			new Column("createdBy", "created_by", Kind.TEXT),
			new Column("updatedBy", "updated_by", Kind.TEXT),
			new Column("version", "version", Kind.NUM));

	private final String jsName;
	private final String table;
	private final String orderBy;
	private final List<Column> writable;

	Collection(String jsName, String table, String orderBy, Column... writable) {
		this.jsName = jsName;
		this.table = table;
		this.orderBy = orderBy;
		this.writable = List.of(writable);
	}

	public String jsName() {
		return jsName;
	}

	public String table() {
		return table;
	}

	public static Optional<Collection> fromJs(String name) {
		return Arrays.stream(values()).filter(c -> c.jsName.equals(name)).findFirst();
	}

	// ───────── SQL ─────────

	String selectSql() {
		String cols = java.util.stream.Stream.concat(SERVER_COLUMNS.stream(), writable.stream())
			.map(Column::db).collect(Collectors.joining(", "));
		return "SELECT " + cols + " FROM " + table + " WHERE space_id IN (:spaceIds) ORDER BY " + orderBy;
	}

	String versionSql() {
		return "SELECT version FROM " + table + " WHERE id = :id AND space_id = :space_id";
	}

	String updateSql() {
		return "UPDATE " + table + " SET "
				+ writable.stream().map(c -> c.db() + " = :" + c.db()).collect(Collectors.joining(", "))
				+ ", updated_by = :user_id, version = version + 1"
				+ " WHERE id = :id AND space_id = :space_id AND version = :cur_version";
	}

	String insertSql() {
		String cols = writable.stream().map(Column::db).collect(Collectors.joining(", "));
		String vals = writable.stream().map(c -> ":" + c.db()).collect(Collectors.joining(", "));
		cols += ", id, space_id, created_by, updated_by, version";
		vals += ", :id, :space_id, :user_id, :user_id, 1";
		if (this == ROUTINES) {
			cols += ", sort_order";
			vals += ", (SELECT NVL(MAX(sort_order), 0) + 1 FROM routines WHERE space_id = :space_id)";
		}
		return "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")";
	}

	String deleteSql() {
		return "DELETE FROM " + table + " WHERE id = :id AND space_id = :space_id";
	}

	// ───────── 값 변환 ─────────

	/** DB 한 행 → 화면 객체. Oracle 은 '' 를 NULL 로 저장하므로 텍스트 NULL 은 '' 로 돌려준다. */
	Map<String, Object> readRow(ResultSet rs) throws SQLException {
		Map<String, Object> item = new LinkedHashMap<>();
		for (Column c : SERVER_COLUMNS) {
			item.put(c.js(), read(rs, c));
		}
		for (Column c : writable) {
			item.put(c.js(), read(rs, c));
		}
		return item;
	}

	private static Object read(ResultSet rs, Column c) throws SQLException {
		return switch (c.kind()) {
			case TEXT, CLOB -> {
				String s = rs.getString(c.db());
				yield s == null ? "" : s;
			}
			case BOOL -> rs.getInt(c.db()) != 0;
			case NUM -> c.db().equals("version") ? (Object) rs.getLong(c.db()) : rs.getDouble(c.db());
		};
	}

	/** 화면 객체 → 바인드 변수 (화면이 바꿀 수 있는 컬럼만). 모르는 필드는 무시한다. */
	Map<String, Object> toParams(Map<String, Object> item) {
		Map<String, Object> params = new LinkedHashMap<>();
		for (Column c : writable) {
			Object v = item.get(c.js());
			params.put(c.db(), switch (c.kind()) {
				case TEXT -> v == null ? null : String.valueOf(v);
				case CLOB -> new SqlParameterValue(Types.CLOB, v == null ? null : String.valueOf(v));
				case BOOL -> truthy(v) ? 1 : 0;
				case NUM -> toNumber(v);
			});
		}
		return params;
	}

	private static boolean truthy(Object v) {
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof Number n) {
			return n.intValue() != 0;
		}
		return v != null && ("true".equalsIgnoreCase(v.toString()) || "1".equals(v.toString()));
	}

	private static double toNumber(Object v) {
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		try {
			return v == null ? 0 : Double.parseDouble(v.toString());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static Column text(String name) {
		return new Column(name, name, Kind.TEXT);
	}

	private static Column text(String js, String db) {
		return new Column(js, db, Kind.TEXT);
	}

	private static Column clob(String name) {
		return new Column(name, name, Kind.CLOB);
	}

	private static Column bool(String name) {
		return new Column(name, name, Kind.BOOL);
	}

	private static Column num(String js, String db) {
		return new Column(js, db, Kind.NUM);
	}
}

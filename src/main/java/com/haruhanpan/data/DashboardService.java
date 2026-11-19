package com.haruhanpan.data;

import com.haruhanpan.account.AppUser;
import com.haruhanpan.space.Access;
import com.haruhanpan.space.Role;
import com.haruhanpan.space.SpaceService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.haruhanpan.wiki.WikiFiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화면 데이터 조회·1건 변경·공간 단위 가져오기. 모든 변경은 먼저 공간 권한을 확인한다.
 * <ul>
 *   <li>보기(viewer): 읽기 + 자기 루틴 체크</li>
 *   <li>편집(editor): 할 일·일정·메모·스니펫·루틴·휴무일 변경</li>
 *   <li>관리자(owner): + 가져오기(공간 전체 교체)</li>
 * </ul>
 */
@Service
public class DashboardService {

	private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
	private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
	private static final Set<String> TODO_STATUSES = Set.of("todo", "doing", "done");
	/** 할 일 반복 (일정 반복과 같은 값). 모르는 값은 반복 없음으로 */
	private static final Set<String> REPEATS = Set.of("daily", "weekday", "weekly", "monthly", "yearly");
	/** 화면에 내려보내는 루틴 체크 기록 기간 */
	private static final int CHECK_DAYS = 120;

	private final CollectionRepository collections;
	private final RoutineRepository routines;
	private final ActivityRepository activity;
	private final SpaceService spaces;
	private final JdbcClient jdbc;
	private final WikiFiles wikiFiles;

	public DashboardService(CollectionRepository collections, RoutineRepository routines, ActivityRepository activity,
			SpaceService spaces, JdbcClient jdbc, WikiFiles wikiFiles) {
		this.collections = collections;
		this.routines = routines;
		this.activity = activity;
		this.spaces = spaces;
		this.jdbc = jdbc;
		this.wikiFiles = wikiFiles;
	}

	/** 내가 속한 모든 공간의 데이터. 항목마다 spaceId 가 붙어 있다 */
	@Transactional(readOnly = true)
	public Map<String, Object> all(AppUser me) {
		List<Map<String, Object>> mySpaces = spaces.spacesOf(me);
		List<String> ids = mySpaces.stream().map(s -> (String) s.get("id")).toList();
		var members = spaces.membersOf(ids);
		mySpaces.forEach(s -> s.put("members", members.getOrDefault((String) s.get("id"), List.of())));

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("me", me.toPublic());
		data.put("spaces", mySpaces);
		for (Collection c : Collection.values()) {
			data.put(c.jsName(), collections.findAll(c, ids));
		}
		data.put("routineChecks", routines.findChecks(ids, LocalDate.now().minusDays(CHECK_DAYS).toString()));

		List<String> scopes = new ArrayList<>(ids);
		scopes.add(RoutineRepository.PUBLIC);
		Map<String, Map<String, String>> dayOffs = routines.findDayOffs(scopes);
		data.put("holidays", dayOffs.getOrDefault(RoutineRepository.PUBLIC, Map.of()));
		dayOffs.remove(RoutineRepository.PUBLIC);
		data.put("spaceDayOffs", dayOffs);

		List<String> teamIds = mySpaces.stream().filter(s -> "team".equals(s.get("kind")))
			.map(s -> (String) s.get("id")).toList();
		data.put("activity", activity.recent(teamIds, 60));
		return data;
	}

	/** @return 저장 후 버전 */
	@Transactional
	public long upsert(AppUser me, String spaceId, Collection c, String id, Map<String, Object> item) {
		Access access = spaces.require(me, spaceId, Role.EDITOR);
		requireId(id);
		Map<String, Object> copy = new HashMap<>(item);
		if (c == Collection.TODOS) {
			normalizeTodo(copy);
			requireMemberOrBlank(spaceId, copy.get("assignee"));
		}
		if (c == Collection.NOTES) {
			checkWikiPage(spaceId, id, copy);
		}
		Long expected = copy.get("version") instanceof Number n && n.longValue() > 0 ? n.longValue() : null;
		boolean existed = collections.version(c, spaceId, id).isPresent();
		long version = collections.upsert(c, spaceId, me.id(), id, copy, expected);
		if (access.isTeam()) {
			activity.record(spaceId, me.id(), existed ? "update" : "create", c.jsName(), id, titleOf(copy));
		}
		return version;
	}

	@Transactional
	public boolean delete(AppUser me, String spaceId, Collection c, String id) {
		Access access = spaces.require(me, spaceId, Role.EDITOR);
		requireId(id);
		String title = jdbc.sql("SELECT title FROM " + c.table() + " WHERE id = :id AND space_id = :sid")
			.param("id", id).param("sid", spaceId).query(String.class).optional().orElse(null);
		if (c == Collection.ROUTINES) {
			routines.deleteChecksOf(id);
		}
		if (c == Collection.NOTES) {
			// 위키 페이지는 하위 페이지와 그 이미지까지 함께 지운다
			List<String> tree = subtree(spaceId, id);
			wikiFiles.deleteForPages(spaceId, tree);
			tree.stream().filter(x -> !x.equals(id)).forEach(x -> collections.delete(c, spaceId, x));
		}
		boolean deleted = collections.delete(c, spaceId, id);
		if (deleted && access.isTeam()) {
			activity.record(spaceId, me.id(), "delete", c.jsName(), id, title);
		}
		return deleted;
	}

	/**
	 * 항목을 다른 공간으로 옮긴다 (두 공간 모두 편집 권한 필요). 루틴은 체크 기록이 사람·공간에 묶여 있어 옮기지 않는다.
	 * 할 일의 담당자가 옮길 공간의 멤버가 아니면 담당자를 비운다.
	 */
	@Transactional
	public void move(AppUser me, String fromSpace, Collection c, String id, String toSpace) {
		Access from = spaces.require(me, fromSpace, Role.EDITOR);
		Access to = spaces.require(me, toSpace, Role.EDITOR);
		requireId(id);
		if (c == Collection.ROUTINES) {
			throw new IllegalArgumentException("루틴은 다른 공간으로 옮길 수 없습니다");
		}
		if (fromSpace.equals(toSpace)) {
			return;
		}
		String title = jdbc.sql("SELECT title FROM " + c.table() + " WHERE id = :id AND space_id = :sid")
			.param("id", id).param("sid", fromSpace).query(String.class).optional()
			.orElseThrow(() -> new com.haruhanpan.common.ApiErrors.NotFound("not found"));
		if (c == Collection.NOTES) {
			// 위키 페이지는 하위 페이지·이미지까지 함께 옮기고, 옮긴 페이지는 새 공간의 최상위가 된다
			List<String> tree = subtree(fromSpace, id);
			tree.stream().filter(x -> !x.equals(id)).forEach(x -> collections.move(c, fromSpace, toSpace, x, me.id()));
			wikiFiles.moveToSpace(fromSpace, tree, toSpace);
			jdbc.sql("UPDATE notes SET parent_id = NULL WHERE id = :id").param("id", id).update();
		}
		collections.move(c, fromSpace, toSpace, id, me.id());
		if (c == Collection.TODOS) {
			Set<String> members = spaces.membersOf(List.of(toSpace)).getOrDefault(toSpace, List.of()).stream()
				.map(m -> (String) m.get("id")).collect(Collectors.toSet());
			String assignee = jdbc.sql("SELECT assignee_id FROM todos WHERE id = :id").param("id", id)
				.query(String.class).optional().orElse(null);
			if (!to.isTeam() || (assignee != null && !members.contains(assignee))) {
				jdbc.sql("UPDATE todos SET assignee_id = NULL WHERE id = :id").param("id", id).update();
			}
		}
		if (from.isTeam()) {
			activity.record(fromSpace, me.id(), "moveout", c.jsName(), id, title);
		}
		if (to.isTeam()) {
			activity.record(toSpace, me.id(), "movein", c.jsName(), id, title);
		}
	}

	@Transactional
	public void setRoutineOrder(AppUser me, String spaceId, List<String> ids) {
		spaces.require(me, spaceId, Role.EDITOR);
		ids.forEach(DashboardService::requireId);
		routines.setOrder(spaceId, ids);
	}

	/** 루틴 체크는 사람별. 보기 권한만 있어도 자기 체크는 할 수 있다 */
	@Transactional
	public void setRoutineCheck(AppUser me, String spaceId, String date, String routineId, boolean checked) {
		spaces.require(me, spaceId, Role.VIEWER);
		requireDate(date);
		requireId(routineId);
		if (!routines.routineInSpace(spaceId, routineId)) {
			throw new IllegalArgumentException("이 공간의 루틴이 아닙니다");
		}
		routines.setCheck(date, routineId, me.id(), checked);
	}

	/** 공간별 휴무일. 이름이 비어 있으면 해제. 공휴일(모든 공간 공통)은 바꾸지 않는다 */
	@Transactional
	public void setDayOff(AppUser me, String spaceId, String date, String name) {
		spaces.require(me, spaceId, Role.EDITOR);
		requireDate(date);
		if (name == null || name.isBlank()) {
			routines.deleteDayOff(spaceId, date);
		} else {
			routines.setDayOff(spaceId, date, name.strip());
		}
	}

	/** 가져오기: 그 공간의 데이터를 한 트랜잭션으로 지우고 다시 넣는다. 실패하면 기존 데이터가 그대로 남는다 */
	@Transactional
	public void replaceSpace(AppUser me, String spaceId, Map<String, Object> data) {
		spaces.require(me, spaceId, Role.OWNER);
		Set<String> memberIds = spaces.membersOf(List.of(spaceId)).getOrDefault(spaceId, List.of()).stream()
			.map(m -> (String) m.get("id")).collect(Collectors.toSet());

		routines.deleteChecksIn(spaceId);
		for (Collection c : Collection.values()) {
			collections.deleteAllIn(c, spaceId);
		}
		routines.deleteDayOffsIn(spaceId);

		// 다른 공간에서 내보낸 파일이면 id 가 겹칠 수 있다 → 겹치는 것만 새 id 로 바꿔 넣는다
		Map<String, String> routineIdMap = new LinkedHashMap<>();
		// 위키 페이지: id 가 바뀌어도 하위 페이지가 상위를 잃지 않도록 먼저 새 id 를 정해 둔다
		Map<String, String> noteIdMap = new LinkedHashMap<>();
		for (Map<String, Object> item : listOfMaps(data.get(Collection.NOTES.jsName()), Collection.NOTES.jsName())) {
			String raw = String.valueOf(item.get("id"));
			requireId(raw);
			noteIdMap.put(raw, collections.existsAnywhere(Collection.NOTES, raw) ? com.haruhanpan.common.Ids.newId("i") : raw);
		}
		for (Collection c : Collection.values()) {
			for (Map<String, Object> item : listOfMaps(data.get(c.jsName()), c.jsName())) {
				Object rawId = item.get("id");
				requireId(rawId == null ? "" : rawId.toString());
				String id = rawId.toString();
				if (c == Collection.NOTES) {
					id = noteIdMap.get(id);
				} else if (collections.existsAnywhere(c, id)) {
					id = com.haruhanpan.common.Ids.newId("i");
				}
				if (c == Collection.ROUTINES) {
					routineIdMap.put(rawId.toString(), id);
				}
				Map<String, Object> copy = new HashMap<>(item);
				if (c == Collection.TODOS) {
					normalizeTodo(copy);
					if (!memberIds.contains(String.valueOf(copy.get("assignee")))) {
						copy.put("assignee", "");
					}
				}
				if (c == Collection.NOTES) {
					copy.put("parentId", noteIdMap.getOrDefault(String.valueOf(copy.get("parentId")), ""));
				}
				collections.upsert(c, spaceId, me.id(), id, copy, null);
			}
		}
		routines.setOrder(spaceId, List.copyOf(routineIdMap.values()));

		// 루틴 체크: 새 형식 [{date, routineId, userId}] 또는 예전 1인용 형식 {date: [routineId]}
		Object log = data.containsKey("routineChecks") ? data.get("routineChecks") : data.get("routineLog");
		for (String[] check : checksOf(log)) {
			String user = memberIds.contains(check[2]) ? check[2] : me.id();
			requireDate(check[0]);
			String routineId = routineIdMap.get(check[1]);
			if (routineId != null) {
				routines.setCheck(check[0], routineId, user, true);
			}
		}
		// 휴무일: 공휴일과 같은 날·같은 이름은 건너뜀 (예전 내보내기 파일에는 공휴일이 같이 들어 있음)
		Map<String, String> publicHolidays = routines.findDayOffs(List.of(RoutineRepository.PUBLIC))
			.getOrDefault(RoutineRepository.PUBLIC, Map.of());
		Object offs = data.containsKey("dayOffs") ? data.get("dayOffs") : data.get("holidays");
		for (var e : mapOf(offs, "holidays").entrySet()) {
			String name = e.getValue() == null ? "" : e.getValue().toString().strip();
			requireDate(e.getKey());
			if (!name.isEmpty() && !name.equals(publicHolidays.get(e.getKey()))) {
				routines.setDayOff(spaceId, e.getKey(), name);
			}
		}
	}

	/** 공간 하나의 데이터 (백업용, 권한 검사 없음) */
	@Transactional(readOnly = true)
	public Map<String, Object> exportSpace(String spaceId) {
		Map<String, Object> data = new LinkedHashMap<>();
		List<String> ids = List.of(spaceId);
		for (Collection c : Collection.values()) {
			data.put(c.jsName(), collections.findAll(c, ids));
		}
		data.put("routineChecks", routines.findChecks(ids, "0000-00-00"));
		data.put("dayOffs", routines.findDayOffs(ids).getOrDefault(spaceId, Map.of()));
		return data;
	}

	/** 할 일의 status 와 done 이 항상 일치하도록 맞춘다 (done=true ⇔ status='done'). */
	static void normalizeTodo(Map<String, Object> todo) {
		Object status = todo.get("status");
		boolean done = Boolean.TRUE.equals(todo.get("done"));
		if (!(status instanceof String s) || !TODO_STATUSES.contains(s)) {
			status = done ? "done" : "todo";
		}
		todo.put("status", status);
		todo.put("done", "done".equals(status));
		if (!(todo.get("repeat") instanceof String r) || !REPEATS.contains(r)) {
			todo.put("repeat", "");
		}
	}

	private void requireMemberOrBlank(String spaceId, Object assignee) {
		if (assignee == null || assignee.toString().isBlank()) {
			return;
		}
		boolean member = spaces.membersOf(List.of(spaceId)).getOrDefault(spaceId, List.of()).stream()
			.anyMatch(m -> assignee.toString().equals(m.get("id")));
		if (!member) {
			throw new IllegalArgumentException("담당자는 이 공간의 멤버여야 합니다");
		}
	}

	/** 위키 페이지 검사: 상위 페이지는 같은 공간에 있어야 하고 자기 자신·자기 하위일 수 없다. 아이콘은 짧게 */
	private void checkWikiPage(String spaceId, String id, Map<String, Object> page) {
		String parent = page.get("parentId") == null ? "" : page.get("parentId").toString().strip();
		page.put("parentId", parent);
		if (!parent.isEmpty()) {
			requireId(parent);
			Map<String, String> parents = wikiParents(spaceId);
			if (!parents.containsKey(parent)) {
				throw new IllegalArgumentException("상위 페이지가 이 공간에 없습니다");
			}
			for (String p = parent; p != null && !p.isEmpty(); p = parents.get(p)) {
				if (p.equals(id)) {
					throw new IllegalArgumentException("페이지를 자기 하위로 옮길 수 없습니다");
				}
			}
		}
		Object icon = page.get("icon");
		if (icon != null && icon.toString().codePointCount(0, icon.toString().length()) > 8) {
			throw new IllegalArgumentException("아이콘은 이모지 하나");
		}
		Object content = page.get("content");
		if (content != null && content.toString().length() > 2_000_000) {
			throw new IllegalArgumentException("페이지가 너무 깁니다");
		}
	}

	/** 공간의 위키 페이지 id → 상위 id */
	private Map<String, String> wikiParents(String spaceId) {
		Map<String, String> parents = new HashMap<>();
		jdbc.sql("SELECT id, parent_id FROM notes WHERE space_id = :sid").param("sid", spaceId)
			.query(rs -> {
				parents.put(rs.getString("id"), rs.getString("parent_id"));
			});
		return parents;
	}

	/** 페이지와 그 아래 모든 하위 페이지 id (자기 자신 포함) */
	private List<String> subtree(String spaceId, String id) {
		Map<String, String> parents = wikiParents(spaceId);
		List<String> tree = new ArrayList<>(List.of(id));
		for (int i = 0; i < tree.size(); i++) {
			String cur = tree.get(i);
			parents.forEach((child, parent) -> {
				if (cur.equals(parent) && !tree.contains(child)) {
					tree.add(child);
				}
			});
		}
		return tree;
	}

	private static String titleOf(Map<String, Object> item) {
		Object t = item.get("title");
		return t == null ? "" : t.toString();
	}

	static void requireId(String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw new IllegalArgumentException("잘못된 id");
		}
	}

	static void requireDate(String date) {
		if (date == null || !DATE.matcher(date).matches()) {
			throw new IllegalArgumentException("잘못된 날짜");
		}
	}

	private static List<String[]> checksOf(Object log) {
		List<String[]> result = new ArrayList<>();
		if (log instanceof List<?> list) {
			for (Object o : list) {
				if (o instanceof Map<?, ?> m && m.get("date") != null && m.get("routineId") != null) {
					result.add(new String[] {m.get("date").toString(), m.get("routineId").toString(), String.valueOf(m.get("userId"))});
				}
			}
		} else if (log instanceof Map<?, ?> map) {
			for (var e : map.entrySet()) {
				if (!(e.getValue() instanceof List<?> rids)) {
					throw new IllegalArgumentException("routineLog 형식이 잘못되었습니다");
				}
				for (Object rid : rids) {
					result.add(new String[] {e.getKey().toString(), String.valueOf(rid), ""});
				}
			}
		} else if (log != null) {
			throw new IllegalArgumentException("routineLog 형식이 잘못되었습니다");
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> listOfMaps(Object value, String name) {
		if (value == null) {
			return List.of();
		}
		if (value instanceof List<?> list && list.stream().allMatch(Map.class::isInstance)) {
			return (List<Map<String, Object>>) list;
		}
		throw new IllegalArgumentException(name + " 형식이 잘못되었습니다");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapOf(Object value, String name) {
		if (value == null) {
			return Map.of();
		}
		if (value instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		throw new IllegalArgumentException(name + " 형식이 잘못되었습니다");
	}
}

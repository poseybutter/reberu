package reberu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.support.ReflectionSupport;

class SecurityTestIdsTest {

    private static final Path SECURITY_DOC = Path.of("docs/architecture/security.md");
    private static final Path CHECKLIST_DOC = Path.of("docs/operations/release-checklist.md");
    private static final String STAGE = "v0.1";
    private static final Pattern STAGE_HEADING = Pattern.compile("^#### (v\\d+\\.\\d+)(?: 이후)? 보안 테스트$");
    private static final Pattern TEST_ID =
            Pattern.compile("(?<![A-Za-z0-9-])T-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?:-\\*)?");
    private static final Pattern CHECKLIST_ROW = Pattern.compile("^\\| `(T-[A-Z*-]+)` \\| (.+) \\|$");

    private static final Set<String> NOT_YET = Set.of("T-AUTHZ-MATRIX");

    @Test
    @DisplayName("security.md §15.1의 v0.1 보안 테스트 ID마다 그 ID를 이름에 적은 테스트가 하나 이상 있음")
    void everyStageTestIdHasTests() throws IOException {
        Map<String, Set<String>> tests = testsById(documentedIds().get(STAGE));

        List<String> missing = tests.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty() && !NOT_YET.contains(entry.getKey()))
                .map(Map.Entry::getKey).toList();
        List<String> done = NOT_YET.stream().filter(id -> !tests.getOrDefault(id, Set.of("없는 ID")).isEmpty())
                .toList();

        assertThat(tests).hasSizeGreaterThan(30);
        assertThat(missing).as("테스트가 없는 v0.1 보안 테스트 ID").isEmpty();
        assertThat(done).as("테스트가 생겼거나 문서에 없는 ID는 NOT_YET에서 지움").isEmpty();
    }

    @Test
    @DisplayName("테스트 이름에 적은 보안 테스트 ID는 모두 security.md §15.1에 있음")
    void testNamesUseDocumentedIds() throws IOException {
        Set<String> documented = new TreeSet<>();
        documentedIds().values().forEach(documented::addAll);
        Set<String> unknown = new TreeSet<>();

        for (String name : displayNames().keySet()) {
            Matcher matcher = TEST_ID.matcher(name);
            while (matcher.find()) {
                String id = matcher.group();
                if (documented.stream().noneMatch(known -> matches(known, id))) {
                    unknown.add(id + " (" + name + ")");
                }
            }
        }

        assertThat(unknown).as("security.md §15.1에 없는 테스트 ID").isEmpty();
    }

    @Test
    @DisplayName("release-checklist.md의 v0.1 보안 테스트 연결표가 실제 테스트 클래스와 같음")
    void checklistMatchesTests() throws IOException {
        Map<String, Set<String>> tests = testsById(documentedIds().get(STAGE));
        Map<String, String> expected = new LinkedHashMap<>();
        tests.forEach((id, classes) -> expected.put(id, classes.isEmpty() ? "없음" : String.join(", ", classes)));
        Map<String, String> documented = new LinkedHashMap<>();
        for (String line : Files.readAllLines(CHECKLIST_DOC, StandardCharsets.UTF_8)) {
            Matcher row = CHECKLIST_ROW.matcher(line);
            if (row.matches()) {
                documented.put(row.group(1), row.group(2).replace("`", ""));
            }
        }

        assertThat(documented).as("연결표를 아래처럼 고침:\n" + table(expected)).isEqualTo(expected);
    }

    @Test
    @DisplayName("테스트 ID는 뒤에 붙은 숫자·소문자까지 한 토큰으로 읽어 틀린 ID를 놓치지 않음")
    void readsWholeTokens() {
        assertThat(ids("T-CSRF: 토큰 없는 요청")).containsExactly("T-CSRF");
        assertThat(ids("T-CSRF2: 토큰 없는 요청")).containsExactly("T-CSRF2");
        assertThat(ids("T-CSRFx와 T-AUTH-STATE-1")).containsExactly("T-CSRFx", "T-AUTH-STATE-1");
        assertThat(ids("TEN-LINK-01·SEC-TOKEN-05·T-TOKEN: 상한")).containsExactly("T-TOKEN");
        assertThat(ids("RT-EVT-02, GET-T-A, SEC-AUTHZ-10")).isEmpty();
        assertThat(ids("| T-TOKEN-* | 토큰 |")).containsExactly("T-TOKEN-*");
        assertThat(matches("T-TOKEN-*", "T-TOKEN")).isTrue();
        assertThat(matches("T-TOKEN-*", "T-TOKEN-HASH")).isTrue();
        assertThat(matches("T-TOKEN-*", "T-TOKENS")).isFalse();
        assertThat(matches("T-CSRF", "T-CSRF2")).isFalse();
    }

    private static List<String> ids(String text) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = TEST_ID.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    private static String table(Map<String, String> rows) {
        StringBuilder table = new StringBuilder();
        rows.forEach((id, classes) -> table.append("| `").append(id).append("` | ")
                .append(classes.equals("없음") ? classes : "`" + classes.replace(", ", "`, `") + "`").append(" |\n"));
        return table.toString();
    }

    private static Map<String, List<String>> documentedIds() throws IOException {
        Map<String, List<String>> ids = new LinkedHashMap<>();
        String stage = null;
        boolean inSection = false;
        for (String line : Files.readAllLines(SECURITY_DOC, StandardCharsets.UTF_8)) {
            if (line.startsWith("### ")) {
                inSection = line.startsWith("### 15.1 ");
                stage = null;
                continue;
            }
            Matcher heading = STAGE_HEADING.matcher(line);
            if (heading.matches()) {
                stage = heading.group(1);
                continue;
            }
            if (!inSection || stage == null || !line.startsWith("| T-")) {
                continue;
            }
            Matcher matcher = TEST_ID.matcher(line.split("\\|")[1]);
            while (matcher.find()) {
                ids.computeIfAbsent(stage, key -> new ArrayList<>()).add(matcher.group());
            }
        }
        assertThat(ids).as("security.md §15.1 단계별 표").containsKey(STAGE);
        return ids;
    }

    private static Map<String, Set<String>> testsById(List<String> ids) {
        Map<String, Set<String>> names = displayNames();
        Map<String, Set<String>> tests = new LinkedHashMap<>();
        for (String id : ids) {
            Set<String> classes = new TreeSet<>();
            names.forEach((name, owners) -> {
                Matcher matcher = TEST_ID.matcher(name);
                while (matcher.find()) {
                    if (matches(id, matcher.group())) {
                        classes.addAll(owners);
                    }
                }
            });
            tests.put(id, classes);
        }
        return tests;
    }

    private static boolean matches(String documented, String used) {
        if (documented.endsWith("-*")) {
            String family = documented.substring(0, documented.length() - 2);
            return used.equals(family) || used.startsWith(family + "-");
        }
        return documented.equals(used);
    }

    private static Map<String, Set<String>> displayNames() {
        Map<String, Set<String>> names = new TreeMap<>();
        for (Class<?> type : ReflectionSupport.findAllClassesInPackage("reberu", candidate -> true, name -> true)) {
            Method[] methods;
            try {
                methods = type.getDeclaredMethods();
            }
            catch (LinkageError unavailable) {
                continue;
            }
            Class<?> top = type;
            while (top.getEnclosingClass() != null) {
                top = top.getEnclosingClass();
            }
            for (Method method : methods) {
                DisplayName name = method.getAnnotation(DisplayName.class);
                if (name != null && method.isAnnotationPresent(Test.class)) {
                    names.computeIfAbsent(name.value(), key -> new LinkedHashSet<>()).add(top.getSimpleName());
                }
            }
        }
        assertThat(names).as("@DisplayName이 붙은 테스트").hasSizeGreaterThan(300);
        return names;
    }

}

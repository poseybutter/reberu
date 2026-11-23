package reberu.core.module.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.util.ClassUtils;
import reberu.core.module.ModuleDefinition;
import reberu.core.module.ModuleDefinition.MigrationLocation;
import reberu.core.module.ModuleDefinition.Source;

class ModuleRegistry {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final String UPSTREAM_PACKAGE = "reberu.";

    private final Map<String, ModuleDefinition> modules;
    private final Map<String, String> packages;

    ModuleRegistry(List<ModuleDefinition> definitions) {
        List<String> violations = violations(definitions);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("모듈 등록 오류(ARCH-MOD-01):\n" + String.join("\n", violations));
        }
        Map<String, ModuleDefinition> byKey = new LinkedHashMap<>();
        Map<String, String> byPackage = new HashMap<>();
        definitions.stream().sorted(Comparator.comparing(ModuleDefinition::key)).forEach(definition -> {
            byKey.put(definition.key(), definition);
            byPackage.put(packageOf(definition), definition.key());
        });
        this.modules = Map.copyOf(byKey);
        this.packages = Map.copyOf(byPackage);
    }

    Optional<ModuleDefinition> find(String key) {
        return Optional.ofNullable(modules.get(key));
    }

    List<ModuleDefinition> all() {
        return modules.values().stream().sorted(Comparator.comparing(ModuleDefinition::key)).toList();
    }

    Optional<String> owner(Class<?> type) {
        String name = ClassUtils.getUserClass(type).getPackageName();
        return packages.entrySet().stream()
                .filter(entry -> name.equals(entry.getKey()) || name.startsWith(entry.getKey() + "."))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue);
    }

    static List<String> violations(List<ModuleDefinition> definitions) {
        List<String> violations = new ArrayList<>();
        Map<String, ModuleDefinition> byKey = new HashMap<>();
        Set<String> seenPackages = new HashSet<>();
        for (ModuleDefinition definition : definitions) {
            String key = definition.key();
            String where = key + " " + ClassUtils.getUserClass(definition).getName();
            if (key == null || !KEY.matcher(key).matches()) {
                violations.add("모듈 key 형식 오류: " + where);
            }
            if (isBlank(definition.name()) || isBlank(definition.description())) {
                violations.add("모듈 이름·설명 없음: " + where);
            }
            if (definition.scope() == null || definition.source() == null) {
                violations.add("모듈 범위·출처 없음: " + where);
            }
            if (key != null && byKey.putIfAbsent(key, definition) != null) {
                violations.add("모듈 key 중복(V1): " + where);
            }
            if (!seenPackages.add(packageOf(definition))) {
                violations.add("모듈 패키지 중복: " + where);
            }
            MigrationLocation location = definition.migrationLocation();
            if ((definition.source() == Source.BUNDLED && location == MigrationLocation.COMPANY)
                    || (definition.source() == Source.EXTENSION && location == MigrationLocation.CORE)) {
                violations.add("출처와 마이그레이션 위치 다름(V5): " + where);
            }
            if (definition.source() == Source.EXTENSION && packageOf(definition).startsWith(UPSTREAM_PACKAGE)) {
                violations.add("확장 모듈이 업스트림 패키지 안에 있음(ARCH-PKG-04): " + where);
            }
        }
        for (ModuleDefinition definition : byKey.values()) {
            for (String required : definition.requires()) {
                ModuleDefinition target = byKey.get(required);
                if (target == null || required.equals(definition.key())) {
                    violations.add("필요한 모듈 없음(V2): " + definition.key() + " → " + required);
                }
                else if (target.scope() != definition.scope()) {
                    violations.add("범위가 다른 모듈은 필요로 할 수 없음: " + definition.key() + " → " + required);
                }
            }
        }
        cycles(byKey).forEach(key -> violations.add("필요한 모듈 순환(V2): " + key));
        duplicates(definitions, ModuleDefinition::expSources)
                .forEach(name -> violations.add("EXP 출처 중복(V6): " + name));
        duplicates(definitions, ModuleDefinition::notificationKinds)
                .forEach(name -> violations.add("알림 종류 중복(V6): " + name));
        duplicates(definitions, ModuleDefinition::realtimeEvents)
                .forEach(name -> violations.add("실시간 이벤트 이름 중복(V6): " + name));
        return violations;
    }

    private static List<String> cycles(Map<String, ModuleDefinition> byKey) {
        List<String> cyclic = new ArrayList<>();
        Map<String, Boolean> done = new HashMap<>();
        byKey.keySet().stream().sorted().forEach(key -> {
            if (!done.containsKey(key) && visit(key, byKey, done, new HashSet<>())) {
                cyclic.add(key);
            }
        });
        return cyclic;
    }

    private static boolean visit(String key, Map<String, ModuleDefinition> byKey, Map<String, Boolean> done,
            Set<String> path) {
        if (path.contains(key)) {
            return true;
        }
        if (done.containsKey(key) || !byKey.containsKey(key)) {
            return false;
        }
        path.add(key);
        boolean cyclic = false;
        for (String required : byKey.get(key).requires()) {
            if (!required.equals(key) && visit(required, byKey, done, path)) {
                cyclic = true;
            }
        }
        path.remove(key);
        done.put(key, cyclic);
        return cyclic;
    }

    private static List<String> duplicates(List<ModuleDefinition> definitions,
            Function<ModuleDefinition, Set<String>> names) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new HashSet<>();
        definitions.forEach(definition -> names.apply(definition).forEach(name -> {
            if (!seen.add(name)) {
                duplicated.add(name);
            }
        }));
        return duplicated.stream().sorted().toList();
    }

    private static String packageOf(ModuleDefinition definition) {
        return ClassUtils.getUserClass(definition).getPackageName();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}

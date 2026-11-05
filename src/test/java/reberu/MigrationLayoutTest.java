package reberu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MigrationLayoutTest {

    private static final List<String> VENDORS = List.of("postgresql", "mysql", "mariadb");
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    @DisplayName("버전 위치: core 한 곳 또는 세 DB 폴더 모두")
    void versionLivesInCoreOrAllVendors() throws Exception {
        Set<Integer> core = versions("db/migration/core");
        Set<Integer> postgresql = versions("db/migration/vendor/postgresql");

        for (String vendor : VENDORS) {
            Set<Integer> vendorVersions = versions("db/migration/vendor/" + vendor);
            assertThat(vendorVersions).as(vendor + "와 postgresql의 버전 목록").isEqualTo(postgresql);
            assertThat(vendorVersions).as(vendor + "와 core의 겹치는 버전").doesNotContainAnyElementsOf(core);
        }
    }

    @Test
    @DisplayName("SQL 파일 이름 정수 버전 형식")
    void sqlFileNamesUseIntegerVersions() throws Exception {
        for (String location : locations()) {
            for (String name : fileNames(location)) {
                assertThat(name).as(location + "/" + name).matches(VERSIONED);
            }
        }
    }

    private static List<String> locations() {
        return Stream.concat(
                Stream.of("db/migration/core"),
                VENDORS.stream().map(vendor -> "db/migration/vendor/" + vendor)
        ).toList();
    }

    private static Set<Integer> versions(String location) throws IOException, URISyntaxException {
        Set<Integer> versions = new TreeSet<>();
        for (String name : fileNames(location)) {
            Matcher matcher = VERSIONED.matcher(name);
            if (matcher.matches()) {
                assertThat(versions.add(Integer.parseInt(matcher.group(1))))
                        .as(location + "의 중복 버전: " + name)
                        .isTrue();
            }
        }
        return versions;
    }

    private static List<String> fileNames(String location) throws IOException, URISyntaxException {
        URL url = MigrationLayoutTest.class.getClassLoader().getResource(location);
        assertThat(url).as(location + " 폴더").isNotNull();
        try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .toList();
        }
    }

}

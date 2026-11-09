package reberu.core.tenancy.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.JobType;
import reberu.core.tenancy.repository.JobTypeRepository;
import tools.jackson.databind.json.JsonMapper;

public class DefaultJobTypes {

    static final List<DefaultJobType> ALL = List.of(
            new DefaultJobType("basic", "기본", "어떤 일에도 쓰는 기본 설정",
                    new JobTypeDefaults(List.of(), List.of("일반", "회의", "문서"), List.of(), 1)),
            new DefaultJobType("dev", "개발", "기능을 만들고 고치는 팀",
                    new JobTypeDefaults(List.of(), List.of("기능", "버그", "개선", "문서"), List.of("배포 일정"), 1)),
            new DefaultJobType("design", "디자인", "화면과 그림을 만드는 팀",
                    new JobTypeDefaults(List.of(), List.of("시안", "피드백", "자료"), List.of("검토 요청"), 1)),
            new DefaultJobType("ops", "운영", "요청을 받고 점검하는 팀",
                    new JobTypeDefaults(List.of(), List.of("요청", "점검", "정리"), List.of("운영 공지"), 1)));

    private final JobTypeRepository jobTypes;
    private final JsonMapper jsonMapper;

    DefaultJobTypes(JobTypeRepository jobTypes, JsonMapper jsonMapper) {
        this.jobTypes = jobTypes;
        this.jsonMapper = jsonMapper;
    }

    public List<DefaultJobType> all() {
        return ALL;
    }

    static Optional<DefaultJobType> find(String key) {
        return ALL.stream().filter(type -> type.key().equals(key)).findFirst();
    }

    Map<String, JobType> copyTo(UUID orgId, Instant at) {
        Map<String, JobType> copied = new LinkedHashMap<>();
        for (DefaultJobType type : ALL) {
            copied.put(type.key(), jobTypes.save(JobType.create(Uuids.v7(), orgId, type.name(), type.description(),
                    jsonMapper.writeValueAsString(type.defaults()), at)));
        }
        return copied;
    }

    String noticeTitles(DefaultJobType type) {
        return jsonMapper.writeValueAsString(type.defaults().noticeTitles());
    }

    public record DefaultJobType(String key, String name, String description, JobTypeDefaults defaults) {
    }

    public record JobTypeDefaults(List<String> modules, List<String> taskLabels, List<String> noticeTitles,
            int reportWeekStart) {
    }

}

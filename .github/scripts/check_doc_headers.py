#!/usr/bin/env python3
"""docs/ 아래 Markdown 문서의 머리말(버전, 수정일) 검사.

PR에서 바뀐 문서마다 다음을 확인한다.
- 제목 바로 아래 머리말 형식
- 고친 문서는 기준 브랜치보다 높은 버전
- 수정일은 그 파일을 마지막으로 고친 커밋의 날짜(±1일), 기준 브랜치의 수정일 이상, 미래가 아님

사용법: check_doc_headers.py <기준 커밋> [대상 커밋, 기본 HEAD]
"""

import datetime
import re
import subprocess
import sys

# 버전 줄 끝의 역슬래시는 Markdown 줄바꿈
VERSION_LINE = re.compile(r"^> 버전: v(\d+)\.(\d+)\.(\d+)\\?$")
DATE_LINE = re.compile(r"^> 수정일: (\d{4}-\d{2}-\d{2})$")
DOC_PATHSPEC = ":(glob)docs/**/*.md"
# 커밋 시각과 머리말 날짜의 시간대 차이 허용
DATE_TOLERANCE_DAYS = 1
FORMAT_HINT = r"제목 아래에 '> 버전: vX.Y.Z\'와 '> 수정일: YYYY-MM-DD' 두 줄을 차례로 둔다"


class HeaderError(Exception):
    pass


def git(*args):
    result = subprocess.run(
        ["git", "-c", "core.quotePath=false", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    )
    return result.stdout


def read_at(commit, path):
    return git("show", f"{commit}:{path}")


def parse_header(text):
    """제목 다음의 첫 두 비어 있지 않은 줄을 머리말로 읽는다."""
    lines = text.replace("\r\n", "\n").split("\n")
    if not lines or not lines[0].startswith("# "):
        raise HeaderError("첫 줄이 '# 제목'이 아니다")
    body = [line.rstrip() for line in lines[1:] if line.strip()]
    if len(body) < 2:
        raise HeaderError(f"머리말이 없다. {FORMAT_HINT}")
    version_match = VERSION_LINE.match(body[0])
    date_match = DATE_LINE.match(body[1])
    if not version_match or not date_match:
        raise HeaderError(f"머리말 형식이 틀리다. {FORMAT_HINT}")
    version = tuple(int(part) for part in version_match.groups())
    try:
        date = datetime.date.fromisoformat(date_match.group(1))
    except ValueError:
        raise HeaderError(f"수정일이 실제 날짜가 아니다: {date_match.group(1)}") from None
    return version, date


def changed_docs(base, head):
    """기준과 대상의 합류 지점 이후 바뀐 문서 목록. (상태, 옛 경로, 새 경로)"""
    output = git("diff", "--name-status", "-M", f"{base}...{head}", "--", DOC_PATHSPEC)
    entries = []
    for line in output.splitlines():
        parts = line.split("\t")
        status = parts[0][0]
        if status == "D":
            continue
        if status == "R":
            entries.append((status, parts[1], parts[2]))
        else:
            entries.append((status, parts[1], parts[1]))
    return entries


def check(base, head):
    merge_base = git("merge-base", base, head).strip()
    latest_allowed = datetime.datetime.now(datetime.timezone.utc).date() + datetime.timedelta(days=1)
    errors = []
    entries = changed_docs(base, head)

    for status, old_path, new_path in entries:
        try:
            version, date = parse_header(read_at(head, new_path))
        except HeaderError as error:
            errors.append((new_path, str(error)))
            continue

        if date > latest_allowed:
            errors.append((new_path, f"수정일이 미래다: {date}"))

        # 같은 날 여러 번 고쳐도 되도록, 날짜는 그 파일을 마지막으로 고친 커밋 날짜와 맞춘다
        commit_date = last_commit_date(head, new_path)
        if abs((date - commit_date).days) > DATE_TOLERANCE_DAYS:
            errors.append((new_path, f"수정일이 마지막으로 고친 날과 다르다: 머리말 {date}, 커밋 {commit_date}"))

        if status not in ("M", "R"):
            continue
        try:
            old_version, old_date = parse_header(read_at(merge_base, old_path))
        except HeaderError:
            # 머리말을 처음 넣는 문서는 형식만 확인
            continue

        if version <= old_version:
            errors.append((new_path, f"버전을 올리지 않았다: {format_version(old_version)} → {format_version(version)}"))
        if date < old_date:
            errors.append((new_path, f"수정일이 이전 수정일보다 앞선다: {old_date} → {date}"))

    for path, message in errors:
        print(f"::error file={path}::{message}")
    if errors:
        print(f"문서 머리말 검사 실패: {len(errors)}건. 규칙은 docs/README.md §2.4")
        return 1
    print(f"문서 머리말 검사 통과: {len(entries)}개 문서")
    return 0


def last_commit_date(commit, path):
    """commit까지의 이력에서 path를 마지막으로 바꾼 커밋의 날짜(커밋한 사람 기준 시간대)."""
    text = git("log", "-1", "--format=%cd", "--date=format:%Y-%m-%d", commit, "--", path).strip()
    return datetime.date.fromisoformat(text)


def format_version(version):
    return "v" + ".".join(str(part) for part in version)


def main(argv):
    # 로컬 Windows 콘솔에서도 한국어 메시지가 깨지지 않게
    sys.stdout.reconfigure(encoding="utf-8")
    if len(argv) not in (2, 3):
        print(__doc__)
        return 2
    base = argv[1]
    head = argv[2] if len(argv) == 3 else "HEAD"
    return check(base, head)


if __name__ == "__main__":
    sys.exit(main(sys.argv))

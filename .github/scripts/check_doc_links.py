#!/usr/bin/env python3
"""Markdown 문서의 링크, 절 참조, 요구사항 ID 검사.

docs/ 아래 문서와 저장소 루트의 README.md, CONTRIBUTING.md, AGENTS.md 전체를 읽어 다음을 확인한다.
- 상대 링크와 그림 경로가 가리키는 파일이 있다
- `#절` 링크가 가리키는 제목이 있다
- "문서 링크 + § 절 번호"가 가리키는 번호 붙은 절이 있다
- 요구사항 ID 정의가 두 번 나오지 않는다
- 쓰인 요구사항 ID가 어딘가에 정의돼 있다

사용법: check_doc_links.py [저장소 루트, 기본 현재 폴더]
"""

import collections
import os
import pathlib
import re
import sys
import unicodedata
import urllib.parse

ROOT_DOCS = ("README.md", "CONTRIBUTING.md", "AGENTS.md")
ID = r"[A-Z]{2,6}(?:-[A-Z0-9]{2,10})+-\d{2,3}"
# 정의는 표의 첫 칸(`| SEC-AUTH-01 |`, `| UX-FLOW-62 (필수) |`)이나 목록 항목(`- API-OPS-01 (필수):`)
ID_DEFINITION = re.compile(rf"^(?:\|\s*`?|[-*] )({ID})`?\s*(?:\||\()")
# `SEC-RATE-01~08`, `ARCH-EVT-05·10·11`처럼 번호만 이어 쓴 참조도 함께 읽는다
ID_REFERENCE = re.compile(rf"(?<![A-Za-z0-9-])({ID})((?:`?[~·]`?\d{{2,3}}(?![\d.]))*)")
LINK = re.compile(r"!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
SECTION_REFERENCES = re.compile(r"\s*((?:§\s?\d+(?:\.\d+)*(?:\s*[,·~]\s*)?)+)")
SECTION_NUMBER = re.compile(r"§\s?(\d+(?:\.\d+)*)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
NUMBERED_HEADING = re.compile(r"^(\d+(?:\.\d+)*)\.?(?:\s|$)")
CODE_SPAN = re.compile(r"`[^`]*`")
SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")


class Document:

    def __init__(self, root, path):
        self.path = path
        self.name = path.relative_to(root).as_posix()
        self.lines = without_code_blocks(path.read_text(encoding="utf-8"))
        self.anchors = set()
        self.sections = set()
        seen = collections.Counter()
        for line in self.lines:
            match = HEADING.match(line)
            if not match:
                continue
            title = match.group(2)
            base = anchor_of(title)
            self.anchors.add(base if seen[base] == 0 else f"{base}-{seen[base]}")
            seen[base] += 1
            numbered = NUMBERED_HEADING.match(title)
            if numbered:
                self.sections.add(numbered.group(1))


def without_code_blocks(text):
    """코드 블록 안은 빈 줄로 바꿔 줄 번호를 지킨다."""
    lines = []
    fence = None
    for line in text.replace("\r\n", "\n").split("\n"):
        stripped = line.lstrip()
        marker = stripped[:3]
        if fence is None and marker in ("```", "~~~"):
            fence = marker
            lines.append("")
        elif fence is not None:
            if stripped.startswith(fence):
                fence = None
            lines.append("")
        else:
            lines.append(line)
    return lines


def anchor_of(title):
    """GitHub가 제목으로 만드는 anchor."""
    title = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", title).replace("`", "")
    kept = []
    for char in title.strip().lower():
        category = unicodedata.category(char)
        if char in "-_ " or category[0] in "LN" or category in ("Mn", "Mc"):
            kept.append(char)
    return "".join(kept).replace(" ", "-")


def find_documents(root):
    paths = sorted((root / "docs").rglob("*.md")) if (root / "docs").is_dir() else []
    paths += [root / name for name in ROOT_DOCS if (root / name).is_file()]
    return [Document(root, path) for path in paths]


def check(root):
    root = root.resolve()
    documents = find_documents(root)
    by_path = {document.path: document for document in documents}
    errors = []
    definitions = collections.defaultdict(list)
    references = []

    for document in documents:
        for number, line in enumerate(document.lines, 1):
            definition = ID_DEFINITION.match(line)
            if definition:
                definitions[definition.group(1)].append((document.name, number))
            for match in ID_REFERENCE.finditer(line):
                for identifier in expand(match.group(1), match.group(2)):
                    references.append((identifier, document.name, number))
            check_links(root, document, number, CODE_SPAN.sub("", line), by_path, errors)

    for identifier, places in sorted(definitions.items()):
        if len(places) > 1:
            first = f"{places[0][0]}:{places[0][1]}"
            for name, number in places[1:]:
                errors.append((name, number, f"요구사항 ID 정의가 겹친다: {identifier} (먼저 정의한 곳 {first})"))
    prefixes = {identifier.split("-")[0] for identifier in definitions}
    for identifier, name, number in references:
        if identifier not in definitions and identifier.split("-")[0] in prefixes:
            errors.append((name, number, f"정의가 없는 요구사항 ID: {identifier}"))

    return documents, len(definitions), sorted(set(errors))


def expand(identifier, tail):
    """`SEC-RATE-01`과 뒤에 붙은 `~08`, `·10`을 ID 목록으로 푼다. 범위는 양 끝만 본다."""
    stem = identifier.rsplit("-", 1)[0]
    return [identifier] + [f"{stem}-{number}" for number in re.findall(r"\d{2,3}", tail)]


def check_links(root, document, number, line, by_path, errors):
    for match in LINK.finditer(line):
        target = match.group(1)
        if SCHEME.match(target):
            continue
        file_part, _, fragment = target.partition("#")
        destination = document.path if not file_part else document.path.parent / urllib.parse.unquote(file_part)
        # resolve()는 Windows에서 대소문자를 실제 이름으로 바꾸므로 쓰지 않는다
        destination = pathlib.Path(os.path.normpath(destination))
        if root not in destination.parents and destination != root:
            errors.append((document.name, number, f"저장소 밖을 가리키는 링크: {target}"))
            continue
        if not exists_exactly(root, destination):
            errors.append((document.name, number, f"링크가 가리키는 파일이 없다: {target}"))
            continue
        linked = by_path.get(destination)
        if linked is None:
            continue
        if fragment and urllib.parse.unquote(fragment).lower() not in linked.anchors:
            errors.append((document.name, number, f"링크가 가리키는 제목이 없다: {target}"))
        sections = SECTION_REFERENCES.match(line, match.end())
        if not sections:
            continue
        for section in SECTION_NUMBER.findall(sections.group(1)):
            if section not in linked.sections:
                errors.append((document.name, number, f"{linked.name}에 §{section} 절이 없다"))


def exists_exactly(root, destination):
    """대소문자까지 같은 이름으로 있는지. Windows·macOS에서도 Linux와 같은 결과를 낸다."""
    current = root
    for part in destination.relative_to(root).parts:
        if not current.is_dir() or part not in {child.name for child in current.iterdir()}:
            return False
        current = current / part
    return True


def main(argv):
    # 로컬 Windows 콘솔에서도 한국어 메시지가 깨지지 않게
    sys.stdout.reconfigure(encoding="utf-8")
    if len(argv) > 2:
        print(__doc__)
        return 2
    root = pathlib.Path(argv[1]) if len(argv) == 2 else pathlib.Path.cwd()
    documents, definitions, errors = check(root)
    for name, number, message in errors:
        print(f"::error file={name},line={number}::{message}")
    if errors:
        print(f"문서 링크·요구사항 ID 검사 실패: {len(errors)}건. 규칙은 docs/README.md §2.5")
        return 1
    print(f"문서 링크·요구사항 ID 검사 통과: 문서 {len(documents)}개, 요구사항 ID {definitions}개")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

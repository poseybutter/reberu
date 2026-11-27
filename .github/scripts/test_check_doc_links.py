#!/usr/bin/env python3
"""check_doc_links.py 검사 사례.

사용법: python3 .github/scripts/test_check_doc_links.py
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import check_doc_links  # noqa: E402

REPOSITORY = pathlib.Path(__file__).resolve().parents[2]


class CheckDocLinksTest(unittest.TestCase):

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.directory.name)
        self.write("docs/rules.md", """# 규칙

            ## 1. 범위

            | ID | 요구사항 | 수준 |
            | --- | --- | --- |
            | SEC-AUTH-01 | 로그인 | 필수 |
            | SEC-AUTH-02 | 로그아웃 | 필수 |
            | `SEC-AUTH-03` | 세션 | 필수 |

            ### 1.1 세부

            - API-PAGE-01 (필수): 커서
            - API-PAGE-02 (필수): 쪽 크기

            ## 2. 가입 방법

            ## 2. 가입 방법
            """)

    def tearDown(self):
        self.directory.cleanup()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        lines = text.split("\n")
        indent = len(lines[-1]) if lines[-1].strip() == "" else 0
        body = [lines[0]] + [line[indent:] if line[:indent].strip() == "" else line for line in lines[1:]]
        path.write_text("\n".join(body), encoding="utf-8")

    def messages(self):
        return [f"{name}:{number} {message}" for name, number, message in check_doc_links.check(self.root)[2]]

    def test_valid_documents_pass(self):
        self.write("docs/guide.md", """# 안내

            [규칙](rules.md) §1, §1.1·§2 그리고 [가입](rules.md#2-가입-방법), [두 번째](rules.md#2-가입-방법-1)
            SEC-AUTH-01~03, API-PAGE-01·02, `SEC-AUTH-02`를 따른다. ![그림](images/a.png)
            [바깥](https://example.com/none.md#x) [메일](mailto:someone@example.com) ISO-8859-15, UTF-8-01
            """)
        self.write("docs/images/a.png", "그림")
        self.write("README.md", "# 저장소\n\n[안내](docs/guide.md), [규칙](docs/rules.md) §2 SEC-AUTH-03\n")

        documents, definitions, errors = check_doc_links.check(self.root)

        self.assertEqual(errors, [])
        self.assertEqual(len(documents), 3)
        self.assertEqual(definitions, 5)

    def test_missing_file_and_image(self):
        self.write("docs/guide.md", "# 안내\n\n[없음](nothing.md)\n![그림](images/none.png)\n")

        self.assertEqual(self.messages(), [
            "docs/guide.md:3 링크가 가리키는 파일이 없다: nothing.md",
            "docs/guide.md:4 링크가 가리키는 파일이 없다: images/none.png",
        ])

    def test_file_name_case_must_match(self):
        self.write("docs/guide.md", "# 안내\n\n[규칙](Rules.md) [규칙](rules.md) [폴더](../docs)\n")

        self.assertEqual(self.messages(), ["docs/guide.md:3 링크가 가리키는 파일이 없다: Rules.md"])

    def test_missing_anchor(self):
        self.write("docs/guide.md", "# 안내\n\n[가입](rules.md#3-없는-절) [여기](#없는-제목) [여기](#안내)\n")

        self.assertEqual(self.messages(), [
            "docs/guide.md:3 링크가 가리키는 제목이 없다: #없는-제목",
            "docs/guide.md:3 링크가 가리키는 제목이 없다: rules.md#3-없는-절",
        ])

    def test_missing_section(self):
        self.write("docs/guide.md", "# 안내\n\n[규칙](rules.md) §1.2\n\n[규칙](rules.md) §2, §3·§1.1\n")

        self.assertEqual(self.messages(), [
            "docs/guide.md:3 docs/rules.md에 §1.2 절이 없다",
            "docs/guide.md:5 docs/rules.md에 §3 절이 없다",
        ])

    def test_duplicate_definition(self):
        self.write("docs/other.md", "# 다른 문서\n\n| SEC-AUTH-02 | 겹침 | 필수 |\n\n- API-PAGE-01 (권장): 겹침\n")

        self.assertEqual(self.messages(), [
            "docs/rules.md:8 요구사항 ID 정의가 겹친다: SEC-AUTH-02 (먼저 정의한 곳 docs/other.md:3)",
            "docs/rules.md:13 요구사항 ID 정의가 겹친다: API-PAGE-01 (먼저 정의한 곳 docs/other.md:5)",
        ])

    def test_undefined_reference(self):
        self.write("docs/guide.md", "# 안내\n\nSEC-AUTH-09를 따른다.\nSEC-AUTH-01~07, API-PAGE-01·05\n")
        self.write("AGENTS.md", "# 작업 안내\n\n`SEC-ATUH-01`\n")

        self.assertEqual(self.messages(), [
            "AGENTS.md:3 정의가 없는 요구사항 ID: SEC-ATUH-01",
            "docs/guide.md:3 정의가 없는 요구사항 ID: SEC-AUTH-09",
            "docs/guide.md:4 정의가 없는 요구사항 ID: API-PAGE-05",
            "docs/guide.md:4 정의가 없는 요구사항 ID: SEC-AUTH-07",
        ])

    def test_code_is_ignored(self):
        self.write("docs/guide.md", """# 안내

            ```markdown
            [없음](nothing.md) SEC-AUTH-99
            | SEC-AUTH-01 | 예시 | 필수 |
            ```

            `[없음](nothing.md)`
            """)

        self.assertEqual(self.messages(), [])

    def test_link_outside_repository(self):
        self.write("docs/guide.md", "# 안내\n\n[밖](../../outside.md)\n")

        self.assertEqual(self.messages(), ["docs/guide.md:3 저장소 밖을 가리키는 링크: ../../outside.md"])

    def test_exit_code_and_annotations(self):
        self.write("docs/guide.md", "# 안내\n\n[없음](nothing.md)\n")

        self.assertEqual(check_doc_links.main(["check_doc_links.py", str(self.root)]), 1)
        (self.root / "docs/guide.md").unlink()
        self.assertEqual(check_doc_links.main(["check_doc_links.py", str(self.root)]), 0)

    def test_repository_documents_pass(self):
        documents, definitions, errors = check_doc_links.check(REPOSITORY)

        self.assertEqual(errors, [])
        self.assertGreater(len(documents), 30)
        self.assertGreater(definitions, 900)


if __name__ == "__main__":
    unittest.main()

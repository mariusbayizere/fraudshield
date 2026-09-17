from __future__ import annotations

from fraudshield_tools.test_tags import is_test_file, tags_in_file


def _ids(rel: str, text: str) -> list[tuple[str, str]]:
    return [(t.requirement_id, t.location) for t in tags_in_file(rel, text)]


def test_python_multiline_decorator_class_marks_and_skips() -> None:
    source = """
import pytest

@pytest.mark.req(
    "FR-02-04",
    "D-05",
)
def test_multiline(): ...

# @pytest.mark.req("D-99")
def test_commented_tag(): ...

def helper():
    text = '@pytest.mark.req("D-98")'

@pytest.mark.skip(reason="flaky")
@pytest.mark.req("D-97")
def test_skipped(): ...

@pytest.mark.req("FR-03-01")
class TestDecisions:
    @pytest.mark.req("D-18")
    def test_boundary(self): ...

    def helper(self): ...

@pytest.mark.skip
@pytest.mark.req("D-96")
class TestSkipped:
    def test_x(self): ...
"""
    assert _ids("ml/tests/test_x.py", source) == [
        ("FR-02-04", "ml/tests/test_x.py:4"),
        ("D-05", "ml/tests/test_x.py:4"),
        ("FR-03-01", "ml/tests/test_x.py:20"),
        ("D-18", "ml/tests/test_x.py:22"),
    ]


def test_java_ignores_comments_strings_and_disabled_tests() -> None:
    source = """
class DecisionTest {
  // @Tag("D-99")
  /* @Tag("D-98") */
  String s = "@Tag(\\"D-97\\")";

  @Tag("FR-03-01")
  @ParameterizedTest
  @CsvSource({"0.85, HIGH", "0.84, MEDIUM"})
  @Disabled("pending")
  void disabledAfterBraces(double score, String tier) {}

  @Tag("D-18")
  @CsvSource({"a", "b"})
  @Test
  void active() {}
}
"""
    assert _ids("backend/x/src/test/java/DecisionTest.java", source) == [
        ("D-18", "backend/x/src/test/java/DecisionTest.java:13"),
    ]


def test_java_class_level_disabled_hides_all_tags() -> None:
    source = '@Disabled\nclass T {\n  @Tag("D-43")\n  @Test\n  void x() {}\n}\n'
    assert _ids("b/src/test/java/T.java", source) == []


def test_typescript_titles_including_next_line_tables_and_skips() -> None:
    source = """
it('[D-33, UX-DASH-01] white on amber fails', () => {});
it.each([
  { label: 'a' },
])(
  '[FR-04-05] renders $label',
  () => {},
);
// it('[D-99] commented out', () => {});
it.skip('[D-98] skipped', () => {});
xit('[D-97] x-skipped', () => {});
test.todo('[D-96] later');
it.skip.each([[1]])('[D-95] skipped table', () => {});
it('no tag here [D-01]', () => {});
describe('[FR-04-02] feed', () => {});
"""
    assert _ids("frontend/src/a.test.ts", source) == [
        ("D-33", "frontend/src/a.test.ts:2"),
        ("UX-DASH-01", "frontend/src/a.test.ts:2"),
        ("FR-04-05", "frontend/src/a.test.ts:6"),
        ("FR-04-02", "frontend/src/a.test.ts:15"),
    ]


def test_test_file_detection() -> None:
    assert is_test_file("backend/common/src/test/java/MoneyTest.java")
    assert not is_test_file("backend/common/src/main/java/Money.java")
    assert is_test_file("ml/tests/test_metrics.py")
    assert not is_test_file("ml/src/fraudshield_ml/metrics.py")
    assert is_test_file("frontend/src/contrast.test.ts")
    assert is_test_file("tests/e2e/login.spec.ts")
    assert not is_test_file("frontend/src/contrast.ts")


def test_python_disabling_forms_found_in_re_review() -> None:
    source = """
import pytest as pt
from pytest import mark
from pytest import mark as m

slow_skip = pt.mark.skip

@pt.mark.req("D-01")
@pt.mark.skipif(True, reason="always")
def test_skipif_true(): ...

@mark.skip
@mark.req("D-02")
def test_skip_via_from_import(): ...

@m.xfail(run=False)
@m.req("D-03")
def test_xfail_not_run(): ...

@pt.mark.xfail
@pt.mark.req("D-04")
def test_expected_failure_is_not_evidence(): ...

@slow_skip
@pt.mark.req("D-05")
def test_skip_through_variable(): ...

@pt.mark.skipif("sys.platform == 'win32'")
@mark.req("FR-01-01")
def test_conditional_skip_counts_until_executed_reports(): ...

class TestOuter:
    pytestmark = [pt.mark.req("FR-01-02")]

    class TestInner:
        @m.req("FR-01-03")
        def test_nested(self): ...
"""
    assert _ids("ml/tests/test_y.py", source) == [
        ("FR-01-01", "ml/tests/test_y.py:29"),
        ("FR-01-02", "ml/tests/test_y.py:33"),
        ("FR-01-03", "ml/tests/test_y.py:36"),
    ]


def test_python_module_pytestmark_skip_disables_file() -> None:
    source = (
        'import pytest\npytestmark = [pytest.mark.skip]\n\n@pytest.mark.req("D-01")\n'
        "def test_x(): ...\n"
    )
    assert _ids("t/test_z.py", source) == []


def test_java_forms_found_in_re_review() -> None:
    source = """
@Tag("FR-01-01")
class OuterTest {
  @Tag("D-01")
  @org.junit.jupiter.api.Disabled
  @Test
  void fullyQualifiedDisabled() {}

  @Tag("D-02")
  @EnabledIfEnvironmentVariable(named = "CI", matches = "true")
  @Test
  void conditionallyEnabled() {}

  @Tag("D-03")
  void helperWithoutTestAnnotation() {}

  @Tag("requires-docker")
  @Tag("FR-01-02")
  @Test
  void active() {
    Runnable r = () -> { char c = '{'; };
  }

  @Disabled
  @Nested
  class DisabledNested {
    @Tag("D-04")
    @Test
    void insideDisabledNested() {}
  }

  @Tag("D-05")
  @Nested
  class NestedWithoutEnabledTests {
    @Disabled @Test void off() {}
  }

  @ArchTest
  @Tag("D-06")
  static final Object rule = null;
}
"""
    assert _ids("b/src/test/java/OuterTest.java", source) == [
        ("FR-01-01", "b/src/test/java/OuterTest.java:2"),
        ("FR-01-02", "b/src/test/java/OuterTest.java:18"),
        ("D-06", "b/src/test/java/OuterTest.java:39"),
    ]


def test_typescript_forms_found_in_re_review() -> None:
    source = """
describe.skip('[FR-04-01] skipped suite', () => {
  it('[D-01] nested in skipped describe', () => {});
});
const note = "it('[D-02] inside a string', () => {})";
it.skipIf(process.env.CI)('[D-03] conditional', () => {});
it.fails('[D-04] expected failure', () => {});
describe('[FR-04-02] suite', () => {
  it('[FR-04-03] runs', () => {});
});
"""
    assert _ids("frontend/src/b.test.ts", source) == [
        ("FR-04-02", "frontend/src/b.test.ts:8"),
        ("FR-04-03", "frontend/src/b.test.ts:9"),
    ]

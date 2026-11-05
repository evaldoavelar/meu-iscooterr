import difflib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import date

# Add scripts/progress to sys.path
TEST_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(TEST_DIR, "../.."))
SCRIPTS_DIR = os.path.join(REPO_ROOT, "scripts", "progress")
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

import progress


class TestProgressValidation(unittest.TestCase):
    """Test validation of progress data."""

    def setUp(self):
        self.valid_data = {
            "project": "Test Project",
            "started": "2026-11-01",
            "state_values": ["done", "doing", "blocked", "todo", "unknown"],
            "weight_note": "peso",
            "phases": [
                {
                    "id": "F0",
                    "name": "Phase 0",
                    "tasks": [
                        {"id": "F0.1", "sprint": "S0", "title": "Task 1", "state": "done", "weight": 2, "done": "2026-11-01"},
                        {"id": "F0.2", "sprint": "S0", "title": "Task 2", "state": "todo", "weight": 3}
                    ]
                }
            ],
            "blockers": [],
            "criteria": []
        }

    def test_valid_data_passes(self):
        progress.validate_progress(self.valid_data)

    def test_duplicate_task_id_fails(self):
        data = json.loads(json.dumps(self.valid_data))
        data["phases"][0]["tasks"].append(
            {"id": "F0.1", "sprint": "S0", "title": "Duplicate", "state": "todo", "weight": 1}
        )
        with self.assertRaises(progress.ValidationError) as ctx:
            progress.validate_progress(data)
        self.assertIn("F0.1", str(ctx.exception))

    def test_invalid_state_fails(self):
        data = json.loads(json.dumps(self.valid_data))
        data["phases"][0]["tasks"][1]["state"] = "in_progress"
        with self.assertRaises(progress.ValidationError) as ctx:
            progress.validate_progress(data)
        self.assertIn("in_progress", str(ctx.exception))

    def test_invalid_weight_zero_or_negative_fails(self):
        data = json.loads(json.dumps(self.valid_data))
        data["phases"][0]["tasks"][1]["weight"] = 0
        with self.assertRaises(progress.ValidationError):
            progress.validate_progress(data)

        data["phases"][0]["tasks"][1]["weight"] = -1
        with self.assertRaises(progress.ValidationError):
            progress.validate_progress(data)

    def test_invalid_weight_non_numeric_fails(self):
        data = json.loads(json.dumps(self.valid_data))
        data["phases"][0]["tasks"][1]["weight"] = "tres"
        with self.assertRaises(progress.ValidationError):
            progress.validate_progress(data)

    def test_done_field_without_state_done_fails(self):
        data = json.loads(json.dumps(self.valid_data))
        data["phases"][0]["tasks"][1]["state"] = "todo"
        data["phases"][0]["tasks"][1]["done"] = "2026-11-02"
        with self.assertRaises(progress.ValidationError) as ctx:
            progress.validate_progress(data)
        self.assertIn("done", str(ctx.exception).lower())

    def test_invalid_done_date_format_fails(self):
        data = json.loads(json.dumps(self.valid_data))
        data["phases"][0]["tasks"][0]["done"] = "01-11-2026"
        with self.assertRaises(progress.ValidationError):
            progress.validate_progress(data)


class TestProgressCalculations(unittest.TestCase):
    """Test pure functions for credit, phase_percent, total_percent, pace, and eta."""

    def test_credit_pure_function(self):
        self.assertEqual(progress.credit({"state": "done"}), 1.0)
        self.assertEqual(progress.credit({"state": "doing"}), 0.5)
        self.assertEqual(progress.credit({"state": "blocked"}), 0.0)
        self.assertEqual(progress.credit({"state": "todo"}), 0.0)
        self.assertEqual(progress.credit({"state": "unknown"}), 0.0)
        self.assertEqual(progress.credit({}), 0.0)

    def test_phase_percent(self):
        phase = {
            "id": "F1",
            "name": "Phase 1",
            "tasks": [
                {"id": "F1.1", "weight": 2, "state": "done"},    # 2 * 1.0 = 2.0
                {"id": "F1.2", "weight": 2, "state": "doing"},   # 2 * 0.5 = 1.0
                {"id": "F1.3", "weight": 4, "state": "todo"},    # 4 * 0.0 = 0.0
                {"id": "F1.4", "weight": 2, "state": "todo", "est": True} # 2 * 0.0 = 0.0
            ]
        }
        # With est: total_weight = 10, earned = 3.0 -> 30.0%
        self.assertAlmostEqual(progress.phase_percent(phase, include_est=True), 30.0, places=4)
        # Without est: total_weight = 8, earned = 3.0 -> 37.5%
        self.assertAlmostEqual(progress.phase_percent(phase, include_est=False), 37.5, places=4)

    def test_phase_percent_empty(self):
        self.assertEqual(progress.phase_percent({"tasks": []}), 0.0)

    def test_total_percent(self):
        phases = [
            {
                "id": "F0",
                "tasks": [
                    {"id": "F0.1", "weight": 4, "state": "done"} # 4.0
                ]
            },
            {
                "id": "F1",
                "tasks": [
                    {"id": "F1.1", "weight": 2, "state": "doing"}, # 1.0
                    {"id": "F1.2", "weight": 4, "state": "todo", "est": True} # 0.0
                ]
            }
        ]
        # With est: total = 10, earned = 5.0 -> 50.0%
        self.assertAlmostEqual(progress.total_percent(phases, include_est=True), 50.0, places=4)
        # Without est: total = 6, earned = 5.0 -> 5.0 / 6 * 100 = 83.3333%
        self.assertAlmostEqual(progress.total_percent(phases, include_est=False), 83.333333, places=4)

    def test_pace_windows(self):
        # Started 2026-11-01, Today 2026-11-10 -> age = 10 days
        started = date(2026, 11, 1)
        today = date(2026, 11, 10)
        tasks = [
            {"id": "T1", "weight": 4, "state": "done", "done": "2026-11-10"}, # diff 0 -> in 3d, 7d, all
            {"id": "T2", "weight": 2, "state": "done", "done": "2026-11-08"}, # diff 2 -> in 3d, 7d, all
            {"id": "T3", "weight": 3, "state": "done", "done": "2026-11-05"}, # diff 5 -> in 7d, all
            {"id": "T4", "weight": 6, "state": "done", "done": "2026-11-02"}, # diff 8 -> in all only
            {"id": "T5", "weight": 5, "state": "doing"},                       # not done
            {"id": "T6", "weight": 7, "state": "done", "done": "2026-11-11"}, # future date, excluded
        ]
        # 3d window: tasks T1 (4) + T2 (2) = 6 weight. Divisor = 3 -> 2.0
        pace_3d = progress.pace(tasks, started, today, window_days=3)
        self.assertAlmostEqual(pace_3d, 2.0, places=4)

        # 7d window: tasks T1 (4) + T2 (2) + T3 (3) = 9 weight. Divisor = 7 -> 9/7 = 1.285714
        pace_7d = progress.pace(tasks, started, today, window_days=7)
        self.assertAlmostEqual(pace_7d, 9.0 / 7.0, places=4)

        # all window: tasks T1 (4) + T2 (2) + T3 (3) + T4 (6) = 15 weight. Divisor = 10 -> 1.5
        pace_all = progress.pace(tasks, started, today, window_days=None)
        self.assertAlmostEqual(pace_all, 1.5, places=4)

    def test_pace_limited_to_project_age(self):
        # Project age is 2 days (started Nov 4, today Nov 5)
        started = date(2026, 11, 4)
        today = date(2026, 11, 5)
        tasks = [
            {"id": "T1", "weight": 6, "state": "done", "done": "2026-11-05"}
        ]
        # 7d window capped at project age (2 days): 6 / 2 = 3.0
        pace_7d = progress.pace(tasks, started, today, window_days=7)
        self.assertAlmostEqual(pace_7d, 3.0, places=4)

    def test_pace_minimum_one_day(self):
        # Project started today (age = 1)
        started = date(2026, 11, 5)
        today = date(2026, 11, 5)
        tasks = [
            {"id": "T1", "weight": 5, "state": "done", "done": "2026-11-05"}
        ]
        pace_all = progress.pace(tasks, started, today, window_days=None)
        self.assertAlmostEqual(pace_all, 5.0, places=4)

    def test_eta_calculation(self):
        remaining = 15.0
        today = date(2026, 11, 5)
        paces = [3.0, 5.0, 2.5]
        # min days = 15 / 5.0 = 3.0 -> date 2026-11-08
        # max days = 15 / 2.5 = 6.0 -> date 2026-11-11
        res = progress.eta(remaining, paces, today)
        self.assertEqual(res["status"], "ok")
        self.assertAlmostEqual(res["min_days"], 3.0, places=4)
        self.assertAlmostEqual(res["max_days"], 6.0, places=4)
        self.assertEqual(res["min_date"], "2026-11-08")
        self.assertEqual(res["max_date"], "2026-11-11")

    def test_eta_zero_pace_indeterminate(self):
        remaining = 10.0
        today = date(2026, 11, 5)
        paces = [0.0, 0.0]
        res = progress.eta(remaining, paces, today)
        self.assertEqual(res["status"], "indeterminado")


class TestStatusAndResearchExtraction(unittest.TestCase):
    """Test extracting STATUS and research tags."""

    def test_extract_status_last_session_line(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            status_file = os.path.join(tmpdir, "STATUS.md")
            with open(status_file, "w", encoding="utf-8") as f:
                f.write("# Status\n")
                f.write("- Sprint 0: done\n")
                f.write("- Sessão 01 (2026-11-01): primeira sessão\n")
                f.write("Algum comentário no meio\n")
                f.write("- Sessão 02 (2026-11-02): segunda sessão com " + ("x" * 400) + "\n")

            line = progress.extract_status_summary(status_file)
            self.assertTrue(line.startswith("- Sessão 02 (2026-11-02):"))
            self.assertTrue(len(line) <= 305) # ~300 chars plus ellipsis
            self.assertTrue(line.endswith("..."))

    def test_extract_research_hypotheses_and_unknowns(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            docs_dir = os.path.join(tmpdir, "docs", "research", "parts")
            os.makedirs(docs_dir, exist_ok=True)
            f1 = os.path.join(docs_dir, "t1.md")
            with open(f1, "w", encoding="utf-8") as f:
                f.write("- Item 1 `[HIPÓTESE]` primeira hipotese\n")
                f.write("- Item 2 `[DESCONHECIDO]` primeiro desconhecido\n")
                f.write("- Item 3 `[HIPÓTESE]` segunda hipotese\n")

            f2 = os.path.join(docs_dir, "t2.md")
            with open(f2, "w", encoding="utf-8") as f:
                for i in range(10):
                    f.write(f"- Hipo {i} `[HIPÓTESE]` texto {i}\n")
                    f.write(f"- Desc {i} `[DESCONHECIDO]` texto {i}\n")

            res = progress.extract_research_tags(os.path.join(tmpdir, "docs", "research"))
            self.assertEqual(res["hipotese_count"], 12)
            self.assertEqual(len(res["hipotese_samples"]), 5)
            self.assertEqual(res["desconhecido_count"], 11)
            self.assertEqual(len(res["desconhecido_samples"]), 5)


class TestSetSubcommandAndSerializer(unittest.TestCase):
    """Test set subcommand behavior, error codes, and single-line diff."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.progress_file = os.path.join(self.tmpdir, "docs", "progress", "progress.json")
        os.makedirs(os.path.dirname(self.progress_file), exist_ok=True)
        # Copy real progress.json
        real_json = os.path.join(REPO_ROOT, "docs", "progress", "progress.json")
        shutil.copyfile(real_json, self.progress_file)

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def test_set_done_records_date_and_updates_file(self):
        ret = progress.cmd_set(
            task_id="F4.10",
            new_state="done",
            date_str="2026-11-06",
            root=self.tmpdir
        )
        self.assertEqual(ret, 0)
        with open(self.progress_file, "r", encoding="utf-8") as f:
            data = json.load(f)
        task = next(t for p in data["phases"] for t in p["tasks"] if t["id"] == "F4.10")
        self.assertEqual(task["state"], "done")
        self.assertEqual(task["done"], "2026-11-06")

    def test_set_leaving_done_removes_date(self):
        ret = progress.cmd_set(
            task_id="F0.1",
            new_state="doing",
            date_str="2026-11-06",
            root=self.tmpdir
        )
        self.assertEqual(ret, 0)
        with open(self.progress_file, "r", encoding="utf-8") as f:
            data = json.load(f)
        task = next(t for p in data["phases"] for t in p["tasks"] if t["id"] == "F0.1")
        self.assertEqual(task["state"], "doing")
        self.assertNotIn("done", task)

    def test_set_nonexistent_id_exits_2(self):
        with open(self.progress_file, "r", encoding="utf-8") as f:
            orig = f.read()

        ret = progress.cmd_set(
            task_id="F99.99",
            new_state="done",
            date_str="2026-11-06",
            root=self.tmpdir
        )
        self.assertEqual(ret, 2)
        with open(self.progress_file, "r", encoding="utf-8") as f:
            after = f.read()
        self.assertEqual(orig, after)

    def test_set_invalid_state_exits_2(self):
        with open(self.progress_file, "r", encoding="utf-8") as f:
            orig = f.read()

        ret = progress.cmd_set(
            task_id="F4.10",
            new_state="invalid_state_name",
            date_str="2026-11-06",
            root=self.tmpdir
        )
        self.assertEqual(ret, 2)
        with open(self.progress_file, "r", encoding="utf-8") as f:
            after = f.read()
        self.assertEqual(orig, after)

    def test_set_diff_is_exactly_one_line(self):
        with open(self.progress_file, "r", encoding="utf-8") as f:
            orig_lines = f.readlines()

        progress.cmd_set(
            task_id="F4.10",
            new_state="doing",
            date_str="2026-11-06",
            root=self.tmpdir
        )

        with open(self.progress_file, "r", encoding="utf-8") as f:
            new_lines = f.readlines()

        diff = list(difflib.unified_diff(orig_lines, new_lines, lineterm=""))
        added = [l for l in diff if l.startswith("+") and not l.startswith("+++")]
        removed = [l for l in diff if l.startswith("-") and not l.startswith("---")]
        self.assertEqual(len(added), 1)
        self.assertEqual(len(removed), 1)
        self.assertIn("F4.10", added[0])

    def test_set_invalid_date_returns_2_and_leaves_file_intact(self):
        with open(self.progress_file, "r", encoding="utf-8") as f:
            orig = f.read()

        for invalid_date in ["2026-13-45", "2026-02-30", "20261105", "not-a-date", "2026-1-1"]:
            ret = progress.cmd_set(
                task_id="F4.10",
                new_state="done",
                date_str=invalid_date,
                root=self.tmpdir
            )
            self.assertEqual(ret, 2, f"Expected 2 for date '{invalid_date}' but got {ret}")
            with open(self.progress_file, "r", encoding="utf-8") as f:
                after = f.read()
            self.assertEqual(orig, after, f"File was modified on invalid date '{invalid_date}'")

    def test_set_aborts_without_writing_if_modified_data_fails_validation(self):
        with open(self.progress_file, "r", encoding="utf-8") as f:
            orig = f.read()

        orig_validate = progress.validate_progress
        call_count = 0

        def failing_validate(data):
            nonlocal call_count
            call_count += 1
            if call_count > 1:
                raise progress.ValidationError("Erro simulado na validação após modificação.")
            return orig_validate(data)

        try:
            progress.validate_progress = failing_validate
            ret = progress.cmd_set(
                task_id="F4.10",
                new_state="doing",
                root=self.tmpdir
            )
            self.assertNotEqual(ret, 0)
            self.assertTrue(call_count >= 2, "validate_progress deve ser chamado no início e antes de gravar")
            with open(self.progress_file, "r", encoding="utf-8") as f:
                after = f.read()
            self.assertEqual(orig, after, "Arquivo não deve ser modificado se validação pós-alteração falhar")
        finally:
            progress.validate_progress = orig_validate

    def test_serialize_and_set_preserves_unknown_keys_at_all_levels_with_one_line_diff(self):
        with open(self.progress_file, "r", encoding="utf-8") as f:
            data = json.load(f)

        # Inject extra keys at all 5 levels: topo, fase, tarefa, blocker, criterion
        data["extra_top_key"] = "top_value"
        data["phases"][0]["extra_phase_key"] = "phase_value"
        # Find F4.10 to add extra task key
        target = next(t for p in data["phases"] for t in p["tasks"] if t["id"] == "F4.10")
        target["extra_task_key"] = "task_value"
        data["blockers"][0]["extra_blocker_key"] = "blocker_value"
        data["criteria"][0]["extra_criterion_key"] = "criterion_value"

        # Serialize and write
        serialized = progress.serialize_progress(data)
        with open(self.progress_file, "w", encoding="utf-8") as f:
            f.write(serialized)

        with open(self.progress_file, "r", encoding="utf-8") as f:
            orig_lines = f.readlines()

        # Execute set on F4.10
        ret = progress.cmd_set(
            task_id="F4.10",
            new_state="doing",
            root=self.tmpdir
        )
        self.assertEqual(ret, 0)

        # Verify all extra keys survived
        with open(self.progress_file, "r", encoding="utf-8") as f:
            after_data = json.load(f)

        self.assertEqual(after_data.get("extra_top_key"), "top_value")
        self.assertEqual(after_data["phases"][0].get("extra_phase_key"), "phase_value")
        target_after = next(t for p in after_data["phases"] for t in p["tasks"] if t["id"] == "F4.10")
        self.assertEqual(target_after.get("extra_task_key"), "task_value")
        self.assertEqual(target_after.get("state"), "doing")
        self.assertEqual(after_data["blockers"][0].get("extra_blocker_key"), "blocker_value")
        self.assertEqual(after_data["criteria"][0].get("extra_criterion_key"), "criterion_value")

        # Verify single-line diff
        with open(self.progress_file, "r", encoding="utf-8") as f:
            new_lines = f.readlines()

        diff = list(difflib.unified_diff(orig_lines, new_lines, lineterm=""))
        added = [l for l in diff if l.startswith("+") and not l.startswith("+++")]
        removed = [l for l in diff if l.startswith("-") and not l.startswith("---")]
        self.assertEqual(len(added), 1)
        self.assertEqual(len(removed), 1)
        self.assertIn("F4.10", added[0])


class TestHtmlDashboardAndEscaping(unittest.TestCase):
    """Test dashboard HTML generation, escaping, and autonomous requirements."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.root = self.tmpdir
        self.docs_dir = os.path.join(self.root, "docs", "progress")
        os.makedirs(self.docs_dir, exist_ok=True)
        # Create minimal progress.json with XSS attempts in strings
        self.json_path = os.path.join(self.docs_dir, "progress.json")
        data = {
            "project": "iScooter <script>alert(1)</script>",
            "started": "2026-11-01",
            "state_values": ["done", "doing", "blocked", "todo", "unknown"],
            "weight_note": "peso & <tags>",
            "phases": [
                {
                    "id": "F0",
                    "name": "Phase <b>0</b>",
                    "tasks": [
                        {"id": "F0.1", "sprint": "S0", "title": "Task & <test>", "state": "done", "weight": 2, "done": "2026-11-01"}
                    ]
                },
                {
                    "id": "F4",
                    "name": "Comunicação BLE real",
                    "tasks": [
                        {"id": "F4.1", "sprint": "S1", "title": "BLE task", "state": "doing", "weight": 2, "blocked_by": "Aguardando <auth>"}
                    ]
                }
            ],
            "blockers": [
                {"id": "B1", "kind": "quota", "text": "Blocker with <b>html</b>"}
            ],
            "criteria": [
                {"text": "Criteria with <script>", "state": "doing"}
            ]
        }
        with open(self.json_path, "w", encoding="utf-8") as f:
            json.dump(data, f)

        # Create dummy STATUS.md
        with open(os.path.join(self.root, "docs", "STATUS.md"), "w", encoding="utf-8") as f:
            f.write("- Sessão 20 (2026-11-05): Marco com <script>alert('status')</script>\n")

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def test_html_generation_and_security(self):
        out_html = os.path.join(self.docs_dir, "dashboard.html")
        progress.cmd_build(root=self.root, out=out_html, today_str="2026-11-05")

        self.assertTrue(os.path.exists(out_html))
        with open(out_html, "r", encoding="utf-8") as f:
            content = f.read()

        # Check no external resources (http:// or https:// in src, href, url())
        self.assertFalse(re.search(r'(src|href|url)\s*=\s*["\']?https?://', content, re.IGNORECASE))
        self.assertFalse(re.search(r'url\s*\(\s*["\']?https?://', content, re.IGNORECASE))

        # Check raw unescaped script tag is not present
        self.assertNotIn("<script>alert(1)</script>", content)
        self.assertNotIn("<b>html</b>", content)
        self.assertNotIn("<script>alert('status')</script>", content)

        # Check JSON embedded inside <script type="application/json"> does not contain unescaped </script>
        script_match = re.search(r'<script\s+type=["\']application/json["\'][^>]*>(.*?)</script>', content, re.DOTALL)
        self.assertIsNotNone(script_match)
        json_payload = script_match.group(1)
        self.assertNotIn("</script>", json_payload)

        # Check pre-rendered server-side overview elements are present in static HTML
        self.assertIn("class=\"total-progress-bar\"", content)
        self.assertIn("%", content)
        self.assertIn("F4", content)
        # Check disclaimer notice
        self.assertIn("pesos são estimativas do coordenador", content)

    def test_js_get_badge_escapes_state(self):
        out_html = os.path.join(self.docs_dir, "dashboard.html")
        progress.cmd_build(root=self.root, out=out_html, today_str="2026-11-05")
        with open(out_html, "r", encoding="utf-8") as f:
            content = f.read()

        # getBadge function must pass state through escapeHtml
        self.assertIn("function getBadge(state, est)", content)
        self.assertIn("escapeHtml(state)", content)


class TestGitHooksAndWorktree(unittest.TestCase):
    """Test git hooks execution, exit codes, and worktree detection."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        # Initialize a real git repo in tmpdir
        subprocess.check_call(["git", "init", "-b", "main", self.tmpdir], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.check_call(["git", "config", "user.name", "Tester"], cwd=self.tmpdir)
        subprocess.check_call(["git", "config", "user.email", "test@test.local"], cwd=self.tmpdir)

        # Copy scripts/hooks and scripts/progress
        dest_scripts = os.path.join(self.tmpdir, "scripts")
        shutil.copytree(os.path.join(REPO_ROOT, "scripts"), dest_scripts)
        # Create minimal docs/progress/progress.json
        os.makedirs(os.path.join(self.tmpdir, "docs", "progress"), exist_ok=True)
        shutil.copyfile(
            os.path.join(REPO_ROOT, "docs", "progress", "progress.json"),
            os.path.join(self.tmpdir, "docs", "progress", "progress.json")
        )
        # Commit files so git is happy
        subprocess.check_call(["git", "add", "."], cwd=self.tmpdir)
        subprocess.check_call(["git", "commit", "-m", "init"], cwd=self.tmpdir, stdout=subprocess.DEVNULL)

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def test_hook_installs_and_uninstalls(self):
        install_sh = os.path.join(self.tmpdir, "scripts", "hooks", "install.sh")
        # Run install
        subprocess.check_call(["bash", install_sh], cwd=self.tmpdir)
        hooks_dir = os.path.join(self.tmpdir, ".git", "hooks")
        self.assertTrue(os.path.exists(os.path.join(hooks_dir, "post-commit")))
        self.assertTrue(os.path.exists(os.path.join(hooks_dir, "post-merge")))

        # Idempotent install
        subprocess.check_call(["bash", install_sh], cwd=self.tmpdir)
        self.assertTrue(os.path.exists(os.path.join(hooks_dir, "post-commit")))

        # Uninstall
        subprocess.check_call(["bash", install_sh, "--uninstall"], cwd=self.tmpdir)
        self.assertFalse(os.path.exists(os.path.join(hooks_dir, "post-commit")))
        self.assertFalse(os.path.exists(os.path.join(hooks_dir, "post-merge")))

    def test_hook_does_not_overwrite_foreign_hook(self):
        hooks_dir = os.path.join(self.tmpdir, ".git", "hooks")
        os.makedirs(hooks_dir, exist_ok=True)
        foreign_hook = os.path.join(hooks_dir, "post-commit")
        with open(foreign_hook, "w") as f:
            f.write("#!/bin/sh\n# foreign hook\necho foreign\n")

        install_sh = os.path.join(self.tmpdir, "scripts", "hooks", "install.sh")
        subprocess.check_call(["bash", install_sh], cwd=self.tmpdir)

        with open(foreign_hook, "r") as f:
            content = f.read()
        self.assertIn("foreign hook", content)

    def test_hook_exits_0_even_when_build_fails(self):
        # Corrupt progress.json
        bad_json = os.path.join(self.tmpdir, "docs", "progress", "progress.json")
        with open(bad_json, "w") as f:
            f.write("{ invalid json")

        post_commit = os.path.join(self.tmpdir, "scripts", "hooks", "post-commit")
        res = subprocess.run(["bash", post_commit], cwd=self.tmpdir, capture_output=True)
        self.assertEqual(res.returncode, 0)

    def test_hook_is_ignored_in_worktree(self):
        # Create a worktree
        wt_dir = os.path.join(self.tmpdir, ".wc", "wt1")
        subprocess.check_call(["git", "worktree", "add", "-b", "wt-branch", wt_dir], cwd=self.tmpdir, stdout=subprocess.DEVNULL)

        # Run post-commit in worktree
        post_commit = os.path.join(self.tmpdir, "scripts", "hooks", "post-commit")
        res = subprocess.run(["bash", post_commit], cwd=wt_dir, capture_output=True)
        self.assertEqual(res.returncode, 0)
        # Verify dashboard.html was not generated in worktree
        self.assertFalse(os.path.exists(os.path.join(wt_dir, "docs", "progress", "dashboard.html")))

    def test_done_sh_wrapper_success_and_error_codes(self):
        done_sh = os.path.join(self.tmpdir, "scripts", "progress", "done.sh")
        # Test updating a task to doing
        res = subprocess.run(["bash", done_sh, "F4.10", "doing"], cwd=self.tmpdir, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)

        # Test invalid task id returns code 2
        res_bad_id = subprocess.run(["bash", done_sh, "F99.99", "done"], cwd=self.tmpdir, capture_output=True, text=True)
        self.assertEqual(res_bad_id.returncode, 2)

        # Test invalid state returns code 2
        res_bad_state = subprocess.run(["bash", done_sh, "F4.10", "bad_state"], cwd=self.tmpdir, capture_output=True, text=True)
        self.assertEqual(res_bad_state.returncode, 2)

    def test_hooks_use_timeout_1(self):
        for hook_name in ["post-commit", "post-merge"]:
            hook_path = os.path.join(REPO_ROOT, "scripts", "hooks", hook_name)
            with open(hook_path, "r", encoding="utf-8") as f:
                content = f.read()
            self.assertIn("timeout 1 ", content, f"{hook_name} deve usar 'timeout 1'")
            self.assertNotIn("timeout 3 ", content, f"{hook_name} não deve usar 'timeout 3'")


if __name__ == "__main__":
    unittest.main()

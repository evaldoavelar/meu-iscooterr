#!/usr/bin/env python3
"""iScooter Connect - Progress Dashboard Generator and State Manager.

Autonomous progress generator conforming to PLANO.md §6 and TASK-DASH.md.
Pure Python 3 standard library, no external dependencies.
"""

import argparse
from datetime import date, timedelta
import glob
import html
import json
import math
import os
import re
import subprocess
import sys
import tempfile


class ValidationError(Exception):
    """Raised when progress.json schema or data integrity check fails."""
    pass


def credit(task):
    """Calculate credit for a task according to PLANO.md §6.

    done = 1.0, doing = 0.5, others = 0.0.
    """
    state = task.get("state")
    if state == "done":
        return 1.0
    if state == "doing":
        return 0.5
    return 0.0


def phase_percent(phase, include_est=True):
    """Calculate completion percentage of a single phase.

    Returns float percentage (0.0 to 100.0).
    """
    tasks = phase.get("tasks", [])
    if not include_est:
        tasks = [t for t in tasks if not t.get("est", False)]
    total_w = sum(t.get("weight", 0) for t in tasks)
    if total_w <= 0:
        return 0.0
    earned_w = sum(t.get("weight", 0) * credit(t) for t in tasks)
    return (earned_w / total_w) * 100.0


def total_percent(phases, include_est=True):
    """Calculate overall completion percentage across all phases.

    Returns float percentage (0.0 to 100.0).
    """
    tasks = [t for p in phases for t in p.get("tasks", [])]
    if not include_est:
        tasks = [t for t in tasks if not t.get("est", False)]
    total_w = sum(t.get("weight", 0) for t in tasks)
    if total_w <= 0:
        return 0.0
    earned_w = sum(t.get("weight", 0) * credit(t) for t in tasks)
    return (earned_w / total_w) * 100.0


def total_weight(phases, include_est=True):
    """Calculate total weight across phases."""
    tasks = [t for p in phases for t in p.get("tasks", [])]
    if not include_est:
        tasks = [t for t in tasks if not t.get("est", False)]
    return sum(t.get("weight", 0) for t in tasks)


def earned_weight(phases, include_est=True):
    """Calculate earned weight across phases."""
    tasks = [t for p in phases for t in p.get("tasks", [])]
    if not include_est:
        tasks = [t for t in tasks if not t.get("est", False)]
    return sum(t.get("weight", 0) * credit(t) for t in tasks)


def remaining_weight(phases, include_est=True):
    """Calculate remaining weight across phases."""
    tasks = [t for p in phases for t in p.get("tasks", [])]
    if not include_est:
        tasks = [t for t in tasks if not t.get("est", False)]
    return sum(t.get("weight", 0) * (1.0 - credit(t)) for t in tasks)


def pace(tasks, started, today, window_days=None):
    """Calculate completion pace (weight per day) for given window.

    window_days: 3, 7, or None (from project start).
    Divisor is capped by project age in days (minimum 1 day).
    """
    age_days = max(1, (today - started).days + 1)
    if window_days is None:
        effective_days = age_days
        relevant = [
            t for t in tasks
            if t.get("state") == "done" and t.get("done") and date.fromisoformat(t["done"]) <= today
        ]
    else:
        effective_days = min(window_days, age_days)
        relevant = [
            t for t in tasks
            if t.get("state") == "done" and t.get("done")
            and 0 <= (today - date.fromisoformat(t["done"])).days < window_days
        ]

    w_sum = sum(t.get("weight", 0) for t in relevant)
    return w_sum / effective_days


def eta(rem_weight, paces, today):
    """Calculate ETA range and predicted completion dates.

    paces: list of pace values (float).
    Returns dict with status ("ok" or "indeterminado"), min_days, max_days, min_date, max_date.
    """
    pos_paces = [p for p in paces if p > 0]
    if rem_weight > 0 and len(pos_paces) < len(paces):
        pos_paces = []  # alguma janela parada (ritmo 0): limite superior ilimitado => indeterminado
    if not pos_paces or rem_weight <= 0:
        if rem_weight <= 0 and any(p > 0 for p in paces):
            return {
                "status": "ok",
                "min_days": 0.0,
                "max_days": 0.0,
                "min_date": today.isoformat(),
                "max_date": today.isoformat()
            }
        return {
            "status": "indeterminado",
            "min_days": None,
            "max_days": None,
            "min_date": None,
            "max_date": None
        }

    days_list = [rem_weight / p for p in pos_paces]
    min_days = min(days_list)
    max_days = max(days_list)
    min_date = today + timedelta(days=math.ceil(min_days))
    max_date = today + timedelta(days=math.ceil(max_days))

    return {
        "status": "ok",
        "min_days": min_days,
        "max_days": max_days,
        "min_date": min_date.isoformat(),
        "max_date": max_date.isoformat()
    }


def validate_progress(data):
    """Validate progress data structure and rules."""
    if not isinstance(data, dict):
        raise ValidationError("progress.json deve ser um objeto JSON.")

    state_values = data.get("state_values")
    if not isinstance(state_values, list) or not all(isinstance(s, str) for s in state_values):
        raise ValidationError("state_values deve ser uma lista de strings.")

    phases = data.get("phases")
    if not isinstance(phases, list):
        raise ValidationError("phases deve ser uma lista.")

    seen_task_ids = set()
    seen_phase_ids = set()

    date_regex = re.compile(r"^\d{4}-\d{2}-\d{2}$")

    for phase in phases:
        if not isinstance(phase, dict):
            raise ValidationError("Cada fase deve ser um objeto.")
        pid = phase.get("id")
        if not pid or not isinstance(pid, str):
            raise ValidationError("Fase sem 'id' válido.")
        if pid in seen_phase_ids:
            raise ValidationError(f"ID de fase duplicado: '{pid}'.")
        seen_phase_ids.add(pid)

        tasks = phase.get("tasks")
        if not isinstance(tasks, list):
            raise ValidationError(f"Fase '{pid}' deve conter lista 'tasks'.")

        for task in tasks:
            if not isinstance(task, dict):
                raise ValidationError("Cada tarefa deve ser um objeto.")
            tid = task.get("id")
            if not tid or not isinstance(tid, str):
                raise ValidationError("Tarefa sem 'id' válido.")
            if tid in seen_task_ids:
                raise ValidationError(f"ID de tarefa duplicado: '{tid}'.")
            seen_task_ids.add(tid)

            state = task.get("state")
            if state not in state_values:
                raise ValidationError(f"Tarefa '{tid}' possui estado inválido '{state}'. Esperado um de {state_values}.")

            weight = task.get("weight")
            if isinstance(weight, bool) or not isinstance(weight, (int, float)) or not math.isfinite(weight) or weight <= 0:
                raise ValidationError(f"Tarefa '{tid}' deve ter 'weight' numérico > 0 (recebido: {weight}).")

            done = task.get("done")
            if state == "done" and done is None:
                raise ValidationError(f"Tarefa '{tid}' com state=done exige o campo 'done' (AAAA-MM-DD).")
            if done is not None:
                if state != "done":
                    raise ValidationError(f"Tarefa '{tid}' possui 'done' mas estado é '{state}' (só permitido se state=done).")
                if not isinstance(done, str) or not date_regex.match(done):
                    raise ValidationError(f"Tarefa '{tid}' possui 'done' com formato de data inválido (esperado AAAA-MM-DD): '{done}'.")
                try:
                    date.fromisoformat(done)
                except ValueError:
                    raise ValidationError(f"Tarefa '{tid}' possui 'done' com data inválida: '{done}'.")


KNOWN_TOP_KEYS = ("project", "started", "state_values", "weight_note", "phases", "blockers", "criteria")
KNOWN_PHASE_KEYS = ("id", "name", "tasks")
KNOWN_TASK_KEYS = ("id", "sprint", "title", "state", "weight", "done", "blocked_by", "est")
KNOWN_BLOCKER_KEYS = ("id", "kind", "text", "ref")
KNOWN_CRITERION_KEYS = ("text", "state", "note")


def _order_dict(d, known_keys):
    """Return dictionary with known keys first in specified order, then remaining keys preserving order."""
    out = {}
    for k in known_keys:
        if k in d:
            out[k] = d[k]
    for k, v in d.items():
        if k not in known_keys:
            out[k] = v
    return out


def serialize_progress(data):
    """Serialize progress data maintaining 1-task-per-line and exact key formatting."""
    lines = []
    lines.append("{\n")
    if "project" in data:
        proj = json.dumps(data["project"], ensure_ascii=False)
        lines.append(f' "project": {proj},\n')
    if "started" in data:
        started = json.dumps(data["started"], ensure_ascii=False)
        lines.append(f' "started": {started},\n')
    if "state_values" in data:
        sv = json.dumps(data["state_values"], ensure_ascii=False)
        lines.append(f' "state_values": {sv},\n')
    if "weight_note" in data:
        wn = json.dumps(data["weight_note"], ensure_ascii=False)
        lines.append(f' "weight_note": {wn},\n')
    lines.append(' "phases": [\n')

    phases = data.get("phases", [])
    for i, phase in enumerate(phases):
        pid = json.dumps(phase.get("id"), ensure_ascii=False)
        pname = json.dumps(phase.get("name"), ensure_ascii=False)
        lines.append(f'  {{"id": {pid}, "name": {pname}, "tasks": [\n')
        tasks = phase.get("tasks", [])
        for j, task in enumerate(tasks):
            comma = "," if j < len(tasks) - 1 else ""
            ordered_task = _order_dict(task, KNOWN_TASK_KEYS)
            tstr = json.dumps(ordered_task, ensure_ascii=False)
            lines.append(f'   {tstr}{comma}\n')
        phase_comma = "," if i < len(phases) - 1 else ""
        extra_phase_keys = [k for k in phase if k not in KNOWN_PHASE_KEYS]
        extra_phase_str = "".join(
            f', {json.dumps(k, ensure_ascii=False)}: {json.dumps(phase[k], ensure_ascii=False)}'
            for k in extra_phase_keys
        )
        lines.append(f'  ]{extra_phase_str}}}{phase_comma}\n')
    lines.append(' ],\n')

    lines.append(' "blockers": [\n')
    blockers = data.get("blockers", [])
    for i, b in enumerate(blockers):
        comma = "," if i < len(blockers) - 1 else ""
        ordered_b = _order_dict(b, KNOWN_BLOCKER_KEYS)
        bstr = json.dumps(ordered_b, ensure_ascii=False)
        lines.append(f'  {bstr}{comma}\n')
    lines.append(' ],\n')

    extra_top_keys = [k for k in data if k not in KNOWN_TOP_KEYS]
    criteria_comma = "," if extra_top_keys else ""
    lines.append(' "criteria": [\n')
    criteria = data.get("criteria", [])
    for i, c in enumerate(criteria):
        comma = "," if i < len(criteria) - 1 else ""
        ordered_c = _order_dict(c, KNOWN_CRITERION_KEYS)
        cstr = json.dumps(ordered_c, ensure_ascii=False)
        lines.append(f'  {cstr}{comma}\n')
    lines.append(f' ]{criteria_comma}\n')

    for idx, k in enumerate(extra_top_keys):
        comma = "," if idx < len(extra_top_keys) - 1 else ""
        k_str = json.dumps(k, ensure_ascii=False)
        v_str = json.dumps(data[k], ensure_ascii=False)
        lines.append(f' {k_str}: {v_str}{comma}\n')

    lines.append('}\n')
    return "".join(lines)


def extract_status_summary(status_path):
    """Extract last session line from docs/STATUS.md, truncated to ~300 chars."""
    if not os.path.exists(status_path):
        return ""
    try:
        with open(status_path, "r", encoding="utf-8", errors="ignore") as f:
            lines = [l.strip() for l in f if re.match(r"^-\s*Sessão\s+\d+", l.strip())]
        if not lines:
            return ""
        last_line = lines[-1]
        if len(last_line) > 300:
            return last_line[:300] + "..."
        return last_line
    except Exception:
        return ""


def extract_research_tags(research_dir):
    """Count and sample [HIPÓTESE] and [DESCONHECIDO] tags in docs/research/**/*.md."""
    hipotese_samples = []
    desconhecido_samples = []
    hipotese_count = 0
    desconhecido_count = 0

    if not os.path.exists(research_dir):
        return {
            "hipotese_count": 0,
            "hipotese_samples": [],
            "desconhecido_count": 0,
            "desconhecido_samples": []
        }

    pattern = os.path.join(research_dir, "**", "*.md")
    for filepath in sorted(glob.glob(pattern, recursive=True)):
        rel = os.path.relpath(filepath, os.path.dirname(research_dir))
        try:
            with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
                for line in f:
                    stripped = line.strip()
                    if "[HIPÓTESE]" in stripped:
                        hipotese_count += 1
                        if len(hipotese_samples) < 5:
                            trunc = stripped[:140] + ("..." if len(stripped) > 140 else "")
                            hipotese_samples.append(f"{rel}: {trunc}")
                    if "[DESCONHECIDO]" in stripped:
                        desconhecido_count += 1
                        if len(desconhecido_samples) < 5:
                            trunc = stripped[:140] + ("..." if len(stripped) > 140 else "")
                            desconhecido_samples.append(f"{rel}: {trunc}")
        except Exception:
            continue

    return {
        "hipotese_count": hipotese_count,
        "hipotese_samples": hipotese_samples,
        "desconhecido_count": desconhecido_count,
        "desconhecido_samples": desconhecido_samples
    }


def extract_git_data(repo_root, today, project_start):
    """Extract last 12 commits and commit velocity from git."""
    default_res = {
        "commits": [],
        "rate_7d": None,
        "rate_all": None,
        "total_commits": 0
    }
    try:
        # Last 12 commits
        cmd_12 = ["git", "log", "-n", "12", "--format=%h|%as|%s"]
        res_12 = subprocess.run(cmd_12, cwd=repo_root, capture_output=True, text=True, timeout=5)
        if res_12.returncode != 0:
            return default_res

        commits = []
        for line in res_12.stdout.strip().splitlines():
            if line:
                parts = line.split("|", 2)
                if len(parts) == 3:
                    commits.append({"hash": parts[0], "date": parts[1], "subject": parts[2]})

        # All commit dates
        cmd_dates = ["git", "log", "--format=%as"]
        res_dates = subprocess.run(cmd_dates, cwd=repo_root, capture_output=True, text=True, timeout=5)
        if res_dates.returncode != 0:
            return {"commits": commits, "rate_7d": None, "rate_all": None, "total_commits": len(commits)}

        all_dates = [l.strip() for l in res_dates.stdout.strip().splitlines() if l.strip()]
        total_commits = len(all_dates)

        commits_7d = [d for d in all_dates if 0 <= (today - date.fromisoformat(d)).days < 7]
        eff_7d = min(7, max(1, (today - project_start).days + 1))
        rate_7d = len(commits_7d) / eff_7d

        eff_all = max(1, (today - project_start).days + 1)
        rate_all = total_commits / eff_all

        return {
            "commits": commits,
            "rate_7d": rate_7d,
            "rate_all": rate_all,
            "total_commits": total_commits
        }
    except Exception:
        return default_res


def resolve_repo_root(root_param=None):
    """Find repository root or progress directory root."""
    if root_param:
        return os.path.abspath(root_param)

    # Try git rev-parse --show-toplevel
    try:
        res = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, timeout=2)
        if res.returncode == 0 and res.stdout.strip():
            return os.path.abspath(res.stdout.strip())
    except Exception:
        pass

    # Look upwards from cwd for docs/progress/progress.json
    cur = os.path.abspath(os.getcwd())
    while True:
        if os.path.exists(os.path.join(cur, "docs", "progress", "progress.json")):
            return cur
        parent = os.path.dirname(cur)
        if parent == cur:
            break
        cur = parent

    return os.path.abspath(os.getcwd())


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Dashboard de Progresso — {project_name}</title>
<style>
:root {{
  --bg: #f8fafc;
  --card-bg: #ffffff;
  --text: #0f172a;
  --text-muted: #64748b;
  --border: #e2e8f0;
  --accent: #2563eb;
  --accent-hover: #1d4ed8;
  --bar-bg: #e2e8f0;
  --bar-fill: #2563eb;
  --success: #15803d;
  --success-bg: #dcfce7;
  --warning: #b45309;
  --warning-bg: #fef3c7;
  --danger: #b91c1c;
  --danger-bg: #fee2e2;
  --info: #0369a1;
  --info-bg: #e0f2fe;
  --neutral-bg: #f1f5f9;
}}

@media (prefers-color-scheme: dark) {{
  :root {{
    --bg: #090d16;
    --card-bg: #131c2e;
    --text: #f1f5f9;
    --text-muted: #94a3b8;
    --border: #243048;
    --accent: #3b82f6;
    --accent-hover: #60a5fa;
    --bar-bg: #1e293b;
    --bar-fill: #3b82f6;
    --success: #4ade80;
    --success-bg: #064e3b;
    --warning: #fbbf24;
    --warning-bg: #78350f;
    --danger: #f87171;
    --danger-bg: #7f1d1d;
    --info: #38bdf8;
    --info-bg: #0c4a6e;
    --neutral-bg: #1e293b;
  }}
}}

[data-theme="light"] {{
  --bg: #f8fafc;
  --card-bg: #ffffff;
  --text: #0f172a;
  --text-muted: #64748b;
  --border: #e2e8f0;
  --accent: #2563eb;
  --accent-hover: #1d4ed8;
  --bar-bg: #e2e8f0;
  --bar-fill: #2563eb;
  --success: #15803d;
  --success-bg: #dcfce7;
  --warning: #b45309;
  --warning-bg: #fef3c7;
  --danger: #b91c1c;
  --danger-bg: #fee2e2;
  --info: #0369a1;
  --info-bg: #e0f2fe;
  --neutral-bg: #f1f5f9;
}}

[data-theme="dark"] {{
  --bg: #090d16;
  --card-bg: #131c2e;
  --text: #f1f5f9;
  --text-muted: #94a3b8;
  --border: #243048;
  --accent: #3b82f6;
  --accent-hover: #60a5fa;
  --bar-bg: #1e293b;
  --bar-fill: #3b82f6;
  --success: #4ade80;
  --success-bg: #064e3b;
  --warning: #fbbf24;
  --warning-bg: #78350f;
  --danger: #f87171;
  --danger-bg: #7f1d1d;
  --info: #38bdf8;
  --info-bg: #0c4a6e;
  --neutral-bg: #1e293b;
}}

* {{ box-sizing: border-box; margin: 0; padding: 0; }}
body {{
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  background-color: var(--bg);
  color: var(--text);
  line-height: 1.5;
  padding: 16px;
  max-width: 980px;
  margin: 0 auto;
}}

header {{
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 20px;
}}

.header-top {{
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}}

h1 {{ font-size: 1.5rem; font-weight: 700; }}
.subtitle {{ color: var(--text-muted); font-size: 0.875rem; }}

button.theme-btn {{
  background: var(--neutral-bg);
  color: var(--text);
  border: 1px solid var(--border);
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
}}
button.theme-btn:hover {{ background: var(--border); }}

.total-progress-bar {{
  background: var(--bar-bg);
  border-radius: 9999px;
  height: 24px;
  overflow: hidden;
  position: relative;
  margin: 8px 0;
}}
.total-progress-fill {{
  background: var(--bar-fill);
  height: 100%;
  border-radius: 9999px;
  transition: width 0.3s ease;
}}

.stats-grid {{
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-top: 8px;
}}
.stat-box {{
  background: var(--neutral-bg);
  border-radius: 6px;
  padding: 12px;
}}
.stat-label {{ font-size: 0.75rem; color: var(--text-muted); text-transform: uppercase; font-weight: 600; }}
.stat-val {{ font-size: 1.25rem; font-weight: 700; margin-top: 4px; }}
.stat-desc {{ font-size: 0.75rem; color: var(--text-muted); }}

.notice-box {{
  background: var(--warning-bg);
  color: var(--warning);
  border-left: 4px solid var(--warning);
  padding: 10px 14px;
  border-radius: 4px;
  font-size: 0.8125rem;
}}

.section-card {{
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 20px;
}}
.section-title {{
  font-size: 1.15rem;
  font-weight: 700;
  margin-bottom: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}}

.phase-card {{
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 14px;
  margin-bottom: 12px;
  background: var(--neutral-bg);
}}
.phase-header {{
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}}
.phase-bar {{
  background: var(--bar-bg);
  height: 10px;
  border-radius: 5px;
  overflow: hidden;
  margin: 6px 0;
}}
.phase-bar-fill {{
  background: var(--accent);
  height: 100%;
}}

details.phase-details {{
  margin-top: 10px;
  border-top: 1px dashed var(--border);
  padding-top: 10px;
}}
details.phase-details summary {{
  cursor: pointer;
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--accent);
}}

.sprint-group {{
  margin-top: 10px;
  padding-left: 8px;
}}
.sprint-title {{
  font-size: 0.8125rem;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 6px;
}}

.task-item {{
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px;
  border-radius: 4px;
  margin-bottom: 6px;
  background: var(--card-bg);
  border: 1px solid var(--border);
  font-size: 0.875rem;
}}
.task-row {{
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}}
.task-meta {{
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.75rem;
  color: var(--text-muted);
  flex-wrap: wrap;
}}

.badge {{
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 9999px;
  font-size: 0.75rem;
  font-weight: 600;
}}
.badge-done {{ background: var(--success-bg); color: var(--success); }}
.badge-doing {{ background: var(--warning-bg); color: var(--warning); }}
.badge-blocked {{ background: var(--danger-bg); color: var(--danger); }}
.badge-todo {{ background: var(--neutral-bg); color: var(--text-muted); border: 1px solid var(--border); }}
.badge-unknown {{ background: var(--info-bg); color: var(--info); }}
.badge-est {{ background: var(--warning-bg); color: var(--warning); font-style: italic; }}

.item-list {{
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}}
.item-card {{
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--neutral-bg);
  font-size: 0.875rem;
}}

.commit-table {{
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8125rem;
}}
.commit-table th, .commit-table td {{
  padding: 8px;
  text-align: left;
  border-bottom: 1px solid var(--border);
}}
.commit-hash {{
  font-family: monospace;
  font-weight: 600;
  color: var(--accent);
}}

.sample-code {{
  font-family: monospace;
  font-size: 0.75rem;
  background: var(--card-bg);
  padding: 6px 8px;
  border-radius: 4px;
  border: 1px solid var(--border);
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}}

.noscript-note {{
  padding: 12px;
  background: var(--info-bg);
  color: var(--info);
  border-radius: 6px;
  font-size: 0.875rem;
  margin-bottom: 16px;
}}
</style>
</head>
<body>

<noscript>
  <div class="noscript-note">
    Visão geral pré-renderizada sem JavaScript. Habilite JavaScript no navegador para exibir a árvore dinâmica de fases, tarefas, critérios e commits.
  </div>
</noscript>

<header>
  <div class="header-top">
    <div>
      <h1>{project_name}</h1>
      <div class="subtitle">Gerado em: {generated_date} (base: {project_started})</div>
    </div>
    <button class="theme-btn" id="theme-toggle" type="button">Alternar Tema</button>
  </div>

  <div class="total-progress-bar">
    <div class="total-progress-fill" style="width: {pct_all_bar}%;"></div>
  </div>

  <div class="stats-grid">
    <div class="stat-box">
      <div class="stat-label">Progresso Total</div>
      <div class="stat-val">{pct_all_str}%</div>
      <div class="stat-desc">sem escopo estimado: {pct_no_est_str}%</div>
    </div>
    <div class="stat-box">
      <div class="stat-label">Pontos de Peso</div>
      <div class="stat-val">{earned_w_str} / {total_w_str}</div>
      <div class="stat-desc">faltam {remaining_w_str} pontos de peso</div>
    </div>
    <div class="stat-box">
      <div class="stat-label">Previsão (ETA)</div>
      <div class="stat-val">{eta_summary_val}</div>
      <div class="stat-desc">{eta_summary_desc}</div>
    </div>
  </div>

  <div class="stat-desc" style="margin-top: 4px;">
    <strong>Método:</strong> Ritmo = peso concluído por dia (janelas 3 d, 7 d e início, limitada à idade). ETA = restante / ritmo.
  </div>

  <div class="notice-box">
    <strong>Aviso:</strong> pesos são estimativas do coordenador; ETA ignora esperas por aprovação do proprietário/cota.
  </div>
</header>

<div id="dynamic-content"></div>

<script type="application/json" id="progress-data">
{json_payload}
</script>

<script>
(function() {{
  // Theme management with localStorage try/catch
  const toggleBtn = document.getElementById('theme-toggle');
  function getPreferredTheme() {{
    try {{
      const saved = localStorage.getItem('theme');
      if (saved) return saved;
    }} catch (e) {{}}
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }}
  function applyTheme(theme) {{
    document.documentElement.setAttribute('data-theme', theme);
    if (toggleBtn) {{
      toggleBtn.textContent = theme === 'dark' ? '☀️ Claro' : '🌙 Escuro';
    }}
  }}
  let currentTheme = getPreferredTheme();
  applyTheme(currentTheme);

  if (toggleBtn) {{
    toggleBtn.addEventListener('click', function() {{
      currentTheme = currentTheme === 'dark' ? 'light' : 'dark';
      applyTheme(currentTheme);
      try {{
        localStorage.setItem('theme', currentTheme);
      }} catch (e) {{}}
    }});
  }}

  // Read raw payload
  const rawDataEl = document.getElementById('progress-data');
  if (!rawDataEl) return;
  let data;
  try {{
    data = JSON.parse(rawDataEl.textContent);
  }} catch (e) {{
    console.error('Falha ao ler dados de progresso:', e);
    return;
  }}

  const container = document.getElementById('dynamic-content');
  if (!container) return;

  function escapeHtml(str) {{
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }}

  function getBadge(state, est) {{
    let label = escapeHtml(state);
    let cls = 'badge-todo';
    if (state === 'done') {{
      label = '✓ Feito';
      cls = 'badge-done';
    }} else if (state === 'doing') {{
      label = '▶ Em andamento';
      cls = 'badge-doing';
    }} else if (state === 'blocked') {{
      label = '⛔ Bloqueado';
      cls = 'badge-blocked';
    }} else if (state === 'todo') {{
      label = '○ Pendente';
      cls = 'badge-todo';
    }} else if (state === 'unknown') {{
      label = '? Desconhecido';
      cls = 'badge-unknown';
    }}
    let html = '<span class="badge ' + cls + '">' + label + '</span>';
    if (est) {{
      html += ' <span class="badge badge-est">[est.]</span>';
    }}
    return html;
  }}

  let htmlParts = [];

  // Section (b) and (c): Phases F0-F7
  htmlParts.push('<section class="section-card"><h2 class="section-title">Fases do Projeto</h2>');
  (data.phases_stats || []).forEach(function(ps) {{
    const isOpen = ps.is_open ? ' open' : '';
    htmlParts.push('<div class="phase-card">');
    htmlParts.push('  <div class="phase-header">');
    htmlParts.push('    <div><strong>' + escapeHtml(ps.id) + ' — ' + escapeHtml(ps.name) + '</strong></div>');
    htmlParts.push('    <div><span class="badge ' + (ps.status === 'concluída' ? 'badge-done' : (ps.status === 'em andamento' ? 'badge-doing' : 'badge-todo')) + '">' + escapeHtml(ps.status) + '</span> ' + ps.percent.toFixed(1) + '%</div>');
    htmlParts.push('  </div>');
    htmlParts.push('  <div class="phase-bar"><div class="phase-bar-fill" style="width: ' + ps.percent.toFixed(1) + '%;"></div></div>');
    
    htmlParts.push('  <details class="phase-details"' + isOpen + '>');
    htmlParts.push('    <summary>Ver tarefas por sprint (' + ps.tasks_count + ' tarefas, peso ' + ps.weight_sum + ')</summary>');
    
    (ps.sprints || []).forEach(function(sp) {{
      htmlParts.push('    <div class="sprint-group">');
      htmlParts.push('      <div class="sprint-title">Sprint: ' + escapeHtml(sp.sprint) + '</div>');
      (sp.tasks || []).forEach(function(t) {{
        htmlParts.push('      <div class="task-item">');
        htmlParts.push('        <div class="task-row">');
        htmlParts.push('          <div><strong>' + escapeHtml(t.id) + ':</strong> ' + escapeHtml(t.title) + '</div>');
        htmlParts.push('          <div>' + getBadge(t.state, t.est) + '</div>');
        htmlParts.push('        </div>');
        htmlParts.push('        <div class="task-meta">');
        htmlParts.push('          <span>Peso: ' + t.weight + '</span>');
        if (t.done) {{
          htmlParts.push('          <span>• Concluído em: ' + escapeHtml(t.done) + '</span>');
        }}
        if (t.blocked_by) {{
          htmlParts.push('          <span style="color: var(--danger);">• Bloqueio: ' + escapeHtml(t.blocked_by) + '</span>');
        }}
        htmlParts.push('        </div>');
        htmlParts.push('      </div>');
      }});
      htmlParts.push('    </div>');
    }});
    
    htmlParts.push('  </details>');
    htmlParts.push('</div>');
  }});
  htmlParts.push('</section>');

  // Section (d): Blockers & Pending
  htmlParts.push('<section class="section-card"><h2 class="section-title">Bloqueios e Pendências Abertas</h2>');
  const allBlockers = data.all_blockers || [];
  if (allBlockers.length === 0) {{
    htmlParts.push('<p class="stat-desc">Nenhum bloqueio aberto no momento.</p>');
  }} else {{
    htmlParts.push('<ul class="item-list">');
    allBlockers.forEach(function(b) {{
      htmlParts.push('<li class="item-card">');
      htmlParts.push('  <span class="badge badge-blocked">⛔ ' + escapeHtml(b.id || 'Bloqueio') + '</span> ');
      if (b.kind) {{
        htmlParts.push('  <span class="badge badge-todo">' + escapeHtml(b.kind) + '</span> ');
      }}
      htmlParts.push('  <span>' + escapeHtml(b.text) + '</span>');
      if (b.ref) {{
        htmlParts.push('  <div class="task-meta" style="margin-top: 4px;">Ref: ' + escapeHtml(b.ref) + '</div>');
      }}
      htmlParts.push('</li>');
    }});
    htmlParts.push('</ul>');
  }}
  htmlParts.push('</section>');

  // Section (e): Project Criteria
  htmlParts.push('<section class="section-card"><h2 class="section-title">Critérios de Aceitação do Projeto</h2>');
  const criteria = data.criteria || [];
  if (criteria.length === 0) {{
    htmlParts.push('<p class="stat-desc">Nenhum critério listado.</p>');
  }} else {{
    htmlParts.push('<ul class="item-list">');
    criteria.forEach(function(c) {{
      htmlParts.push('<li class="item-card task-row">');
      htmlParts.push('  <div>');
      htmlParts.push('    <div>' + escapeHtml(c.text) + '</div>');
      if (c.note) {{
        htmlParts.push('    <div class="task-meta">Nota: ' + escapeHtml(c.note) + '</div>');
      }}
      htmlParts.push('  </div>');
      htmlParts.push('  <div>' + getBadge(c.state, false) + '</div>');
      htmlParts.push('</li>');
    }});
    htmlParts.push('</ul>');
  }}
  htmlParts.push('</section>');

  // Section (f): Last Status & 12 Commits
  htmlParts.push('<section class="section-card"><h2 class="section-title">Último Marco e Commits Recentes</h2>');
  if (data.status_summary) {{
    htmlParts.push('<div class="item-card" style="margin-bottom: 14px;">');
    htmlParts.push('  <div class="stat-label">Último Marco (STATUS.md)</div>');
    htmlParts.push('  <div style="margin-top: 4px;">' + escapeHtml(data.status_summary) + '</div>');
    htmlParts.push('</div>');
  }}
  const commits = (data.git && data.git.commits) || [];
  if (commits.length > 0) {{
    htmlParts.push('<div style="overflow-x: auto;">');
    htmlParts.push('<table class="commit-table"><thead><tr><th>Hash</th><th>Data</th><th>Assunto</th></tr></thead><tbody>');
    commits.forEach(function(cm) {{
      htmlParts.push('<tr><td class="commit-hash">' + escapeHtml(cm.hash) + '</td><td>' + escapeHtml(cm.date) + '</td><td>' + escapeHtml(cm.subject) + '</td></tr>');
    }});
    htmlParts.push('</tbody></table></div>');
  }} else {{
    htmlParts.push('<p class="stat-desc">Nenhum commit encontrado via git log.</p>');
  }}
  htmlParts.push('</section>');

  // Section (g): Velocity / Ritmo
  htmlParts.push('<section class="section-card"><h2 class="section-title">Ritmo de Execução</h2>');
  htmlParts.push('<div class="stats-grid">');
  htmlParts.push('  <div class="stat-box"><div class="stat-label">Ritmo (3 dias)</div><div class="stat-val">' + data.paces.p3d.toFixed(2) + ' pts/dia</div></div>');
  htmlParts.push('  <div class="stat-box"><div class="stat-label">Ritmo (7 dias)</div><div class="stat-val">' + data.paces.p7d.toFixed(2) + ' pts/dia</div></div>');
  htmlParts.push('  <div class="stat-box"><div class="stat-label">Ritmo (Início)</div><div class="stat-val">' + data.paces.pall.toFixed(2) + ' pts/dia</div></div>');
  if (data.git && data.git.rate_7d !== null) {{
    htmlParts.push('  <div class="stat-box"><div class="stat-label">Commits / dia (7d)</div><div class="stat-val">' + data.git.rate_7d.toFixed(1) + '</div></div>');
    htmlParts.push('  <div class="stat-box"><div class="stat-label">Commits / dia (Total)</div><div class="stat-val">' + data.git.rate_all.toFixed(1) + '</div><div class="stat-desc">' + data.git.total_commits + ' commits</div></div>');
  }}
  htmlParts.push('</div>');
  htmlParts.push('</section>');

  // Section (h): Hypotheses & Unknowns
  htmlParts.push('<section class="section-card"><h2 class="section-title">Hipóteses e Desconhecidos Abertos</h2>');
  const res = data.research || {{}};
  htmlParts.push('<div class="stats-grid" style="margin-bottom: 12px;">');
  htmlParts.push('  <div class="stat-box"><div class="stat-label">Total [HIPÓTESE]</div><div class="stat-val">' + (res.hipotese_count || 0) + '</div></div>');
  htmlParts.push('  <div class="stat-box"><div class="stat-label">Total [DESCONHECIDO]</div><div class="stat-val">' + (res.desconhecido_count || 0) + '</div></div>');
  htmlParts.push('</div>');

  if ((res.hipotese_samples || []).length > 0) {{
    htmlParts.push('<div style="margin-bottom: 10px;"><strong>Amostra [HIPÓTESE] (primeiras 5):</strong></div>');
    res.hipotese_samples.forEach(function(s) {{
      htmlParts.push('<div class="sample-code" style="margin-bottom: 4px;">' + escapeHtml(s) + '</div>');
    }});
  }}
  if ((res.desconhecido_samples || []).length > 0) {{
    htmlParts.push('<div style="margin-top: 10px; margin-bottom: 10px;"><strong>Amostra [DESCONHECIDO] (primeiras 5):</strong></div>');
    res.desconhecido_samples.forEach(function(s) {{
      htmlParts.push('<div class="sample-code" style="margin-bottom: 4px;">' + escapeHtml(s) + '</div>');
    }});
  }}
  htmlParts.push('</section>');

  container.innerHTML = htmlParts.join('\\n');
}})();
</script>
</body>
</html>
"""


def cmd_build(root=None, out=None, today_str=None):
    """Execute build subcommand: read json, validate, calculate stats, output dashboard.html."""
    repo_root = resolve_repo_root(root)
    json_path = os.path.join(repo_root, "docs", "progress", "progress.json")
    if not os.path.exists(json_path):
        sys.stderr.write(f"Erro: arquivo de estado não encontrado: {json_path}\n")
        return 1

    try:
        with open(json_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        sys.stderr.write(f"Erro ao carregar {json_path}: {e}\n")
        return 1

    try:
        validate_progress(data)
    except ValidationError as e:
        sys.stderr.write(f"Erro de validação em {json_path}: {e}\n")
        return 1

    today = date.fromisoformat(today_str) if today_str else date.today()
    started = date.fromisoformat(data["started"])

    phases = data.get("phases", [])
    all_tasks = [t for p in phases for t in p.get("tasks", [])]

    # Overall calculations
    pct_all = total_percent(phases, include_est=True)
    pct_no_est = total_percent(phases, include_est=False)
    tot_w = total_weight(phases, include_est=True)
    earn_w = earned_weight(phases, include_est=True)
    rem_w = remaining_weight(phases, include_est=True)

    # Pace calculations
    p3d = pace(all_tasks, started, today, window_days=3)
    p7d = pace(all_tasks, started, today, window_days=7)
    pall = pace(all_tasks, started, today, window_days=None)

    eta_res = eta(rem_w, [p3d, p7d, pall], today)

    # Determine default open phase (F4 or first with incomplete tasks)
    open_phase_id = "F4"

    phases_stats = []
    for phase in phases:
        pct = phase_percent(phase, include_est=True)
        p_tasks = phase.get("tasks", [])
        p_weights = sum(t.get("weight", 0) for t in p_tasks)

        # Status derivado: concluída / em andamento / pendente
        if len(p_tasks) > 0 and all(t.get("state") == "done" for t in p_tasks):
            p_status = "concluída"
        elif any(t.get("state") == "doing" for t in p_tasks) or pct > 0.0:
            p_status = "em andamento"
        else:
            p_status = "pendente"

        # Group tasks by sprint
        sprints_map = {}
        for t in p_tasks:
            sp = t.get("sprint", "Outros")
            if sp not in sprints_map:
                sprints_map[sp] = []
            sprints_map[sp].append(t)

        sprints_list = [{"sprint": sp, "tasks": ts} for sp, ts in sprints_map.items()]

        phases_stats.append({
            "id": phase["id"],
            "name": phase["name"],
            "status": p_status,
            "percent": pct,
            "tasks_count": len(p_tasks),
            "weight_sum": p_weights,
            "is_open": (phase["id"] == open_phase_id),
            "sprints": sprints_list
        })

    # Blockers section: combine explicit blockers + tasks blocked/doing with blocked_by
    all_blockers = []
    for b in data.get("blockers", []):
        all_blockers.append({
            "id": b.get("id"),
            "kind": b.get("kind"),
            "text": b.get("text"),
            "ref": b.get("ref")
        })
    for t in all_tasks:
        if t.get("blocked_by") and t.get("state") in ("blocked", "doing"):
            all_blockers.append({
                "id": t.get("id"),
                "kind": "tarefa " + t.get("state"),
                "text": t.get("blocked_by"),
                "ref": t.get("id") + " - " + t.get("title")
            })

    # External data
    status_summary = extract_status_summary(os.path.join(repo_root, "docs", "STATUS.md"))
    research_data = extract_research_tags(os.path.join(repo_root, "docs", "research"))
    git_data = extract_git_data(repo_root, today, started)

    # Format ETA strings for pre-rendered overview
    if eta_res["status"] == "ok":
        min_d = eta_res["min_days"]
        max_d = eta_res["max_days"]
        if min_d == max_d:
            eta_summary_val = f"{min_d:.1f} dias"
            eta_summary_desc = f"previsto: {eta_res['min_date']}"
        else:
            eta_summary_val = f"{min_d:.1f} a {max_d:.1f} dias"
            eta_summary_desc = f"entre {eta_res['min_date']} e {eta_res['max_date']}"
    else:
        eta_summary_val = "Indeterminado"
        eta_summary_desc = "ritmo atual zerado ou insuficiente"

    # Assemble embedded JSON
    embedded_data = {
        "project": data.get("project", "iScooter Connect"),
        "started": data.get("started"),
        "today": today.isoformat(),
        "totals": {
            "percent_all": pct_all,
            "percent_no_est": pct_no_est,
            "total_weight": tot_w,
            "earned_weight": earn_w,
            "remaining_weight": rem_w
        },
        "paces": {
            "p3d": p3d,
            "p7d": p7d,
            "pall": pall
        },
        "eta": eta_res,
        "phases_stats": phases_stats,
        "all_blockers": all_blockers,
        "criteria": data.get("criteria", []),
        "status_summary": status_summary,
        "research": research_data,
        "git": git_data
    }

    raw_json = json.dumps(embedded_data, ensure_ascii=False)
    # Strictly escape <, >, & in JSON payload to avoid breaking HTML script tag or leaking raw tags
    safe_json = (
        raw_json.replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
    )

    out_path = out or os.path.join(repo_root, "docs", "progress", "dashboard.html")
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)

    rendered_html = HTML_TEMPLATE.format(
        project_name=html.escape(data.get("project", "iScooter Connect")),
        generated_date=today.isoformat(),
        project_started=data.get("started"),
        pct_all_bar=f"{pct_all:.1f}",
        pct_all_str=f"{pct_all:.1f}",
        pct_no_est_str=f"{pct_no_est:.1f}",
        earned_w_str=f"{earn_w:g}",
        total_w_str=f"{tot_w:g}",
        remaining_w_str=f"{rem_w:g}",
        eta_summary_val=html.escape(eta_summary_val),
        eta_summary_desc=html.escape(eta_summary_desc),
        json_payload=safe_json
    )

    # Atomic write via temporary file + os.replace
    out_dir = os.path.dirname(os.path.abspath(out_path))
    fd, tmp_file = tempfile.mkstemp(dir=out_dir, prefix="dash_", suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(rendered_html)
    os.replace(tmp_file, out_path)
    return 0


def cmd_set(task_id, new_state, date_str=None, root=None, out=None, today_str=None):
    """Execute set subcommand: update single task in progress.json and regenerate dashboard."""
    repo_root = resolve_repo_root(root)
    json_path = os.path.join(repo_root, "docs", "progress", "progress.json")
    if not os.path.exists(json_path):
        sys.stderr.write(f"Erro: arquivo de estado não encontrado: {json_path}\n")
        return 1

    if date_str is not None:
        date_regex = re.compile(r"^\d{4}-\d{2}-\d{2}$")
        if not isinstance(date_str, str) or not date_regex.match(date_str):
            sys.stderr.write(f"Erro: data inválida '{date_str}'. Formato esperado: AAAA-MM-DD.\n")
            return 2
        try:
            date.fromisoformat(date_str)
        except ValueError as e:
            sys.stderr.write(f"Erro: data inválida '{date_str}': {e}\n")
            return 2

    if today_str is not None:
        try:
            date.fromisoformat(today_str)
        except ValueError as e:
            sys.stderr.write(f"Erro: --today inválido '{today_str}': {e}\n")
            return 2

    try:
        with open(json_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        sys.stderr.write(f"Erro ao ler {json_path}: {e}\n")
        return 1

    try:
        validate_progress(data)
    except ValidationError as e:
        sys.stderr.write(f"Erro de validação em {json_path}: {e}\n")
        return 1

    state_values = data.get("state_values", [])
    if new_state not in state_values:
        sys.stderr.write(f"Erro: estado inválido '{new_state}'. Esperado um de: {state_values}\n")
        return 2

    # Find target task
    target_task = None
    for phase in data.get("phases", []):
        for task in phase.get("tasks", []):
            if task.get("id") == task_id:
                target_task = task
                break
        if target_task:
            break

    if not target_task:
        sys.stderr.write(f"Erro: tarefa com id '{task_id}' não encontrada.\n")
        return 2

    target_task["state"] = new_state
    if new_state == "done":
        eff_date = date_str or today_str or date.today().isoformat()
        target_task["done"] = eff_date
    else:
        target_task.pop("done", None)

    # Validate modified data BEFORE writing
    try:
        validate_progress(data)
    except ValidationError as e:
        sys.stderr.write(f"Erro de validação após modificação: {e}\n")
        return 1

    # Serialize preserving 1 task per line and atomic write
    serialized = serialize_progress(data)
    json_dir = os.path.dirname(os.path.abspath(json_path))
    fd, tmp_file = tempfile.mkstemp(dir=json_dir, prefix="prog_", suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(serialized)
    os.replace(tmp_file, json_path)

    # After set, call build
    build_ret = cmd_build(root=repo_root, out=out, today_str=today_str)
    return build_ret


def main():
    parser = argparse.ArgumentParser(description="iScooter Connect Progress Dashboard Generator")
    parser.add_argument("--today", dest="global_today", help="Data base para cálculos (AAAA-MM-DD)", default=None)
    parser.add_argument("--root", dest="global_root", help="Raiz do repositório/projeto", default=None)

    subparsers = parser.add_subparsers(dest="subcommand")

    # build
    parser_build = subparsers.add_parser("build", help="Constrói o dashboard.html")
    parser_build.add_argument("--out", help="Caminho do arquivo HTML de saída", default=None)
    parser_build.add_argument("--root", help="Raiz do repositório", default=None)
    parser_build.add_argument("--today", help="Data base para cálculos (AAAA-MM-DD)", default=None)

    # set
    parser_set = subparsers.add_parser("set", help="Altera o estado de uma tarefa")
    parser_set.add_argument("task_id", help="ID da tarefa (ex: F4.10)")
    parser_set.add_argument("state", help="Novo estado da tarefa")
    parser_set.add_argument("--date", help="Data de conclusão (AAAA-MM-DD)", default=None)
    parser_set.add_argument("--root", help="Raiz do repositório", default=None)
    parser_set.add_argument("--out", help="Caminho do dashboard.html", default=None)
    parser_set.add_argument("--today", help="Data base (AAAA-MM-DD)", default=None)

    args = parser.parse_args()

    if not args.subcommand:
        parser.print_help()
        sys.exit(1)

    root = getattr(args, "root", None) or args.global_root
    today = getattr(args, "today", None) or args.global_today

    if args.subcommand == "build":
        out = args.out
        ret = cmd_build(root=root, out=out, today_str=today)
        sys.exit(ret)
    elif args.subcommand == "set":
        out = args.out
        ret = cmd_set(
            task_id=args.task_id,
            new_state=args.state,
            date_str=args.date,
            root=root,
            out=out,
            today_str=today
        )
        sys.exit(ret)


if __name__ == "__main__":
    main()

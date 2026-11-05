#!/usr/bin/env python3
"""Monitor de agentes externos (Codex/agy/Haiku) em split view, num único console (Textual).

Uso:
  agent-monitor.py                      abre o monitor neste terminal (instância única)
  agent-monitor.py --ensure             abre o monitor num novo konsole se ainda não houver um
  agent-monitor.py --register NOME TIPO LOG PID [WORKTREE [PROMPT [RESULTADO]]]
                                        registra um agente em .omc/agents/NOME.json
Teclas: q sair · p expande/recolhe todos os prompts · r idem resultados · f arquivos · l logs (todas as seções iniciam recolhidas) · d esconde concluídos · c limpa concluídos
Cada painel: estado, PID, tempo, CPU/RAM da árvore de processos, prompt (Markdown, recolhido),
resultado (Markdown, quando existir), arquivos alterados e o final do log — tudo recolhido por padrão. Roda no venv `tools/monitor-venv` (textual + psutil).
"""
import fcntl, json, os, re, subprocess, sys, time
from pathlib import Path

ROOT = Path(os.environ.get("REPO_ROOT", "/home/evaldo/Documents/ix7"))
REG = ROOT / ".omc" / "agents"
LOCK = "/tmp/ix7-agent-monitor.lock"
VENV_PY = ROOT / "tools" / "monitor-venv" / "bin" / "python"
EXIT_RE = re.compile(r"^EXIT (\d+)\s*$")
NOISE = re.compile(r"^(hook:|\s*$)")


def register(name, kind, log, pid, worktree="", prompt="", result=""):
    REG.mkdir(parents=True, exist_ok=True)
    data = dict(name=name, kind=kind, log=str(log), pid=int(pid), worktree=worktree,
                prompt=prompt, result=result, start=time.time())
    tmp = REG / f".{name}.tmp"
    tmp.write_text(json.dumps(data))
    tmp.replace(REG / f"{name}.json")


def monitor_running():
    fd = os.open(LOCK, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        os.close(fd)
        return True
    fcntl.flock(fd, fcntl.LOCK_UN)
    os.close(fd)
    return False


def ensure():
    if monitor_running():
        return
    py = str(VENV_PY) if VENV_PY.exists() else sys.executable
    subprocess.Popen(["konsole", "--workdir", str(ROOT), "-e", py, str(Path(__file__).resolve())],
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)


def read_text(path, limit=60000):
    try:
        with open(path, "rb") as f:
            return f.read(limit).decode("utf-8", "replace")
    except OSError:
        return ""


def run_app():
    import psutil
    from textual.app import App, ComposeResult
    from textual.containers import Grid, Vertical, VerticalScroll
    from textual.widgets import Collapsible, Footer, Markdown, RichLog, Static

    class Agent:
        def __init__(self, path):
            d = json.loads(path.read_text())
            self.path = path
            self.name, self.kind, self.log = d["name"], d["kind"], Path(d["log"])
            self.pid, self.start, self.worktree = d["pid"], d["start"], d.get("worktree", "")
            self.prompt, self.result = d.get("prompt", ""), d.get("result", "")
            if self.result == str(self.log):
                self.result = ""  # resultado == log: não duplicar
            self.proc, self.cpu, self.rss, self.nproc = None, 0.0, 0, 0
            self.alive, self.exit_code, self.ended = False, None, None
            self.lines, self.mtime = [], 0.0
            self.changes, self.changes_at = [], 0.0
            self.refresh_proc(True)

        def refresh_proc(self, first=False):
            try:
                if self.proc is None:
                    self.proc = psutil.Process(self.pid)
                if not self.proc.is_running() or self.proc.status() == psutil.STATUS_ZOMBIE:
                    raise psutil.NoSuchProcess(self.pid)
                cpu = rss = 0
                tree = [self.proc] + self.proc.children(recursive=True)
                for p in tree:
                    try:
                        cpu += p.cpu_percent(None)
                        rss += p.memory_info().rss
                    except psutil.Error:
                        pass
                self.cpu, self.rss, self.nproc, self.alive = cpu, rss, len(tree), True
            except psutil.Error:
                if self.alive or first:
                    self.ended = self.ended or time.time()
                self.alive, self.cpu, self.rss, self.nproc = False, 0.0, 0, 0

        def refresh_log(self):
            try:
                st = self.log.stat()
            except OSError:
                return False
            if st.st_mtime == self.mtime:
                return False
            self.mtime = st.st_mtime
            with open(self.log, "rb") as f:
                f.seek(max(0, st.st_size - 64 * 1024))
                ls = [l.rstrip() for l in f.read().decode("utf-8", "replace").splitlines()]
            for l in reversed(ls):
                m = EXIT_RE.match(l)
                if m:
                    self.exit_code = int(m.group(1))
                    break
            self.lines = [l for l in ls if not NOISE.match(l)][-300:]
            return True

        def refresh_changes(self):
            if not self.worktree or time.time() - self.changes_at < 3:
                return
            self.changes_at = time.time()
            try:
                out = subprocess.run(["git", "-C", self.worktree, "status", "--porcelain", "-uall"],
                                     capture_output=True, text=True, timeout=5).stdout
            except (OSError, subprocess.SubprocessError):
                return
            res = []
            for l in out.splitlines():
                f = l[3:].strip()
                try:
                    age = time.time() - os.stat(os.path.join(self.worktree, f)).st_mtime
                except OSError:
                    age = -1
                res.append((age, l[:2].strip() or "?", f))
            self.changes = sorted(res, key=lambda t: (t[0] < 0, t[0]))

        @property
        def state(self):
            if self.alive:
                return "RODANDO", "cyan"
            if self.exit_code is None:
                return "ENCERRADO?", "yellow"
            return ("CONCLUÍDO", "green") if self.exit_code == 0 else (f"FALHOU ({self.exit_code})", "red")

        def elapsed(self):
            end = time.time() if self.alive else (self.ended or time.time())
            s = int(end - self.start)
            return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}"

    class Panel(Vertical):
        def __init__(self, agent):
            super().__init__(id="p-" + re.sub(r"\W", "_", agent.name))
            self.agent = agent
            self.border_title = f" {agent.name} [{agent.kind}] "
            self.res_loaded = False

        def compose(self) -> ComposeResult:
            yield Static("", classes="status")
            yield Static("", classes="activity")
            with Collapsible(title="Prompt", collapsed=True, classes="prompt"):
                yield Markdown(read_text(self.agent.prompt) or "_(prompt não registrado)_")
            with Collapsible(title="Resultado (Markdown)", collapsed=True, classes="result"):
                yield Markdown("_(ainda sem resultado)_", classes="result-md")
            with Collapsible(title="Arquivos alterados", collapsed=True, classes="files"):
                yield Static("", classes="files-body")
            with Collapsible(title="Log", collapsed=True, classes="logbox"):
                yield RichLog(wrap=True, markup=False, highlight=False, max_lines=400, classes="log")

        def on_mount(self):
            self.query_one(".result").display = False
            self.update_view(force=True)

        def update_view(self, force=False):
            a = self.agent
            st, color = a.state
            dot = "●" if a.alive else "○"
            res = (f"CPU {a.cpu:5.1f}%  RAM {a.rss / 1048576:,.0f} MB  procs {a.nproc}" if a.alive
                   else "recursos: — (processo terminou)")
            self.query_one(".status", Static).update(
                f"[b {color}]{dot} {st}[/]  PID {a.pid}  ⏱ {a.elapsed()}\n{res}")
            ch = a.changes
            last = f"última edição há {int(min((c[0] for c in ch if c[0] >= 0), default=0))}s" if ch else "sem edições"
            size = f"{a.log.stat().st_size} B" if a.log.exists() else "ainda não criado"
            self.query_one(".activity", Static).update(
                f"[dim]Δ {len(ch)} arquivo(s) · {last} · log {size}\n{a.worktree or a.log}[/]")
            files = self.query_one(".files", Collapsible)
            files.title = f"Arquivos alterados ({len(ch)})"
            self.query_one(".files-body", Static).update(
                "\n".join(f"[b]{s_:>2}[/] {f}  [dim]{int(age)}s[/]" if age >= 0 else f"[b]{s_:>2}[/] {f}"
                          for age, s_, f in ch[:40]) or "[dim](nenhum)[/]")
            if a.result and not self.res_loaded and not a.alive:
                txt = read_text(a.result)
                if txt.strip():
                    self.query_one(".result-md", Markdown).update(txt)
                    self.query_one(".result").display = True
                    self.res_loaded = True
            if a.refresh_log() or force:
                log = self.query_one(".log", RichLog)
                log.clear()
                lines = a.lines
                if not lines:
                    lines = ["(sem saída ainda: agy/codex em modo -p só gravam o log ao terminar)"]
                for l in lines:
                    log.write(l)

    class Monitor(App):
        TITLE = "ix7 · agentes"
        BINDINGS = [("q", "quit", "Sair"), ("p", "toggle('prompt')", "Prompts"),
                    ("r", "toggle('result')", "Resultados"),
                    ("f", "toggle('files')", "Arquivos"), ("l", "toggle('logbox')", "Logs"), ("d", "hide_done", "Esconder concluídos"),
                    ("c", "clear_done", "Limpar concluídos")]
        CSS = """
        #top { height: 1; background: $primary; color: $text; padding: 0 1; }
        #grid { height: 1fr; grid-gutter: 0; }
        Panel { border: round $primary; height: 100%; padding: 0 1; }
        Panel:focus-within { border: heavy $accent; }
        .status { height: 2; }
        .activity { height: 2; }
        Collapsible { padding: 0; margin: 0; border-top: none; }
        Collapsible Markdown { max-height: 18; overflow-y: auto; }
        .files-body { max-height: 8; overflow-y: auto; }
        .log { height: 16; min-height: 4; border-top: solid $panel; }
        #empty { padding: 1 2; }
        """

        def __init__(self):
            super().__init__()
            self.agents, self.hide = {}, False

        def compose(self) -> ComposeResult:
            yield Static("", id="top")
            yield Grid(id="grid")
            yield Footer()

        def on_mount(self):
            psutil.cpu_percent(None)
            self.set_interval(1.0, self.tick)
            self.tick()

        def action_toggle(self, kind: str):
            cols = [c for c in self.query(f"Collapsible.{kind}") if c.display]
            to = not all(c.collapsed for c in cols) if cols else False
            for c in cols:
                c.collapsed = to

        def action_hide_done(self):
            self.hide = not self.hide

        def action_clear_done(self):
            for a in list(self.agents.values()):
                if not a.alive:
                    a.path.unlink(missing_ok=True)

        def tick(self):
            REG.mkdir(parents=True, exist_ok=True)
            seen = set()
            for p in sorted(REG.glob("*.json")):
                try:
                    d = json.loads(p.read_text())
                except (OSError, ValueError):
                    continue
                seen.add(p.name)
                cur = self.agents.get(p.name)
                if cur is None or cur.start != d["start"]:
                    self.agents[p.name] = Agent(p)
            for k in [k for k in self.agents if k not in seen]:
                del self.agents[k]
            for a in self.agents.values():
                a.refresh_proc()
                a.refresh_changes()
            vis = sorted((a for a in self.agents.values() if a.alive or not self.hide),
                         key=lambda a: (not a.alive, a.start))
            grid = self.query_one("#grid", Grid)
            have = {p.agent.name: p for p in grid.query(Panel)}
            names = {a.name for a in vis}
            for n, p in have.items():
                if n not in names:
                    p.remove()
            for a in vis:
                if a.name in have and have[a.name].agent.start != a.start:
                    have[a.name].remove()
                    del have[a.name]
                if a.name not in have:
                    grid.mount(Panel(a))
            n = max(1, len(vis))
            cols = 1 if n == 1 else 2 if n <= 4 else 3
            grid.styles.grid_size_columns = cols
            grid.styles.grid_size_rows = -(-n // cols)
            for p in grid.query(Panel):
                if p.is_mounted:
                    try:
                        p.update_view()
                    except Exception:
                        pass
            vm = psutil.virtual_memory()
            gradle = sum(1 for p in psutil.process_iter(["cmdline"]) if "gradle" in " ".join(p.info["cmdline"] or []).lower())
            nrun = sum(a.alive for a in self.agents.values())
            self.query_one("#top", Static).update(
                f"agentes: {nrun} rodando / {len(self.agents)} · CPU {psutil.cpu_percent(None):4.1f}% · "
                f"RAM {vm.used / 2**30:.1f}/{vm.total / 2**30:.1f} GiB (livre {vm.available / 2**30:.1f}) · "
                f"load {os.getloadavg()[0]:.1f} · Gradle procs {gradle} · {time.strftime('%H:%M:%S')}")

    Monitor().run()


def main():
    a = sys.argv[1:]
    if a[:1] == ["--register"]:
        register(*a[1:8])
        return
    if a[:1] == ["--ensure"]:
        ensure()
        return
    fd = os.open(LOCK, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("Já existe um monitor aberto.")
        return
    run_app()


if __name__ == "__main__":
    main()

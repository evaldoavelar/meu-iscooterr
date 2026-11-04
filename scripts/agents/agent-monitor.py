#!/usr/bin/env python3
"""Monitor de agentes externos (Codex/agy/Haiku) em split view, num único console.

Uso:
  agent-monitor.py                      abre o monitor neste terminal (instância única)
  agent-monitor.py --ensure             abre o monitor num novo konsole se ainda não houver um
  agent-monitor.py --register NOME TIPO LOG PID [WORKTREE]
                                        registra um agente em .omc/agents/NOME.json
Teclas: q sair · Tab/←/→ foco · d esconde/mostra concluídos · c limpa concluídos · +/- linhas do log
Cada painel mostra: estado (RODANDO/CONCLUÍDO/FALHOU), PID, tempo, CPU/RAM da árvore de processos e
o final do log (linhas de "hook:" do Codex são filtradas). Depende só de stdlib + psutil.
"""
import curses, fcntl, json, os, re, subprocess, sys, time
from pathlib import Path

import psutil

ROOT = Path(os.environ.get("REPO_ROOT", "/home/evaldo/Documents/ix7"))
REG = ROOT / ".omc" / "agents"
LOCK = "/tmp/ix7-agent-monitor.lock"
EXIT_RE = re.compile(r"^EXIT (\d+)\s*$")
NOISE = re.compile(r"^(hook:|\s*$)")


def register(name, kind, log, pid, worktree=""):
    REG.mkdir(parents=True, exist_ok=True)
    data = dict(name=name, kind=kind, log=str(log), pid=int(pid), worktree=worktree, start=time.time())
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
    subprocess.Popen(
        ["konsole", "--workdir", str(ROOT), "-e", sys.executable, str(Path(__file__).resolve())],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)


class Agent:
    def __init__(self, path):
        self.path = path
        d = json.loads(path.read_text())
        self.name, self.kind, self.log = d["name"], d["kind"], Path(d["log"])
        self.pid, self.start, self.worktree = d["pid"], d["start"], d.get("worktree", "")
        self.proc = None
        self.cpu = 0.0
        self.rss = 0
        self.nproc = 0
        self.alive = False
        self.exit_code = None
        self.ended = None
        self.lines = []
        self.mtime = 0.0
        self.refresh_proc(first=True)

    def refresh_proc(self, first=False):
        try:
            if self.proc is None:
                self.proc = psutil.Process(self.pid)
            if not self.proc.is_running() or self.proc.status() == psutil.STATUS_ZOMBIE:
                raise psutil.NoSuchProcess(self.pid)
            tree = [self.proc] + self.proc.children(recursive=True)
            cpu = rss = 0
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

    def refresh_log(self, keep=400):
        try:
            st = self.log.stat()
            if st.st_mtime == self.mtime and self.lines:
                return
            self.mtime = st.st_mtime
            with open(self.log, "rb") as f:
                f.seek(max(0, st.st_size - 64 * 1024))
                raw = f.read().decode("utf-8", "replace")
            ls = [l.rstrip() for l in raw.splitlines()]
            for l in reversed(ls):
                m = EXIT_RE.match(l)
                if m:
                    self.exit_code = int(m.group(1))
                    break
            self.lines = [l for l in ls if not NOISE.match(l)][-keep:]
        except OSError:
            self.lines = ["(log ainda não existe)"]

    @property
    def state(self):
        if self.alive:
            return "RODANDO"
        if self.exit_code is None:
            return "ENCERRADO?"
        return "CONCLUÍDO" if self.exit_code == 0 else f"FALHOU ({self.exit_code})"

    def elapsed(self):
        end = time.time() if self.alive else (self.ended or time.time())
        s = int(end - self.start)
        return f"{s // 3600:d}:{s % 3600 // 60:02d}:{s % 60:02d}"


def fmt_mb(b):
    return f"{b / 1048576:,.0f} MB"


def put(win, y, x, text, attr=0):
    h, w = win.getmaxyx()
    if y < 0 or y >= h or x >= w:
        return
    try:
        win.addnstr(y, x, text, max(0, w - x - (1 if y == h - 1 else 0)), attr)
    except curses.error:
        pass


def draw_panel(scr, a, y, x, h, w, focused, tail_n, C):
    color = {"RODANDO": C["run"], "CONCLUÍDO": C["ok"]}.get(a.state, C["bad"] if a.state.startswith("FALHOU") else C["dim"])
    border = curses.A_BOLD if focused else 0
    for i in range(w):
        put(scr, y, x + i, "─", border)
        put(scr, y + h - 1, x + i, "─", border)
    for j in range(h):
        put(scr, y + j, x, "│", border)
        put(scr, y + j, x + w - 1, "│", border)
    put(scr, y, x, "┌", border); put(scr, y, x + w - 1, "┐", border)
    put(scr, y + h - 1, x, "└", border); put(scr, y + h - 1, x + w - 1, "┘", border)
    put(scr, y, x + 2, f" {a.name} [{a.kind}] ", curses.A_BOLD | (curses.A_REVERSE if focused else 0))
    inner = w - 4
    dot = "●" if a.alive else "○"
    put(scr, y + 1, x + 2, f"{dot} {a.state}", color | curses.A_BOLD)
    put(scr, y + 1, x + 2 + len(a.state) + 3, f"PID {a.pid}  ⏱ {a.elapsed()}")
    res = f"CPU {a.cpu:5.1f}%  RAM {fmt_mb(a.rss)}  procs {a.nproc}" if a.alive else "recursos: — (processo terminou)"
    put(scr, y + 2, x + 2, res[:inner])
    wt = (a.worktree or str(a.log))[-inner:]
    put(scr, y + 3, x + 2, wt, C["dim"])
    put(scr, y + 4, x + 1, "─" * (w - 2), C["dim"])
    room = h - 6
    if room <= 0:
        return
    out = []
    for l in a.lines[-room * 3:]:
        while len(l) > inner:
            out.append(l[:inner]); l = l[inner:]
        out.append(l)
    for i, l in enumerate(out[-min(room, tail_n):]):
        put(scr, y + 5 + i, x + 2, l[:inner])


def grid(n, h, w):
    if n <= 0:
        return 1, 1
    best = (1, n)
    for cols in range(1, n + 1):
        rows = -(-n // cols)
        if w // cols >= 50 and h // rows >= 11:
            best = (rows, cols)
            break
        best = (rows, cols)
    return best


def run(scr):
    curses.curs_set(0)
    curses.use_default_colors()
    for i, c in enumerate((curses.COLOR_GREEN, curses.COLOR_CYAN, curses.COLOR_RED, curses.COLOR_BLACK), 1):
        curses.init_pair(i, c if i < 4 else 8 if curses.COLORS > 8 else curses.COLOR_WHITE, -1)
    C = dict(ok=curses.color_pair(1), run=curses.color_pair(2), bad=curses.color_pair(3), dim=curses.color_pair(4))
    scr.timeout(1000)
    agents, focus, hide_done, tail_n = {}, 0, False, 200
    psutil.cpu_percent(None)
    while True:
        REG.mkdir(parents=True, exist_ok=True)
        seen = set()
        for p in sorted(REG.glob("*.json")):
            seen.add(p.name)
            try:
                if p.name not in agents or agents[p.name].start != json.loads(p.read_text())["start"]:
                    agents[p.name] = Agent(p)
            except (OSError, ValueError, KeyError):
                continue
        for k in list(agents):
            if k not in seen:
                del agents[k]
        for a in agents.values():
            a.refresh_proc(); a.refresh_log()
        vis = [a for a in sorted(agents.values(), key=lambda a: (not a.alive, a.start)) if a.alive or not hide_done]
        focus = min(focus, max(0, len(vis) - 1))
        scr.erase()
        h, w = scr.getmaxyx()
        vm, cpu = psutil.virtual_memory(), psutil.cpu_percent(None)
        gr = [p for p in psutil.process_iter(["name", "cmdline"]) if "gradle" in " ".join(p.info["cmdline"] or []).lower()][:50]
        gcount = len({p.pid for p in gr})
        nrun = sum(a.alive for a in agents.values())
        head = (f" ix7 · agentes: {nrun} rodando / {len(agents)} · CPU {cpu:4.1f}% · RAM {vm.used / 2**30:.1f}/{vm.total / 2**30:.1f} GiB "
                f"(livre {vm.available / 2**30:.1f}) · load {os.getloadavg()[0]:.1f} · Gradle procs {gcount} · {time.strftime('%H:%M:%S')}")
        put(scr, 0, 0, head.ljust(w), curses.A_REVERSE)
        put(scr, h - 1, 0, " q sair · Tab/←/→ foco · d esconde concluídos · c limpa concluídos · +/- linhas".ljust(w - 1), curses.A_REVERSE)
        if not vis:
            put(scr, 2, 2, "Nenhum agente registrado. Os scripts run-*.sh registram automaticamente.")
        else:
            rows, cols = grid(len(vis), h - 2, w)
            ph, pw = (h - 2) // rows, w // cols
            for i, a in enumerate(vis):
                r, c = divmod(i, cols)
                draw_panel(scr, a, 1 + r * ph, c * pw, ph, pw, i == focus, tail_n, C)
        scr.refresh()
        k = scr.getch()
        if k in (ord("q"), 27):
            return
        elif k in (9, curses.KEY_RIGHT, curses.KEY_DOWN):
            focus = (focus + 1) % max(1, len(vis))
        elif k in (curses.KEY_LEFT, curses.KEY_UP, curses.KEY_BTAB):
            focus = (focus - 1) % max(1, len(vis))
        elif k == ord("d"):
            hide_done = not hide_done
        elif k == ord("c"):
            for a in list(agents.values()):
                if not a.alive:
                    a.path.unlink(missing_ok=True)
        elif k == ord("+"):
            tail_n += 10
        elif k == ord("-"):
            tail_n = max(5, tail_n - 10)


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--register":
        register(*sys.argv[2:7])
        return
    if len(sys.argv) > 1 and sys.argv[1] == "--ensure":
        ensure()
        return
    fd = os.open(LOCK, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("Já existe um monitor aberto.")
        return
    curses.wrapper(run)


if __name__ == "__main__":
    main()

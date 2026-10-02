"""
Launches real Minecraft 1.20.1 + Fabric with Claude Companion (and optionally other mods) on a headless
machine, drops straight into a pre-generated singleplayer world, lets the mod's self-test run, then
reports what happened as GitHub annotations: crash reports, errors, and the [SELFTEST] results.

usage: python ci/smoketest.py --label full --mods fabric-api,sodium,iris,controlify --jar build/libs/x.jar
"""
import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

MC = "1.20.1"
UA = {"User-Agent": "claude-companion-ci/1.0"}


def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)


def download(url, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


def annotate(level, title, text):
    text = text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::{level} title={title}::{text[:60000]}", flush=True)


# ---------------------------------------------------------------- mods from Modrinth

def modrinth_versions(project):
    q = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([MC])})
    return get_json(f"https://api.modrinth.com/v2/project/{project}/version?{q}")


def install_mods(slugs, mods_dir):
    done, queue, installed = set(), list(slugs), []
    while queue:
        proj = queue.pop(0)
        if proj in done:
            continue
        done.add(proj)
        versions = modrinth_versions(proj)
        if not versions:
            print(f"!! no {MC} fabric version for {proj}")
            continue
        v = next((x for x in versions if x["version_type"] == "release"), versions[0])
        f = next((x for x in v["files"] if x["primary"]), v["files"][0])
        download(f["url"], mods_dir / f["filename"])
        installed.append(f"{proj} {v['version_number']}")
        done.add(v["project_id"])
        for dep in v.get("dependencies", []):
            if dep["dependency_type"] == "required" and dep.get("project_id"):
                queue.append(dep["project_id"])
    return installed


# ---------------------------------------------------------------- world

def make_world(java, world_dir, name):
    """Generate a world with the vanilla dedicated server so the client can quick-play straight into it."""
    manifest = get_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    meta = get_json(next(v["url"] for v in manifest["versions"] if v["id"] == MC))
    sdir = Path("server")
    sdir.mkdir(exist_ok=True)
    download(meta["downloads"]["server"]["url"], sdir / "server.jar")
    (sdir / "eula.txt").write_text("eula=true\n")
    (sdir / "server.properties").write_text(f"level-name={name}\nonline-mode=false\nlevel-seed=claude\nspawn-protection=0\n")
    p = subprocess.Popen([java, "-Xmx2G", "-jar", "server.jar", "nogui"], cwd=sdir, stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    deadline = time.time() + 300
    for line in p.stdout:
        if "Done (" in line:
            p.stdin.write("stop\n")
            p.stdin.flush()
        if time.time() > deadline:
            p.kill()
            break
    p.wait()
    shutil.copytree(sdir / name, world_dir / name, dirs_exist_ok=True)
    print(f"world generated at {world_dir / name}")


# ---------------------------------------------------------------- run

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--mods", default="fabric-api")
    ap.add_argument("--jar", required=True)
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--tts", default="csm")
    args = ap.parse_args()

    from portablemc.fabric import FabricVersion
    from portablemc.standard import Context, StandardRunner

    main_dir = Path("mc").absolute()
    work = (main_dir / f"run-{args.label}").absolute()
    mods = work / "mods"
    mods.mkdir(parents=True, exist_ok=True)

    installed = install_mods([m for m in args.mods.split(",") if m], mods)
    shutil.copy(args.jar, mods / Path(args.jar).name)
    installed.append("claude-companion " + Path(args.jar).name)
    annotate("notice", f"[{args.label}] mods", "\n".join(installed))

    cfg = {"ttsEngine": args.tts, "voiceEnabled": True}
    if os.environ.get("ANTHROPIC_API_KEY"):
        cfg["apiKey"] = os.environ["ANTHROPIC_API_KEY"]
    (work / "config").mkdir(exist_ok=True)
    (work / "config" / "claudecompanion.json").write_text(json.dumps(cfg))
    (work / "options.txt").write_text("\n".join([
        "onboardAccessibility:false", "pauseOnLostFocus:false", "renderDistance:4", "simulationDistance:5",
        "maxFps:30", "tutorialStep:none", "narrator:0", "skipMultiplayerWarning:true", "joinedFirstServer:true",
    ]) + "\n")

    version = FabricVersion.with_fabric(MC, context=Context(main_dir, work))
    version.set_auth_offline("Shiv", None)
    version.set_quick_play_singleplayer("selftest")
    env = version.install()
    java = env.jvm_args[0]
    env.jvm_args[1:1] = ["-Xmx3G", "-Dclaudecompanion.selftest=true"]

    if not (work / "saves" / "selftest").exists():
        make_world(java, work / "saves", "selftest")

    log_path = Path(f"game-{args.label}.log")
    timed_out = {"v": False}

    class Runner(StandardRunner):
        def process_create(self, a, work_dir):
            p = subprocess.Popen(a, cwd=work_dir, stdout=open(log_path, "w"), stderr=subprocess.STDOUT)

            def kill():
                if p.poll() is None:
                    timed_out["v"] = True
                    p.kill()
            threading.Timer(args.timeout, kill).start()
            return p

    start = time.time()
    env.run(Runner())
    took = int(time.time() - start)

    # ------------------------------------------------------------ report
    latest = work / "logs" / "latest.log"
    text = latest.read_text(errors="replace") if latest.exists() else log_path.read_text(errors="replace")
    crashes = sorted(glob.glob(str(work / "crash-reports" / "*.txt")))
    ok = True

    if crashes:
        ok = False
        annotate("error", f"[{args.label}] CRASH REPORT", Path(crashes[-1]).read_text(errors="replace")[:20000])
    selftest = [l for l in text.splitlines() if "[SELFTEST]" in l or "[Claude says]" in l or "[Claude voice" in l]
    if selftest:
        annotate("notice", f"[{args.label}] self-test", "\n".join(l.split("]: ", 1)[-1] for l in selftest))
    done = [l for l in selftest if "DONE passed=" in l]
    if not done or "failed=0" not in done[-1]:
        ok = False
    interesting = []
    lines = text.splitlines()
    for i, l in enumerate(lines):
        low = l.lower()
        if ("/error]" in low or "exception" in low or "/fatal]" in low) and "selftest" not in low:
            interesting.extend(lines[i:i + 6])
    if interesting:
        annotate("warning", f"[{args.label}] errors in log", "\n".join(interesting[:300]))
    summary = f"label={args.label} ran {took}s timed_out={timed_out['v']} crash={bool(crashes)} selftest_done={bool(done)} -> {'PASS' if ok else 'FAIL'}"
    annotate("notice" if ok else "error", f"[{args.label}] result", summary)
    if not done:
        annotate("warning", f"[{args.label}] log tail", "\n".join(lines[-150:]))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

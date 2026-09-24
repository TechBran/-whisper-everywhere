"""Run the SHIPPED NPU pack binaries on Qualcomm AI Hub's hosted real phones.

Why this exists: the census admits silicon nobody here can run the app on (the Galaxy S25 and
S26 generations, every plain 8 Elite / 8 Elite Gen 5 phone, the 7 Gen 4), and since 4.15 the
8 Gen 1 (SM8450), on which no v69 binary has yet run in this program. AI Hub hosts real devices
for each of those chips. A profile job on the exact bytes Play delivers answers "does this
family's context binary load and execute on that silicon, and how fast" without a phone in hand.

What it runs is PLAN below, one entry per census family: each 8 Elite family on its own Galaxy
phone (S25, S26), the 7 Gen 4 QRD (that family's only hosted device), 8gen1 on the two hosted
SM8450 devices (the Galaxy S22 family and the Tab S8), and the S23 (qcs8550) and S24 (8gen3) as
controls. The plain-bin reference phones ("Snapdragon 8 Elite QRD", "Snapdragon 8 Elite Gen 5
QRD") are NOT in the plan since 2026-09-24 (the reason is at PLAN); their 2026-09-22 PASS on the
v0.62.2 binaries stays the plain-bin evidence the 8 Elite rows cite.

First run: 2026-09-22, all 14 jobs PASS on the v0.62.2 binaries, QRDs included
(docs/measurements/2026-09-22-aihub-hosted-device-matrix.md). Re-planned 2026-09-24 for the
v0.63.0 refresh and the 8gen1 family: not yet run.

What it does NOT test: the app. Play delivery, onboarding, capture, mel, the decode loop and the
speaker pipeline only run in the real app on a real phone.

Three steps, each refusing to go on if the last one did not hold:
  1. hash every local pack file against NpuFleetCensus's own digests (so "PASS" means the bytes
     Play delivers, never a stray copy);
  2. upload each file once (QNN context binary) and submit one profile job per planned device;
  3. wait, and print load time, inference time and peak memory per job.

State (model ids, job ids, results) lives in --state, so an interrupted run resumes without
re-uploading or re-submitting anything.

Setup, once per machine: `pip install qai-hub`, then `qai-hub configure --api_token <token>`
(the token is the account owner's; never commit it). Usage:
  python tools/aihub_matrix.py [--tier npu-turbo] [--state aihub_state.json] [--report-only]
"""
import argparse
import hashlib
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CENSUS = os.path.join(REPO, "app", "src", "main", "java", "com", "whispereverywhere", "npu",
                      "NpuFleetCensus.kt")
MODULE_BY_TIER = {"npu-turbo": "npu_turbo", "npu": "npu_small"}

# family -> hosted devices. The Galaxy phone is the pack's own target. The two controls run
# families that were device-executed in the app on their PREVIOUS binaries (Fold6, S23 Ultra),
# which is what lets their numbers calibrate the rest.
#
# 2026-09-24: the plain-bin QRDs ("Snapdragon 8 Elite QRD", "Snapdragon 8 Elite Gen 5 QRD") are
# out of the plan, by the head's brief, which records both as deprecated on AI Hub; each family
# keeps its Galaxy phone. (qai-hub 0.55.0's get_devices() still listed both that day with no
# deprecation attribute, so the removal rests on the brief, not on a refused job.) Their
# 2026-09-22 PASS on the v0.62.2 binaries is the plain-bin evidence the 8 Elite rows cite; it is
# not repeated for v0.63.0 here. The 7 Gen 4 QRD stays: it is that family's only hosted device.
# 8gen1 is new, on the two hosted SM8450 devices (both hexagon:v69 / soc-model:36 in AI Hub's
# own attributes): the pack's reference phone and the tablet the owner has in hand.
PLAN = {
    "8elite_galaxy": ["Samsung Galaxy S25"],
    "8elite5_galaxy": ["Samsung Galaxy S26"],
    "7gen4": ["Snapdragon 7 Gen 4 QRD"],
    "qcs8550": ["Samsung Galaxy S23"],
    "8gen3": ["Samsung Galaxy S24"],
    "8gen1": ["Samsung Galaxy S22 (Family)", "Samsung Galaxy Tab S8"],
}
# --qairt_version 2.50: the v0.63.0 context binaries are QAIRT 2.50.0.260828221209 builds, and a
# profile job defaults to an older QAIRT (2.45 when this was written) that would refuse to load a
# newer context. 2.50 is also the runtime the app ships since 4.15.
OPTIONS = "--max_profiler_iterations 10 --qairt_version 2.50"


def census_rows():
    text = open(CENSUS, encoding="utf-8").read()
    rows = {}
    for block in re.findall(r"PackArtifact\((.*?)\n        \)", text, re.S):
        fam = re.search(r'familyId = "([^"]+)"', block)
        tier = re.search(r'tierId = "([^"]+)"', block)
        ents = re.findall(r'PackEntry\(\s*"([^"]+)", ([0-9_]+)L,\s*"([0-9a-f]{64})"', block)
        if fam and tier and len(ents) == 2:
            rows[(fam[1], tier[1])] = [(n, int(b.replace("_", "")), s) for n, b, s in ents]
    return rows


def verified_paths(tier):
    rows = census_rows()
    module = MODULE_BY_TIER[tier]
    paths, bad = {}, []
    for fam in PLAN:
        d = os.path.join(REPO, module, "src", "main", "assets", f"{module}#group_soc_{fam}")
        paths[fam] = {}
        for (name, nbytes, sha), part in zip(rows[(fam, tier)], ("encoder", "decoder")):
            p = os.path.join(d, name)
            h = hashlib.sha256()
            with open(p, "rb") as f:
                for chunk in iter(lambda: f.read(8 << 20), b""):
                    h.update(chunk)
            ok = os.path.getsize(p) == nbytes and h.hexdigest() == sha
            print(f"{fam:15} {part:7} {'MATCH' if ok else 'MISMATCH'} {nbytes:>11,} B", flush=True)
            if not ok:
                bad.append(p)
            paths[fam][part] = p
    if bad:
        sys.exit("refusing to upload bytes the census does not name: " + ", ".join(bad))
    return paths


def load(path):
    return json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {"models": {}, "jobs": {}}


def save(path, st):
    json.dump(st, open(path, "w", encoding="utf-8"), indent=1)


def submit(tier, state_path):
    import qai_hub as hub
    paths = verified_paths(tier)
    st = load(state_path)
    for fam, devices in PLAN.items():
        for part in ("encoder", "decoder"):
            key = f"{tier}/{fam}/{part}"
            if key not in st["models"]:
                m = hub.upload_model(paths[fam][part], name=f"we-{fam}-{tier}-{part}")
                st["models"][key] = {"id": m.model_id, "type": str(m.model_type)}
                save(state_path, st)
            model = hub.get_model(st["models"][key]["id"])
            for dev in devices:
                jkey = f"{key}@{dev}"
                if jkey not in st["jobs"]:
                    job = hub.submit_profile_job(model=model, device=hub.Device(dev), options=OPTIONS,
                                                 name=f"we-{fam}-{tier}-{part}-{dev}")
                    st["jobs"][jkey] = {"id": job.job_id}
                    save(state_path, st)
                    print(f"submitted {jkey} -> {job.job_id}", flush=True)
    return st


def report(state_path):
    import qai_hub as hub
    st = load(state_path)
    failed = 0
    for jkey, rec in st["jobs"].items():
        job = hub.get_job(rec["id"])
        job.wait()
        status = job.get_status()
        rec["status"] = status.code
        if status.success:
            ex = job.download_profile().get("execution_summary", {})
            rec["inference_ms"] = round(ex.get("estimated_inference_time", 0) / 1000.0, 1)
            rec["first_load_ms"] = round(ex.get("first_load_time", 0) / 1000.0, 1)
            rec["warm_load_ms"] = round(ex.get("warm_load_time", 0) / 1000.0, 1)
            rec["peak_mem_mb"] = round(ex.get("estimated_inference_peak_memory", 0) / 1e6, 1)
        else:
            failed += 1
            rec["message"] = getattr(status, "message", "") or ""
        save(state_path, st)
        print(f"{jkey:62} {rec['status']:8} "
              + (f"infer {rec['inference_ms']} ms  first-load {rec['first_load_ms']} ms  "
                 f"peak {rec['peak_mem_mb']} MB" if status.success else rec["message"][:300]), flush=True)
    print(f"{len(st['jobs']) - failed}/{len(st['jobs'])} jobs passed")
    return failed


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", default="npu-turbo", choices=sorted(MODULE_BY_TIER))
    ap.add_argument("--state", default="aihub_state.json")
    ap.add_argument("--report-only", action="store_true")
    a = ap.parse_args()
    if not a.report_only:
        submit(a.tier, a.state)
    sys.exit(1 if report(a.state) else 0)

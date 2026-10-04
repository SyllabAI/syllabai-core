#!/usr/bin/env python3
"""Safe-merge pre-verifier — ADVISORY ONLY (T-COORD-2, P5).

Computes the merge-safety facts an operator would otherwise re-verify by
hand and posts/updates ONE comment on the PR:

  R1  task packet — T-ids found in the PR title/body, whether a packet
      exists in .syllabai/tasks/ and its status (repo-agnostic: repos
      without .syllabai report that the packet registry lives in the
      master pack)
  R2  CI — check runs on the PR head commit (success / failed / pending)
  R3  mergeability — GitHub mergeable_state
  R4  base freshness — commits the head is behind the base branch tip
  R5  active leases — contention visibility from .syllabai/locks.yaml

The verifier is never a required check and never blocks a merge: the
operator's explicit merge word remains the gate. A green verdict only
means "nothing needs re-verifying by hand before giving the word".

Usage:
  safe_merge_check.py [--post]            # in CI (needs GITHUB_TOKEN env)
  safe_merge_check.py --dry-run           # local: print only, no posting

Exit codes: 0 always (advisory), 2 on internal error.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

import yaml

COMMENT_MARKER = "<!-- safe-merge-verifier -->"
TASK_ID_RE = re.compile(r"\bT-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*\b")


def api(method: str, url: str, token: str | None, payload: dict | None = None):
    req = urllib.request.Request(url, method=method)
    if token:
        req.add_header("Authorization", f"token {token}")
    req.add_header("Accept", "application/vnd.github+json")
    body = None
    if payload is not None:
        req.add_header("Content-Type", "application/json")
        body = json.dumps(payload).encode()
    try:
        with urllib.request.urlopen(req, body) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as exc:
        try:
            return exc.code, json.loads(exc.read().decode() or "{}")
        except Exception:  # noqa: BLE001 - degrade to status-only
            return exc.code, {}


def extract_task_ids(text: str) -> list[str]:
    seen, out = set(), []
    for match in TASK_ID_RE.findall(text or ""):
        if match not in seen and len(match) > 2:
            seen.add(match)
            out.append(match)
    return out


def packet_status(repo_root: Path, task_id: str) -> str | None:
    packet = repo_root / ".syllabai" / "tasks" / f"{task_id}.yaml"
    if not packet.is_file():
        return None
    try:
        with open(packet, "r", encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)
        task = (doc or {}).get("task") if isinstance(doc, dict) else None
        return str(task.get("status")) if isinstance(task, dict) else "UNREADABLE"
    except yaml.YAMLError:
        return "UNPARSEABLE"


def active_leases(repo_root: Path) -> list[dict]:
    locks_file = repo_root / ".syllabai" / "locks.yaml"
    if not locks_file.is_file():
        return []
    try:
        with open(locks_file, "r", encoding="utf-8") as fh:
            doc = yaml.safe_load(fh) or {}
        return [l for l in (doc.get("locks") or []) if isinstance(l, dict)]
    except yaml.YAMLError:
        return [{"resource": "(locks.yaml unparseable)", "task": "-", "owner": "-"}]


def build_comment(repo, pr, repo_root: Path, api_base: str, token: str | None) -> str:
    lines = [COMMENT_MARKER, "## 🛂 Safe-merge pre-verifier (advisory)", ""]
    lines.append("_Nothing here blocks a merge — the operator's explicit merge word remains the gate._")
    lines.append("")

    # R1 — task packet (dedupe: the same id often appears in title AND body)
    seen_ids: set[str] = set()
    ids = []
    for tid in extract_task_ids(pr.get("title", "")) + extract_task_ids(pr.get("body", "")):
        if tid not in seen_ids:
            seen_ids.add(tid)
            ids.append(tid)
    if not repo_root.joinpath(".syllabai").is_dir():
        lines.append("**R1 packet:** no `.syllabai/` in this repo — packet registry lives in the master pack.")
    elif not ids:
        lines.append("**R1 packet:** ⚠️ no T-id found in PR title/body.")
    else:
        for tid in ids[:5]:
            status = packet_status(repo_root, tid)
            if status is None:
                lines.append(f"**R1 packet:** ⚠️ `{tid}` referenced but no packet file exists.")
            else:
                emoji = "✅" if status in {"VERIFYING", "EXECUTING", "DONE"} else "ℹ️"
                lines.append(f"**R1 packet:** {emoji} `{tid}` status **{status}**")
    if ids:
        lines.append(f"  <sub>ids seen: {', '.join(f'`{i}`' for i in ids[:8])}</sub>")

    # R2 — CI on head
    head = pr["head"]["sha"]
    status, runs = api("GET", f"{api_base}/repos/{repo}/commits/{head}/check-runs", token)
    check_runs = runs.get("check_runs", []) if status == 200 else []
    if check_runs:
        ok = [r for r in check_runs if r.get("conclusion") == "success"]
        bad = [r for r in check_runs if r.get("conclusion") not in (None, "success", "skipped", "neutral")]
        pending = [r for r in check_runs if r.get("status") != "completed"]
        state = "✅" if not bad and not pending and ok else ("⚠️" if bad else "⏳")
        detail = f"{len(ok)} success"
        if bad:
            detail += f", {len(bad)} failing ({', '.join(r['name'] for r in bad[:3])})"
        if pending:
            detail += f", {len(pending)} pending ({', '.join(r['name'] for r in pending[:3])})"
        lines.append(f"**R2 CI:** {state} {detail} on head `{head[:7]}`")
    else:
        lines.append(f"**R2 CI:** ℹ️ no check runs on head `{head[:7]}`")

    # R3 — mergeability
    state = pr.get("mergeable_state")
    emoji = {"clean": "✅", "dirty": "❌", "blocked": "⛔", "unstable": "⚠️", "unknown": "❔"}.get(state, "❔")
    lines.append(f"**R3 mergeability:** {emoji} `{state}` (mergeable={pr.get('mergeable')})")

    # R4 — base freshness
    base_sha = pr["base"]["sha"]
    status, cmp = api("GET", f"{api_base}/repos/{repo}/compare/{base_sha}...{head}", token)
    if status == 200:
        behind = cmp.get("behind_by", 0)
        emoji = "✅" if behind == 0 else ("ℹ️" if behind <= 10 else "⚠️")
        lines.append(f"**R4 base freshness:** {emoji} head is {behind} commit(s) behind the base tip (`{base_sha[:7]}`)")
    else:
        lines.append("**R4 base freshness:** ℹ️ compare unavailable")

    # R5 — active leases
    leases = active_leases(repo_root)
    if leases:
        lines.append(f"**R5 active leases:** ℹ️ {len(leases)} in force —")
        for l in leases[:6]:
            lines.append(f"  - `{l.get('resource')}` by {l.get('owner')} for {l.get('task')} (expires {l.get('expires_at')})")
    else:
        lines.append("**R5 active leases:** ✅ none in force")

    # Verdict
    lines.append("")
    if (pr.get("mergeable_state") == "clean" and check_runs
            and not any(r.get("conclusion") not in (None, "success", "skipped", "neutral")
                        for r in check_runs)
            and not any(r.get("status") != "completed" for r in check_runs)):
        lines.append("### ✅ ADVISORY-SAFE — CI green, mergeable clean, no contention surfaced.")
    else:
        lines.append("### ⚠️ ADVISORY-REVIEW — see the rows above before giving the merge word.")
    lines.append("")
    lines.append(f"<sub>safe-merge pre-verifier (T-COORD-2 P5) · advisory only · head `{head[:7]}`</sub>")
    return "\n".join(lines)


def post_comment(repo: str, pr_number: int, body: str, api_base: str, token: str | None) -> str:
    status, comments = api("GET", f"{api_base}/repos/{repo}/issues/{pr_number}/comments", token)
    existing = None
    if status == 200:
        for c in comments:
            if isinstance(c, dict) and str(c.get("body", "")).startswith(COMMENT_MARKER):
                existing = c
                break
    if existing:
        status, _ = api("PATCH", f"{api_base}/repos/{repo}/issues/comments/{existing['id']}", token, {"body": body})
        return "updated existing comment" if status == 200 else f"update failed (HTTP {status})"
    status, _ = api("POST", f"{api_base}/repos/{repo}/issues/{pr_number}/comments", token, {"body": body})
    return "posted new comment" if status == 201 else f"post failed (HTTP {status})"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"),
                        help="owner/name (default: GITHUB_REPOSITORY)")
    parser.add_argument("--pr", type=int, default=os.environ.get("PR_NUMBER"),
                        help="PR number (default: PR_NUMBER env)")
    parser.add_argument("--api", default="https://api.github.com")
    parser.add_argument("--post", action="store_true",
                        help="post/update the PR comment (default: dry-run print)")
    parser.add_argument("--repo-root", default=".")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GITHUB_PAT")
    if not args.repo or not args.pr:
        print("safe-merge: --repo and --pr (or GITHUB_REPOSITORY/PR_NUMBER) are required", file=sys.stderr)
        return 2

    status, pr = api("GET", f"{args.api}/repos/{args.repo}/pulls/{args.pr}", token)
    if status != 200:
        print(f"safe-merge: PR fetch failed (HTTP {status})", file=sys.stderr)
        return 0  # advisory: a fetch failure must never block anything

    body = build_comment(args.repo, pr, Path(args.repo_root), args.api, token)

    if args.post and token:
        outcome = post_comment(args.repo, args.pr, body, args.api, token)
        print(f"safe-merge: {outcome}")
    else:
        print(body)
        print("\n[dry-run — pass --post with a token to comment]")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"safe-merge: internal error: {exc}", file=sys.stderr)
        sys.exit(2)

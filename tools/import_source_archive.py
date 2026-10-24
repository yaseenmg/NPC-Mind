#!/usr/bin/env python3
"""Import files from a source ZIP into a GitHub branch without overwriting files.

Requires Python 3.10+ (standard library only) and GITHUB_TOKEN with Contents: write.
Dry-run is the default. Add --apply to commit the missing files.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import PurePosixPath

API = "https://api.github.com"
REPO_DEFAULT = "yaseenmg/NPC-Mind"


class GitHubAPIError(RuntimeError):
    pass


def api_request(token: str, method: str, endpoint: str, payload=None):
    url = endpoint if endpoint.startswith("https://") else API + endpoint
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
            "User-Agent": "NPC-Mind-source-importer",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            raw = response.read()
            return json.loads(raw.decode("utf-8")) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise GitHubAPIError(f"GitHub API {method} {url} -> HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise GitHubAPIError(f"Network error contacting GitHub: {exc}") from exc


def safe_zip_path(name: str, strip_prefix: str = "") -> str | None:
    name = name.replace("\\", "/")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        return None
    normalized = path.as_posix()
    if strip_prefix:
        prefix = strip_prefix.strip("/").replace("\\", "/")
        if normalized == prefix:
            return None
        if not normalized.startswith(prefix + "/"):
            return None
        normalized = normalized[len(prefix) + 1:]
    if not normalized or normalized.startswith(".git/"):
        return None
    return normalized


def read_archive(archive_path: str, nested_zip: str | None, strip_prefix: str):
    with zipfile.ZipFile(archive_path) as outer:
        if nested_zip:
            try:
                inner_bytes = outer.read(nested_zip)
            except KeyError as exc:
                candidates = [n for n in outer.namelist() if n.lower().endswith(".zip")]
                raise ValueError(
                    f"Nested ZIP not found: {nested_zip}\nZIPs inside archive: {candidates[:30]}"
                ) from exc
            import io
            source_zip = zipfile.ZipFile(io.BytesIO(inner_bytes))
            close_source = True
        else:
            source_zip = outer
            close_source = False

        try:
            files = {}
            for info in source_zip.infolist():
                if info.is_dir():
                    continue
                repo_path = safe_zip_path(info.filename, strip_prefix)
                if repo_path is None:
                    continue
                files[repo_path] = source_zip.read(info)
            return files
        finally:
            if close_source:
                source_zip.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", help="Path to source ZIP file")
    parser.add_argument("--repo", default=REPO_DEFAULT, help="GitHub owner/repository")
    parser.add_argument("--branch", default="source-import-tool", help="Target branch (default: source-import-tool)")
    parser.add_argument("--nested-zip", help="If the archive contains the actual source ZIP, provide its exact path inside the outer ZIP")
    parser.add_argument("--strip-prefix", default="", help="Remove this leading directory from each ZIP path")
    parser.add_argument("--apply", action="store_true", help="Actually upload and commit; otherwise only preview")
    parser.add_argument("--max-files", type=int, default=500, help="Safety limit for one commit (default: 500)")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN")
    if args.apply and not token:
        parser.error("Set GITHUB_TOKEN in your environment before using --apply.")
    if not os.path.isfile(args.archive):
        parser.error(f"Archive not found: {args.archive}")

    try:
        archive_files = read_archive(args.archive, args.nested_zip, args.strip_prefix)
    except (OSError, zipfile.BadZipFile, ValueError) as exc:
        print(f"ERROR reading archive: {exc}", file=sys.stderr)
        return 2

    if not archive_files:
        print("No files found in the selected ZIP (check --nested-zip / --strip-prefix).")
        return 2
    if len(archive_files) > args.max_files:
        print(f"Safety stop: {len(archive_files)} files exceeds --max-files={args.max_files}.", file=sys.stderr)
        return 2

    if not args.apply:
        print(f"DRY RUN: found {len(archive_files)} files in {args.archive}")
        print("No repository changes made. Re-run with --apply to upload.")
        for path in list(sorted(archive_files))[:80]:
            print(f"  {path} ({len(archive_files[path])} bytes)")
        if len(archive_files) > 80:
            print(f"  ... and {len(archive_files) - 80} more")
        return 0

    repo = urllib.parse.quote(args.repo, safe="/")
    branch_q = urllib.parse.quote(args.branch, safe="")
    try:
        ref = api_request(token, "GET", f"/repos/{repo}/git/ref/heads/{branch_q}")
        parent_sha = ref["object"]["sha"]
        commit = api_request(token, "GET", f"/repos/{repo}/git/commits/{parent_sha}")
        base_tree_sha = commit["tree"]["sha"]
        current_tree = api_request(token, "GET", f"/repos/{repo}/git/trees/{base_tree_sha}?recursive=1")
        if current_tree.get("truncated"):
            raise GitHubAPIError("Repository tree response was truncated; refusing to risk overwriting files.")
        existing = {entry["path"] for entry in current_tree.get("tree", []) if entry.get("type") == "blob"}

        missing = {path: data for path, data in archive_files.items() if path not in existing}
        skipped = sorted(set(archive_files) - set(missing))
        print(f"Archive files: {len(archive_files)} | already present (will not overwrite): {len(skipped)} | new files: {len(missing)}")
        if skipped:
            print("Skipped existing paths (first 20):")
            for path in skipped[:20]:
                print(f"  {path}")
        if not missing:
            print("Nothing to upload; every archive path already exists on the target branch.")
            return 0

        tree_entries = []
        for index, (path, data) in enumerate(sorted(missing.items()), start=1):
            blob = api_request(token, "POST", f"/repos/{repo}/git/blobs", {
                "content": base64.b64encode(data).decode("ascii"),
                "encoding": "base64",
            })
            tree_entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
            print(f"[{index}/{len(missing)}] prepared {path}")
            time.sleep(0.05)

        new_tree = api_request(token, "POST", f"/repos/{repo}/git/trees", {
            "base_tree": base_tree_sha,
            "tree": tree_entries,
        })
        new_commit = api_request(token, "POST", f"/repos/{repo}/git/commits", {
            "message": f"Import {len(missing)} missing source files from ZIP",
            "tree": new_tree["sha"],
            "parents": [parent_sha],
        })
        api_request(token, "PATCH", f"/repos/{repo}/git/refs/heads/{branch_q}", {
            "sha": new_commit["sha"],
            "force": False,
        })
        print("\nSUCCESS")
        print(f"Branch: https://github.com/{args.repo}/tree/{urllib.parse.quote(args.branch, safe='/')}")
        print(f"Commit: https://github.com/{args.repo}/commit/{new_commit['sha']}")
        print(f"Uploaded {len(missing)} new files. Existing files were left unchanged.")
        print("Review the branch and open a pull request when ready.")
        return 0
    except (GitHubAPIError, KeyError, urllib.error.URLError) as exc:
        print(f"\nFAILED: {exc}", file=sys.stderr)
        print("The default branch is not force-pushed. Check token permissions and branch name.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Prepare a Session Android release.

Creates the release branch off master (or a chosen base), commits a version
bump, pushes the branch, and creates a GitHub *draft* release -- then stops.

You then do the rest yourself:
  - merge `dev` or cherry-pick the commits you want into the release branch
  - regenerate the draft's notes with scripts/update-release-notes.py
  - build/upload with scripts/build-and-release.py
  - sign, then publish the draft

The draft's target tracks the branch name, so any commits you add later land in
the tag that is created when the draft is published.

Usage:
    scripts/prepare-release.py 1.34.0
    scripts/prepare-release.py 1.34.0 --from dev
    scripts/prepare-release.py 1.34.0 --version-code 460
    scripts/prepare-release.py 1.34.0 --dry-run
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

REMOTE = "origin"
REPO = "session-foundation/session-android"   # explicit: repo has several remotes, gh can't auto-pick
GRADLE = "app/build.gradle.kts"
CODE_RE = re.compile(r'^(val canonicalVersionCode = )(\d+)[ \t]*$', re.M)
NAME_RE = re.compile(r'^(val canonicalVersionName = ")([^"]*)(")[ \t]*$', re.M)

PLACEHOLDER_NOTES = ("_Release notes are generated with "
                     "`scripts/update-release-notes.py` once the release branch is finalized._")


def run(cmd):
    print(f"  $ {' '.join(cmd)}")
    subprocess.run(cmd, check=True)


def ok(cmd):
    """Return True if the command exits 0 (quietly)."""
    return subprocess.run(cmd, capture_output=True).returncode == 0


def capture(cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip()


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("version", help="release version, e.g. 1.34.0")
    p.add_argument("--from", dest="base", default="master",
                   help="branch to fork the release from (default: master)")
    p.add_argument("--version-code", type=int, default=None,
                   help="override the versionCode (default: current + 1)")
    p.add_argument("--yes", action="store_true", help="skip the confirmation prompt")
    p.add_argument("--dry-run", action="store_true",
                   help="show what would happen; make no changes")
    args = p.parse_args()

    version = args.version
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        sys.exit(f"Version '{version}' is not in X.Y.Z form.")

    if shutil.which("gh") is None:
        sys.exit("`gh` (GitHub CLI) not found; install it from https://cli.github.com/")

    # Run from the repo root (parent of this script's directory).
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)

    branch = f"release/{version}"

    # Only uncommitted changes to *tracked* files should block; ignore untracked
    # files (e.g. release-creds.zip, build outputs) which don't affect branching.
    if capture(["git", "status", "--porcelain", "--untracked-files=no"]):
        sys.exit("You have uncommitted changes to tracked files; commit or stash them first.")

    print(f"Fetching {REMOTE} ...")
    run(["git", "fetch", REMOTE, "--tags", "--quiet"])

    if ok(["git", "rev-parse", "--verify", "--quiet", branch]) or \
       ok(["git", "ls-remote", "--exit-code", "--heads", REMOTE, branch]):
        sys.exit(f"Branch {branch} already exists (locally or on {REMOTE}).")

    base_ref = f"{REMOTE}/{args.base}"
    if not ok(["git", "rev-parse", "--verify", "--quiet", base_ref]):
        sys.exit(f"Base ref {base_ref} not found (did you mean a different --from?).")

    # read the version from the base ref (NOT the current working tree) so the plan and the
    # bump reflect exactly what we fork from, regardless of which branch is checked out
    base_gradle = capture(["git", "show", f"{base_ref}:{GRADLE}"])
    m_code, m_name = CODE_RE.search(base_gradle), NAME_RE.search(base_gradle)
    if not m_code or not m_name:
        sys.exit(f"Could not find canonicalVersionCode / canonicalVersionName in {base_ref}:{GRADLE}.")
    cur_code, cur_name = int(m_code.group(2)), m_name.group(2)
    new_code = args.version_code if args.version_code is not None else cur_code + 1
    if new_code <= cur_code:
        print(f"WARNING: new versionCode {new_code} is not greater than current {cur_code}.",
              file=sys.stderr)

    print()
    print(f"  Release version :  {version}   (was {cur_name})")
    print(f"  versionCode     :  {new_code}   (was {cur_code})")
    print(f"  Branch          :  {branch}   forked from {base_ref}")
    print(f"  GitHub draft    :  '{version}' (draft, target {branch}, placeholder notes)")
    print()

    if args.dry_run:
        print("Dry run -- no changes made.")
        return
    if not args.yes and input("Proceed? [y/N] ").strip().lower() not in ("y", "yes"):
        sys.exit("Aborted.")

    run(["git", "switch", "-c", branch, base_ref])

    # now on the fresh branch off base_ref: bump the file as it exists there
    text = open(GRADLE).read()
    new_text = CODE_RE.sub(rf"\g<1>{new_code}", text, count=1)
    new_text = NAME_RE.sub(rf"\g<1>{version}\g<3>", new_text, count=1)
    if new_text == text:
        sys.exit(f"Version bump produced no change in {GRADLE}; aborting.")
    with open(GRADLE, "w") as f:
        f.write(new_text)

    run(["git", "add", GRADLE])
    run(["git", "commit", "-m", f"Bump version to {version} ({new_code})"])
    run(["git", "push", "-u", REMOTE, branch])
    run(["gh", "release", "create", version, "-R", REPO, "--draft", "--target", branch,
         "--title", version, "--notes", PLACEHOLDER_NOTES])

    print(f"""
Done. {branch} is pushed, version bumped to {version} ({new_code}), and a draft
GitHub release '{version}' exists.

Next steps (yours):
  1. Merge dev or cherry-pick into {branch} (you're already on it), then push:
         git merge dev              # full release
         git cherry-pick <sha>...   # patch release
         git push
  2. Regenerate the draft's notes from the finalized branch:
         scripts/update-release-notes.py {version}
  3. Build + upload artifacts:
         scripts/build-and-release.py
  4. Sign the release, then publish the draft:
         gh release edit {version} --draft=false
""")


if __name__ == "__main__":
    main()

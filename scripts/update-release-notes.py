#!/usr/bin/env python3
"""Regenerate a release's changelog notes and write them onto its GitHub draft.

Uses GitHub's own release-notes generator (the "What's Changed" list built from
merged PR titles) for the release branch as it currently stands, and overwrites
the draft release's body with the result. Run it after you have merged /
cherry-picked everything into the release branch, and re-run it whenever the
branch changes.

IMPORTANT: this overwrites the entire release body. Run it BEFORE the PGP
signing step that appends the signature block, or that block will be clobbered.

Usage:
    scripts/update-release-notes.py 1.34.0
    scripts/update-release-notes.py 1.34.0 --target release/1.34.0
    scripts/update-release-notes.py 1.34.0 --dry-run
    scripts/update-release-notes.py 1.34.0 --force      # allow editing a published (non-draft) release
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

REPO = "session-foundation/session-android"   # explicit: repo has several remotes, gh can't auto-pick


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("version", help="release version / tag, e.g. 1.34.0")
    p.add_argument("--target", default=None,
                   help="branch/commitish to generate notes for (default: release/<version>)")
    p.add_argument("--dry-run", action="store_true",
                   help="print the generated notes without writing them")
    p.add_argument("--force", action="store_true",
                   help="proceed even if the release is already published (not a draft)")
    args = p.parse_args()

    if shutil.which("gh") is None:
        sys.exit("`gh` (GitHub CLI) not found; install it from https://cli.github.com/")

    # Run from the repo root so gh resolves the correct repo.
    os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

    version = args.version
    target = args.target or f"release/{version}"

    # The release must already exist (created by prepare-release.py).
    view = subprocess.run(["gh", "release", "view", version, "-R", REPO, "--json", "isDraft"],
                          capture_output=True, text=True)
    if view.returncode != 0:
        sys.exit(f"No release '{version}' found. Create it first with prepare-release.py.")
    if not json.loads(view.stdout)["isDraft"] and not args.force:
        sys.exit(f"Release '{version}' is already published. Re-run with --force to overwrite "
                 f"its notes (this will also wipe any appended signature block).")

    # Ask GitHub to build the notes for the branch as it stands now.
    gen = subprocess.run(
        ["gh", "api", "--method", "POST", f"repos/{REPO}/releases/generate-notes",
         "-f", f"tag_name={version}", "-f", f"target_commitish={target}", "--jq", ".body"],
        capture_output=True, text=True)
    if gen.returncode != 0:
        sys.exit(f"Failed to generate notes:\n{gen.stderr.strip()}")
    body = gen.stdout.strip()
    if not body:
        sys.exit("GitHub returned empty notes -- check that the target branch exists and has "
                 "commits beyond the previous tag.")

    if args.dry_run:
        print(f"--- generated notes for {version} (target {target}) ---\n")
        print(body)
        return

    # Write via a temp file to avoid any argv quoting issues with the body.
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as f:
        f.write(body)
        notes_file = f.name
    try:
        subprocess.run(["gh", "release", "edit", version, "-R", REPO, "--notes-file", notes_file], check=True)
    finally:
        os.remove(notes_file)

    print(f"Updated the notes on draft release '{version}' (generated from {target}).")
    print("Reminder: run this before the PGP signing step, since it overwrites the whole body.")


if __name__ == "__main__":
    main()

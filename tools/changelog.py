#!/usr/bin/env python3
"""Build a Markdown changelog from the commits since the previous tag.

Only user-facing changes (feat / fix / perf) are listed. With --translate a
Simplified Chinese copy is appended below the English changelog through the
DeepSeek chat API; the English text always stays first. The translation is best
effort: when the API is unavailable the English changelog is written and a
warning goes to stderr, so a release is never blocked by the translator.

Usage:
    python3 tools/changelog.py --tag v1.0.2 --output NOTES.md
    DEEPSEEK_API_KEY=sk-... python3 tools/changelog.py --tag v1.0.2 --output NOTES.md --translate

Environment:
    DEEPSEEK_API_KEY   API key used by --translate (nothing is translated without it)
    DEEPSEEK_API_BASE  defaults to https://api.deepseek.com
    DEEPSEEK_MODEL     defaults to deepseek-chat
    GITHUB_REPOSITORY  owner/repo, used for commit and compare links
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

# Only user-facing changes reach the release notes; build/ci/docs/refactor/... are
# dropped. Order matters: this is the order sections appear in the release notes.
TYPE_TITLES = {
    "feat": "Features",
    "fix": "Fixes",
    "perf": "Performance",
}

SUBJECT_RE = re.compile(r"^(?P<type>[A-Za-z]+)(?:\([^)]*\))?!?:\s*(?P<subject>.+)$")
CHUNK_LIMIT = 6000

SYSTEM_PROMPT = (
    "You are a meticulous translator for software release notes. "
    "Translate the user's Markdown changelog from English into Simplified Chinese. "
    "Rules: keep the Markdown structure, headings, list nesting and links intact; "
    "do not translate code identifiers, file paths, URLs, commit hashes, version "
    "numbers, or product names such as WhaleChat, DeepSeek, Android and WebView; "
    "keep the tone plain and factual. Output only the translated Markdown, with no "
    "code fence and no commentary."
)


def git(*args):
    result = subprocess.run(
        ["git", *args], check=True, capture_output=True, text=True, encoding="utf-8"
    )
    return result.stdout


def previous_tag(tag):
    try:
        return git("describe", "--tags", "--abbrev=0", "--match", "v*", f"{tag}^").strip()
    except subprocess.CalledProcessError:
        return ""


def read_commits(rev_range):
    sep_field = "\x1f"
    sep_record = "\x1e"
    fmt = sep_field.join(["%H", "%s", "%b"]) + sep_record
    out = git("log", "--no-merges", f"--format={fmt}", rev_range)
    for record in out.split(sep_record):
        record = record.strip("\n")
        if not record:
            continue
        parts = record.split(sep_field)
        if len(parts) < 2:
            continue
        sha, subject = parts[0].strip(), parts[1].strip()
        body = parts[2].strip() if len(parts) > 2 else ""
        yield sha, subject, body


def classify(subject):
    match = SUBJECT_RE.match(subject)
    if match and match.group("type").lower() in TYPE_TITLES:
        return match.group("type").lower(), match.group("subject").strip()
    return None, subject


def repo_slug():
    return os.environ.get("GITHUB_REPOSITORY", "").strip()


def build_changelog(tag, commits):
    version = tag[1:] if tag.startswith("v") else tag
    slug = repo_slug()
    sections = {key: [] for key in TYPE_TITLES}

    for sha, subject, _body in commits:
        kind, text = classify(subject)
        if not kind:
            continue
        if slug:
            entry = f"- {text} ([{sha[:7]}](https://github.com/{slug}/commit/{sha}))"
        else:
            entry = f"- {text} ({sha[:7]})"
        sections[kind].append(entry)

    lines = [f"# WhaleChat {version}", ""]
    for kind, title in TYPE_TITLES.items():
        items = sections[kind]
        if items:
            lines.extend([f"## {title}", ""])
            lines.extend(items)
            lines.append("")
    if not any(sections.values()):
        lines.extend(["_No user-facing changes._", ""])
    return "\n".join(lines).rstrip() + "\n"


def compare_link(tag, from_ref):
    slug = repo_slug()
    if not slug or not from_ref:
        return ""
    return f"**Full Changelog**: https://github.com/{slug}/compare/{from_ref}...{tag}"


def chunk_text(text, limit=CHUNK_LIMIT):
    chunks = []
    current = ""
    for line in text.splitlines(keepends=True):
        while len(line) > limit:
            if current:
                chunks.append(current)
                current = ""
            chunks.append(line[:limit])
            line = line[limit:]
        if current and len(current) + len(line) > limit:
            chunks.append(current)
            current = ""
        current += line
    if current:
        chunks.append(current)
    return chunks


def translate_chunk(text, api_key, api_base, model):
    payload = {
        "model": model,
        "temperature": 0.2,
        "stream": False,
        "max_tokens": 8000,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": text},
        ],
    }
    request = urllib.request.Request(
        api_base.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        data = json.loads(response.read().decode("utf-8"))
    content = data["choices"][0]["message"]["content"].strip()
    if content.startswith("```"):
        content = re.sub(r"^```[A-Za-z]*\n", "", content)
        content = re.sub(r"\n```$", "", content).strip()
    if not content:
        raise ValueError("empty translation")
    return content


def translate(text, api_key, api_base, model):
    pieces = chunk_text(text)
    translated = []
    for index, piece in enumerate(pieces, 1):
        last_error = None
        for attempt in range(3):
            try:
                translated.append(translate_chunk(piece, api_key, api_base, model))
                last_error = None
                break
            except (urllib.error.URLError, urllib.error.HTTPError, ValueError, KeyError) as error:
                last_error = error
                if attempt < 2:
                    time.sleep(2**attempt)
        if last_error is not None:
            raise last_error
        if len(pieces) > 1:
            print(f"translated chunk {index}/{len(pieces)}", file=sys.stderr)
    return "\n".join(translated).rstrip() + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tag", required=True, help="tag being released, e.g. v1.0.2")
    parser.add_argument("--output", required=True, help="file to write the changelog to")
    parser.add_argument(
        "--from-ref",
        default="",
        help="explicit start of the commit range (defaults to the previous v* tag)",
    )
    parser.add_argument(
        "--translate",
        action="store_true",
        help="append a Simplified Chinese translation below the English changelog",
    )
    parser.add_argument("--api-base", default=os.environ.get("DEEPSEEK_API_BASE", "https://api.deepseek.com"))
    parser.add_argument("--model", default=os.environ.get("DEEPSEEK_MODEL", "deepseek-chat"))
    args = parser.parse_args()

    from_ref = args.from_ref or previous_tag(args.tag)
    rev_range = f"{from_ref}..{args.tag}" if from_ref else args.tag
    commits = list(read_commits(rev_range))
    english = build_changelog(args.tag, commits)
    changelog = english

    if args.translate:
        api_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
        if not api_key:
            print("warning: DEEPSEEK_API_KEY is not set, publishing the changelog in English only", file=sys.stderr)
        else:
            try:
                chinese = translate(english, api_key, args.api_base, args.model)
                # English first, translated copy below it.
                changelog = english.rstrip("\n") + "\n\n---\n\n" + chinese
            except Exception as error:  # noqa: BLE001 - a failed translation must not break the release
                print(f"warning: translation failed ({error}), publishing the changelog in English only", file=sys.stderr)

    link = compare_link(args.tag, from_ref)
    if link:
        changelog = changelog.rstrip() + "\n\n" + link + "\n"

    with open(args.output, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(changelog)
    print(f"wrote {args.output} ({len(commits)} commits since {from_ref or 'the beginning'})", file=sys.stderr)


if __name__ == "__main__":
    main()

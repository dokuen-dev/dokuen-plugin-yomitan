#!/usr/bin/env python3
"""Build the interactive test-plan checklist (HTML) from the Markdown test plan.

The Markdown plan is the single source of truth; the HTML is a generated file.

  python scripts/build_test_plan_app.py           regenerate the HTML
  python scripts/build_test_plan_app.py --check   exit 1 if the committed HTML is stale (for CI / pre-commit)

Only Part 1 (Procedure) is used: the text between '# Part 1' and '# Part 2', i.e. the
preamble and every '## Phase N: Title' section with its '- [ ] **N.M** step' items (a step may have nested
sub-bullets, indented under it).
The parser is strict: anything it does not recognise is an error, so a change to the
plan's format cannot silently drop text from the app.
"""
import argparse
import difflib
import hashlib
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
# EDIT these two to match where the plan and its checklist live in the repo.
DEFAULT_SOURCE = HERE.parent / "docs" / "anki-export-manual-test-plan.md"
DEFAULT_OUTPUT = HERE.parent / "docs" / "anki-test-plan-checklist.html"
TEMPLATE = HERE / "test_plan_app.template.html"

PHASE_RE = re.compile(r"^## Phase (\d+): (.+?)\s*$")
ITEM_RE = re.compile(r"^- \[ \] \*\*(\d+)\.(\d+)\*\* (.*\S)\s*$")
SUB_RE = re.compile(r"^(\s+)- (.*\S)\s*$")  # sub-bullet under a step; nesting follows indentation


def fail(msg):
    sys.exit(f"error: {msg}")


def check_markup(where, t):
    """The page renders only `code` and **bold**; refuse anything else rather than show it raw."""
    if t.count("`") % 2:
        fail(f"{where}: unbalanced backtick: {t[:80]!r}")
    plain = re.sub(r"\*\*[^*]+\*\*", "", re.sub(r"`[^`]*`", "", t))
    if "*" in plain or "](" in plain or plain.lstrip().startswith("|"):
        fail(f"{where}: markdown the app cannot render (only `code` and **bold**): {t[:80]!r}")


def part1_lines(md):
    lines = md.splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith("# Part 1")), None)
    if start is None:
        fail("no '# Part 1' heading found")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("# Part 2")), len(lines))
    body = lines[start + 1:end]
    while body and body[-1].strip() in ("", "---"):
        body.pop()
    return body


def parse_blocks(lines):
    """Group lines into paragraphs and bullet lists; wrapped lines are joined with a space."""
    items = []
    for l in lines:
        if not l.strip():
            items.append(None)
        elif l.startswith("- "):
            items.append(["li", l[2:].strip()])
        elif re.match(r"\s+[-*+] ", l):
            fail(f"nested bullets are only supported under checklist steps: {l.strip()[:60]!r}")
        elif items and items[-1] and l[:1] in " \t" and items[-1][0] == "li":
            items[-1][1] += " " + l.strip()
        elif items and items[-1] and items[-1][0] == "p":
            items[-1][1] += " " + l.strip()
        else:
            items.append(["p", l.strip()])
    out = []
    for it in items:
        if it is None:
            continue
        if it[0] == "li":
            if out and out[-1]["t"] == "ul":
                out[-1]["x"].append(it[1])
            else:
                out.append({"t": "ul", "x": [it[1]]})
        else:
            out.append({"t": "p", "x": it[1]})
    return out


def note_text(lines):
    parts = []
    for b in parse_blocks(lines):
        parts.append(b["x"] if b["t"] == "p" else "\n".join("- " + i for i in b["x"]))
    return "\n\n".join(parts)


def parse(md, source_name):
    lines = part1_lines(md)
    first = next((i for i, l in enumerate(lines) if PHASE_RE.match(l)), None)
    if first is None:
        fail("no '## Phase N: Title' headings found in Part 1")
    preamble = parse_blocks(lines[:first])

    phases, cur, stack = [], None, []
    for off, l in enumerate(lines[first:], start=1):
        where = f"Part 1 line {first + off}"
        m = PHASE_RE.match(l)
        if m:
            n = int(m.group(1))
            if n != len(phases) + 1:
                fail(f"{where}: expected Phase {len(phases) + 1}, found Phase {n}")
            cur = {"n": n, "title": m.group(2), "intro": [], "steps": []}
            phases.append(cur)
            stack = []
        elif l.startswith("## "):
            fail(f"{where}: unrecognised heading: {l!r}")
        elif l.startswith("- ["):
            m = ITEM_RE.match(l)
            if not m:
                fail(f"{where}: checklist item not in the form '- [ ] **N.M** text': {l!r}")
            ph, idx = int(m.group(1)), int(m.group(2))
            if ph != cur["n"] or idx != len(cur["steps"]) + 1:
                fail(f"{where}: expected step {cur['n']}.{len(cur['steps']) + 1}, found {ph}.{idx}")
            cur["steps"].append([f"{ph}.{idx}", m.group(3)])
            stack = []
        elif not l.strip():
            continue
        elif not cur["steps"]:
            cur["intro"].append(l)
        elif SUB_RE.match(l):
            m = SUB_RE.match(l)
            ind = len(m.group(1).expandtabs(4))
            step = cur["steps"][-1]
            if len(step) == 2:
                step.append([])
            while stack and stack[-1][0] >= ind:
                stack.pop()
            node = {"x": m.group(2)}
            (stack[-1][1].setdefault("c", []) if stack else step[2]).append(node)
            stack.append((ind, node))
        elif l[:1] in " \t":  # wrapped text of the step or of its last sub-bullet
            if stack:
                stack[-1][1]["x"] += " " + l.strip()
            else:
                cur["steps"][-1][1] += " " + l.strip()
        else:
            fail(f"{where}: unexpected text between steps: {l!r}")

    for p in phases:
        if not p["steps"]:
            fail(f"Phase {p['n']} has no steps")
    def texts():
        for b in preamble:
            for t in ([b["x"]] if b["t"] == "p" else b["x"]):
                yield "preamble", t
        for p in phases:
            for t in p["intro"]:
                yield f"Phase {p['n']} intro", t
            for st in p["steps"]:
                yield f"step {st[0]}", st[1]
                todo = list(st[2]) if len(st) > 2 else []
                while todo:
                    n = todo.pop()
                    yield f"step {st[0]}", n["x"]
                    todo.extend(n.get("c", []))
    for where, t in texts():
        check_markup(where, t)
    return {
        "source": source_name,
        "hash": hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()[:12],
        "preamble": preamble,
        "phases": [{"n": p["n"], "title": p["title"], "note": note_text(p["intro"]), "steps": p["steps"]}
                   for p in phases],
    }


def emit(plan):
    j = lambda v: json.dumps(v, ensure_ascii=False)
    out = ["{",
           f'"source":{j(plan["source"])},',
           f'"hash":{j(plan["hash"])},',
           '"preamble":[', ",\n".join(j(b) for b in plan["preamble"]), "],",
           '"phases":[']
    for i, p in enumerate(plan["phases"]):
        out.append(f'{{"n":{p["n"]},"title":{j(p["title"])},"note":{j(p["note"])},"steps":[')
        out.append(",\n".join(j(s) for s in p["steps"]))
        out.append("]}" + ("," if i < len(plan["phases"]) - 1 else ""))
    out += ["]", "}"]
    return "\n".join(out).replace("</", "<\\/")  # keep the JSON safe inside <script>


def build(source, template):
    plan = parse(source.read_text(encoding="utf-8"), source.name)
    tpl = template.read_text(encoding="utf-8")
    if tpl.count("/*__PLAN__*/") != 1:
        fail("template must contain exactly one /*__PLAN__*/ placeholder")
    n_steps = sum(len(p["steps"]) for p in plan["phases"])
    return tpl.replace("/*__PLAN__*/", emit(plan)), f"{len(plan['phases'])} phases, {n_steps} steps"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="Markdown test plan")
    ap.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="generated HTML")
    ap.add_argument("--template", type=Path, default=TEMPLATE)
    ap.add_argument("--check", action="store_true", help="do not write; exit 1 if the output is stale")
    args = ap.parse_args()

    html, summary = build(args.source, args.template)
    current = args.output.read_bytes().decode("utf-8") if args.output.exists() else None
    if args.check:
        if current == html:
            print(f"up to date ({summary})")
            return 0
        print(f"STALE: {args.output} does not match {args.source.name} + template.", file=sys.stderr)
        if current is not None:
            diff = list(difflib.unified_diff(current.splitlines(), html.splitlines(),
                                             "committed", "generated", lineterm="", n=0))
            print("\n".join(diff[:40]), file=sys.stderr)
            if len(diff) > 40:
                print(f"... ({len(diff) - 40} more diff lines)", file=sys.stderr)
        print(f"Run: python {Path(__file__).name} and commit the result.", file=sys.stderr)
        return 1
    if current == html:
        print(f"{args.output} already up to date ({summary})")
    else:
        args.output.write_bytes(html.encode("utf-8"))
        print(f"wrote {args.output} ({summary})")
    return 0


if __name__ == "__main__":
    sys.exit(main())

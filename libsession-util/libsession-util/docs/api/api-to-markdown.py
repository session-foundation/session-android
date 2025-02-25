#!/usr/bin/env python3

import sys
import os
import shutil
import re
import fileinput
from enum import Enum, auto
import json
import requests
import argparse

parser = argparse.ArgumentParser()
parser.add_argument(
    "-L",
    "--markdown-level",
    type=int,
    choices=[1, 2, 3, 4],
    default=2,
    help="Specify a heading level for the top-level endpoints; the default is 2, which means "
    "endpoints start in a `## name` section. For example, 3 would start endpoints with `### name` "
    "instead.",
)
parser.add_argument("--out", "-o", metavar='DIR', default="api", help="Output directory for generated endpoints")
parser.add_argument("--disable-no-args", action='store_true', help="disable NO_ARGS enforcement of `Inputs: none`")
parser.add_argument("--dev", action='store_true', help="generate dev mode docs, which include endpoints marked 'Dev-RPC'")
parser.add_argument("--no-sort", "-S", action='store_true', help="disable sorting endpoints by name (use file order)")
parser.add_argument("--no-group", "-G", action='store_true', help="disable grouping endpoints by category")
parser.add_argument("--no-emdash", "-M", action='store_true', help="disable converting ' -- ' to ' — ' (em-dashes)")
parser.add_argument("--rpc", metavar='URL', default="http://public-na.optf.ngo:22023", help="URL to a running oxend RPC node for live example fetching")
parser.add_argument("filename", nargs="+")
args = parser.parse_args()

for f in args.filename:
    if not os.path.exists(f):
        parser.error(f"{f} does not exist!")


# We parse the file looking for `///` comment blocks beginning with "API: <cat>/<name>".
#
# <name> is the API endpoint name to use in the documentation (alternative names can be specified
# using "Old names:"; see below).
#
# <cat> is the category for grouping endpoints together.
#
# Following comment lines are then a Markdown long description, until we find one or more of:
#
# "Inputs: none."
# "Outputs: none."
# "Member variable."
# "Inputs:" followed by markdown (typically an unordered list) until the next match from this list.
# "Outputs:" followed by markdown
# "Example input:" followed by a code block (i.e. containing json)
# "Example output:" followed by a code block (i.e. json output)
# "Example-JSON-Fetch" goes and fetches the endpoint (live) with the previous example input as the
#     "params" value (or no params if "Inputs: none").
# "Old names: a, b, c"
#
# subject to the following rules:
# - each section must have exactly one Input; if the type inherits NO_ARGS then it *must* be an
#   "Inputs: none".
# - each section must have exactly one Output
# - "Example input:" section must be immediately followed by an "Example output"
# - "Example output:" sections are permitted without a preceding example input only if the endpoint
#   takes no inputs.
# - 0 or more example pairs are permitted.
# - Old names is permitted only once, if it occurs at all; the given names will be indicated as
#   deprecated, old names for the endpoint.
#
# Immediately following the command we expect to find a not-only-comment line (e.g. `struct
# <whatever>`) and apply some checks to this:
# - if the line contains the word `NO_ARGS` then we double-check that "Inputs: none" was also given
#   and error if a more complex Inputs: section was written.


hdr = '#' * args.markdown_level
MD_INPUT_HEADER = f"{hdr}# Parameters"
MD_OUTPUT_HEADER = f"{hdr}# Returns"

MD_EXAMPLES_HEADER = f"{hdr}# Examples"
MD_DECL_HEADER = f"{hdr}# Declaration"
MD_EXAMPLE_IN_HDR = f"{hdr}## Input"
MD_EXAMPLE_OUT_HDR = f"{hdr}## Output"

MD_EX_SINGLE_IN_HDR = f"{hdr}# Example Input"
MD_EX_SINGLE_OUT_HDR = f"{hdr}# Example Output"

MD_NO_INPUT = "This endpoint takes no inputs."

RPC_COMMENT = re.compile(r"^\s*/// ?")
RPC_START = re.compile(r"^API:\s*([\w/:&\\\[\]=,\(\)\<\>]+)(.*)$")
DEV_RPC_START = re.compile(r"^Dev-API:\s*([\w/:]+)(.*)$")
IN_NONE = re.compile(r"^Inputs?: *[nN]one\.?$")
IN_SOME = re.compile(r"^Inputs?:\s*$")
MEMBER_VAR = re.compile(r"^Member +[vV]ar(?:iable)?\.?$")
DECL_SOME = re.compile(r"^Declaration?:\s*$")
OUT_SOME = re.compile(r"^Outputs?:\s*$")
EXAMPLE_IN = re.compile(r"^Example [iI]nputs?:\s*$")
EXAMPLE_OUT = re.compile(r"^Example [oO]utputs?:\s*$")
EXAMPLE_JSON_FETCH = re.compile(r"^Example-JSON-Fetch\s*$")
OLD_NAMES = re.compile(r"[Oo]ld [nN]ames?:")
PLAIN_NAME = re.compile(r"\w+")
NO_ARGS = re.compile(r"\bNO_ARGS\b")

input = fileinput.input(args.filename)
rpc_name = None


def error(msg):
    print(
        f"\x1b[31;1mERROR\x1b[0m[{input.filename()}:{input.filelineno()}] "
        f"while parsing endpoint {rpc_name}:",
        file=sys.stderr,
    )
    if msg and isinstance(msg, list):
        for m in msg:
            print(f"    - {m}", file=sys.stderr)
    else:
        print(f"    {msg}", file=sys.stderr)
    sys.exit(1)


class Parsing(Enum):
    DESC = auto()
    INPUTS = auto()
    DECL = auto()
    OUTPUTS = auto()
    EX_IN = auto()
    EX_OUT = auto()
    NONE = auto()


cur_file = None

endpoints = {}

while True:
    line = input.readline()
    if not line:
        break

    cur_file = input.filename()

    line, removed_comment = re.subn(RPC_COMMENT, "", line, count=1)
    if not removed_comment:
        continue

    m = re.search(RPC_START, line)
    if not m and args.dev:
        m = re.search(DEV_RPC_START, line)
    if not m:
        continue
    if m and m[2]:
        error(f"found trailing garbage after 'API: {m[1]}': {m[2]}")
    if m[1].count('/') != 1:
        error(f"Found invalid API name: expected 'cat/name', not '{m[1]}'")

    cat, rpc_name = m[1].split('/')
    if args.no_group:
        cat = ''
    description, decl, inputs, outputs = "", "", "", ""
    done_desc = False
    no_inputs = False
    member_var = False
    examples = []
    cur_ex_in = None
    old_names = []

    mode = Parsing.DESC

    while True:
        line = input.readline()
        line, removed_comment = re.subn(RPC_COMMENT, "", line, count=1)
        if not removed_comment:
            if not decl:
                decl_lines = []
                decl_prefix = 1000
                while line and line.lstrip(' ')[0:2] not in ('//', '/*'):
                    decl_lines.append(line.rstrip())
                    indent = len(line) - len(line.lstrip(' '))
                    if indent < decl_prefix:
                        decl_prefix = indent
                    if ';' in line or '{' in line:
                        break

                    line = input.readline()

                if decl_lines:
                    if decl_prefix > 0:
                        decl_lines = [x[decl_prefix:] for x in decl_lines]
                    decl = '```cpp\n' + '\n'.join(decl_lines) + '\n```'

            break

        if re.search(IN_NONE, line):
            if inputs:
                error("found multiple Inputs:")
            inputs, no_inputs, mode = MD_NO_INPUT, True, Parsing.NONE

        elif re.search(MEMBER_VAR, line):
            member_var, no_inputs, mode = True, True, Parsing.DESC

        elif re.search(DECL_SOME, line):
            if inputs:
                error("found multiple Syntax:")
            mode = Parsing.DECL

        elif re.search(IN_SOME, line):
            if inputs:
                error("found multiple Inputs:")
            mode = Parsing.INPUTS

        elif re.search(OUT_SOME, line):
            if outputs:
                error("found multiple Outputs:")
            mode = Parsing.OUTPUTS

        elif re.search(EXAMPLE_IN, line):
            if cur_ex_in is not None:
                error("found multiple input examples without paired output examples")
            cur_ex_in = ""
            mode = Parsing.EX_IN

        elif re.search(EXAMPLE_OUT, line):
            if not cur_ex_in and not no_inputs:
                error(
                    "found output example without preceding input example (or 'Inputs: none.')"
                )
            examples.append([cur_ex_in, ""])
            cur_ex_in = None
            mode = Parsing.EX_OUT

        elif re.search(EXAMPLE_JSON_FETCH, line):
            if not cur_ex_in and not no_inputs:
                error(
                    "found output example fetch instruction without preceding input (or 'Inputs: none.')"
                )
            params = None
            if cur_ex_in:
                params = cur_ex_in.strip()
                if not params.startswith("```json\n"):
                    error("current example input is not tagged as json for Example-JSON-Fetch")
                params = params[8:]
                if not params.endswith("\n```"):
                    error("current example input doesn't look right (expected trailing ```)")
                params = params[:-4]
                try:
                    params = json.loads(params)
                except Exception as e:
                    error("failed to parse json example input as json")

            result = requests.post(args.rpc + "/json_rpc", json={"jsonrpc": "2.0", "id": "0", "method": rpc_name, "params": params}).json()
            if 'error' in result:
                error(f"JSON fetched example returned an error: {result['error']}")
            elif 'result' not in result:
                error(f"JSON fetched example doesn't contain a \"result\" key: {result}")
            ex_out = json.dumps(result["result"], indent=2, sort_keys=True)

            examples.append([cur_ex_in, f"\n```json\n{ex_out}\n```\n"])
            cur_ex_in = None
            mode = Parsing.NONE

        elif re.search(OLD_NAMES, line):
            old_names = [x.strip() for x in line.split(':', 1)[1].split(',')]
            if not old_names or not all(re.fullmatch(PLAIN_NAME, n) for n in old_names):
                error(f"found unparseable old names line: {line}")

        elif mode == Parsing.NONE:
            if line and not line.isspace():
                error(f"Found unexpected content while looking for a tag: '{line}'")

        elif mode == Parsing.DESC:
            description += line

        elif mode == Parsing.DECL:
            decl += line

        elif mode == Parsing.INPUTS:
            inputs += line

        elif mode == Parsing.OUTPUTS:
            outputs += line

        elif mode == Parsing.EX_IN:
            cur_ex_in += line

        elif mode == Parsing.EX_OUT:
            examples[-1][1] += line

    problems = []
    # We hit the end of the commented section
    if not description or inputs.isspace():
        problems.append("endpoint has no description")
    if (not inputs or inputs.isspace()) and not member_var:
        problems.append(
            "endpoint has no inputs description; perhaps you need to add 'Inputs: none.'?"
        )
#    if not outputs or outputs.isspace():
#        problems.append("endpoint has no outputs description")
    if cur_ex_in is not None:
        problems.append(
            "endpoint has a trailing example input without a following example output"
        )
    if not no_inputs and any(not x[0] or x[0].isspace() for x in examples):
        problems.append("found one or more blank input examples")
    if any(not x[1] or x[1].isspace() for x in examples):
        problems.append("found one or more blank output examples")

    if old_names:
        s = 's' if len(old_names) > 1 else ''
        description += f"\n\n> _For backwards compatibility this endpoint is also accessible via the following deprecated endpoint name{s}:_"
        for n in old_names:
            description += f"\n> - _`{n}`_"

    if not args.disable_no_args:
        if re.search(NO_ARGS, line) and not no_inputs:
            problems.append("found NO_ARGS, but 'Inputs: none' was specified in description")

    if problems:
        error(problems)

    md = f"""
{hdr} `{rpc_name}`

{description}

{MD_DECL_HEADER}

{decl}
"""
    if not member_var:
        md = md + f"""
{MD_INPUT_HEADER}

{inputs}

{MD_OUTPUT_HEADER}

{outputs}
"""

    if examples:
        if len(examples) > 1:
            md += f"\n\n{MD_EXAMPLES_HEADER}\n\n"
            for ex in examples:
                if ex[0] is not None:
                    md += f"""
{MD_EXAMPLE_IN_HDR}

{ex[0]}
"""
                md += f"""
{MD_EXAMPLE_OUT_HDR}

{ex[1]}
"""

        else:
            if examples[0][0] is not None:
                md += f"\n\n{MD_EX_SINGLE_IN_HDR}\n\n{examples[0][0]}"
            md += f"\n\n{MD_EX_SINGLE_OUT_HDR}\n\n{examples[0][1]}"

    if not args.no_emdash:
        md = md.replace(" -- ", " — ")

    if cat in endpoints:
        endpoints[cat].append((rpc_name, md))
    else:
        endpoints[cat] = [(rpc_name, md)]

if not endpoints:
    error(f"Found no parseable endpoint descriptions in {cur_file}")

if not args.no_sort:
    for v in endpoints.values():
        v.sort(key=lambda x: x[0])

os.makedirs(args.out, exist_ok=True)

static_path = os.path.dirname(os.path.realpath(__file__)) + '/static'

for f in ('index.md', 'sidebar.md'):
    shutil.copy(f"{static_path}/{f}", f"{args.out}/{f}")
    print(f"Copied static/{f} => {args.out}/{f}")

preamble_prefix = static_path + '/preamble-'

for cat, eps in endpoints.items():
    out = f"{args.out}/{cat}.md"
    with open(out, "w") as f:
        preamble = f"{preamble_prefix}{cat}.md"
        if os.path.isfile(preamble):
            with open(preamble, "r") as fp:
                for line in fp:
                    f.write(line)
            f.write("\n\n")
        else:
            print(f"Warning: {preamble} doesn't exist, writing generic preamble for {cat}", file=sys.stderr)
            f.write(f"# {cat} endpoints\n\n")

        for _, md in eps:
            f.write(md)
    print(f"Wrote {out}")

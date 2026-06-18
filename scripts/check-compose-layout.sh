#!/usr/bin/env bash
# Guard against the Compose layout trap:
#   "a non-weighted fillMax child starves its weight(1f) sibling".
#
# THE TRAP (the PR #347 bug):
#   In a vertical  Column { Box(Modifier.weight(1f)) { … }; <bar> }  Compose measures the
#   NON-weighted child (<bar>) FIRST and hands it the FULL available height as its max. If
#   that child applies Modifier.fillMaxHeight() / .fillMaxSize(), it expands to the whole
#   height, swallows the column, and the weight(1f) sibling collapses to 0dp. That shipped
#   once: HbBottomNavItem used fillMaxHeight() → the bottom bar filled the whole screen and
#   every content area above it went blank. PR #347 fixed it and added the regression test
#   BottomNavLayoutTest; this script is the GENERIC tripwire so the class can't sneak back in
#   via any future bar/header/footer added to a Column.
#
# WHAT IT FLAGS:
#   Inside a `Column { … }` body — written either as a bare trailing lambda (`Column { … }`)
#   or with an arg-list (`Column(Modifier.…) { … }`) — a DIRECT child that applies
#   `.fillMaxHeight()` or `.fillMaxSize()` while a DIFFERENT direct child of the SAME Column
#   uses `.weight(1f` — i.e. exactly the height-eating-sibling shape. Detection is brace-depth
#   aware (only true *siblings* in the same Column count); weighted Row children, fills nested
#   inside the weighted child, and overlay Boxes that are siblings of the Column
#   (scrims/drawers/sheets) are NOT flagged.
#
# HEURISTIC LIMITS (it is a grep-grade static scan, not the Kotlin compiler — deliberately
# CONSERVATIVE to avoid false positives, so it can MISS some real cases):
#   - It reasons about brace depth, not full Kotlin syntax. Braces inside string literals or
#     block comments are not stripped and could, in theory, skew depth; the codebase doesn't
#     do that in layout code today.
#   - It only sees fills/weights written as literal `.fillMaxHeight()/.fillMaxSize()` and
#     `.weight(1f`/`.weight(1.0f`. A fill hidden behind a passed-in `modifier` param, an
#     extension, or `fillMaxHeight(fraction)` with an arg is invisible to it (the fraction
#     form rarely fully starves a sibling anyway).
#   - "Direct child" is approximated by brace depth: a composable call counts as a direct
#     child of the Column when it opens while the Column body is the innermost open block.
#
# OPT-OUT:
#   Put the marker comment  // layout-guard:allow  on the offending child's line (the one
#   carrying the fillMax*) to excuse an intentional case. Use sparingly and explain why.
#
# USAGE:  bash scripts/check-compose-layout.sh
#   exit 0 = clean, exit 1 = at least one risky sibling pair found (prints file:line).
set -euo pipefail

cd "$(dirname "$0")/.."

ROOT="android/app/src/main"
ALLOW_MARKER="layout-guard:allow"

if [[ ! -d "$ROOT" ]]; then
  echo "✗ $ROOT not found — run from the repo root." >&2
  exit 2
fi

# Collect Kotlin sources. Use -print0 / read -d '' so paths with spaces survive.
files=()
while IFS= read -r -d '' f; do
  files+=("$f")
done < <(find "$ROOT" -type f -name '*.kt' -print0)

if [[ ${#files[@]} -eq 0 ]]; then
  echo "✗ no .kt files under $ROOT" >&2
  exit 2
fi

# The whole scan is one awk pass per file. awk tracks brace depth and, for every Column body,
# remembers whether it has seen a weighted direct child and/or a fill-height direct child;
# when both occur in the same Column, it prints the fill child's location.
findings="$(
  awk -v allow="$ALLOW_MARKER" '
    # ---------------------------------------------------------------------------
    # Model: one char-walk over every (comment-stripped) line, tracking BRACE depth
    # and PAREN depth. A frame is pushed per `{`. For a Column frame we remember
    # whether a weighted direct child and/or a fill-height direct child has been
    # seen; both in the same Column => emit the fill child.
    #
    # A "direct child" of a Column is a `Word( … )` call that OPENS while the Column
    # body is the innermost open block (brace depth == the Column body depth, paren
    # depth == that body baseline). Its modifier chain lives inside that paren group
    # (possibly across many lines); we buffer the paren-group text and classify it
    # when the group closes — so weight()/fillMax*() are read from the child args,
    # never from its trailing-lambda CONTENT.
    # ---------------------------------------------------------------------------
    FNR == 1 {
      bdepth = 0          # brace depth
      pdepth = 0          # paren depth
      delete owner; delete colW; delete colFillLine; delete colFillText
      owner[0] = ""
      pendingOwner = ""   # identifier from a `Word( … )` arg-list right before a `{`
      lastWord = ""       # most recent identifier seen (owner of a bare, parens-free `{`)
      capturing = 0       # 1 while buffering a direct child`s paren group
      capDepth = 0        # paren depth at which the captured child opened
      capBrace = 0        # brace depth (= Column body depth) the child belongs to
      cbuf = ""           # buffered child text
      cline = 0           # line the child opened on
      callowed = 0        # opt-out marker seen within the child span
    }

    {
      raw = $0
      cmt = index(raw, "//")
      code = (cmt > 0) ? substr(raw, 1, cmt - 1) : raw
      lineAllow = (index(raw, allow) > 0)

      n = length(code)
      i = 1
      word = ""
      while (i <= n) {
        c = substr(code, i, 1)
        isW = (c ~ /[A-Za-z0-9_]/)

        if (capturing) cbuf = cbuf c   # accumulate everything inside the child paren group

        if (isW) {
          word = word c
        } else {
          if (word != "") lastWord = word   # an identifier just ended; remember it
          if (c == "(") {
            pdepth++
            # Only STATEMENT-LEVEL calls (pdepth just became 1 inside this brace block) name a
            # block owner or open a direct child. Calls nested in a modifier chain (pdepth>1,
            # e.g. Modifier.fillMaxSize()) must not be mistaken for the composable itself.
            if (pdepth == 1 && word != "") {
              pendingOwner = word
              if (!capturing && owner[bdepth] == "Column") {
                # New DIRECT child of this Column begins here.
                capturing = 1
                capDepth = 0            # its paren group closes when pdepth returns to 0
                capBrace = bdepth
                cbuf = word "("         # seed buffer with the call we just opened
                cline = FNR
                callowed = lineAllow
              }
            }
          } else if (c == ")") {
            if (pdepth > 0) pdepth--
            if (capturing && pdepth == capDepth) {
              # Child paren group complete — classify it.
              if (lineAllow) callowed = 1
              classifyChild(capBrace, cbuf, cline, callowed)
              capturing = 0; cbuf = ""
            }
          } else if (c == "{") {
            bdepth++
            # A `Word(args) {` block names its owner via pendingOwner; a bare, parens-free
            # `Word {` (the more common Compose shape) has no arg-list, so fall back to the
            # identifier immediately before the brace — so `Column {` registers as a Column
            # block exactly like `Column(...) {` does.
            owner[bdepth] = (pendingOwner != "") ? pendingOwner : lastWord
            colW[bdepth] = 0
            colFillLine[bdepth] = 0
            colFillText[bdepth] = ""
            pendingOwner = ""
            lastWord = ""
          } else if (c == "}") {
            if (bdepth > 0) {
              delete owner[bdepth]; delete colW[bdepth]
              delete colFillLine[bdepth]; delete colFillText[bdepth]
              bdepth--
            }
          }
          word = ""
        }
        i++
      }
      if (word != "") lastWord = word           # identifier ending the line (e.g. `Column` then `{` on the next)
      if (capturing && lineAllow) callowed = 1   # marker on any line of the span counts
    }

    # Classify a direct child of the Column body at brace-level d from its buffered
    # call-args text. Record a weighted child; remember the first fill child; emit once
    # both exist in this Column.
    function classifyChild(d, text, lno, allowed) {
      if (owner[d] != "Column") return
      isWeighted = (text ~ /\.weight\(1f|\.weight\(1\.0f/)
      fills = (text ~ /\.fillMaxHeight\(\)|\.fillMaxSize\(\)/)
      if (isWeighted) colW[d] = 1
      # A child that is itself weighted is safe even if it also fills (weight wins).
      if (fills && !isWeighted && !allowed && colFillLine[d] == 0) {
        colFillLine[d] = lno
        t = text; gsub(/[ \t]+/, " ", t); colFillText[d] = t
      }
      if (colW[d] && colFillLine[d] > 0) {
        print FILENAME ":" colFillLine[d] "\t" colFillText[d]
        colFillLine[d] = 0   # one finding per Column
      }
    }
  ' "${files[@]}"
)"

if [[ -n "$findings" ]]; then
  echo "✗ Compose layout guard: a non-weighted fillMax* child can starve its weight(1f) sibling." >&2
  echo "  In a Column, the non-weighted child is measured first with the full height; .fillMaxHeight()/" >&2
  echo "  .fillMaxSize() on it makes it eat the column and collapses the weight(1f) sibling to 0dp." >&2
  echo "  Fix the child (drop the fill / give the bar a fixed/wrap height), or add  // $ALLOW_MARKER  if intentional." >&2
  echo "" >&2
  printf '%s\n' "$findings" >&2
  exit 1
fi

echo "✓ Compose layout guard: no non-weighted fillMax* child starving a weight(1f) sibling."
exit 0

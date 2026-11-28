#!/usr/bin/env bash
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
p=src/main/java/com/rlclones/ai/brain/ReplayBuffer.java
git show 5b5c0ef40033f5963997be8e5f7baf9848a824ac:"$p" > "$tmp/base.java"
git show 96f6e02d242c660829a8cc267b27cce9b96f1167:"$p" > "$tmp/ReplayBuffer.java"
test "$(git hash-object "$tmp/base.java")" = 61776604923926aba356777b8dbc368fbeeea523
test "$(git hash-object "$tmp/ReplayBuffer.java")" = 7a446468addf356f929dd862b2e7a3ee909f5b3b
sed 's/public final class ReplayBuffer/public final class OldReplayBuffer/g;s/\bReplayBuffer(/OldReplayBuffer(/g;s/addAll(ReplayBuffer other)/addAll(OldReplayBuffer other)/g' "$tmp/base.java" > "$tmp/OldReplayBuffer.java"
javac -encoding UTF-8 --release 17 -d "$tmp/classes" "$tmp/OldReplayBuffer.java" "$tmp/ReplayBuffer.java" "$root/scripts/ReplayExactCheck.java"
java -cp "$tmp/classes" ReplayExactCheck

#!/usr/bin/env bash
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
base="${1:-5b5c0ef40033f5963997be8e5f7baf9848a824ac}"
candidate="${2:-dbf6e06fe0690dcb8be8b642b24e9342ba849da0}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
source="src/main/java/com/rlclones/ai/observe/AgentEvents.java"
java -version
for version in baseline candidate; do
  rev="$base"; if [[ "$version" == candidate ]]; then rev="$candidate"; fi
  mkdir -p "$tmp/$version/classes"
  git -C "$root" show "$rev:$source" > "$tmp/$version/AgentEvents.java"
  javac --release 17 -d "$tmp/$version/classes" "$tmp/$version/AgentEvents.java" "$root/scripts/EventIntervalBenchmark.java"
done
for version in baseline candidate candidate baseline; do
  echo "$version"
  java -Xms256m -Xmx256m -cp "$tmp/$version/classes" EventIntervalBenchmark
done

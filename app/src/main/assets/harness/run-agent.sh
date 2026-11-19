#!/bin/sh
# Runs sub agents, each confined to a single folder, in the background.
#
#   run-agent.sh start  <build|plan> <folder> <prompt>   prints: RUN <id>
#   run-agent.sh status <folder> <id>                    prints: STATE <running|done N|lost>, then the run's events,
#                                                        then a line "=====RESULT FILE=====" and what the agent
#                                                        wrote into its result file, if anything
#
# AGENT_ENGINE picks who does the work: opencode (the default) or claude (Claude Code).
# A run is ended after AGENT_TIMEOUT seconds; its exit code is then 124.
#
# Every run is given a folder of its own, ~/.reduction-result, to leave its final result in:
# result.md there is the second way a result comes back, beside what the agent prints.
# Pictures left in that folder (png, jpg, jpeg, webp, gif) come back with the result, to be
# looked at by whoever receives it: after the result file, for each picture, a line
# "=====IMAGE <name> <media type>=====" and the file in base64. At most 4 pictures of at
# most 3 MB each, in the order of their names; the rest are named on a line
# "=====IMAGE SKIPPED <name>=====" and not sent.
#   run-agent.sh stop   <folder> <id>                    prints: STOPPED
#   run-agent.sh changes <folder>                        prints: git diff --stat of <folder>, worked out in a
#                                                        sandbox of its own (see below), or nothing
#
# The agent may do anything opencode can do — run commands, use the network, write
# files — but the only part of the file system it can change is <folder>. The rest of
# the home directory, other projects, keys and Windows drives are not visible to it;
# the system is read-only. The environment is emptied so no host secret leaks in.
# "plan" agents do not change files at all.
#
# The one credential an agent can read is the one it runs on. For opencode that is the
# model key the phone sends with each start (AGENT_KEY), the phone's own DeepSeek key.
# Claude Code runs on this computer's own login: it is given its settings folder, with
# the records of other sessions in it hidden, and nothing else of the home directory.
set -eu

home=$HOME
model=${AGENT_MODEL:-deepseek/deepseek-flash}
limit=${AGENT_TIMEOUT:-900}
engine=${AGENT_ENGINE:-opencode}
opencode=$home/.opencode/bin/opencode

say() { printf '%s\n' "$1"; exit 0; }

# Checks <folder>, creates it, and sets $dir (resolved) and $state (opencode's own state
# for this folder, kept outside every agent folder).
locate() {
  dir=$1
  case "$dir" in "~/"*) dir=$home/${dir#"~/"} ;; esac
  case "$dir" in
    "$home"/?*) ;;
    *) say "harness：文件夹必须在 $home 之下：$dir" ;;
  esac
  mkdir -p "$dir" 2>/dev/null || say "harness：无法创建文件夹：$dir"
  dir=$(cd "$dir" && pwd -P)
  case "$dir" in
    "$home"/?*) ;;
    *) say "harness：文件夹解析后不在 $home 之下：$dir" ;;
  esac
  name=$(printf '%s' "${dir#"$home"/}" | tr -c 'A-Za-z0-9._-' '_')
  state=$home/.local/share/reduction-agents/$name
}

# A git worktree keeps its history in its main repository, outside the folder. That repository is
# let in only when it lists this folder as one of its own worktrees (or, for a submodule, as its
# work tree): a .git file alone, which an earlier agent may have written, could point anywhere.
registered() {
  [ -n "$1" ] && [ -d "$1" ] || return 1
  self=$(readlink -f "$dir/.git")
  for entry in "$1"/worktrees/*/gitdir; do
    [ -f "$entry" ] && [ "$(readlink -f "$(cat "$entry")")" = "$self" ] && return 0
  done
  tree=$(git config --file "$1/config" --get core.worktree 2>/dev/null || true)
  [ -n "$tree" ] || return 1
  case "$tree" in /*) ;; *) tree=$1/$tree ;; esac
  [ "$(readlink -f "$tree")" = "$dir" ]
}

run_dir() {
  case "$1" in
    *[!A-Za-z0-9-]*|'') say "harness：运行编号无效" ;;
  esac
  run=$state/runs/$1
  [ -d "$run" ] || say "STATE lost"
}

cmd=${1:?command}
shift

case "$cmd" in
start)
  agent=${1:?agent}
  locate "${2:?folder}"
  prompt=${3:?prompt}
  case "$agent" in build|plan) ;; *) say "harness：未知的 agent 类型 $agent" ;; esac
  case "$engine" in opencode|claude) ;; *) say "harness：未知的执行者 $engine" ;; esac
  command -v bwrap >/dev/null 2>&1 || say "harness：电脑上没有 bwrap，无法隔离运行"
  if [ "$engine" = claude ]; then
    claude=$(readlink -f "$(PATH="$home/.local/bin:$PATH" command -v claude 2>/dev/null || echo /nonexistent)")
    [ -x "$claude" ] || say "harness：这台电脑上找不到 Claude Code"
    [ -d "$home/.claude" ] || say "harness：这台电脑上的 Claude Code 还没有登录"
  else
    [ -x "$opencode" ] || say "harness：找不到 opencode"
    mkdir -p "$state/opencode"
    # The agent runs on the key the phone sent. Without one it falls back to this computer's.
    key=${AGENT_KEY:-}
    if [ -n "$key" ]; then
      case "$key" in *[!A-Za-z0-9_-]*) say "harness：模型密钥格式无效" ;; esac
      ( umask 077; printf '{"deepseek":{"type":"api","key":"%s"}}\n' "$key" > "$state/opencode/auth.json" )
    else
      cp -p "$home/.local/share/opencode/auth.json" "$state/opencode/auth.json" 2>/dev/null \
        || say "harness：没有收到模型密钥，这台电脑的 opencode 也没有登录"
    fi
  fi

  id=$(date +%Y%m%d-%H%M%S)-$$
  run=$state/runs/$id
  mkdir -p "$run/result"
  printf '%s\n' "$prompt" > "$run/prompt.txt"

  set -- \
    --clearenv --setenv HOME "$home" --setenv PATH /usr/local/bin:/usr/bin:/bin \
    --setenv LANG C.UTF-8 --setenv TERM dumb \
    --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp --tmpfs /mnt --tmpfs "$home" \
    --bind "$run/result" "$home/.reduction-result" \
    --unshare-pid --die-with-parent --new-session
  [ -d /mnt/wsl ] && set -- "$@" --ro-bind /mnt/wsl /mnt/wsl
  # A read-only system still has doors out: the sockets under /run (the session bus, docker) take
  # orders from anyone who can reach them. /run is left empty, but for the resolver if it lives there.
  set -- "$@" --tmpfs /run --unshare-ipc
  resolver=$(readlink -f /etc/resolv.conf 2>/dev/null || true)
  case "$resolver" in /run/?*) set -- "$@" --ro-bind "${resolver%/*}" "${resolver%/*}" ;; esac

  # A read-only agent gets its folder read-only from the system itself, not by being asked.
  if [ "$agent" = plan ] && [ "$engine" = claude ]; then
    set -- "$@" --ro-bind "$dir" "$dir" --chdir "$dir"
  else
    set -- "$@" --bind "$dir" "$dir" --chdir "$dir"
  fi

  # A git worktree keeps its history in the main repository; the agent needs that to use git.
  if [ -f "$dir/.git" ]; then
    common=$(git -C "$dir" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
    registered "$common" || common=
    if [ -n "$common" ] && [ -d "$common" ]; then
      if [ "$agent" = plan ] && [ "$engine" = claude ]; then set -- "$@" --ro-bind "$common" "$common"; else set -- "$@" --bind "$common" "$common"; fi
    fi
  fi

  if [ "$engine" = claude ]; then
    # Claude Code itself, its settings and login, and nothing of what other sessions said.
    set -- "$@" --ro-bind "$(dirname "$(dirname "$claude")")" "$(dirname "$(dirname "$claude")")" --bind "$home/.claude" "$home/.claude" --tmpfs "$home/.claude/projects"
    [ -f "$home/.claude.json" ] && set -- "$@" --bind "$home/.claude.json" "$home/.claude.json"
    # Whatever this computer tells Claude Code through its environment goes with it; nothing else does.
    for name in $(env | sed -n 's/^\(ANTHROPIC_[A-Z0-9_]*\|CLAUDE_CODE_[A-Z0-9_]*\)=.*/\1/p'); do
      eval "value=\${$name}"
      set -- "$@" --setenv "$name" "$value"
    done
    # Auto mode: nobody is there to answer a prompt, so Claude Code's own safety check decides each
    # step, allowing ordinary work and refusing what looks dangerous. The walls around it hold either way.
    set -- "$@" timeout "$limit" "$claude" -p --output-format stream-json --verbose --permission-mode auto
    input=$run/prompt.txt
  else
    # opencode's own data for this folder, and nothing else of $state: the runs' pid and exit files
    # stay out of reach, so an agent cannot point "stop" at another process or fake its own end.
    set -- "$@" --ro-bind "$home/.opencode" "$home/.opencode" --bind "$state/opencode" "$home/.local/share/opencode"
    [ -d "$home/.config/opencode" ] && set -- "$@" --ro-bind "$home/.config/opencode" "$home/.config/opencode"
    # opencode loads code from its cache (the model providers it installs). The agent writes into a copy
    # of its own, taken once per folder, never into the one opencode on this computer runs from.
    if [ ! -d "$state/cache" ]; then
      mkdir -p "$state/cache.tmp"
      [ -d "$home/.cache/opencode" ] && cp -a "$home/.cache/opencode/." "$state/cache.tmp/"
      mv "$state/cache.tmp" "$state/cache"
    fi
    set -- "$@" --bind "$state/cache" "$home/.cache/opencode"
    set -- "$@" timeout "$limit" "$opencode" run --format json --model "$model" --agent "$agent" --auto --dir "$dir" "$prompt"
    input=/dev/null
  fi

  # Detached from this shell, so the run outlives the SSH command that started it.
  nohup setsid sh -c '
    out=$1; input=$2; shift 2
    "$@" > "$out/events.jsonl" 2>&1 < "$input"
    echo $? > "$out/exit"
  ' sh "$run" "$input" bwrap "$@" > /dev/null 2>&1 < /dev/null &
  echo $! > "$run/pid"
  printf 'RUN %s\n' "$id"
  ;;

status)
  locate "${1:?folder}"
  run_dir "${2:?id}"
  if [ -f "$run/exit" ]; then
    printf 'STATE done %s\n' "$(cat "$run/exit")"
  elif kill -0 "$(cat "$run/pid" 2>/dev/null || echo 0)" 2>/dev/null; then
    printf 'STATE running\n'
  else
    printf 'STATE lost\n'
  fi
  [ -f "$run/events.jsonl" ] && cat "$run/events.jsonl"
  if [ -s "$run/result/result.md" ]; then
    printf '\n=====RESULT FILE=====\n'
    cat "$run/result/result.md"
  fi
  # Pictures are handed over once the run is over, not while it may still be drawing them.
  if [ -f "$run/exit" ]; then
    sent=0
    for picture in "$run/result"/*; do
      [ -f "$picture" ] && [ ! -L "$picture" ] || continue
      name=$(basename "$picture")
      case "$name" in *[!A-Za-z0-9._-]*) continue ;; esac
      case "$name" in
        *.png|*.PNG) kind=image/png ;;
        *.jpg|*.jpeg|*.JPG|*.JPEG) kind=image/jpeg ;;
        *.webp|*.WEBP) kind=image/webp ;;
        *.gif|*.GIF) kind=image/gif ;;
        *) continue ;;
      esac
      if [ "$sent" -ge 4 ] || [ "$(wc -c < "$picture")" -gt 3145728 ]; then
        printf '\n=====IMAGE SKIPPED %s=====\n' "$name"
        continue
      fi
      sent=$((sent + 1))
      printf '\n=====IMAGE %s %s=====\n' "$name" "$kind"
      base64 -w 0 "$picture"
    done
  fi
  ;;

stop)
  locate "${1:?folder}"
  run_dir "${2:?id}"
  pid=$(cat "$run/pid" 2>/dev/null || echo 0)
  [ "$pid" -gt 1 ] 2>/dev/null && kill -TERM -- "-$pid" 2>/dev/null || true
  [ -f "$run/exit" ] || echo 143 > "$run/exit"
  printf 'STOPPED\n'
  ;;

changes)
  # The folder's .git is the agent's to write, and git runs what its config names (fsmonitor, filters,
  # diff drivers) while it compares. So the comparison is made where such a program can do no harm:
  # nothing writable, no network, no home directory; what it prints is all that comes back.
  locate "${1:?folder}"
  command -v bwrap >/dev/null 2>&1 || exit 0
  common=
  [ -f "$dir/.git" ] && common=$(git -C "$dir" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
  registered "$common" || common=
  set -- \
    --clearenv --setenv HOME "$home" --setenv PATH /usr/local/bin:/usr/bin:/bin --setenv LANG C.UTF-8 \
    --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp --tmpfs /mnt --tmpfs /run --tmpfs "$home" \
    --ro-bind "$dir" "$dir" --unshare-all --die-with-parent --new-session
  [ -n "$common" ] && set -- "$@" --ro-bind "$common" "$common"
  bwrap "$@" git -c core.fsmonitor=false -c core.hooksPath=/dev/null -C "$dir" --no-pager \
    diff --stat --no-ext-diff --no-textconv 2>/dev/null || true
  ;;

*)
  say "harness：未知命令 $cmd"
  ;;
esac

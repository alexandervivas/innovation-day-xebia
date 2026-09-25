# Setup

Deliver the digest's first task: repository structure, governance files, CB-01 and CB-02, then propose GitHub issues and stop.

## Preconditions

1. Confirm `pwd` is the `innovation-day-xebia` repository root and `git status` is clean or empty. This repository is the project root even though the digest calls the project `core-banking-mcp`; use that name in `build.sbt`, docs, and the compose project.
2. Confirm JDK 21, sbt, docker, and `gh auth status`. Report anything missing and continue with what does not depend on it.
3. Spawn `version-scout` (read-only, may run in parallel with the first docs batch) for: Scala 3, sbt 1.x, sbt-scalafmt, fast-mcp-scala (coordinates + its ZIO and MCP-spec versions), ZIO, zio-test, Magnum, Flyway core + Postgres module, Postgres JDBC driver, Postgres 16 Docker tag, zio-config or plain `System.env`. Pin only verified values.

## Batches (one writer at a time)

1. `implementation-worker` on `haiku`: `.gitignore`, `.env.example` (placeholders only), `docker-compose.yml` (Postgres 16, named volume, healthcheck, env from `.env`), `CLAUDE.md` (condensed rules from SKILL.md's invariants + stack + structure), `README.md` skeleton, `PLAN.md` (timeline + critical path), `BACKLOG.md` (all CB stories with epic, `critical-path`/`stretch`, status), `docs/demo-script.md` (demo prompts verbatim, grouped), `scripts/secret-scan.sh` with `--self-test`.
2. `implementation-worker` on `sonnet`, after the scout returns: `build.sbt`, `project/build.properties`, `project/plugins.sbt`, `.scalafmt.conf`, package skeleton under `src/main/scala/corebanking/` (`Server.scala`, `config/`, `db/`, `domain/`, `engine/`, `tools/` with placeholder objects only where needed to compile), CB-01: MCP server with a `ping` tool over stdio that returns `{"pong": true, "env": "<CORE_ENV>"}`. Evidence: `sbt compile`, `sbt run` starts and answers an MCP `initialize` + `tools/call ping` over stdin.
3. `implementation-worker` on `sonnet`: CB-02 env guard in `config/` as a pure validation (`CoreEnv` enum, `parse(String): Either[String, CoreEnv]`) wired into `Server.scala` so `CORE_ENV=production` fails loudly with a non-zero exit and a clear message before any ZIO layer starts. Every tool response includes `env`.
4. `test-worker` on `sonnet`: zio-test for the guard (`mock`, `sandbox`, `production`, missing, mixed case) and for the `ping` response shape.
5. `risk-reviewer` on the whole tree.

## Gates In The Parent Thread

```bash
docker compose up -d && docker compose ps
sbt compile test
CORE_ENV=production sbt run   # must exit non-zero with a clear message
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
```

Also check that no committed file contains a personal email (the digest's audience is external colleagues), and that Metals' MCP server is noted in README's setup section for agent sessions (`metals.startMcpServer`), without configuring anything outside the repository.

## Finish

1. Commit on `main` with a Conventional Commit (for example `chore: scaffold core-banking-mcp with ping tool and env guard`). Do not push until the owner says so.
2. Print the proposed GitHub issues as a table (title, epic label, `critical-path`/`stretch`, body summary) and the exact `gh label create` and `gh issue create` commands. **Ask before running any of them.**
3. Stop and report per the Completion Report, naming CB-03 as the next story.

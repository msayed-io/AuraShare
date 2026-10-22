#!/usr/bin/env bash
# Temporary investigation only; no application or signing changes.
set -euo pipefail
mkdir -p app/build/reports/hang-diagnostic
cat > "$RUNNER_TEMP/test-trace.init.gradle" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        beforeTest { descriptor ->
            logger.lifecycle("TEST_START ${descriptor.className} :: ${descriptor.name}")
        }
        afterTest { descriptor, result ->
            logger.lifecycle("TEST_END ${descriptor.className} :: ${descriptor.name} :: ${result.resultType}")
        }
        testLogging { showStandardStreams = true; exceptionFormat = 'full' }
    }
}
GRADLE
(
  # Wait for the actual test JVM, not compilation/toolchain setup.
  for attempt in $(seq 1 120); do
    if jcmd -l | grep -q 'GradleWorkerMain'; then
      sleep 90
      for snapshot in 1 2; do
        while read -r pid main; do
          if [[ "$main" == *GradleWorkerMain* ]]; then
            timeout 20s jcmd "$pid" Thread.print -l | tee "app/build/reports/hang-diagnostic/threads-${pid}-${snapshot}.txt" || true
          fi
        done < <(jcmd -l)
        sleep 15
      done
      exit 0
    fi
    sleep 5
  done
) &
watchdog=$!
trap 'kill "$watchdog" 2>/dev/null || true' EXIT
# Fail before the job deadline so reports and dumps are always uploaded.
timeout --signal=TERM --kill-after=20s 420s gradle --no-daemon --no-configuration-cache --info --stacktrace --console=plain \
  --init-script "$RUNNER_TEMP/test-trace.init.gradle" testDebugUnitTest \
  --tests 'com.example.TransferAnnounceTest' \
  --tests 'com.example.StreamingTransferRegressionTest'

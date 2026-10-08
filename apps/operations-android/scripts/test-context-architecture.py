#!/usr/bin/env python3
"""Exercise context fitness rules against isolated copies of real module sources."""
from pathlib import Path
import shutil
import subprocess
import tempfile

SOURCE = Path(__file__).resolve().parents[1]
PROBES = (
    ('contexts/salescommitment/presentation/build.gradle.kts',
     '\ndependencies { implementation(project(":contexts:inventoryavailability:infrastructure")) }\n',
     'foreign implementation dependency'),
    ('core/network/build.gradle.kts',
     '\ndependencies { implementation(project(":contexts:salescommitment:application")) }\n',
     'runtime dependency on a context'),
    ('contexts/salescommitment/domain/build.gradle.kts',
     '\ndependencies { implementation(project(":contexts:inventoryavailability:domain")) }\n',
     'foreign domain dependency'),
    ('contexts/salescommitment/domain/src/main/kotlin/com/nexa/mobile/operations/salescommitment/domain/model/commercial/CustomerCommitment.kt',
     '\nimport android.content.Context\n', 'framework import in domain'),
    ('contexts/salescommitment/infrastructure/src/main/kotlin/com/nexa/mobile/operations/salescommitment/infrastructure/transport/NexaDirectOrderGateway.kt',
     '\nimport com.nexa.mobile.operations.salescommitment.presentation.commercial.FieldRequestViewModel\n',
     'presentation import in infrastructure'),
    ('contexts/salescommitment/application/src/main/kotlin/com/nexa/mobile/operations/salescommitment/application/commercial/FieldRequestSubmissionCoordinator.kt',
     '\nfun forbiddenFileRead(file: java.io.File) = file.readBytes()\n', 'file IO in application'),
)

with tempfile.TemporaryDirectory(prefix='nexa-context-fitness-') as directory:
    root = Path(directory)
    for name in ('contexts', 'core', 'app', 'scripts'):
        shutil.copytree(SOURCE / name, root / name,
                        ignore=shutil.ignore_patterns('build', '.gradle', '__pycache__'))

    def check():
        return subprocess.run(['python3', str(root / 'scripts/verify-context-architecture.py')],
                              capture_output=True, text=True)

    baseline = check()
    if baseline.returncode:
        raise SystemExit(f'Baseline architecture failed:\n{baseline.stderr}')
    for relative, addition, expected in PROBES:
        path = root / relative
        original = path.read_text()
        try:
            path.write_text(original + addition)
            result = check()
            if result.returncode == 0 or expected not in result.stderr:
                raise SystemExit(f'Probe did not reject {expected}:\n{result.stdout}{result.stderr}')
        finally:
            path.write_text(original)
        print(f'PASS: rejects {expected}')
print(f'PASS: baseline and {len(PROBES)} negative context fitness probes')

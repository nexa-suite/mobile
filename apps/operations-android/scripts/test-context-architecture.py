#!/usr/bin/env python3
"""Exercise context fitness rules against isolated copies of real module sources."""
from pathlib import Path
import shutil
import subprocess
import tempfile

SOURCE = Path(__file__).resolve().parents[1]
PROBES = (
    ('contexts/salescommitment/presentation/build.gradle.kts',
     '\ndependencies { implementation(project(":contexts:creditreceivables:domain")) }\n',
     'foreign domain dependency'),
    ('contexts/salescommitment/presentation/src/main/kotlin/com/nexa/mobile/operations/salescommitment/presentation/commercial/CustomerProgressViewModel.kt',
     '\nimport com.nexa.mobile.operations.creditreceivables.domain.model.commercial.CustomerCredit\n',
     'foreign domain model'),
    ('contexts/catalogcommercialpolicy/presentation/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/presentation/commercial/CommercialCatalogViewModel.kt',
     '\nimport com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters.CatalogOperationsContextAdapter\n',
     'outward context import'),
    ('contexts/inventoryavailability/infrastructure/src/main/kotlin/com/nexa/mobile/operations/inventoryavailability/infrastructure/adapters/LotSubstitutionGatewayRepositories.kt',
     '\nimport com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority\n',
     'foreign non-public contract import'),
    ('app/src/main/kotlin/com/nexa/mobile/operations/RootViewModel.kt',
     '\nclass WarehouseFeatureViewModel : ViewModel()\n',
     'feature ViewModel in composition root'),
    ('contexts/catalogcommercialpolicy/domain/build.gradle.kts',
     '\ndependencies { api(project(":contexts:tenantaccessgovernance:domain")) }\n',
     'foreign domain dependency'),
    ('contexts/catalogcommercialpolicy/domain/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/domain/model/warehouse/ScannerModels.kt',
     '\nimport com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary\n',
     'foreign domain model'),
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
     '\nimport com.nexa.mobile.operations.salescommitment.presentation.commercial.DirectOrderViewModel\n',
     'presentation import in infrastructure'),
    ('contexts/salescommitment/application/src/main/kotlin/com/nexa/mobile/operations/salescommitment/application/commercial/DirectOrderSubmissionCoordinator.kt',
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

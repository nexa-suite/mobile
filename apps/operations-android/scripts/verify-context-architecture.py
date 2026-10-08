#!/usr/bin/env python3
"""Enforce the canonical Operations client context and dependency boundaries."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CONTEXTS = (
    'tenantaccessgovernance', 'customerbuyerrelationships', 'catalogcommercialpolicy',
    'salescommitment', 'inventoryavailability', 'fulfillmentdelivery',
    'creditreceivables', 'payments', 'businessdocuments', 'notifications',
    'businesstraceability',
)
LAYERS = ('domain', 'application', 'infrastructure', 'presentation')
PREFIX = 'com.nexa.mobile.operations.'
errors = []

def require(condition, message):
    if not condition:
        errors.append(message)

actual = {p.name for p in (ROOT / 'contexts').iterdir() if p.is_dir()}
require(actual == set(CONTEXTS), f'Canonical context roots differ: {actual ^ set(CONTEXTS)}')
for context in CONTEXTS:
    base = ROOT / 'contexts' / context
    require((base / 'README.md').is_file(), f'{context}: missing ownership and implementation status')
    for layer in LAYERS:
        module = base / layer
        if not (module / 'build.gradle.kts').is_file():
            continue
        build = (module / 'build.gradle.kts').read_text()
        jvm = layer in ('domain', 'application')
        require(('libs.plugins.kotlin.jvm' in build) == jvm, f'{context}/{layer}: invalid toolchain')
        for dep in re.findall(r'(?:api|implementation)\(project\("(:[^\"]+)"\)\)', build):
            if jvm:
                require(dep.startswith(':contexts:') and dep.split(':')[-1] in ('domain', 'application'),
                        f'{context}/{layer}: framework dependency {dep}')
                if layer == 'domain':
                    require(dep.endswith(':domain'), f'{context}/domain: outward dependency {dep}')
            if layer == 'presentation':
                require(dep.split(':')[-1] not in ('infrastructure', 'auth', 'network', 'local'),
                        f'{context}/presentation: implementation dependency {dep}')
        for source in (module / 'src/main').rglob('*.kt'):
            body = source.read_text()
            package = re.search(r'^package (\S+)', body, re.M)
            require(package and package.group(1).startswith(f'{PREFIX}{context}.{layer}.'),
                    f'{source.relative_to(ROOT)}: package outside owning layer')
            for imported in re.findall(r'^import (\S+)', body, re.M):
                if jvm:
                    require(not imported.startswith(('android.', 'androidx.', 'dagger.', 'javax.inject.',
                            'retrofit2.', 'okhttp3.', 'kotlinx.serialization.', 'java.sql.')),
                            f'{source.name}: framework import in {layer}: {imported}')
                if layer == 'domain':
                    require(not imported.startswith('java.io.'), f'{source.name}: file boundary in domain')
                if jvm or layer == 'presentation':
                    require(not imported.startswith(PREFIX + 'core.network'),
                            f'{source.name}: transport in {layer}')
                    require(not imported.startswith(PREFIX + 'core.local'),
                            f'{source.name}: persistence in {layer}')
                    parts = imported.removeprefix(PREFIX).split('.')
                    if imported.startswith(PREFIX) and parts[0] in CONTEXTS:
                        require(parts[1] not in ('infrastructure', 'presentation') or
                                (layer == 'presentation' and parts[0] == context and parts[1] == layer),
                                f'{source.name}: outward context import {imported}')
                        if layer == 'domain':
                            require(parts[1] == 'domain', f'{source.name}: domain imports application')
                if layer == 'presentation':
                    require(not imported.startswith(('kotlinx.serialization.', 'org.json.', 'retrofit2.', 'okhttp3.')),
                            f'{source.name}: protocol encoding in presentation')
                if layer == 'infrastructure':
                    parts = imported.removeprefix(PREFIX).split('.')
                    if imported.startswith(PREFIX) and parts[0] in CONTEXTS and parts[0] != context:
                        require(parts[1] not in ('infrastructure', 'presentation'),
                                f'{source.name}: foreign implementation import {imported}')
                    require(not imported.startswith(('androidx.compose.', 'androidx.lifecycle.')),
                            f'{source.name}: UI import in infrastructure')

require(not any(p.is_dir() and p.name != 'scoped' for p in (ROOT / 'core/local/src/main/kotlin/com/nexa/mobile/operations/core/local').iterdir()),
        'Workflow-specific persistence remains in generic core/local')

for foundation in ('network', 'local', 'auth', 'device', 'designsystem'):
    for source in (ROOT / 'core' / foundation / 'src/main').rglob('*.kt'):
        body = source.read_text()
        require(not any(PREFIX + context + '.' in body for context in CONTEXTS),
                f'{source.name}: foundation depends on a context')
        require(PREFIX + 'feature.' not in body, f'{source.name}: legacy feature dependency')
        if foundation == 'network':
            require(re.search(r'/api/v1/[a-z][a-z-]+', body) is None, f'{source.name}: business route in generic transport')
for source in (ROOT / 'app/src/main').rglob('*.kt'):
    require('DebugReviewActivity' not in source.read_text() and 'ReviewScenarioProvider' not in source.read_text(),
            f'{source.name}: debug tooling in release source')
for path in ('app/src/main/AndroidManifest.xml', 'app/src/release/AndroidManifest.xml'):
    p = ROOT / path
    if p.exists():
        require('ACCESS_LOCAL_NETWORK' not in p.read_text(), f'{path}: debug permission in release')
require(not any((ROOT / 'feature').rglob('*.kt')), 'Legacy feature source remains outside canonical contexts')
require(not any((ROOT / 'data').rglob('*.kt')), 'Legacy data source remains outside context infrastructure')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'PASS: {len(CONTEXTS)} canonical context roots and layer boundaries')
